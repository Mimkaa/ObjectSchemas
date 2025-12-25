# supervisor.py
#
# Runs STECHEN processes
# ✅ prints everything to console
# ❌ does NOT write any log files

import subprocess
import sys
import time
import threading
from pathlib import Path

# ==================================================
# CONFIG
# ==================================================

PYTHON = sys.executable
BASE_DIR = Path(__file__).resolve().parent

PLANNER = BASE_DIR / "planner_process.py"
PIPE_EXECUTOR = BASE_DIR / "pipeExec" / "pipe_exec_daemon.py"
PIPELINE_FILE = BASE_DIR / "pipeExec" / "pipeline.txt"

RESTART_DELAY_SEC = 2.0
POLL_SEC = 0.5


# ==================================================
# CONSOLE ENCODING (Windows-safe)
# ==================================================
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# ==================================================
# UTILS
# ==================================================

def _ts() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def _log(msg: str) -> None:
    try:
        print(f"[{_ts()}] {msg}")
    except Exception:
        pass


# ==================================================
# PROCESS WRAPPER (console-only)
# ==================================================

class ManagedProcess:
    def __init__(self, name: str, script: Path):
        self.name = name
        self.script = script
        self.proc: subprocess.Popen | None = None
        self._reader_thread: threading.Thread | None = None
        self._stop_reader = threading.Event()

    def start(self):
        self.stop()

        if not self.script.exists():
            raise FileNotFoundError(f"{self.name} not found: {self.script}")

        _log(f"[START] {self.name}")

        self._stop_reader.clear()

        self.proc = subprocess.Popen(
            [PYTHON, str(self.script)],
            cwd=str(BASE_DIR),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )

        self._reader_thread = threading.Thread(
            target=self._reader_loop,
            daemon=True,
        )
        self._reader_thread.start()

    def _reader_loop(self):
        if not self.proc or not self.proc.stdout:
            return

        for line in self.proc.stdout:
            if self._stop_reader.is_set():
                break
            try:
                print(f"[{self.name}] {line.rstrip()}")
            except Exception:
                pass

    def poll(self):
        return None if self.proc is None else self.proc.poll()

    def stop(self):
        if self.proc is None:
            return

        if self.proc.poll() is None:
            _log(f"[STOP] {self.name}")
            self._stop_reader.set()
            try:
                self.proc.terminate()
                self.proc.wait(timeout=2.0)
            except Exception:
                try:
                    self.proc.kill()
                except Exception:
                    pass

        try:
            if self.proc.stdout:
                self.proc.stdout.close()
        except Exception:
            pass

        self.proc = None

    def restart(self):
        _log(f"[RESTART] {self.name}")
        time.sleep(RESTART_DELAY_SEC)
        self.start()


# ==================================================
# PIPELINE STATE (console only)
# ==================================================

def pipeline_is_empty() -> bool:
    try:
        return PIPELINE_FILE.read_text(
            encoding="utf-8", errors="replace"
        ).strip() == ""
    except Exception:
        return True


# ==================================================
# MAIN
# ==================================================

def main():
    _log("[SUPERVISOR] started")
    _log(f"[SUPERVISOR] pipeline file: {PIPELINE_FILE}")

    processes = [
        ManagedProcess("Planner", PLANNER),
        ManagedProcess("PipeExecutor", PIPE_EXECUTOR),
    ]

    for p in processes:
        p.start()

    prev_empty = pipeline_is_empty()

    try:
        while True:
            now_empty = pipeline_is_empty()
            if now_empty != prev_empty:
                state = "EMPTY" if now_empty else "NON-EMPTY"
                _log(f"[PIPELINE] became {state}")
                prev_empty = now_empty

            for p in processes:
                code = p.poll()
                if code is not None:
                    _log(f"[CRASH] {p.name} exit={code}")
                    p.restart()

            time.sleep(POLL_SEC)

    except KeyboardInterrupt:
        _log("[SUPERVISOR] stopping")
        for p in processes:
            p.stop()


if __name__ == "__main__":
    main()
