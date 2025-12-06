import os
import json
import requests
import subprocess
from pathlib import Path
from openai import OpenAI

PROMPTS_FILE = "prompt.txt"
GITHUB_RAW_BASE = "https://raw.githubusercontent.com/Mimkaa/ObjectSchemas/main/"

client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))


# ---------------------------------------------------------
# Build classpath from all jars under cwd (recursive)
# ---------------------------------------------------------
def build_local_classpath(cwd: Path) -> str:
    entries = ["."]  # always include current directory
    for jar in cwd.rglob("*.jar"):
        entries.append(str(jar))
    return os.pathsep.join(entries)


# ---------------------------------------------------------
# Download + compile a .java script if missing
# ---------------------------------------------------------
def ensure_script_available(script_name: str, cwd: Path):
    java_file = cwd / f"{script_name}.java"
    class_file = cwd / f"{script_name}.class"

    if java_file.exists():
        return

    url = f"{GITHUB_RAW_BASE}{script_name}.java"
    print(f"🌐 Downloading missing script: {url}")

    try:
        resp = requests.get(url, timeout=10)
        resp.raise_for_status()
        java_file.write_text(resp.text, encoding="utf-8")
        print(f"✅ Downloaded {java_file}")

        # Compile with full CP
        cp = build_local_classpath(cwd)
        print(f"🛠 Compiling {script_name}.java ...")

        cmd = ["javac", "-cp", cp, str(java_file)]
        result = subprocess.run(cmd, cwd=str(cwd), text=True, capture_output=True)

        if result.returncode != 0:
            print("❌ javac failed:")
            print(result.stderr)
        else:
            print(f"✅ Compiled {class_file}")

    except Exception as e:
        print(f"❌ Error downloading/compiling {script_name}: {e}")


# ---------------------------------------------------------
# GPT-5.1 Parser: extract script + parameters
# ---------------------------------------------------------
def ask_gpt_to_parse(raw_command: str) -> dict:
    prompt = f"""
You are the STECHEN SYSTEM command parser.

Given a STECHEN command line, extract:
- script name
- boolean: is_java
- all named parameters without angle brackets
- output JSON only, no commentary
- %%%))) must be ignored
- angle brackets <likeThis> must be removed

Example output:
{{
  "script": "CreateDirectory",
  "is_java": true,
  "parameters": {{
      "name": "ProjectX"
  }}
}}

Parse:
{raw_command}
"""

    completion = client.chat.completions.create(
        model="gpt-5.1",
        response_format={"type": "json_object"},
        messages=[{"role": "user", "content": prompt}]
    )

    return json.loads(completion.choices[0].message.content)


# ---------------------------------------------------------
# Force full classpath for ALL java commands
# ---------------------------------------------------------
def force_classpath(cmd: str, cwd: Path) -> str:
    parts = cmd.split()
    if not parts or parts[0] != "java":
        return cmd

    cp = build_local_classpath(cwd)

    cleaned = []
    skip = False
    for p in parts:
        if skip:
            skip = False
            continue
        if p in ("-cp", "-classpath"):
            skip = True
            continue
        cleaned.append(p)

    script_and_args = " ".join(cleaned[1:])
    new_cmd = f'java -cp "{cp}" {script_and_args}'
    return new_cmd


# ---------------------------------------------------------
# Build final java command from parsed GPT result
# ---------------------------------------------------------
def assemble_java_command(parsed: dict, cwd: Path) -> str:
    cp = build_local_classpath(cwd)
    script = parsed["script"]
    params = parsed["parameters"]

    param_list = []
    for k, v in params.items():
        param_list.append(f"--{k}")
        param_list.append(str(v))

    return f'java -cp "{cp}" {script} ' + " ".join(param_list)


# ---------------------------------------------------------
# Execute a single STECHEN line
# ---------------------------------------------------------
def run_stechen_command(raw_cmd: str, index: int, cwd: Path):
    print("\n" + "─" * 80)
    print(f"🧠 Raw STECHEN {index}: {raw_cmd}")
    print("─" * 80)

    # GPT parse
    parsed = ask_gpt_to_parse(raw_cmd)
    print(f"🤖 GPT Parsed:\n{json.dumps(parsed, indent=2)}")

    is_java = parsed.get("is_java", True)
    script_name = parsed["script"]

    if is_java:
        ensure_script_available(script_name, cwd)
        final_cmd = assemble_java_command(parsed, cwd)
    else:
        final_cmd = raw_cmd  # future extension for Python scripts etc.

    # ENFORCE full CP
    final_cmd = force_classpath(final_cmd, cwd)

    print(f"🚀 Executing: {final_cmd}")

    result = subprocess.run(final_cmd, shell=True, cwd=str(cwd))

    if result.returncode == 0:
        print(f"✅ Command {index} OK")
    else:
        print(f"❌ Command {index} FAILED code {result.returncode}")


# ---------------------------------------------------------
# Main executor
# ---------------------------------------------------------
def main():
    cwd = Path.cwd()
    prompt_path = cwd / PROMPTS_FILE

    if not prompt_path.exists():
        print(f"❌ No {PROMPTS_FILE} found in {cwd}")
        return

    lines = [
        line.strip()
        for line in prompt_path.read_text().splitlines()
        if line.strip()
    ]

    print("🤖 STECHEN GPT-5.1 EXECUTION ENGINE")
    print("────────────────────────────────────────────")

    for i, line in enumerate(lines, start=1):
        run_stechen_command(line, i, cwd)

    print("\n✅ All commands processed.")


if __name__ == "__main__":
    main()
