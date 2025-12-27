# run_next_pipeline_step.py
#
# Run ONLY the next pipeline step (the earliest step_id that has not been
# successfully executed yet), using DBOperator to fetch payload and execute the
# corresponding Java tool fetched from GitHub (cached locally).
#
# ✅ Behavior:
# - Finds the FIRST "pending" step:
#     pending = step_id exists in pipeline, but NO SUCCESS RESULT exists in pipelineLong for that step
# - Executes exactly ONE step
# - Logs STEP + RESULT into pipelineLong using DBOperator.log_pipeline_long()
# - Every CHECKPOINT_EVERY successful executions overall:
#     - calls the "current summarizer style" (2) WHAT HAPPENED using GPT)
#     - writes to pipelineCheckpoints using DBOperator.insert_checkpoint()
#
# Usage:
#   python run_next_pipeline_step.py
#
# Required:
#   pip install openai
#   set OPENAI_API_KEY
#
# Optional env:
#   STECHEN_DB_PATH, STECHEN_WORK_DIR, STECHEN_GITHUB_BASE_RAW
#   STECHEN_RULES_FILE (default RULES.txt)
#   STECHEN_MODEL (default gpt-5.2)
#   STECHEN_CHECKPOINT_EVERY (default 50)
#
import os
import sys
import time
import base64
import subprocess
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Tuple, Optional

from openai import OpenAI

from db_operator import DBOperator  # must include: log_pipeline_long, insert_checkpoint, ensure_runtime_tables

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# -----------------------------
# CONFIG
# -----------------------------
DB_PATH = os.getenv("STECHEN_DB_PATH", "stechen.db")
WORK_DIR = Path(os.getenv("STECHEN_WORK_DIR", ".")).resolve()
GITHUB_BASE_RAW = os.getenv(
    "STECHEN_GITHUB_BASE_RAW",
    "https://raw.githubusercontent.com/Mimkaa/ObjectSchemas/main"
)

RULES_FILE = os.getenv("STECHEN_RULES_FILE", "RULES.txt")
MODEL = os.getenv("STECHEN_MODEL", "gpt-5.2")
CHECKPOINT_EVERY = int(os.getenv("STECHEN_CHECKPOINT_EVERY", "50"))

JAVA_CMD = "java"
JAVAC_CMD = "javac"
CLASSPATH_SEP = ";" if os.name == "nt" else ":"


# -----------------------------
# Console
# -----------------------------
def log(*args):
    try:
        print(*args)
    except Exception:
        pass


def _read_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def _cap(s: Optional[str], n: int) -> str:
    if s is None:
        return ""
    s = str(s)
    return s if len(s) <= n else s[:n] + "\n... [truncated]"


# =========================================================
# Runner helpers
# =========================================================
def b64_utf8(s: str) -> str:
    return base64.b64encode((s or "").encode("utf-8", errors="replace")).decode("ascii")


def build_classpath() -> str:
    jars = [str(p) for p in WORK_DIR.glob("*.jar")]
    return CLASSPATH_SEP.join([str(WORK_DIR)] + jars)


def download_java(script_name: str) -> Path:
    java_filename = f"{script_name}.java"
    local_path = WORK_DIR / java_filename
    if local_path.exists():
        return local_path

    url = f"{GITHUB_BASE_RAW}/{java_filename}"
    log(f"[DL] {java_filename} <- {url}")
    try:
        with urllib.request.urlopen(url) as resp, open(local_path, "wb") as out:
            out.write(resp.read())
    except Exception as e:
        raise FileNotFoundError(f"Failed to download {java_filename}: {e}")

    return local_path


def compile_java(script_name: str) -> None:
    java_filename = f"{script_name}.java"
    src_path = WORK_DIR / java_filename
    if not src_path.exists():
        raise FileNotFoundError(f"{java_filename} not found in {WORK_DIR}")

    cp = build_classpath()
    cmd = [JAVAC_CMD, "-cp", cp, java_filename]

    res = subprocess.run(
        cmd,
        cwd=str(WORK_DIR),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if res.returncode != 0:
        combined = (res.stdout or "") + ("\n" if res.stdout and res.stderr else "") + (res.stderr or "")
        raise RuntimeError(f"[JAVAC] failed for {java_filename}\n{combined}")


def run_java(script_name: str, argv: List[str]) -> str:
    cp = build_classpath()
    cmd = [JAVA_CMD, "-cp", cp, script_name] + argv

    log("[JAVA] Running:", " ".join(cmd))
    res = subprocess.run(
        cmd,
        cwd=str(WORK_DIR),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )

    combined = (res.stdout or "") + ("\n" if res.stdout and res.stderr else "") + (res.stderr or "")
    if res.returncode != 0:
        raise RuntimeError(f"[JAVA] {script_name} exited with {res.returncode}\n{combined}")

    return combined


# =========================================================
# Convert DB step -> CLI argv (B64 flags)
# =========================================================
def build_argv_for_step(step: Dict[str, Any]) -> Tuple[str, List[str], str]:
    script = step["script_name"]
    payload = step.get("payload") or {}

    step_id = step["step_id"]
    run_id = step.get("run_id")
    step_index = step.get("step_index")
    prefix = f"[step_id={step_id} run_id={run_id} step_index={step_index}]"

    mapping: Dict[str, List[Tuple[str, str]]] = {
        "DynamicJarLoader": [("library", "--libraryB64")],
        "DynamicClassCreator": [("class_name", "--nameB64")],
        "CreateDirectory": [("directory_name", "--nameB64"), ("target_path", "--pathB64")],
        "CurrentDirUpdate": [("dirname", "--dirnameB64")],

        "CreateTextFile": [("file_name", "--nameB64"), ("target_path", "--pathB64")],
        "CreateTextFileFromBase64": [("file_name", "--nameB64"), ("target_path", "--pathB64")],

        "DynamicDelegateCreator": [
            ("parent", "--parentB64"),
            ("field_file", "--fieldFileB64"),
            ("method_file", "--methodFileB64"),
            ("output_dir", "--outputDirB64"),
        ],
        "ClassMethodCloner": [
            ("class_name_to_modify", "--classNameToModifyB64"),
            ("delegate_class", "--delegateclassB64"),
            ("method_name", "--methodB64"),
        ],
        "ClassFieldCloner": [
            ("class_name_to_modify", "--classNameToModifyB64"),
            ("delegate_class", "--delegateclassB64"),
            ("field_name", "--fieldB64"),
        ],
        "RunClass": [
            ("class_name", "--classB64"),
            ("args_text", "--argsB64"),
        ],
    }

    argv: List[str] = []

    if script in mapping:
        for col, flag in mapping[script]:
            v = payload.get(col)
            if v is None or str(v).strip() == "":
                continue
            argv += [flag, b64_utf8(str(v))]
    else:
        # fallback: --<col>B64 for every payload key (except step_id)
        for k, v in payload.items():
            if k in ("step_id",) or v is None:
                continue
            argv += [f"--{k}B64", b64_utf8(str(v))]

    # CreateTextFileFromBase64: already base64 -> pass raw as --contentB64
    if script == "CreateTextFileFromBase64":
        v = payload.get("content_b64")
        if v:
            argv += ["--contentB64", str(v)]

    # CreateTextFile: resolve content_ref -> _resolved_content -> content_text
    if script == "CreateTextFile":
        text = None
        if payload.get("_resolved_content") is not None:
            text = payload.get("_resolved_content")
        elif payload.get("content_text"):
            text = payload.get("content_text")

        if text is not None:
            argv += ["--contentB64", b64_utf8(str(text))]

    human_cmd = f"{prefix} java {script} " + " ".join(argv)
    return script, argv, human_cmd.strip()


# =========================================================
# GPT summarizer (CURRENT summarizer style: ONLY "2) WHAT HAPPENED")
# =========================================================
MAX_RULES_CHARS = 80_000
MAX_EVIDENCE_CHARS = 25_000
MAX_PAYLOAD_CHARS = 10_000
MAX_SUMMARY_CHARS = 15_000


def _format_steps_for_inference(steps: List[Dict[str, Any]]) -> str:
    chunks: List[str] = []
    for s in steps:
        step_id = s.get("step_id")
        script = s.get("script_name")
        created_at = s.get("created_at")
        run_id = s.get("run_id")
        step_index = s.get("step_index")
        payload = s.get("payload", {})

        chunks.append(
            f"STEP_ID={step_id}  TIME={created_at}  SCRIPT={script}"
            + (f"  RUN_ID={run_id}" if run_id else "")
            + (f"  STEP_INDEX={step_index}" if step_index is not None else "")
        )

        # tiny signature helps inference
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
                sig = f"--name {payload.get('file_name','')} (content_ref={payload.get('content_ref')})"
            elif script == "DynamicDelegateCreator":
                sig = f"--parent {payload.get('parent','')} --methodFile {payload.get('method_file','')} --fieldFile {payload.get('field_file','')}"
            elif script == "ClassMethodCloner":
                sig = f"--classNameToModify {payload.get('class_name_to_modify','')} --method {payload.get('method_name','')}"
            elif script == "ClassFieldCloner":
                sig = f"--classNameToModify {payload.get('class_name_to_modify','')} --field {payload.get('field_name','')}"
            elif script == "RunClass":
                sig = f"--class {payload.get('class_name','')}"
        except Exception:
            sig = ""

        if sig:
            chunks.append(f"SIG: {sig}")

        chunks.append("PAYLOAD:")
        chunks.append(_cap(str(payload), MAX_PAYLOAD_CHARS) or "{}")
        chunks.append("----")

    return "\n".join(chunks)


def _summarizer_instructions() -> str:
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
        "- Output ONLY one heading: exactly '2) WHAT HAPPENED' then bullet points.\n"
        "- No other headings.\n"
    )


def summarize_last_steps_like_current(
    op: DBOperator,
    rules_text: str,
    n_steps: int,
    model: str,
) -> str:
    cur = op.conn.cursor()
    rows = cur.execute(
        "SELECT step_id FROM pipeline ORDER BY step_id DESC LIMIT ?",
        (int(max(1, n_steps)),),
    ).fetchall()
    if not rows:
        return "2) WHAT HAPPENED\n- No pipeline steps found."

    steps = [op.get_step(int(r["step_id"])) for r in rows]
    steps.reverse()
    evidence = _cap(_format_steps_for_inference(steps), MAX_EVIDENCE_CHARS)

    inp = (
        "STECHEN_SYSTEM_RULES:\n"
        f"{_cap(rules_text, MAX_RULES_CHARS)}\n\n"
        "LAST_STEPS:\n"
        f"{evidence}\n"
    )

    client = OpenAI()
    resp = client.responses.create(
        model=model,
        instructions=_summarizer_instructions(),
        input=inp,
        reasoning={"effort": "low"},
    )
    out = (resp.output_text or "").strip()
    if not out.startswith("2) WHAT HAPPENED"):
        out = "2) WHAT HAPPENED\n" + out
    return _cap(out, MAX_SUMMARY_CHARS)


# =========================================================
# Pending-step selection
# =========================================================
def get_next_pending_step_id(op: DBOperator) -> Optional[int]:
    """
    "Pending" = step exists in pipeline, but there is no SUCCESS RESULT in pipelineLong
    that contains "[step_id=<id>" in the command string.

    (We use command text because pipelineLong doesn't have step_id column.)
    """
    cur = op.conn.cursor()

    # For each pipeline step_id, check if there's a SUCCESS RESULT log referencing it.
    # Pick the smallest step_id that does NOT have a success.
    rows = cur.execute("SELECT step_id FROM pipeline ORDER BY step_id ASC").fetchall()
    for r in rows:
        sid = int(r["step_id"])
        pat = f"%[step_id={sid} %"
        ok = cur.execute(
            """
            SELECT 1 FROM pipelineLong
            WHERE event_type='RESULT' AND status='SUCCESS' AND command LIKE ?
            LIMIT 1
            """,
            (pat,),
        ).fetchone()
        if not ok:
            return sid
    return None


def count_total_success_results(op: DBOperator) -> int:
    cur = op.conn.cursor()
    row = cur.execute(
        "SELECT COUNT(*) AS c FROM pipelineLong WHERE event_type='RESULT' AND status='SUCCESS'"
    ).fetchone()
    return int(row["c"]) if row and row["c"] is not None else 0


# =========================================================
# Main (run only next pending step)
# =========================================================
def main():
    log("[RUN] Running ONE pipeline step (next pending by step_id).")
    log(f"[RUN] DB_PATH:  {Path(DB_PATH).resolve()}")
    log(f"[RUN] WORK_DIR: {WORK_DIR}")
    log(f"[RUN] GITHUB:   {GITHUB_BASE_RAW}")
    log(f"[RUN] CHECKPOINT_EVERY = {CHECKPOINT_EVERY}")

    rules_text = _read_text(Path(RULES_FILE).resolve())

    op = DBOperator(DB_PATH)
    op.ensure_runtime_tables()

    try:
        step_id = get_next_pending_step_id(op)
        if step_id is None:
            log("[RUN] No pending steps. All pipeline steps appear SUCCESS.")
            return

        step = op.get_step(step_id)
        script, argv, human_cmd = build_argv_for_step(step)

        run_id = step.get("run_id")

        log("")
        log("=" * 60)
        log(f"[RUN] step_id={step_id} script={script}")
        log("=" * 60)

        # STEP log
        op.log_pipeline_long("STEP", "RUN", human_cmd, "Starting execution")

        try:
            download_java(script)
            compile_java(script)
            out = run_java(script, argv)

            msg = out.strip() if out.strip() else "Executed successfully"
            op.log_pipeline_long("RESULT", "SUCCESS", human_cmd, msg)

        except Exception as e:
            op.log_pipeline_long("RESULT", "FAIL", human_cmd, str(e))
            log("[FAIL]", e)
            raise

        # checkpoint trigger based on total success count
        if CHECKPOINT_EVERY > 0:
            total_success = count_total_success_results(op)
            if total_success % CHECKPOINT_EVERY == 0:
                summary = summarize_last_steps_like_current(
                    op=op,
                    rules_text=rules_text,
                    n_steps=CHECKPOINT_EVERY,
                    model=MODEL,
                )

                # Choose pipeline step_id range for the last CHECKPOINT_EVERY pipeline steps (by step_id)
                cur = op.conn.cursor()
                last_ids = cur.execute(
                    "SELECT step_id FROM pipeline ORDER BY step_id DESC LIMIT ?",
                    (int(CHECKPOINT_EVERY),),
                ).fetchall()
                last_ids = [int(r["step_id"]) for r in last_ids]
                last_ids.reverse()

                if last_ids:
                    step_id_from = last_ids[0]
                    step_id_to = last_ids[-1]
                else:
                    step_id_from = step_id
                    step_id_to = step_id

                op.insert_checkpoint(
                    run_id=run_id,
                    step_id_from=step_id_from,
                    step_id_to=step_id_to,
                    n_steps=CHECKPOINT_EVERY,
                    summary=summary,
                    state_json=None,
                )

                log("[CHECKPOINT] wrote pipelineCheckpoints:",
                    f"step_id_from={step_id_from}",
                    f"step_id_to={step_id_to}",
                    f"n_steps={CHECKPOINT_EVERY}")

        log("[OK] Executed one step successfully.")

    finally:
        op.close()


if __name__ == "__main__":
    main()
