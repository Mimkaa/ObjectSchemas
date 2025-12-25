# planner_process.py
#
# Vision-based STECHEN planner with EXPLICIT "DONE" JUDGMENT (NO OCR)
# - Waits until pipeExec/pipeline.txt is empty
# - Captures live screenshot (PNG -> data-url)
# - Judge step (VISION): returns DONE or NOT_DONE based primarily on screenshot
# - If NOT_DONE: Planner step outputs ONE next pipeline command block
# - Writes pipeline.txt atomically
#
# ✅ Behavior:
#   - prints to console
#   - logs the SAME output into SQLite table: plannerLong (auto-created)
#   - reads:
#       - ONE last log record from plannerLong (planner log)
#       - TWO last log records from pipelineLong (executor/pipeline log)  ✅
#
# Requirements:
#   pip install openai mss pillow
#   set OPENAI_API_KEY

import base64
import sys
import time
import sqlite3
from io import BytesIO
from pathlib import Path

from openai import OpenAI

# --------------------------------------------------
# Windows console Unicode safety
# --------------------------------------------------
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# --------------------------------------------------
# PATHS
# --------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent
PIPE_EXEC_DIR = BASE_DIR / "pipeExec"

PIPELINE_FILE = PIPE_EXEC_DIR / "pipeline.txt"
RULES_FILE = PIPE_EXEC_DIR / "RULES.txt"
DB_PATH = BASE_DIR / "stechen.db"
GOAL_FILE = BASE_DIR / "GOAL.txt"

SNAPSHOT_DIR = BASE_DIR / "snapshots"
SCREENSHOT_PATH = SNAPSHOT_DIR / "latest.png"
SUMMARY_TXT_PATH = SNAPSHOT_DIR / "last_summary.txt"

# --------------------------------------------------
# TABLE NAMES
# --------------------------------------------------
PLANNER_TABLE = "plannerLong"
EXECUTOR_TABLE = "pipelineLong"  # written by pipe_exec_daemon.py (STEP/RESULT schema)

# --------------------------------------------------
# MODEL + LIMITS
# --------------------------------------------------
MODEL = "gpt-5.2"
SUMMARY_LAST_N = 10
POLL_INTERVAL_SEC = 2.0

MAX_RULES_CHARS = 80_000
MAX_GOAL_CHARS = 8_000
MAX_DB_SUMMARY_CHARS = 25_000

# safety cap for concatenated log strings
MAX_ONE_LOG_LINE_CHARS = 4000

# --------------------------------------------------
# Import summarizer
# --------------------------------------------------
sys.path.insert(0, str(BASE_DIR))
from stechen_gpt_summarize import summarize_last_n_commands  # noqa: E402

# --------------------------------------------------
# DB LOGGING (console + sqlite tee)
# --------------------------------------------------
def _ts() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def cap_text(s: str, n: int, tail: bool = False) -> str:
    s = (s or "").strip()
    if len(s) <= n:
        return s
    return (s[-n:] if tail else (s[:n] + "\n... [truncated]"))


def ensure_planner_table(conn: sqlite3.Connection) -> None:
    """
    Creates plannerLong if it doesn't exist.
    """
    conn.execute(f"""
        CREATE TABLE IF NOT EXISTS {PLANNER_TABLE} (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            level TEXT NOT NULL,
            message TEXT NOT NULL
        )
    """)
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{PLANNER_TABLE}_ts ON {PLANNER_TABLE}(ts)")
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{PLANNER_TABLE}_level ON {PLANNER_TABLE}(level)")
    conn.commit()


def ensure_executor_table_if_missing(conn: sqlite3.Connection) -> None:
    """
    Safety: if executor isn't started yet, create pipelineLong with the NEW schema
    used by the patched pipe_exec_daemon.py that logs ONLY STEP + RESULT.

    Schema:
      pipelineLong(id, ts, event_type, status, command, message)
    """
    conn.execute(f"""
        CREATE TABLE IF NOT EXISTS {EXECUTOR_TABLE} (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts TEXT NOT NULL,
            event_type TEXT NOT NULL,   -- 'STEP' or 'RESULT'
            status TEXT NOT NULL,       -- 'RUN' | 'SUCCESS' | 'FAIL'
            command TEXT,
            message TEXT
        )
    """)
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{EXECUTOR_TABLE}_ts ON {EXECUTOR_TABLE}(ts)")
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{EXECUTOR_TABLE}_event ON {EXECUTOR_TABLE}(event_type)")
    conn.execute(f"CREATE INDEX IF NOT EXISTS idx_{EXECUTOR_TABLE}_status ON {EXECUTOR_TABLE}(status)")
    conn.commit()


def db_insert_planner_line(conn: sqlite3.Connection, level: str, message: str) -> None:
    ts = _ts()
    msg = (message or "")
    if len(msg) > 50_000:
        msg = msg[:50_000] + " ... [truncated]"

    for _ in range(5):
        try:
            conn.execute(
                f"INSERT INTO {PLANNER_TABLE}(ts, level, message) VALUES(?,?,?)",
                (ts, level, msg),
            )
            conn.commit()
            return
        except sqlite3.OperationalError as e:
            if "locked" in str(e).lower():
                time.sleep(0.05)
                continue
            raise


_DB_CONN: sqlite3.Connection | None = None


def log(*args, sep=" ", end="\n", level="INFO") -> None:
    """
    Print to console AND insert same text into stechen.db.plannerLong
    """
    msg = sep.join(str(a) for a in args)

    # Console
    try:
        print(msg, end=end)
    except Exception:
        pass

    # DB
    global _DB_CONN
    if _DB_CONN is not None:
        try:
            db_insert_planner_line(_DB_CONN, level, msg.rstrip())
        except Exception:
            # never crash planner because of logging
            pass


def fetch_last_planner_log(conn: sqlite3.Connection) -> str:
    """
    Returns ONE last record from plannerLong as a formatted string.
    """
    try:
        row = conn.execute(
            f"SELECT ts, level, message FROM {PLANNER_TABLE} ORDER BY id DESC LIMIT 1"
        ).fetchone()
    except Exception:
        return ""
    if not row:
        return ""
    ts, level, msg = row
    return cap_text(f"[{ts}] [{level}] {msg}", MAX_ONE_LOG_LINE_CHARS, tail=True)


def fetch_last_executor_logs(conn: sqlite3.Connection, n: int = 2) -> str:
    """
    Returns the last N records from pipelineLong (NEW schema) as multi-line text.
    (Most recent LAST, i.e., ordered oldest -> newest in the returned string.)
    """
    try:
        rows = conn.execute(
            f"""
            SELECT ts, event_type, status, command, message
            FROM {EXECUTOR_TABLE}
            ORDER BY id DESC
            LIMIT ?
            """,
            (n,),
        ).fetchall()
    except Exception:
        return ""

    if not rows:
        return ""

    rows.reverse()  # oldest -> newest

    lines = []
    for ts, event_type, status, command, message in rows:
        cmd_part = f" | cmd={command}" if command else ""
        msg_part = f" | msg={message}" if message else ""
        lines.append(f"[{ts}] {event_type}/{status}{cmd_part}{msg_part}")

    return cap_text("\n".join(lines), MAX_ONE_LOG_LINE_CHARS, tail=True)

# --------------------------------------------------
# FILE HELPERS
# --------------------------------------------------
def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""


def atomic_write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8", errors="replace")
    tmp.replace(path)


def pipeline_is_empty() -> bool:
    return read_text(PIPELINE_FILE).strip() == ""


def load_goal() -> str:
    return cap_text(read_text(GOAL_FILE), MAX_GOAL_CHARS)


def load_rules() -> str:
    return cap_text(read_text(RULES_FILE), MAX_RULES_CHARS)


def capture_screenshot_bytes(monitor_index: int = 1, max_width: int = 1280) -> bytes:
    import mss
    from PIL import Image

    SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)

    with mss.mss() as sct:
        monitors = sct.monitors
        if monitor_index < 0 or monitor_index >= len(monitors):
            monitor_index = 1 if len(monitors) > 1 else 0

        mon = monitors[monitor_index]
        raw = sct.grab(mon)
        img = Image.frombytes("RGB", raw.size, raw.bgra, "raw", "BGRX")

        if img.width > max_width:
            scale = max_width / img.width
            img = img.resize((max_width, int(img.height * scale)))

        buf = BytesIO()
        img.save(buf, format="PNG", optimize=True)
        png_bytes = buf.getvalue()

        SCREENSHOT_PATH.write_bytes(png_bytes)
        return png_bytes


def png_to_data_url(png_bytes: bytes) -> str:
    return "data:image/png;base64," + base64.b64encode(png_bytes).decode("ascii")

# --------------------------------------------------
# 1) JUDGE STEP (VISION): DONE vs NOT_DONE
# --------------------------------------------------
JUDGE_SYSTEM_PROMPT = """
You are a STRICT visual verifier.

You are given:
- GOAL text
- Optional logs
- A SCREENSHOT of the current UI (PRIMARY source of truth)

Task:
Decide if the GOAL is ALREADY ACHIEVED based primarily on what you can see in the screenshot.

Output rules (CRITICAL):
- Output EXACTLY one token: DONE or NOT_DONE
- No extra text, no punctuation, no explanation.
""".strip()


def judge_done(goal: str, last_exec_logs: str, last_planner_log: str, screenshot_png: bytes) -> bool:
    client = OpenAI()

    goal = cap_text(goal, 4000)
    last_exec_logs = cap_text(last_exec_logs, 2500, tail=True)
    last_planner_log = cap_text(last_planner_log, 1500, tail=True)

    resp = client.responses.create(
        model=MODEL,
        instructions=JUDGE_SYSTEM_PROMPT,
        input=[
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_text",
                        "text": (
                            f"GOAL:\n{goal}\n\n"
                            f"LAST EXECUTOR LOGS (2 rows):\n{last_exec_logs}\n\n"
                            f"LAST PLANNER LOG (1 row):\n{last_planner_log}\n"
                        ),
                    },
                    {"type": "input_image", "image_url": png_to_data_url(screenshot_png)},
                ],
            }
        ],
        reasoning={"effort": "low"},
    )

    out = (resp.output_text or "").strip().upper()
    return out == "DONE"

# --------------------------------------------------
# 2) PLANNER STEP (VISION): output pipeline commands ONLY
# --------------------------------------------------
PLANNER_SYSTEM_PROMPT = """
You are the STECHEN PLANNER.

You do NOT execute code.
You ONLY output VALID STECHEN PIPELINE COMMANDS.

Hard rules:
- Output ONLY pipeline commands (no explanations).
- Commands MUST be separated by EXACTLY two blank lines.
- Choose ONE small next step only.
- Do NOT repeat mandatory library loading if already done.
- If no safe action is possible, output NOTHING.

Important:
- Rely heavily on the SCREENSHOT to decide what is currently visible / what state the UI is in.
- Use the DB summary and logs only as supporting evidence.
""".strip()


def plan_next_step(
    goal: str,
    rules: str,
    exec_summary: str,
    last_exec_logs: str,
    last_planner_log: str,
    screenshot_png: bytes,
) -> str:
    client = OpenAI()

    rules = cap_text(rules, MAX_RULES_CHARS)
    goal = cap_text(goal, MAX_GOAL_CHARS)
    exec_summary = cap_text(exec_summary, MAX_DB_SUMMARY_CHARS)
    last_exec_logs = cap_text(last_exec_logs, 4000, tail=True)
    last_planner_log = cap_text(last_planner_log, 2000, tail=True)

    resp = client.responses.create(
        model=MODEL,
        instructions=PLANNER_SYSTEM_PROMPT,
        input=[
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_text",
                        "text": f"""
GOAL:
-----
{goal}

STECHEN PIPE SPEC:
-----------------
{rules}

LAST EXECUTION SUMMARY (from stechen.db) — MUST include "1) LAST COMMAND":
------------------------------------------------------------------------
{exec_summary}

LAST EXECUTOR LOGS (2 rows from stechen.db.{EXECUTOR_TABLE}):
------------------------------------------------------------
{last_exec_logs}

LAST PLANNER LOG (1 row from stechen.db.{PLANNER_TABLE}):
--------------------------------------------------------
{last_planner_log}

TASK:
Using the SCREENSHOT as the primary source of truth (UI state),
decide the NEXT SINGLE STECHEN PIPELINE STEP that moves toward the GOAL.

Rules:
- Output ONLY the pipeline command(s).
- Separate commands by EXACTLY two blank lines.
- Choose ONE small next step only.
- If no safe step exists, output nothing.
""".strip(),
                    },
                    {"type": "input_image", "image_url": png_to_data_url(screenshot_png)},
                ],
            }
        ],
        reasoning={"effort": "low"},
    )

    return (resp.output_text or "").strip()

# --------------------------------------------------
# MAIN LOOP
# --------------------------------------------------
def main():
    global _DB_CONN

    _DB_CONN = sqlite3.connect(str(DB_PATH), timeout=30.0, isolation_level=None)
    try:
        _DB_CONN.execute("PRAGMA journal_mode=WAL;")
        _DB_CONN.execute("PRAGMA synchronous=NORMAL;")
    except Exception:
        pass

    ensure_planner_table(_DB_CONN)
    ensure_executor_table_if_missing(_DB_CONN)

    log("=== PLANNER START ===")
    log("[PLANNER] started (VISION + DONE JUDGE)")
    log(f"[PLANNER] Goal:      {GOAL_FILE}")
    log(f"[PLANNER] Rules:     {RULES_FILE}")
    log(f"[PLANNER] DB:        {DB_PATH}")
    log(f"[PLANNER] Pipeline:  {PIPELINE_FILE}")
    log(f"[PLANNER] Shot:      {SCREENSHOT_PATH}")
    log(f"[PLANNER] Logging table: {PLANNER_TABLE}")
    log(f"[PLANNER] Reading last 2 executor rows from table: {EXECUTOR_TABLE}")

    rules = load_rules()
    if not rules.strip():
        raise RuntimeError("RULES.txt missing or empty")

    while True:
        try:
            if not pipeline_is_empty():
                time.sleep(POLL_INTERVAL_SEC)
                continue

            goal = load_goal()
            if not goal.strip():
                log("[PLANNER] no action (GOAL.txt missing/empty)")
                time.sleep(POLL_INTERVAL_SEC)
                continue

            # Read logs: executor (2 rows), planner (1 row)
            last_exec_logs = fetch_last_executor_logs(_DB_CONN, n=2)
            last_planner_log = fetch_last_planner_log(_DB_CONN)

            # Screenshot FIRST (so judge & planner see same UI moment)
            screenshot = capture_screenshot_bytes(monitor_index=1, max_width=1280)

            # 1) JUDGE DONE based on screenshot primarily
            is_done = judge_done(goal, last_exec_logs, last_planner_log, screenshot)
            if is_done:
                log("[PLANNER] DONE detected from screenshot -> output nothing")
                time.sleep(POLL_INTERVAL_SEC)
                continue

            # 2) Summarize last executed commands
            exec_summary = summarize_last_n_commands(
                db_path=str(DB_PATH),
                rules_file=str(RULES_FILE),
                n=SUMMARY_LAST_N,
                model=MODEL,
            )
            exec_summary = cap_text(exec_summary, MAX_DB_SUMMARY_CHARS)

            SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)
            SUMMARY_TXT_PATH.write_text(exec_summary + "\n", encoding="utf-8", errors="replace")

            # 3) Plan next step (vision)
            commands = plan_next_step(goal, rules, exec_summary, last_exec_logs, last_planner_log, screenshot)

            if commands:
                atomic_write(PIPELINE_FILE, commands + "\n")
                log("[PLANNER] wrote next pipeline step")
            else:
                log("[PLANNER] no action (model output empty)")

        except Exception as e:
            log("[PLANNER] error:", e, level="ERROR")

        time.sleep(POLL_INTERVAL_SEC)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        log("[FATAL] Unhandled exception:", e, level="ERROR")
        raise
