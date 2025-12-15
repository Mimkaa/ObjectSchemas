import os
import subprocess
import urllib.request
from pathlib import Path
import base64
import shutil

# ==============================================
# CONFIG
# ==============================================

PIPELINE_FILE = "pipeline_b64.txt"

PROJECT_ROOT = Path(".").resolve()

# Everything happens here: downloaded .java, compiled .class, downloaded .jar
WORK_DIR = PROJECT_ROOT / ".stechen_work"

GITHUB_BASE_RAW = "https://raw.githubusercontent.com/Mimkaa/ObjectSchemas/main"

JAVA_CMD = "java"
JAVAC_CMD = "javac"
CLASSPATH_SEP = ";" if os.name == "nt" else ":"

PIPELINE_STATE_FILE = ".pipeline_state"  # keep in project root


# ==============================================
# WORKSPACE SETUP
# ==============================================
def ensure_workspace():
    WORK_DIR.mkdir(parents=True, exist_ok=True)


# ==============================================
# CLEANUP
# ==============================================
def cleanup_workspace():
    # Delete only the known workspace folder.
    if WORK_DIR.exists():
        print(f"🧹 Cleaning up workspace: {WORK_DIR}")
        shutil.rmtree(WORK_DIR)


# ==============================================
# CLASSPATH BUILDER (WORK_DIR + all jars INSIDE WORK_DIR)
# ==============================================
def build_classpath():
    ensure_workspace()
    jars = [str(p) for p in WORK_DIR.glob("*.jar")]
    # Include WORK_DIR so compiled .class files are found
    return CLASSPATH_SEP.join([str(WORK_DIR)] + jars)


# ==============================================
# DOWNLOAD JAVA FILE AUTOMATICALLY (INTO WORK_DIR)
# ==============================================
def download_java(script_name: str) -> Path:
    ensure_workspace()
    java_filename = f"{script_name}.java"
    local_path = WORK_DIR / java_filename

    if local_path.exists():
        print(f"📦 Using cached {java_filename} (workspace)")
        return local_path

    url = f"{GITHUB_BASE_RAW}/{java_filename}"
    print(f"⬇️  Downloading {java_filename} from {url}")

    try:
        with urllib.request.urlopen(url) as resp, open(local_path, "wb") as out:
            out.write(resp.read())
    except Exception as e:
        raise FileNotFoundError(f"Failed to download {java_filename}: {e}")

    print(f"✅ Saved {local_path}")
    return local_path


# ==============================================
# COMPILE JAVA SOURCE (IN WORK_DIR, USING ONLY WORK_DIR JARS)
# ==============================================
def compile_java(script_name: str):
    ensure_workspace()
    java_filename = f"{script_name}.java"
    src_path = WORK_DIR / java_filename

    if not src_path.exists():
        raise FileNotFoundError(f"{java_filename} not found in workspace")

    classpath = build_classpath()
    cmd = [JAVAC_CMD, "-cp", classpath, java_filename]

    print(f"🛠  Compiling {java_filename} (workspace) ...")
    result = subprocess.run(
        cmd,
        cwd=str(WORK_DIR),
        capture_output=True,
        text=True
    )

    if result.returncode != 0:
        print("❌ javac failed:")
        print(result.stdout)
        print(result.stderr)
        raise RuntimeError(f"Compilation failed for {java_filename}")

    print("✅ Compilation OK")


# ==============================================
# JAVA RUNNER (RUNS FROM WORK_DIR, USING ONLY WORK_DIR JARS)
# ==============================================
def run_java(script_name: str, raw_params):
    ensure_workspace()

    main_class = script_name
    classpath = build_classpath()
    cmd = [JAVA_CMD, "-cp", classpath, main_class] + raw_params

    print(f"🚀 Running (workspace): {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=str(WORK_DIR))

    if result.returncode != 0:
        raise RuntimeError(f"{main_class} exited with code {result.returncode}")


# ==============================================
# LOAD COMMAND BLOCKS (2 blank lines = separator)
# ==============================================
def load_command_blocks(path: Path):
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()

    blocks = []
    current = []
    blank_count = 0

    for line in lines:
        if line.strip() == "":
            blank_count += 1
            if blank_count == 2:
                if current:
                    blocks.append(current)
                    current = []
                blank_count = 0
            continue
        else:
            blank_count = 0
            current.append(line)

    if current:
        blocks.append(current)

    return blocks


# ==============================================
# PARSE A COMMAND LINE (NO QUOTE LOGIC)
# ==============================================
def parse_command_line(command: str):
    """
    pipeline_b64.txt contains base64 blobs without spaces,
    so split() is correct here.
    """
    s = command.strip()
    if not s:
        return []
    return s.split()


# ==============================================
# DECODE --flagB64 / --flagBase64
# ==============================================
def decode_b64_flags(params):
    out = []
    i = 0
    while i < len(params):
        flag = params[i]

        if isinstance(flag, str) and flag.startswith("--"):
            low = flag.lower()
            is_b64 = low.endswith("b64")
            is_base64 = low.endswith("base64")

            if is_b64 or is_base64:
                if i + 1 >= len(params):
                    raise RuntimeError(f"{flag} has no value to decode")

                val = params[i + 1]
                if isinstance(val, str) and val.startswith("--"):
                    raise RuntimeError(f"{flag} has no value to decode (next token is another flag)")

                new_flag = flag[:-3] if is_b64 else flag[:-6]

                try:
                    decoded = base64.b64decode(val).decode("utf-8")
                except Exception as e:
                    raise RuntimeError(f"Failed to base64-decode value for {flag}: {e}")

                out.append(new_flag)
                out.append(decoded)
                i += 2
                continue

        out.append(flag)
        i += 1

    return out


# ==============================================
# PROCESS ONE BLOCK
# ==============================================
def process_block(block_lines):
    logical_lines = [
        l.strip()
        for l in block_lines
        if l.strip() and not l.lstrip().startswith("#")
    ]
    if not logical_lines:
        return

    command = " ".join(logical_lines)

    print("\n==============================")
    print(f"🔧 PIPELINE STEP: {command}")
    print("==============================")

    parts = parse_command_line(command)
    if not parts:
        return

    script_name = parts[0]
    params = decode_b64_flags(parts[1:])

    # Everything runs in WORK_DIR
    ensure_workspace()

    download_java(script_name)
    compile_java(script_name)
    run_java(script_name, params)


# ==============================================
# POINTER HELPERS (KEEP IN PROJECT ROOT)
# ==============================================
def load_pointer() -> int:
    state_path = PROJECT_ROOT / PIPELINE_STATE_FILE
    if not state_path.exists():
        return 0
    try:
        return int(state_path.read_text(encoding="utf-8").strip())
    except Exception:
        return 0


def save_pointer(index: int):
    state_path = PROJECT_ROOT / PIPELINE_STATE_FILE
    state_path.write_text(str(index), encoding="utf-8")


# ==============================================
# MAIN
# ==============================================
def main():
    pipeline_path = PROJECT_ROOT / PIPELINE_FILE
    if not pipeline_path.exists():
        print(f"❌ {PIPELINE_FILE} not found in {PROJECT_ROOT}")
        print("Tip: run: python pipeline_to_b64.py  (to generate pipeline_b64.txt)")
        return

    blocks = load_command_blocks(pipeline_path)
    total = len(blocks)

    pointer = load_pointer()
    if pointer < 0 or pointer > total:
        pointer = 0

    print(f"📌 Loaded {total} blocks from {PIPELINE_FILE}")
    print(f"📍 Starting from block index {pointer} (0-based)")
    print(f"📂 Workspace: {WORK_DIR}")

    failed = False

    for idx, block in enumerate(blocks):
        if idx < pointer:
            continue

        print(f"\n▶ Executing block {idx + 1}/{total}")

        try:
            process_block(block)
        except Exception as e:
            failed = True
            print("❌ Pipeline error:")
            print("   Block:")
            for line in block:
                print("   ", line)
            print("   Exception:", e)
            break
        else:
            next_index = idx + 1
            save_pointer(next_index)
            print(f"✅ Block {idx + 1} done. Next start index saved as {next_index}.")

    if not failed:
        cleanup_workspace()
        print("🧼 Workspace cleaned successfully.")

    print("\n🏁 Pipeline run finished.")


if __name__ == "__main__":
    main()
