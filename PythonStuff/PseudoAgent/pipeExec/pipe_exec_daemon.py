# run_all_pipeline_steps.py
#
# Run ALL steps currently stored in pipeline table (ordered by step_id ASC),
# using DBOperator to fetch payload for each step and executing the corresponding
# Java tool fetched from GitHub (cached locally).
#
# ✅ Behavior:
# - Iterates through ALL pipeline records (oldest -> newest by step_id)
# - For each step:
#     - Build argv using B64 flags (Python encodes values, Java decodes)
#     - Download <Script>.java from GitHub if not cached
#     - Compile with jars in WORK_DIR on classpath
#     - Run java tool
#     - Log ONLY STEP + RESULT into pipelineLong
# - DOES NOT delete or modify pipeline table (pure "replay")
#
# Usage:
#   python run_all_pipeline_steps.py
#
# Optional env:
#   STECHEN_DB_PATH, STECHEN_WORK_DIR, STECHEN_GITHUB_BASE_RAW
#
# Notes:
# - Requires your Java tools to support the *B64 flags used below.
# - CreateTextFile supports --contentB64 (decoded inside Java).
# - CreateTextFileFromBase64 supports --contentB64 (already base64, passed raw).
#
import os
import sys
import time
import base64
import subprocess
import urllib.request
import sqlite3
from pathlib import Path
from typing import Any, Dict, List, Tuple

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from db_operator import DBOperator  # db_operator.py in same folder

# -----------------------------
# CONFIG
# -----------------------------
DB_PATH = os.getenv("STECHEN_DB_PATH", "stechen.db")
WORK_DIR = Path(os.getenv("STECHEN_WORK_DIR", ".")).resolve()
GITHUB_BASE_RAW = os.getenv(
    "STECHEN_GITHUB_BASE_RAW",
    "https://raw.githubusercontent.com/Mimkaa/ObjectSchemas/main"
)

JAVA_CMD = "java"
JAVAC_CMD = "javac"
CLASSPATH_SEP = ";" if os.name == "nt" else ":"

PIPELINE_LONG_TABLE = "pipelineLong"
MAX_DB_MSG = 20_000
MAX_DB_CMD = 50_000


# =========================================================
# DB logging (STEP + RESULT only)
# =========================================================
def _ts() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def ensure_pipeline_long_table(conn: sqlite3.Connection) -> None:
    conn.execute(f"""
        CREATE TABLE IF NOT EXISTS {PIPELINE_LONG_TABLE} (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            event_type TEXT NOT NULL,   -- STEP | RESULT
            status TEXT NOT NULL,       -- RUN | SUCCESS | FAIL
            command TEXT,
            message TEXT
        )
    """)
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{PIPELINE_LONG_TABLE}_ts ON {PIPELINE_LONG_TABLE}(ts)")
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{PIPELINE_LONG_TABLE}_event ON {PIPELINE_LONG_TABLE}(event_type)")
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{PIPELINE_LONG_TABLE}_status ON {PIPELINE_LONG_TABLE}(status)")
    conn.commit()


def db_event(conn: sqlite3.Connection, event_type: str, status: str, command: str, message: str) -> None:
    cmd = (command or "")
    msg = (message or "")

    if len(cmd) > MAX_DB_CMD:
        cmd = cmd[:MAX_DB_CMD] + " ... [truncated]"
    if len(msg) > MAX_DB_MSG:
        msg = msg[:MAX_DB_MSG] + " ... [truncated]"

    conn.execute(
        f"INSERT INTO {PIPELINE_LONG_TABLE}(ts, event_type, status, command, message) VALUES(?,?,?,?,?)",
        (_ts(), event_type, status, cmd, msg),
    )
    conn.commit()


# =========================================================
# Runner helpers
# =========================================================
def log(*args):
    try:
        print(*args)
    except Exception:
        pass


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
# Flags must match your UPDATED Java tools.
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
# Main (run all)
# =========================================================
def main():
    log("[RUN] Running ALL pipeline steps (one-shot replay), ordered by step_id ASC.")
    log(f"[RUN] DB_PATH:  {Path(DB_PATH).resolve()}")
    log(f"[RUN] WORK_DIR: {WORK_DIR}")
    log(f"[RUN] GITHUB:   {GITHUB_BASE_RAW}")
    log("[RUN] NOTE: pipeline table is NOT modified (nothing deleted).")

    conn = sqlite3.connect(DB_PATH, timeout=30.0)
    try:
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA synchronous=NORMAL;")
    except Exception:
        pass
    ensure_pipeline_long_table(conn)

    op = DBOperator(DB_PATH)

    try:
        # Get ALL step_ids in chronological order (oldest -> newest)
        cur = op.conn.cursor()
        rows = cur.execute("SELECT step_id FROM pipeline ORDER BY step_id ASC").fetchall()
        if not rows:
            log("[RUN] No pipeline steps found.")
            return

        step_ids = [r["step_id"] for r in rows]
        log(f"[RUN] Found {len(step_ids)} steps.")

        for idx, step_id in enumerate(step_ids, start=1):
            human_cmd = "<unknown>"
            try:
                step = op.get_step(step_id)
                script, argv, human_cmd = build_argv_for_step(step)

                log("")
                log("=" * 60)
                log(f"[RUN] ({idx}/{len(step_ids)}) step_id={step_id} script={script}")
                log("=" * 60)

                # STEP log
                db_event(conn, "STEP", "RUN", human_cmd, "Starting execution")

                # Download + compile + run
                download_java(script)
                compile_java(script)
                out = run_java(script, argv)

                # RESULT log
                msg = out.strip() if out.strip() else "Executed successfully"
                db_event(conn, "RESULT", "SUCCESS", human_cmd, msg)

            except Exception as e:
                # RESULT fail
                try:
                    db_event(conn, "RESULT", "FAIL", human_cmd, str(e))
                except Exception:
                    pass
                log("[FAIL]", e)
                # Stop at first failure (safer for STECHEN)
                raise

        log("")
        log("[OK] Finished running all pipeline steps.")

    finally:
        try:
            op.close()
        except Exception:
            pass
        try:
            conn.close()
        except Exception:
            pass


if __name__ == "__main__":
    main()
