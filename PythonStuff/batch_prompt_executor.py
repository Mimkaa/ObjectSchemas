# batch_prompt_executor.py

import os
from pathlib import Path

from pineConeRetrieval import ScriptRetriever
from scriptRunner import JavaScriptRunner
from scriptEnricher import JavaScriptEnricher
from currentDirExecutor import CurrentDirExecutor  # same as in interactive_prompt.py

PROMPTS_FILE = "prompt.txt"
PROMPT_DELIMITER = "%%%)))"

# --- Load API Keys from Environment Variables ---
OPENAI_KEY = os.getenv("OPENAI_API_KEY")
PINECONE_KEY = os.getenv("PINECONE_API_KEY")

# --- Safety check ---
if not OPENAI_KEY or not PINECONE_KEY:
    print("❌ Missing environment variables. Please set OPENAI_API_KEY and PINECONE_API_KEY.")
    print("   Example (PowerShell):")
    print("   [System.Environment]::SetEnvironmentVariable('OPENAI_API_KEY','sk-xxxx','Machine')")
    print("   [System.Environment]::SetEnvironmentVariable('PINECONE_API_KEY','pcsk-xxxx','Machine')")
    raise SystemExit(1)

# --- Initialize components ---
retriever = ScriptRetriever(openai_key=OPENAI_KEY, pinecone_key=PINECONE_KEY)
runner = JavaScriptRunner(openai_key=OPENAI_KEY)
executor = CurrentDirExecutor()  # ✅ Handles directory switching


def process_prompt(prompt: str, index: int):
    """
    Runs ONE full STECHEN pipeline step (retriever -> runner -> enricher)
    for a given natural-language prompt.
    """
    prompt = prompt.strip()
    if not prompt:
        return

    print("\n" + "─" * 80)
    print(f"🧠 Processing prompt {index}: {prompt}")
    print("─" * 80)

    # --- Step 1: Retrieve script from Pinecone / GitHub ---
    try:
        retriever.prompt = prompt  # set the prompt in the instance
        executor.execute(retriever)  # execute the retriever in current dir
        print("✅ Script retrieved successfully.")
    except Exception as e:
        print(f"❌ Failed to retrieve script for prompt {index}: {e}")
        return

    # --- Step 2: Extract parameters only (do NOT execute) ---
    try:
        executor.execute(runner)
        print("✅ Parameters extracted and saved to JSON.")
    except Exception as e:
        print(f"❌ Failed to extract parameters for prompt {index}: {e}")
        return

    # --- Step 3 & 4: Enrich parameters and execute script in CurrentWorkingDir ---
    try:
        enricher = JavaScriptEnricher(openai_key=OPENAI_KEY)  # JSON inferred automatically
        print("📁 Preparing to execute enricher in target directory...")
        executor.execute(enricher)  # ✅ Executes in directory from CurrentWorkingDir.txt
        print("✅ Parameters enriched and script executed successfully.")
    except Exception as e:
        print(f"❌ Enrichment or execution failed for prompt {index}: {e}")


def main():
    path = Path(PROMPTS_FILE)
    if not path.exists():
        print(f"❌ Prompts file '{PROMPTS_FILE}' not found in {Path.cwd()}")
        raise SystemExit(1)

    print("🤖 Batch Script Retriever → Parameter Extractor → Enricher & Executor")
    print(f"📄 Reading prompts from: {path.resolve()}")
    print("────────────────────────────────────────────")

    text = path.read_text(encoding="utf-8")

    # Split by delimiter, then rebuild each prompt with the delimiter
    raw_parts = text.split(PROMPT_DELIMITER)

    prompts = []
    for part in raw_parts:
        core = part.strip()
        if not core:
            continue
        # ensure each prompt ends with the delimiter, as required by STECHEN rules
        full_prompt = core + " " + PROMPT_DELIMITER
        prompts.append(full_prompt)

    if not prompts:
        print("⚠️ No prompts found in prompt.txt.")
        raise SystemExit(1)

    for i, prompt in enumerate(prompts, start=1):
        process_prompt(prompt, i)

    print("\n✅ All prompts processed.")


if __name__ == "__main__":
    main()
