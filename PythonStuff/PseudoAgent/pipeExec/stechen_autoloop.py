# stechen_autoloop.py
#
# Master control loop for STECHEN (planner-first).
#
# Behavior:
#   0) BOOTSTRAP (once):
#        - Insert DynamicJarLoader(net.bytebuddy:byte-buddy:1.15.3) into pipeline (if missing)
#        - Execute exactly ONE step once (bytebuddy) via single_step_pipeline_executor.py
#   0.5) PRELOOP EXECUTE (once):
#        - Call executor ONCE more before entering the while loop
#          (useful if your executor always runs "last step" and you want to flush
#           any other pre-existing last step or confirm the system is runnable)
#   while True:
#     1) Run planner.py   → inserts EXACTLY ONE next step
#     2) Run single_step_pipeline_executor.py → executes EXACTLY ONE step
#
# Stops when:
#   - planner fails
#   - executor fails
#
# Usage:
#   python stechen_autoloop.py
#
# Required files:
#   - planner.py
#   - single_step_pipeline_executor.py
#   - db_operator.py
#
import os
import subprocess
import sys
import time

from db_operator import DBOperator

PLANNER_CMD  = [sys.executable, "planner.py"]
EXECUTOR_CMD = [sys.executable, "single_step_pipeline_executor.py"]

SLEEP_BETWEEN_ITERATIONS_SEC = 0.2

DB_PATH = os.getenv("STECHEN_DB_PATH", "stechen.db")
BOOTSTRAP_RUN_ID = os.getenv("STECHEN_RUN_ID", "bootstrap")


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
    """
    Insert DynamicJarLoader(net.bytebuddy:byte-buddy:1.15.3) into pipeline,
    then execute exactly one step once before the main loop.

    Safe behavior:
    - Only inserts if that exact library step is not already present in pipeline.
    """
    inserted = False

    op = DBOperator(DB_PATH)
    try:
        op.ensure_runtime_tables()

        # Check if byte-buddy already exists in pipeline (anywhere)
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

    # Execute exactly one step once (the single-step executor should run the last step).
    # If we didn't insert anything (already existed), we still run once so the system
    # can load it if it hasn't yet been executed.
    label = "BOOTSTRAP EXECUTOR (ByteBuddy)" if inserted else "BOOTSTRAP EXECUTOR (already present)"
    if not run(EXECUTOR_CMD, label):
        return False

    return True


def main():
    # --------------------------------------------------
    # 0) BOOTSTRAP ONCE: ensure ByteBuddy jar is loaded
    # --------------------------------------------------
    if not bootstrap_bytebuddy():
        print("\n[HALT] Bootstrap failed.")
        return

    # --------------------------------------------------
    # 0.5) PRE-LOOP: call executor once more before loop
    # --------------------------------------------------
    if not run(EXECUTOR_CMD, "PRE-LOOP SINGLE STEP EXECUTOR"):
        print("\n[HALT] Pre-loop executor run failed.")
        return

    iteration = 0

    while True:
        iteration += 1
        print("\n" + "#" * 70)
        print(f"# STECHEN AUTORUN — ITERATION {iteration}")
        print("#" * 70)

        # 1) PLANNER — inserts exactly ONE step
        if not run(PLANNER_CMD, "PLANNER"):
            break

        # 2) EXECUTOR — runs exactly ONE step
        if not run(EXECUTOR_CMD, "SINGLE STEP EXECUTOR"):
            break

        time.sleep(SLEEP_BETWEEN_ITERATIONS_SEC)

    print("\n[HALT] STECHEN loop finished.")


if __name__ == "__main__":
    main()
