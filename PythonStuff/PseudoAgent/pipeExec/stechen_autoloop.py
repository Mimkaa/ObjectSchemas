import os
import subprocess
import sys
import time
from pathlib import Path  # ✅ add

from db_operator import DBOperator

PLANNER_CMD  = [sys.executable, "planner.py"]
EXECUTOR_CMD = [sys.executable, "single_step_pipeline_executor.py"]

SLEEP_BETWEEN_ITERATIONS_SEC = 0.2

DB_PATH = os.getenv("STECHEN_DB_PATH", "stechen.db")
BOOTSTRAP_RUN_ID = os.getenv("STECHEN_RUN_ID", "bootstrap")

# ✅ add: where we look for the ready signal
WORK_DIR = Path(os.getenv("STECHEN_WORK_DIR", ".")).resolve()
READY_FILE = WORK_DIR / ".ready"


def run(cmd, label: str) -> bool:
    print(f"\n==== {label} ====")
    res = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )

    if res.stdout:
        print(res.stdout)
    if res.stderr:
        print(res.stderr, file=sys.stderr)

    if res.returncode != 0:
        print(f"[STOP] {label} failed with exit code {res.returncode}")
        return False

    return True


def bootstrap_bytebuddy() -> bool:
    inserted = False

    op = DBOperator(DB_PATH)
    try:
        op.ensure_runtime_tables()

        cur = op.conn.cursor()
        exists = cur.execute(
            """
            SELECT 1
            FROM pipeline p
            JOIN DynamicJarLoader d ON d.step_id = p.step_id
            WHERE p.script_name = 'DynamicJarLoader'
              AND d.library = ?
            LIMIT 1
            """,
            ("net.bytebuddy:byte-buddy:1.15.3",),
        ).fetchone()

        if exists:
            print("[BOOTSTRAP] ByteBuddy already present in pipeline. Skipping insert.")
        else:
            step_id = op.insert_step(
                script_name="DynamicJarLoader",
                params={"library": "net.bytebuddy:byte-buddy:1.15.3"},
                run_id=BOOTSTRAP_RUN_ID,
                step_index=None,
            )
            inserted = True
            print(f"[BOOTSTRAP] Inserted DynamicJarLoader(byte-buddy) as step_id={step_id}")

    except Exception as e:
        print("[BOOTSTRAP] Failed to insert bootstrap step:", e)
        return False
    finally:
        try:
            op.close()
        except Exception:
            pass

    label = "BOOTSTRAP EXECUTOR (ByteBuddy)" if inserted else "BOOTSTRAP EXECUTOR (already present)"
    if not run(EXECUTOR_CMD, label):
        return False

    return True


def main():
    if not bootstrap_bytebuddy():
        print("\n[HALT] Bootstrap failed.")
        return

    if not run(EXECUTOR_CMD, "PRE-LOOP SINGLE STEP EXECUTOR"):
        print("\n[HALT] Pre-loop executor run failed.")
        return

    iteration = 0

    while True:
        # ✅ BREAK CONDITION: stop if an interactive script signaled readiness
        if READY_FILE.exists():
            print(f"\n[HALT] Found ready signal: {READY_FILE}")
            break

        iteration += 1
        print("\n" + "#" * 70)
        print(f"# STECHEN AUTORUN — ITERATION {iteration}")
        print("#" * 70)

        if not run(PLANNER_CMD, "PLANNER"):
            break

        if not run(EXECUTOR_CMD, "SINGLE STEP EXECUTOR"):
            break

        # ✅ optional: also check immediately after execution (faster stop)
        if READY_FILE.exists():
            print(f"\n[HALT] Found ready signal after execution: {READY_FILE}")
            break

        time.sleep(SLEEP_BETWEEN_ITERATIONS_SEC)

    print("\n[HALT] STECHEN loop finished.")


if __name__ == "__main__":
    main()
