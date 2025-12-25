# pipe_exec_daemon.py
#
# ✅ DB logging policy (STRICT):
#   - Write to DB ONLY when:
#       1) A block is about to be executed (the command)   -> event_type='STEP'
#       2) The block finished (SUCCESS/FAIL)              -> event_type='RESULT'
#   - NOTHING else goes to DB (no [JAVAC] OK, no [DL], no idle waiting, etc.)
#
# Console output remains verbose (as before).

import sys
import os
import re
import base64
import subprocess
import urllib.request
import time
import sqlite3
from pathlib import Path
from typing import Optional

# =========================================================
# Fix Windows console Unicode crashes (cp1252 etc.)
# =========================================================
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# =========================================================
# HARD-CODED PATHS (relative to this file)
# =========================================================
PIPE_EXEC_DIR = Path(__file__).resolve().parent           # ...\PseudoAgent\pipeExec
BASE_DIR = PIPE_EXEC_DIR.parent                           # ...\PseudoAgent

sys.path.insert(0, str(BASE_DIR))
from stechen_db import StechenDB  # noqa: E402

DB_FILE = BASE_DIR / "stechen.db"
PIPELINE_FILE = PIPE_EXEC_DIR / "pipeline.txt"
WORK_DIR = BASE_DIR

# ==============================================
# CONFIG
# ==============================================
GITHUB_BASE_RAW = "https://raw.githubusercontent.com/Mimkaa/ObjectSchemas/main"

JAVA_CMD = "java"
JAVAC_CMD = "javac"
CLASSPATH_SEP = ";" if os.name == "nt" else ":"

POLL_INTERVAL_SEC = 0.5
IDLE_PRINT_EVERY_SEC = 10.0

MAX_DB_OUTPUT_CHARS = 200_000

# =========================================================
# DB RUNTIME LOGGING (ONLY STEP + RESULT)
# =========================================================
PIPELINE_LONG_TABLE = "pipelineLong"
_DB_CONN: sqlite3.Connection | None = None


def _ts() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def ensure_pipeline_long_table(conn: sqlite3.Connection) -> None:
    """
    Minimal, planner-friendly table:
    - exactly 2 rows per executed block (STEP + RESULT)
    """
    conn.execute(f"""
        CREATE TABLE IF NOT EXISTS {PIPELINE_LONG_TABLE} (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            event_type TEXT NOT NULL,   -- 'STEP' or 'RESULT'
            status TEXT NOT NULL,       -- 'RUN' | 'SUCCESS' | 'FAIL'
            command TEXT,               -- full command for STEP, repeated for RESULT (optional but useful)
            message TEXT                -- short human message / error summary
        )
    """)
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{PIPELINE_LONG_TABLE}_ts ON {PIPELINE_LONG_TABLE}(ts)")
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{PIPELINE_LONG_TABLE}_event ON {PIPELINE_LONG_TABLE}(event_type)")
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{PIPELINE_LONG_TABLE}_status ON {PIPELINE_LONG_TABLE}(status)")
    conn.commit()


def _db_insert_event(conn: sqlite3.Connection, event_type: str, status: str, command: str | None, message: str) -> None:
    ts = _ts()
    msg = (message or "")
    cmd = (command or "")

    # keep rows reasonably sized
    if len(msg) > 20_000:
        msg = msg[:20_000] + " ... [truncated]"
    if len(cmd) > 50_000:
        cmd = cmd[:50_000] + " ... [truncated]"

    for _ in range(5):
        try:
            conn.execute(
                f"INSERT INTO {PIPELINE_LONG_TABLE}(ts, event_type, status, command, message) VALUES(?,?,?,?,?)",
                (ts, event_type, status, cmd, msg),
            )
            conn.commit()
            return
        except sqlite3.OperationalError as e:
            if "locked" in str(e).lower():
                time.sleep(0.05)
                continue
            raise


def db_step(command: str) -> None:
    """
    Write STEP row to DB.
    """
    global _DB_CONN
    if _DB_CONN is None:
        return
    try:
        _db_insert_event(_DB_CONN, "STEP", "RUN", command, "Starting execution")
    except Exception:
        pass


def db_result(command: str, ok: bool, error_summary: str = "") -> None:
    """
    Write RESULT row to DB.
    """
    global _DB_CONN
    if _DB_CONN is None:
        return
    try:
        if ok:
            _db_insert_event(_DB_CONN, "RESULT", "SUCCESS", command, "Executed successfully")
        else:
            msg = error_summary.strip() or "Execution failed"
            _db_insert_event(_DB_CONN, "RESULT", "FAIL", command, msg)
    except Exception:
        pass


# =========================================================
# CONSOLE LOGGING (prints only)
# =========================================================
def log(*args, sep=" ", end="\n") -> None:
    msg = sep.join(str(a) for a in args)
    try:
        print(msg, end=end)
    except Exception:
        pass

# ==============================================
# CLASSPATH BUILDER (WORK_DIR + all jars)
# ==============================================
def build_classpath() -> str:
    jars = [str(p) for p in WORK_DIR.glob("*.jar")]
    return CLASSPATH_SEP.join([str(WORK_DIR)] + jars)

# ==============================================
# DOWNLOAD JAVA FILE (IN WORK_DIR)
# ==============================================
def download_java(script_name: str) -> Path:
    java_filename = f"{script_name}.java"
    local_path = WORK_DIR / java_filename

    if local_path.exists():
        log(f"[CACHE] Using cached {java_filename}")
        return local_path

    url = f"{GITHUB_BASE_RAW}/{java_filename}"
    log(f"[DL] Downloading {java_filename} from {url}")

    try:
        with urllib.request.urlopen(url) as resp, open(local_path, "wb") as out:
            out.write(resp.read())
    except Exception as e:
        raise FileNotFoundError(f"Failed to download {java_filename}: {e}")

    log(f"[DL] Saved {local_path}")
    return local_path

# ==============================================
# COMPILE JAVA SOURCE (WORK_DIR)
# ==============================================
def compile_java(script_name: str) -> str:
    java_filename = f"{script_name}.java"
    src_path = WORK_DIR / java_filename

    if not src_path.exists():
        raise FileNotFoundError(f"{java_filename} not found at {src_path}")

    classpath = build_classpath()
    cmd = [JAVAC_CMD, "-cp", classpath, java_filename]

    log(f"[JAVAC] Compiling {java_filename} ...")
    result = subprocess.run(
        cmd,
        cwd=str(WORK_DIR),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )

    combined = ""
    if result.stdout:
        combined += result.stdout
    if result.stderr:
        if combined and not combined.endswith("\n"):
            combined += "\n"
        combined += result.stderr

    if result.returncode != 0:
        log("[JAVAC] ERROR")
        if combined:
            log(combined)
        raise RuntimeError(f"Compilation failed for {java_filename}\n{combined}")

    log("[JAVAC] OK")
    return combined

# ==============================================
# JAVA RUNNER (WORK_DIR)
# ==============================================
def run_java(script_name: str, raw_params) -> str:
    classpath = build_classpath()
    cmd = [JAVA_CMD, "-cp", classpath, script_name] + raw_params

    log(f"[JAVA] Running: {' '.join(cmd)}")
    result = subprocess.run(
        cmd,
        cwd=str(WORK_DIR),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )

    combined = ""
    if result.stdout:
        combined += result.stdout
    if result.stderr:
        if combined and not combined.endswith("\n"):
            combined += "\n"
        combined += result.stderr

    if result.returncode != 0:
        raise RuntimeError(f"{script_name} exited with code {result.returncode}\n{combined}")

    return combined

# ==============================================
# BASE64 FLAG DECODER
# ==============================================
def decode_b64_flags(params):
    out, i = [], 0
    while i < len(params):
        flag = params[i]

        if flag.startswith("--") and flag.lower().endswith(("b64", "base64")):
            if i + 1 >= len(params):
                raise RuntimeError(f"{flag} missing value")

            decoded = base64.b64decode(params[i + 1]).decode("utf-8", errors="replace")
            clean_flag = flag[:-3] if flag.lower().endswith("b64") else flag[:-6]

            out += [clean_flag, decoded]
            i += 2
            continue

        out.append(flag)
        i += 1

    return out

# ==============================================
# PROCESS ONE BLOCK
#   Returns: (command_text_for_db, combined_output)
# ==============================================
def process_block(block_lines):
    logical = [l.rstrip() for l in block_lines if l.strip() and not l.lstrip().startswith("#")]
    if not logical:
        return None, None

    command = " ".join(logical).strip()

    # Console only (verbose)
    log("")
    log("==============================")
    log(f"[STEP] {command}")
    log("==============================")

    combined_output = ""

    # Special handling: --SomethingB64 (raw tail becomes base64 payload)
    m = re.search(r'(?<!\S)(--[A-Za-z0-9_-]+(?:B64|Base64))\s+', command)
    if m:
        flag = m.group(1)
        head = command[:m.start()].strip()
        raw_tail = command[m.end():]

        parts = head.split()
        script_name = parts[0]
        params = parts[1:]

        encoded = base64.b64encode(raw_tail.encode("utf-8")).decode("ascii")
        params += [flag, encoded]
        params = decode_b64_flags(params)

        download_java(script_name)
        comp_out = compile_java(script_name)
        run_out = run_java(script_name, params)

        if comp_out:
            combined_output += "[javac]\n" + comp_out
            if not combined_output.endswith("\n"):
                combined_output += "\n"
        if run_out:
            combined_output += "[java]\n" + run_out

        return command, combined_output

    # Normal case
    parts = command.split()
    script_name = parts[0]
    params = decode_b64_flags(parts[1:])

    download_java(script_name)
    comp_out = compile_java(script_name)
    run_out = run_java(script_name, params)

    if comp_out:
        combined_output += "[javac]\n" + comp_out
        if not combined_output.endswith("\n"):
            combined_output += "\n"
    if run_out:
        combined_output += "[java]\n" + run_out

    return command, combined_output

# ==============================================
# BLOCK PARSING (2 blank lines = separator)
# ==============================================
def parse_blocks_from_text(text: str):
    lines = text.splitlines()
    blocks, current, blanks = [], [], 0

    for line in lines:
        if line.strip() == "":
            blanks += 1
            if blanks == 2:
                blocks.append(current)
                current = []
                blanks = 0
            else:
                current.append(line)
            continue

        blanks = 0
        current.append(line)

    if current:
        blocks.append(current)

    def is_effectively_empty(b):
        return not any(l.strip() and not l.lstrip().startswith("#") for l in b)

    blocks = [b for b in blocks if not is_effectively_empty(b)]
    return blocks


def blocks_to_text(blocks):
    out_lines = []
    for bi, block in enumerate(blocks):
        while block and block[-1].strip() == "":
            block = block[:-1]
        out_lines.extend(block)
        if bi != len(blocks) - 1:
            out_lines.append("")
            out_lines.append("")
    return "\n".join(out_lines) + ("\n" if out_lines else "")

# ==============================================
# ATOMIC FILE WRITE
# ==============================================
def atomic_write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8", errors="replace")
    tmp.replace(path)

# ==============================================
# CONSUME ONE BLOCK
# ==============================================
def try_consume_one_block(pipeline_path: Path):
    pipeline_path.parent.mkdir(parents=True, exist_ok=True)
    if not pipeline_path.exists():
        pipeline_path.write_text("", encoding="utf-8", errors="replace")

    text = pipeline_path.read_text(encoding="utf-8", errors="replace")
    blocks = parse_blocks_from_text(text)
    if not blocks:
        return None, None

    first = blocks[0]
    rest = blocks[1:]
    atomic_write(pipeline_path, blocks_to_text(rest))
    return first, rest


def put_block_back_on_top(pipeline_path: Path, block_lines):
    text = pipeline_path.read_text(encoding="utf-8", errors="replace") if pipeline_path.exists() else ""
    blocks = parse_blocks_from_text(text)
    blocks = [block_lines] + blocks
    atomic_write(pipeline_path, blocks_to_text(blocks))


def cap_output(s: Optional[str]) -> Optional[str]:
    if s is None:
        return None
    if len(s) <= MAX_DB_OUTPUT_CHARS:
        return s
    return s[:MAX_DB_OUTPUT_CHARS] + "\n... [truncated]"

# ==============================================
# MAIN DAEMON LOOP
# ==============================================
def main():
    global _DB_CONN

    # Open sqlite connection for runtime STEP/RESULT logs only
    _DB_CONN = sqlite3.connect(str(DB_FILE), timeout=30.0, isolation_level=None)
    try:
        _DB_CONN.execute("PRAGMA journal_mode=WAL;")
        _DB_CONN.execute("PRAGMA synchronous=NORMAL;")
    except Exception:
        pass

    ensure_pipeline_long_table(_DB_CONN)

    log("[EXEC] Pipe executor daemon started.")
    log(f"[EXEC] Watching:  {PIPELINE_FILE}")
    log(f"[EXEC] DB:        {DB_FILE}")
    log(f"[EXEC] WORK_DIR:  {WORK_DIR}")
    log("[EXEC] Behavior: consume first block, execute it, remove it from file.")
    log("[EXEC] If file is empty: wait.\n")

    pipeline_path = PIPELINE_FILE
    last_idle_print = 0.0

    db = StechenDB(str(DB_FILE))
    db.init()

    while True:
        block, _ = try_consume_one_block(pipeline_path)

        if block is None:
            now = time.time()
            if now - last_idle_print >= IDLE_PRINT_EVERY_SEC:
                log("[EXEC] pipeline.txt empty - waiting...")
                last_idle_print = now
            time.sleep(POLL_INTERVAL_SEC)
            continue

        command_text: Optional[str] = None
        combined_output: Optional[str] = None

        try:
            # Build command string early so we can log STEP even if execution fails later
            logical = [l.rstrip() for l in block if l.strip() and not l.lstrip().startswith("#")]
            command_text = " ".join(logical).strip() if logical else None

            if command_text:
                db_step(command_text)  # ✅ DB: STEP only

            command_text, combined_output = process_block(block)

            if command_text is not None:
                db.log_command(command_text, "SUCCESS", cap_output(combined_output))
                db_result(command_text, ok=True)  # ✅ DB: RESULT only

            log("[EXEC] Block executed and removed from pipeline.txt")

        except Exception as e:
            # Still log to your existing commands table (StechenDB)
            fail_output = ""
            if combined_output:
                fail_output += combined_output
                if not fail_output.endswith("\n"):
                    fail_output += "\n"
            fail_output += f"Exception: {e}"

            if command_text is None:
                raw_block = "\n".join(block)
                db.log_command(raw_block, "FAIL", cap_output(fail_output))
                db_result(raw_block, ok=False, error_summary=str(e))  # ✅ DB: RESULT only
            else:
                db.log_command(command_text, "FAIL", cap_output(fail_output))
                db_result(command_text, ok=False, error_summary=str(e))  # ✅ DB: RESULT only

            log("[EXEC] Block failed:", e)

            # Put it back so it isn't lost
            try:
                put_block_back_on_top(pipeline_path, block)
                log("[EXEC] Put failed block back on top of pipeline.txt")
            except Exception as e2:
                log("[EXEC] Could not restore block to pipeline.txt:", e2)

            time.sleep(1.0)


if __name__ == "__main__":
    main()
