# stechen_gpt_summarize.py
#
# Purpose:
#   Summarize the last N STECHEN pipeline steps from stechen.db (NEW schema):
#     - pipeline(step_id, script_name, created_at, run_id, step_index)
#     - per-script tables keyed by step_id
#   + include RULES.txt
#   + ask GPT to produce ONLY:
#       2) WHAT HAPPENED
#
# Behavior change (requested):
#   - Infer WHICH PIPELINE PHASE you're in based on RULES + step pattern.
#   - Reasonably assume intent of standard patterns (method build, field build, run).
#   - Only mark "unknown" where the inference would be unsafe or ambiguous.
#
# Usage:
#   python stechen_gpt_summarize.py
#   python stechen_gpt_summarize.py stechen.db RULES.txt 10
#
# Env overrides:
#   STECHEN_DB_PATH, STECHEN_RULES_FILE, STECHEN_N, STECHEN_MODEL
#
# Requirements:
#   pip install openai
#   set OPENAI_API_KEY

import os
import sys
import sqlite3
from pathlib import Path
from typing import Any, Dict, List, Optional

from openai import OpenAI

DEFAULT_DB = "stechen.db"
DEFAULT_RULES = "RULES.txt"
DEFAULT_N = 10
MODEL = "gpt-5.2"

MAX_RULES_CHARS = 80_000
MAX_PAYLOAD_CHARS = 10_000
MAX_VALUE_CHARS = 4_000


def _cap(s: Optional[str], n: int) -> str:
    if s is None:
        return ""
    s = str(s)
    if len(s) <= n:
        return s
    return s[:n] + "\n... [truncated]"


def _read_text(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(f"Rules file not found: {path.resolve()}")
    return path.read_text(encoding="utf-8", errors="replace")


def _safe_table_name(name: str) -> str:
    out = []
    for ch in name:
        if ch.isalnum() or ch == "_":
            out.append(ch)
    return "".join(out)


def _fetch_tables(conn: sqlite3.Connection) -> List[str]:
    cur = conn.cursor()
    rows = cur.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
    return [r[0] for r in rows]


def _fetch_payload(conn: sqlite3.Connection, script_name: str, step_id: int) -> Dict[str, Any]:
    cur = conn.cursor()
    t = _safe_table_name(script_name)

    exists = cur.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        (t,),
    ).fetchone()
    if not exists:
        return {"_payload_error": f"Missing table for script: {t}"}

    row = cur.execute(f"SELECT * FROM {t} WHERE step_id=?", (step_id,)).fetchone()
    if not row:
        return {"_payload_error": f"No payload row in {t} for step_id={step_id}"}

    payload = dict(row)

    # cap values for stable prompts
    for k, v in list(payload.items()):
        if isinstance(v, (bytes, bytearray)):
            payload[k] = f"<{len(v)} bytes>"
        else:
            payload[k] = _cap(v, MAX_VALUE_CHARS)

    return payload


def _format_steps(steps: List[Dict[str, Any]]) -> str:
    """
    Make the evidence easy to pattern-match:
      - show the canonical "command-ish" signature the rules talk about
    """
    chunks: List[str] = []
    for s in steps:
        step_id = s.get("step_id")
        script = s.get("script_name")
        created_at = s.get("created_at")
        run_id = s.get("run_id")
        step_index = s.get("step_index")
        payload = s.get("payload", {})

        chunks.append(f"STEP_ID={step_id}  TIME={created_at}  SCRIPT={script}"
                      + (f"  RUN_ID={run_id}" if run_id else "")
                      + (f"  STEP_INDEX={step_index}" if step_index is not None else ""))

        # Add a short signature line per script to help inference
        sig = ""
        try:
            if script == "DynamicJarLoader":
                sig = f"--library {payload.get('library','')}"
            elif script == "DynamicClassCreator":
                sig = f"--name {payload.get('class_name','')}"
            elif script == "CreateDirectory":
                sig = f"--name {payload.get('directory_name','')} --path {payload.get('target_path','')}"
            elif script == "CurrentDirUpdate":
                sig = f"--dirname {payload.get('dirname','')}"
            elif script == "CreateTextFile":
                sig = f"--name {payload.get('file_name','')} (content_text_len={len(payload.get('content_text') or '')}, content_ref={payload.get('content_ref')})"
            elif script == "CreateTextFileFromBase64":
                sig = f"--name {payload.get('file_name','')} (content_b64_len={len(payload.get('content_b64') or '')})"
            elif script == "DynamicDelegateCreator":
                sig = f"--parent {payload.get('parent','')} --methodFile {payload.get('method_file','')} --fieldFile {payload.get('field_file','')} --outputDir {payload.get('output_dir','')}"
            elif script == "ClassMethodCloner":
                sig = f"--classNameToModify {payload.get('class_name_to_modify','')} --delegateclass {payload.get('delegate_class','')} --method {payload.get('method_name','')}"
            elif script == "ClassFieldCloner":
                sig = f"--classNameToModify {payload.get('class_name_to_modify','')} --delegateclass {payload.get('delegate_class','')} --field {payload.get('field_name','')}"
            elif script == "RunClass":
                sig = f"--class {payload.get('class_name','')} --args {payload.get('args_text','')}"
        except Exception:
            sig = ""

        if sig:
            chunks.append(f"SIG: {sig}")

        # Include raw payload (capped) as backup evidence
        chunks.append("PAYLOAD:")
        chunks.append(_cap(str(payload), MAX_PAYLOAD_CHARS) or "{}")
        chunks.append("----")

    return "\n".join(chunks)


def _build_instructions() -> str:
    return (
        "You are a STECHEN pipeline summarizer.\n"
        "You will be given:\n"
        "(A) STECHEN_SYSTEM_RULES (authoritative)\n"
        "(B) LAST_STEPS (the last N recorded steps)\n\n"
        "Your job is to infer what is happening RIGHT NOW.\n"
        "Important: This system is rule-driven. Use the rules to interpret intent.\n\n"
        "Inference rules you MUST apply:\n"
        "- Recognize STANDARD METHOD CONSTRUCTION: CreateTextFile -> DynamicDelegateCreator -> ClassMethodCloner.\n"
        "  If you see that pattern, assume we are 'adding method <methodName>' to the base class.\n"
        "- Recognize STANDARD FIELD CONSTRUCTION: (delegate creation + ClassFieldCloner).\n"
        "- If RunClass appears, assume we are executing the accumulated base class now.\n"
        "- If method/field source is missing (content stored by reference), still infer purpose from filenames and method_name.\n"
        "- You MAY reasonably assume earlier prerequisite steps were done if the current steps depend on them.\n"
        "  Only say 'unknown' if it changes what the next action should be.\n\n"
        "Output rules (HARD):\n"
        "- Output ONLY one heading: exactly '2) WHAT HAPPENED'\n"
        "- Then bullet points.\n"
        "- No other headings, no extra commentary.\n"
        "- Prefer confident, rule-backed interpretation over 'unknown'.\n"
    )


def summarize_last_n_commands(
    db_path: str = DEFAULT_DB,
    rules_file: str = DEFAULT_RULES,
    n: int = DEFAULT_N,
    model: str = MODEL,
) -> str:
    base_dir = Path(__file__).resolve().parent

    db_p = Path(db_path)
    if not db_p.is_absolute():
        db_p = (base_dir / db_p).resolve()

    rules_p = Path(rules_file)
    if not rules_p.is_absolute():
        rules_p = (base_dir / rules_p).resolve()

    rules_text = _cap(_read_text(rules_p), MAX_RULES_CHARS)

    if not db_p.exists():
        return f"2) WHAT HAPPENED\n- No database found at: {db_p}"

    conn = sqlite3.connect(str(db_p), timeout=30.0)
    conn.row_factory = sqlite3.Row
    try:
        tables = _fetch_tables(conn)
        if "pipeline" not in tables:
            return "2) WHAT HAPPENED\n- No 'pipeline' table found (schema not initialized)."

        cur = conn.cursor()
        rows = cur.execute(
            "SELECT step_id, script_name, created_at, run_id, step_index "
            "FROM pipeline ORDER BY step_id DESC LIMIT ?",
            (int(n),),
        ).fetchall()

        if not rows:
            return "2) WHAT HAPPENED\n- No pipeline steps found."

        steps_newest_first: List[Dict[str, Any]] = []
        for r in rows:
            step_id = int(r["step_id"])
            script_name = str(r["script_name"])
            payload = _fetch_payload(conn, script_name, step_id)

            steps_newest_first.append(
                {
                    "step_id": step_id,
                    "script_name": script_name,
                    "created_at": r["created_at"],
                    "run_id": r["run_id"],
                    "step_index": r["step_index"],
                    "payload": payload,
                }
            )

        # chronological is easier for narrative
        steps_chrono = list(reversed(steps_newest_first))
        steps_text = _format_steps(steps_chrono)

    finally:
        conn.close()

    input_text = (
        "STECHEN_SYSTEM_RULES:\n"
        f"{rules_text}\n\n"
        "LAST_STEPS:\n"
        f"{steps_text}\n"
    )

    client = OpenAI()
    resp = client.responses.create(
        model=model,
        instructions=_build_instructions(),
        input=input_text,
        reasoning={"effort": "low"},
    )

    out = (resp.output_text or "").strip()
    if not out.startswith("2) WHAT HAPPENED"):
        out = "2) WHAT HAPPENED\n" + out
    return out


if __name__ == "__main__":
    # CLI args > env > defaults
    db_path = os.getenv("STECHEN_DB_PATH", DEFAULT_DB)
    rules_file = os.getenv("STECHEN_RULES_FILE", DEFAULT_RULES)
    n = int(os.getenv("STECHEN_N", str(DEFAULT_N)))
    model = os.getenv("STECHEN_MODEL", MODEL)

    if len(sys.argv) >= 2:
        db_path = sys.argv[1]
    if len(sys.argv) >= 3:
        rules_file = sys.argv[2]
    if len(sys.argv) >= 4:
        n = int(sys.argv[3])

    print(summarize_last_n_commands(db_path=db_path, rules_file=rules_file, n=n, model=model))
