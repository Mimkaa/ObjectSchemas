import os
import sys
import subprocess
from openai import OpenAI

# ==============================
# CONFIG
# ==============================

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROMPT_FILE = os.path.join(BASE_DIR, "prompt.txt")
BATCH_EXECUTOR = os.path.join(BASE_DIR, "prompts_executor.py")

OPENAI_MODEL = "gpt-5.1"
client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))


# ==============================
# LOAD FULL STECHEN SPEC
# ==============================

with open("STECHEN_SPEC.txt", "r", encoding="utf-8") as f:
    STECHEN_SPEC = f.read()


# ==============================
# PROMPT BUILDER
# ==============================

def stechen_prompt(goal: str) -> str:
    return f"""
{STECHEN_SPEC}

You MUST output ONLY valid STECHEN commands — no JSON, no code, no [[[ ]]].

STRICT RULES:
• Only commands defined in the SPEC.
• One command per line.
• Every line must end in %%%))).
• No commentary, markdown, lists or numbering.
• If needed, break into multiple STECHEN script steps.
• Angle bracket protocol required for all identifiers.

Task:
{goal}

OUTPUT FORMAT:
ONLY STECHEN PROMPTS — ready for prompt.txt
"""


# ==============================
# GENERATE COMMANDS
# ==============================

def generate_commands(goal: str) -> str:
    response = client.responses.create(
        model=OPENAI_MODEL,
        instructions="Produce ONLY STECHEN prompts. No other syntax allowed.",
        input=stechen_prompt(goal),
        max_output_tokens=2048,
        temperature=0.1
    )
    text = response.output_text.strip()

    # Reject invalid format
    if "[[[" in text or "{" in text or "```" in text:
        raise ValueError("\n❌ INVALID OUTPUT — Not valid STECHEN format.\n")

    return text


# ==============================
# WRITE OUTPUT
# ==============================

def save(text: str):
    with open(PROMPT_FILE, "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print(f"\n📝 prompt.txt written → {PROMPT_FILE}\n{text}\n")


# ==============================
# EXECUTE BATCH
# ==============================

def run_exec():
    if not os.path.exists(BATCH_EXECUTOR):
        print(f"⚠️ Executor missing: {BATCH_EXECUTOR}")
        return
    print("🚀 Running prompts_executor...\n")
    subprocess.run(["python", BATCH_EXECUTOR], cwd=BASE_DIR)


# ==============================
# ENTRY POINT (NO LOOP)
# ==============================

if __name__ == "__main__":

    if len(sys.argv) < 2:
        print("\nUsage:\n  python prompt_generator.py \"<task>\"\n")
        sys.exit(0)

    goal = " ".join(sys.argv[1:])
    print(f"\n🧠 STECHEN generating for: {goal}\n")

    try:
        commands = generate_commands(goal)
        save(commands)
        run_exec()
        print("\n✔ DONE.\n")

    except Exception as e:
        print(e)
        print("\n❌ Generation failed.\n")
