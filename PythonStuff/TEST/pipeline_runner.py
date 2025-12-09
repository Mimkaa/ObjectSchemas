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
# FLAG NORMALIZATION (case-insensitive)
# ==============================================
def normalize_flags(tokens):
    result = []
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t.startswith("--"):
            flag = "--" + t[2:].lower()
            result.append(flag)
            i += 1
            while i < len(tokens) and not tokens[i].startswith("--"):
                result.append(tokens[i])
                i += 1
        else:
            result.append(t)
            i += 1
    return result


# ==============================================
# JAVA RUNNER
# ==============================================
def run_java(script_name: str, raw_params):
    main_class = script_name
    args = normalize_flags(raw_params)
    classpath = build_classpath()
    cmd = [JAVA_CMD, "-cp", classpath, main_class] + args

    print(f"🚀 Running: {' '.join(cmd)}")
    result = subprocess.run(cmd)

    if result.returncode != 0:
        raise RuntimeError(f"{main_class} exited with code {result.returncode}")


# ==============================================
# PROCESS A SINGLE LINE AS ONE COMMAND
# ==============================================
def process_line(line: str):
    stripped = line.strip()

    # Treat any # line as comment (including leading spaces)
    if not stripped or stripped.lstrip().startswith("#"):
        return

    parts = stripped.split()
    script_raw = parts[0]
    params = parts[1:]

    script_name = script_raw

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

    for line in file.read_text(encoding="utf-8").splitlines():
        try:
            process_line(line)
        except Exception as e:
            print(f"❌ Error while processing line: {line.strip()}")
            print(f"   {e}")
            break


if __name__ == "__main__":
    main()
