import os
import subprocess
import urllib.request
from pathlib import Path

# ==============================================
# CONFIG
# ==============================================

PIPELINE_FILE = "pipeline.txt"
CURRENT_DIR = Path(".")
GITHUB_BASE_RAW = "https://raw.githubusercontent.com/Mimkaa/ObjectSchemas/main"

JAVA_CMD = "java"
JAVAC_CMD = "javac"
CLASSPATH_SEP = ";" if os.name == "nt" else ":"


# ==============================================
# CLASS PATH BUILDER (java + all jars)
# ==============================================
def build_classpath():
    jars = [str(p) for p in CURRENT_DIR.glob("*.jar")]
    return CLASSPATH_SEP.join(["."] + jars)


# ==============================================
# DOWNLOAD JAVA FILE AUTOMATICALLY
# ==============================================
def download_java(script_name: str) -> Path:
    java_filename = f"{script_name}.java"
    local_path = CURRENT_DIR / java_filename

    if local_path.exists():
        print(f"📦 Using cached {java_filename}")
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
# COMPILE JAVA SOURCE
# ==============================================
def compile_java(script_name: str):
    java_filename = f"{script_name}.java"
    src_path = CURRENT_DIR / java_filename

    if not src_path.exists():
        raise FileNotFoundError(f"{java_filename} not found")

    classpath = build_classpath()
    cmd = [JAVAC_CMD, "-cp", classpath, java_filename]

    print(f"🛠  Compiling {java_filename} ...")
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        print("❌ javac failed:")
        print(result.stdout)
        print(result.stderr)
        raise RuntimeError(f"Compilation failed for {java_filename}")

    print("✅ Compilation OK")


# ==============================================
# JAVA RUNNER  (NO FLAG NORMALIZATION)
# ==============================================
def run_java(script_name: str, raw_params):
    """
    Run the Java main class with arguments exactly as given
    in pipeline.txt (no case changes on flags).
    """
    main_class = script_name  # class name = file name without .java
    args = raw_params
    classpath = build_classpath()

    cmd = [JAVA_CMD, "-cp", classpath, main_class] + args

    print(f"🚀 Running: {' '.join(cmd)}")
    result = subprocess.run(cmd)

    if result.returncode != 0:
        raise RuntimeError(f"{main_class} exited with code {result.returncode}")


# ==============================================
# LOAD COMMAND BLOCKS (2 blank lines = separator)
# ==============================================
def load_command_blocks(path: Path):
    """
    Reads pipeline.txt and splits it into blocks.
    Two consecutive blank lines separate blocks.

    Each block can contain:
      - comments (# ...)
      - exactly one effective command line
    """
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
# PROCESS ONE BLOCK (comments + one command)
# ==============================================
def process_block(block_lines):
    """
    block_lines: list of raw lines (may contain comments and blank lines, but no two consecutive blanks)

    We:
      - ignore comment lines (starting with '#')
      - ignore empty lines
      - take the FIRST non-comment, non-empty line as the command
    """
    logical_lines = [
        l.strip()
        for l in block_lines
        if l.strip() and not l.lstrip().startswith("#")
    ]

    if not logical_lines:
        # Block contains only comments/blank lines
        return

    # One command line per block
    stripped = logical_lines[0]

    parts = stripped.split()
    script_raw = parts[0]
    params = parts[1:]

    script_name = script_raw  # use exact casing

    print("\n==============================")
    print(f"🔧 PIPELINE STEP: {stripped}")
    print("==============================")

    download_java(script_name)
    compile_java(script_name)
    run_java(script_name, params)


# ==============================================
# MAIN ENTRY
# ==============================================
def main():
    file = CURRENT_DIR / PIPELINE_FILE
    if not file.exists():
        print("❌ pipeline.txt not found")
        return

    blocks = load_command_blocks(file)

    for block in blocks:
        try:
            process_block(block)
        except Exception as e:
            print("❌ Pipeline error:")
            print("   Block:")
            for line in block:
                print("   ", line)
            print("   Exception:", e)
            break


if __name__ == "__main__":
    main()
