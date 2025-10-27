import os
import subprocess
import openai

# === API key ===
openai.api_key = "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"

# === Read LastLoadedScript.txt ===
last_loaded_path = os.path.join(os.getcwd(), "LastLoadedScript.txt")

if not os.path.exists(last_loaded_path):
    print("❌ LastLoadedScript.txt not found.")
    exit()

with open(last_loaded_path, "r", encoding="utf-8") as f:
    lines = f.read().splitlines()
    if len(lines) < 3:
        print("❌ LastLoadedScript.txt does not have enough lines.")
        exit()
    script_name = lines[0]
    prompt = lines[1]
    usage = lines[2]

script_path = os.path.join(os.getcwd(), f"{script_name}.java")
if not os.path.exists(script_path):
    print(f"❌ Script file {script_name}.java not found in current folder.")
    exit()

# === Ask GPT to extract possible parameters ===
gpt_prompt = f"""
Given the following prompt and usage for a Java program, 
extract possible command-line parameters and their default values if not specified.

Prompt: {prompt}
Usage: {usage}

Return the result as a JSON object with parameter names as keys and default values as values.
If a parameter has no default, put an empty string as its value.
"""

response = openai.chat.completions.create(
    model="gpt-4",
    messages=[
        {"role": "system", "content": "You are an expert Java developer."},
        {"role": "user", "content": gpt_prompt}
    ],
    temperature=0
)

parameters_json = response.choices[0].message.content.strip()
print(f"⚡ GPT extracted parameters:\n{parameters_json}")

# === Convert parameters into command-line arguments ===
import json
try:
    params = json.loads(parameters_json)
except json.JSONDecodeError:
    print("❌ GPT response could not be parsed as JSON. Using no parameters.")
    params = {}

args = []
for key, value in params.items():
    if value != "":
        args.append(f"--{key}")
        args.append(str(value))

# === Compile and run Java script ===
print(f"⚡ Compiling {script_name}.java...")
compile_result = subprocess.run(
    ["javac", script_path], capture_output=True, text=True
)

if compile_result.returncode != 0:
    print("❌ Compilation failed:\n", compile_result.stderr)
    exit()

print(f"⚡ Running {script_name} with arguments: {args}...")
run_result = subprocess.run(
    ["java", script_name] + args, capture_output=True, text=True
)

print("=== Script Output ===")
print(run_result.stdout)
if run_result.stderr:
    print("=== Script Errors ===")
    print(run_result.stderr)
