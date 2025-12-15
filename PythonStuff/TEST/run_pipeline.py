import subprocess
import sys
from pathlib import Path

# ============================
# CONFIG
# ============================

PIPELINE_TO_B64 = "pipeline_to_b64.py"
PIPELINE_RUNNER = "pipeline_runner.py"

PYTHON = sys.executable  # ensures same Python / venv is used


def run_step(script_name: str):
    script_path = Path(script_name)

    if not script_path.exists():
        raise FileNotFoundError(f"Required script not found: {script_name}")

    print(f"\n==============================")
    print(f"▶ Running {script_name}")
    print(f"==============================")

    result = subprocess.run(
        [PYTHON, script_name],
        text=True
    )

    if result.returncode != 0:
        raise RuntimeError(f"{script_name} failed with exit code {result.returncode}")

    print(f"✅ {script_name} completed successfully")


def main():
    try:
        # 1) Convert pipeline.txt -> pipeline_b64.txt
        run_step(PIPELINE_TO_B64)

        # 2) Execute the pipeline
        run_step(PIPELINE_RUNNER)

        print("\n🏁 Full pipeline execution finished successfully.")

    except Exception as e:
        print("\n❌ Pipeline execution failed:")
        print("   ", e)
        sys.exit(1)


if __name__ == "__main__":
    main()
