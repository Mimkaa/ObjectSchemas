# planner.py
#
# STECHEN Planner (DB-driven, schema-safe) — NO CHECKPOINT WRITES
#
# ✅ Behavior:
# 1) If pipeline is empty → seed FIRST mandatory step:
#       DynamicJarLoader { "library": "net.bytebuddy:byte-buddy:1.15.3" }
#    (and EXIT)  — no OpenAI call, no checkpoints written
# 2) If pipeline not empty → build the SAME planner input as before:
#       RULES + GOAL + ALL EXISTING CHECKPOINTS (as context) + last executor logs
#       + fresh summarizer output + last N evidence
#    Then ask GPT for EXACTLY ONE next step JSON and insert it into the DB.
#
# ❌ Removed:
# - Any writes to pipelineCheckpoints (planner never inserts checkpoints)
#
# Usage:
#   python planner.py
#
# Env:
#   OPENAI_API_KEY (required unless DB empty and seeding first step)
#   STECHEN_DB_PATH, STECHEN_RULES_FILE, STECHEN_GOAL_FILE
#   STECHEN_N_STEPS (default 5)   [kept, but summary/evidence overridden below]
#   STECHEN_MODEL (default "gpt-5.2")
#   STECHEN_RUN_ID (optional fallback)
#
import os
import json
import time
import sqlite3
from pathlib import Path
from typing import Any, Dict, List, Optional

from openai import OpenAI
from db_operator import DBOperator


# -----------------------------
# CONFIG
# -----------------------------
DB_PATH = os.getenv("STECHEN_DB_PATH", "stechen.db")
RULES_FILE = os.getenv("STECHEN_RULES_FILE", "RULES.txt")
GOAL_FILE = os.getenv("STECHEN_GOAL_FILE", "goal.txt")

N_STEPS = int(os.getenv("STECHEN_N_STEPS", "5"))
MODEL = os.getenv("STECHEN_MODEL", "gpt-5.2")
DEFAULT_RUN_ID = os.getenv("STECHEN_RUN_ID", None)

PIPELINE_LONG_TABLE = "pipelineLong"
CHECKPOINTS_TABLE = "pipelineCheckpoints"

# caps removed logically by making _cap a no-op
MAX_RULES_CHARS = 80_000
MAX_GOAL_CHARS = 20_000
MAX_EVIDENCE_CHARS = 25_000
MAX_SUMMARY_CHARS = 15_000
MAX_PLAN_JSON_CHARS = 15_000

MAX_CHECKPOINTS_CONTEXT_CHARS = 60_000
MAX_ONE_CHECKPOINT_SUMMARY_CHARS = 6_000
MAX_ONE_CHECKPOINT_STATE_CHARS = 2_000

MAX_PAYLOAD_CHARS = 10_000
MAX_VALUE_CHARS = 4_000

# Force these per your request:
FRESH_SUMMARY_N = 50     # <-- fresh summary is always last 50
EVIDENCE_N = 5           # <-- evidence is last 5 steps

# Seed behavior: if pipeline empty, we insert this as the first step
SEED_FIRST_STEP = {
    "script_name": "DynamicJarLoader",
    "params": {"library": "net.bytebuddy:byte-buddy:1.15.3"},
    "run_id": DEFAULT_RUN_ID,
    "step_index": None,
}


# -----------------------------
# small utils
# -----------------------------
def _cap(s: Optional[str], n: int) -> str:
    # ✅ TRUNCATION REMOVED: always return full text
    if s is None:
        return ""
    return str(s)


def _read_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def _safe_table_name(name: str) -> str:
    out = []
    for ch in str(name):
        if ch.isalnum() or ch == "_":
            out.append(ch)
    return "".join(out)


def _ts() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def log(*args) -> None:
    try:
        print(*args)
    except Exception:
        pass


def row_to_dict(cur: sqlite3.Cursor, row: Any) -> Dict[str, Any]:
    if row is None:
        return {}
    if isinstance(row, sqlite3.Row):
        return dict(row)
    cols = [d[0] for d in (cur.description or [])]
    return dict(zip(cols, row))


# -----------------------------
# DB reads: pipelineLong + checkpoints
# -----------------------------
def ensure_schema_tables_exist(conn: sqlite3.Connection) -> None:
    conn.execute(f"""
        CREATE TABLE IF NOT EXISTS {PIPELINE_LONG_TABLE} (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          ts TEXT NOT NULL,
          event_type TEXT NOT NULL,
          status TEXT NOT NULL,
          command TEXT,
          message TEXT
        )
    """)
    conn.execute(f"""
        CREATE TABLE IF NOT EXISTS {CHECKPOINTS_TABLE} (
          checkpoint_id INTEGER PRIMARY KEY AUTOINCREMENT,
          created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S','now')),
          run_id        TEXT,
          step_id_from  INTEGER NOT NULL,
          step_id_to    INTEGER NOT NULL,
          n_steps       INTEGER NOT NULL,
          summary       TEXT NOT NULL,
          state_json    TEXT
        )
    """)
    conn.commit()


def get_last_pipeline_long(conn: sqlite3.Connection) -> Dict[str, Optional[Dict[str, Any]]]:
    out: Dict[str, Optional[Dict[str, Any]]] = {"last_step": None, "last_result": None}
    cur = conn.cursor()

    cur.execute(
        f"""
        SELECT id, ts, event_type, status, command, message
        FROM {PIPELINE_LONG_TABLE}
        WHERE event_type='STEP'
        ORDER BY id DESC
        LIMIT 1
        """
    )
    row_step = cur.fetchone()
    if row_step:
        out["last_step"] = row_to_dict(cur, row_step)

    cur.execute(
        f"""
        SELECT id, ts, event_type, status, command, message
        FROM {PIPELINE_LONG_TABLE}
        WHERE event_type='RESULT'
        ORDER BY id DESC
        LIMIT 1
        """
    )
    row_res = cur.fetchone()
    if row_res:
        out["last_result"] = row_to_dict(cur, row_res)

    return out


def get_all_checkpoints(conn: sqlite3.Connection) -> List[Dict[str, Any]]:
    cur = conn.cursor()
    cur.execute(
        f"""
        SELECT checkpoint_id, created_at, run_id, step_id_from, step_id_to, n_steps, summary, state_json
        FROM {CHECKPOINTS_TABLE}
        ORDER BY checkpoint_id ASC
        """
    )
    rows = cur.fetchall() or []
    return [row_to_dict(cur, r) for r in rows]


def format_all_checkpoints(checkpoints: List[Dict[str, Any]]) -> str:
    if not checkpoints:
        return "CHECKPOINTS: <none>\n"

    parts: List[str] = ["CHECKPOINTS (oldest -> newest):"]
    for cp in checkpoints:
        parts.append(
            f"- checkpoint_id={cp.get('checkpoint_id')} created_at={cp.get('created_at')} run_id={cp.get('run_id')} "
            f"step_id_from={cp.get('step_id_from')} step_id_to={cp.get('step_id_to')} n_steps={cp.get('n_steps')}"
        )
        parts.append("  SUMMARY:")
        # ✅ no truncation
        parts.append("  " + str(cp.get("summary") or "").replace("\n", "\n  "))
        if cp.get("state_json"):
            parts.append("  STATE_JSON:")
            parts.append("  " + str(cp.get("state_json") or "").replace("\n", "\n  "))
        parts.append("")

    txt = "\n".join(parts).strip() + "\n"
    return txt


# -----------------------------
# Summarizer (same style as stechen_gpt_summarize.py)
# -----------------------------
def _fetch_payload(conn: sqlite3.Connection, script_name: str, step_id: int) -> Dict[str, Any]:
    cur = conn.cursor()
    t = _safe_table_name(script_name)

    cur.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        (t,),
    )
    if not cur.fetchone():
        return {"_payload_error": f"Missing table for script: {t}"}

    cur.execute(f"SELECT * FROM {t} WHERE step_id=?", (int(step_id),))
    row = cur.fetchone()
    if not row:
        return {"_payload_error": f"No payload row in {t} for step_id={step_id}"}

    payload = row_to_dict(cur, row)

    # ✅ no truncation of fields
    for k, v in list(payload.items()):
        if isinstance(v, (bytes, bytearray)):
            payload[k] = f"<{len(v)} bytes>"
        else:
            payload[k] = v

    # Resolve payload_store reference if present
    if "content_ref" in payload and payload.get("content_ref"):
        ref = payload["content_ref"]
        cur.execute("SELECT text_content FROM payload_store WHERE payload_id=?", (ref,))
        r2 = cur.fetchone()
        if r2:
            r2d = row_to_dict(cur, r2)
            payload["_resolved_content"] = r2d.get("text_content") or ""

    return payload


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
            elif script == "CreateTextFileFromBase64":
                sig = f"--name {payload.get('file_name','')} (content_b64_len={len(payload.get('content_b64') or '')})"
            elif script == "DynamicDelegateCreator":
                sig = (
                    f"--parent {payload.get('parent','')} "
                    f"--methodFile {payload.get('method_file','')} "
                    f"--fieldFile {payload.get('field_file','')} "
                    f"--outputDir {payload.get('output_dir','')}"
                )
            elif script == "ClassMethodCloner":
                sig = (
                    f"--classNameToModify {payload.get('class_name_to_modify','')} "
                    f"--delegateclass {payload.get('delegate_class','')} "
                    f"--method {payload.get('method_name','')}"
                )
            elif script == "ClassFieldCloner":
                sig = (
                    f"--classNameToModify {payload.get('class_name_to_modify','')} "
                    f"--delegateclass {payload.get('delegate_class','')} "
                    f"--field {payload.get('field_name','')}"
                )
            elif script == "RunClass":
                sig = f"--class {payload.get('class_name','')} --args {payload.get('args_text','')}"
        except Exception:
            sig = ""

        if sig:
            chunks.append(f"SIG: {sig}")

        chunks.append("PAYLOAD:")
        chunks.append(str(payload) if payload is not None else "{}")
        chunks.append("----")

    return "\n".join(chunks)


def _summarizer_instructions() -> str:
    # NOTE: you asked to keep this exactly as-is, so it's unchanged.
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


def summarize_last_steps_like_current_summarizer(
    conn: sqlite3.Connection,
    rules_text: str,
    n_steps: int,
    model: str,
) -> str:
    cur = conn.cursor()
    cur.execute(
        "SELECT step_id, script_name, created_at, run_id, step_index "
        "FROM pipeline ORDER BY step_id DESC LIMIT ?",
        (int(n_steps),),
    )
    rows = cur.fetchall() or []

    if not rows:
        return "2) WHAT HAPPENED\n- No pipeline steps found."

    steps_newest_first: List[Dict[str, Any]] = []
    for r in rows:
        if isinstance(r, sqlite3.Row):
            step_id = int(r["step_id"])
            script_name = str(r["script_name"])
            created_at = r["created_at"]
            run_id = r["run_id"]
            step_index = r["step_index"]
        else:
            step_id = int(r[0])
            script_name = str(r[1])
            created_at = r[2]
            run_id = r[3]
            step_index = r[4]

        payload = _fetch_payload(conn, script_name, step_id)
        steps_newest_first.append(
            {
                "step_id": step_id,
                "script_name": script_name,
                "created_at": created_at,
                "run_id": run_id,
                "step_index": step_index,
                "payload": payload,
            }
        )

    steps_chrono = list(reversed(steps_newest_first))
    steps_text = _format_steps_for_inference(steps_chrono)

    input_text = (
        "STECHEN_SYSTEM_RULES:\n"
        f"{rules_text}\n\n"
        "LAST_STEPS:\n"
        f"{steps_text}\n"
    )

    client = OpenAI()
    resp = client.responses.create(
        model=model,
        instructions=_summarizer_instructions(),
        input=input_text,
        reasoning={"effort": "low"},
    )

    out = (resp.output_text or "").strip()
    if not out.startswith("2) WHAT HAPPENED"):
        out = "2) WHAT HAPPENED\n" + out
    return out


# -----------------------------
# Planner prompt: produce ONE next DB step JSON
# -----------------------------
def planner_instructions(schema_hint: str) -> str:
    return (
        "You are the STECHEN planner.\n"
        "You will be given RULES, GOAL, checkpoints, last executor log, a fresh pipeline summary, and step evidence.\n"
        "You must output EXACTLY ONE next pipeline DB step to insert.\n\n"
        "HARD OUTPUT RULE:\n"
        "- Output ONLY a single JSON object. No markdown. No extra text.\n\n"
        "JSON schema:\n"
        "{\n"
        "  \"script_name\": \"<tool name>\",\n"
        "  \"params\": {\"<db_column>\": \"<value>\", ...},\n"
        "  \"run_id\": \"<optional>\",\n"
        "  \"step_index\": null\n"
        "}\n\n"
        "CRITICAL: params keys MUST match the per-script table column names.\n"
        "Use these exact keys (schema hint):\n"
        f"{schema_hint}\n\n"
        "Constraints:\n"
        "- Choose the smallest safe next step that follows the rules.\n"
        "- Do NOT invent past actions; base decisions on evidence.\n"
        "- If next step is CreateTextFile with huge content, prefer content_ref convention (payload_store).\n"
    )


def parse_plan_json(raw: str) -> Dict[str, Any]:
    s = (raw or "").strip()
    if not s:
        raise ValueError("Planner returned empty output.")

    try:
        obj = json.loads(s)
    except Exception as e:
        # ✅ no truncation of RAW
        raise ValueError(f"Planner output is not valid JSON: {e}\nRAW:\n{s}")

    if not isinstance(obj, dict):
        raise ValueError("Planner JSON must be an object.")
    if "script_name" not in obj or "params" not in obj:
        raise ValueError("Planner JSON must contain 'script_name' and 'params'.")
    if not isinstance(obj["params"], dict):
        raise ValueError("'params' must be an object.")

    if not obj.get("run_id"):
        obj["run_id"] = DEFAULT_RUN_ID
    if "step_index" not in obj:
        obj["step_index"] = None

    return obj


def normalize_plan_params(script_name: str, params: Dict[str, Any]) -> Dict[str, Any]:
    p = dict(params or {})

    if script_name == "DynamicClassCreator":
        if "class_name" not in p and "name" in p:
            p["class_name"] = p.pop("name")

    if script_name == "DynamicJarLoader":
        if "library" not in p and "lib" in p:
            p["library"] = p.pop("lib")

    if script_name == "CurrentDirUpdate":
        if "dirname" not in p and "dir" in p:
            p["dirname"] = p.pop("dir")

    if script_name == "CreateDirectory":
        if "directory_name" not in p and "name" in p:
            p["directory_name"] = p.pop("name")

    if script_name == "RunClass":
        if "class_name" not in p and "class" in p:
            p["class_name"] = p.pop("class")

    return p


def get_schema_hint(op: DBOperator, script_names: List[str]) -> str:
    lines: List[str] = []
    cur = op.conn.cursor()
    for s in script_names:
        t = _safe_table_name(s)
        cur.execute(f"PRAGMA table_info({t})")
        cols = cur.fetchall() or []
        if not cols:
            continue

        required: List[str] = []
        optional: List[str] = []
        for r in cols:
            rdict = dict(r) if isinstance(r, sqlite3.Row) else {
                "name": r[1], "notnull": r[3], "dflt_value": r[4], "pk": r[5]
            }
            name = rdict["name"]
            if name == "step_id":
                continue
            notnull = int(rdict.get("notnull") or 0)
            dflt = rdict.get("dflt_value")
            if notnull == 1 and dflt is None:
                required.append(name)
            else:
                optional.append(name)

        if required:
            lines.append(f"- {t}: params MUST include {required}; optional {optional}")
        else:
            lines.append(f"- {t}: optional params {optional}")

    lines.append("")
    lines.append("Examples:")
    lines.append('- DynamicClassCreator → {"class_name": "..."}')
    lines.append('- DynamicJarLoader    → {"library": "net.bytebuddy:byte-buddy:1.15.3"}')
    return "\n".join(lines).strip()


def validate_params_against_schema(op: DBOperator, script_name: str, params: Dict[str, Any]) -> None:
    cur = op.conn.cursor()
    t = _safe_table_name(script_name)

    cur.execute(f"PRAGMA table_info({t})")
    cols = cur.fetchall() or []
    if not cols:
        raise ValueError(f"Schema error: table '{t}' does not exist.")

    required: List[str] = []
    for r in cols:
        rdict = dict(r) if isinstance(r, sqlite3.Row) else {
            "name": r[1], "notnull": r[3], "dflt_value": r[4], "pk": r[5]
        }
        name = rdict["name"]
        if name == "step_id":
            continue
        notnull = int(rdict.get("notnull") or 0)
        dflt = rdict.get("dflt_value")
        pk = int(rdict.get("pk") or 0)
        if pk == 1:
            continue
        if notnull == 1 and dflt is None:
            required.append(name)

    missing = [c for c in required if c not in params or str(params.get(c) or "").strip() == ""]
    if missing:
        raise ValueError(
            f"Planner produced invalid params for '{t}'. Missing required columns: {missing}. "
            f"Got keys: {list(params.keys())}"
        )


def pipeline_is_empty(conn: sqlite3.Connection) -> bool:
    cur = conn.cursor()
    cur.execute("SELECT 1 FROM pipeline LIMIT 1")
    return cur.fetchone() is None


# -----------------------------
# Main
# -----------------------------
def main():
    base_dir = Path(__file__).resolve().parent

    db_p = Path(DB_PATH)
    if not db_p.is_absolute():
        db_p = (base_dir / db_p).resolve()

    rules_p = Path(RULES_FILE)
    if not rules_p.is_absolute():
        rules_p = (base_dir / rules_p).resolve()

    goal_p = Path(GOAL_FILE)
    if not goal_p.is_absolute():
        goal_p = (base_dir / goal_p).resolve()

    rules_text = _read_text(rules_p)
    goal_text = _read_text(goal_p)

    if not db_p.exists():
        raise FileNotFoundError(f"Database not found: {db_p}")

    conn = sqlite3.connect(str(db_p), timeout=30.0)
    conn.row_factory = sqlite3.Row
    try:
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA synchronous=NORMAL;")
    except Exception:
        pass

    ensure_schema_tables_exist(conn)

    # If pipeline is empty -> seed first mandatory step and exit successfully.
    if pipeline_is_empty(conn):
        log("[PLANNER] Pipeline is empty. Seeding first mandatory step:")
        log("          ", SEED_FIRST_STEP)

        op = DBOperator(str(db_p))
        try:
            op.ensure_runtime_tables()
            script_name = SEED_FIRST_STEP["script_name"]
            params = SEED_FIRST_STEP["params"]
            run_id = SEED_FIRST_STEP.get("run_id")
            step_index = SEED_FIRST_STEP.get("step_index")

            params = normalize_plan_params(script_name, params)
            validate_params_against_schema(op, script_name, params)

            new_step_id = op.insert_step(
                script_name=script_name,
                params=params,
                run_id=run_id,
                step_index=step_index,
            )
        finally:
            try:
                op.close()
            except Exception:
                pass
            try:
                conn.close()
            except Exception:
                pass

        log(f"[OK] Seeded step_id={new_step_id} ({script_name})")
        return

    # -----------------------------
    # Normal planner flow (non-empty pipeline)
    # -----------------------------
    all_cps = get_all_checkpoints(conn)
    checkpoints_txt = format_all_checkpoints(all_cps)

    logs = get_last_pipeline_long(conn)

    fresh_summary = summarize_last_steps_like_current_summarizer(
        conn=conn,
        rules_text=rules_text,
        n_steps=FRESH_SUMMARY_N,
        model=MODEL,
    )

    # Evidence is last EVIDENCE_N pipeline steps
    cur = conn.cursor()
    cur.execute(
        "SELECT step_id, script_name, created_at, run_id, step_index "
        "FROM pipeline ORDER BY step_id DESC LIMIT ?",
        (int(EVIDENCE_N),),
    )
    rows = cur.fetchall() or []

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
    steps_chrono = list(reversed(steps_newest_first))
    evidence_text = _format_steps_for_inference(steps_chrono)

    last_exec_txt = "EXECUTOR_LOGS: <none>\n"
    if logs["last_step"] or logs["last_result"]:
        parts = ["EXECUTOR_LOGS:"]
        if logs["last_step"]:
            parts.append(f"LAST_STEP: ts={logs['last_step'].get('ts')} status={logs['last_step'].get('status')}")
            parts.append(f"COMMAND: {logs['last_step'].get('command')}")
            parts.append(f"MESSAGE: {logs['last_step'].get('message')}")
        if logs["last_result"]:
            parts.append(f"LAST_RESULT: ts={logs['last_result'].get('ts')} status={logs['last_result'].get('status')}")
            parts.append(f"COMMAND: {logs['last_result'].get('command')}")
            parts.append(f"MESSAGE: {logs['last_result'].get('message')}")
        last_exec_txt = "\n".join(parts) + "\n"

    op_for_schema = DBOperator(str(db_p))
    try:
        schema_hint = get_schema_hint(
            op_for_schema,
            script_names=[
                "DynamicJarLoader",
                "DynamicClassCreator",
                "CreateDirectory",
                "CurrentDirUpdate",
                "CreateTextFile",
                "CreateTextFileFromBase64",
                "DynamicDelegateCreator",
                "ClassMethodCloner",
                "ClassFieldCloner",
                "RunClass",
            ],
        )
    finally:
        try:
            op_for_schema.close()
        except Exception:
            pass

    planner_input = (
        "STECHEN_RULES:\n"
        f"{rules_text}\n\n"
        "GOAL:\n"
        f"{goal_text}\n\n"
        f"{checkpoints_txt}\n"
        f"{last_exec_txt}\n"
        "FRESH_SUMMARY:\n"
        f"{fresh_summary}\n\n"
        "LAST_PIPELINE_STEPS_EVIDENCE:\n"
        f"{evidence_text}\n"
    )

    client = OpenAI()
    resp = client.responses.create(
        model=MODEL,
        instructions=planner_instructions(schema_hint=schema_hint),
        input=planner_input,
        reasoning={"effort": "low"},
    )
    raw_plan = (resp.output_text or "").strip()
    plan = parse_plan_json(raw_plan)

    script_name = _safe_table_name(str(plan["script_name"]))
    params = plan["params"] or {}
    run_id = plan.get("run_id")
    step_index = plan.get("step_index")

    op = DBOperator(str(db_p))
    try:
        op.ensure_runtime_tables()

        params = normalize_plan_params(script_name, params)
        validate_params_against_schema(op, script_name, params)

        new_step_id = op.insert_step(
            script_name=script_name,
            params=params,
            run_id=run_id,
            step_index=step_index,
        )
    finally:
        try:
            op.close()
        except Exception:
            pass
        try:
            conn.close()
        except Exception:
            pass

    log(f"[OK] Inserted next pipeline step_id={new_step_id} script={script_name}")
    log("[OK] Note: planner does NOT write pipelineCheckpoints (handled by checkpoint writer).")


if __name__ == "__main__":
    main()
