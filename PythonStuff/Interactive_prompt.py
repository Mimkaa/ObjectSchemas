# interactive_prompt.py
import os
from pineConeRetrieval import ScriptRetriever
from scriptRunner import JavaScriptRunner
from scriptEnricher import JavaScriptEnricher
from currentDirExecutor import CurrentDirExecutor  # ✅ Import the new executor

# --- Load API Keys from Environment Variables ---
OPENAI_KEY = os.getenv("OPENAI_API_KEY")
PINECONE_KEY = os.getenv("PINECONE_API_KEY")

# --- Safety check ---
if not OPENAI_KEY or not PINECONE_KEY:
    print("❌ Missing environment variables. Please set OPENAI_API_KEY and PINECONE_API_KEY.")
    print("   Example (PowerShell):")
    print("   [System.Environment]::SetEnvironmentVariable('OPENAI_API_KEY','sk-xxxx','Machine')")
    print("   [System.Environment]::SetEnvironmentVariable('PINECONE_API_KEY','pcsk-xxxx','Machine')")
    exit(1)

# --- Initialize components ---
retriever = ScriptRetriever(openai_key=OPENAI_KEY, pinecone_key=PINECONE_KEY)
runner = JavaScriptRunner(openai_key=OPENAI_KEY)
executor = CurrentDirExecutor()  # ✅ Handles directory switching

print("🤖 Interactive Script Retriever → Parameter Extractor → Enricher & Executor")
print("Type your prompt (or 'exit' to quit')")
print("────────────────────────────────────────────")

while True:
    prompt = input("\n🗣️ Enter prompt: ").strip()
    if prompt.lower() in {"exit", "quit"}:
        print("👋 Exiting...")
        break
    if not prompt:
        continue

    # --- Step 1: Retrieve script from Pinecone / GitHub ---
    try:
        retriever.prompt = prompt  # set the prompt in the instance
        executor.execute(retriever)  # execute the retriever in current dir
        print("✅ Script retrieved successfully.")
    except Exception as e:
        print(f"❌ Failed to retrieve script: {e}")
        continue

    # --- Step 2: Extract parameters only (do NOT execute) ---
    try:
        executor.execute(runner)
        print("✅ Parameters extracted and saved to JSON.")
    except Exception as e:
        print(f"❌ Failed to extract parameters: {e}")
        continue

    # --- Step 3 & 4: Enrich parameters and execute script in CurrentWorkingDir ---
    try:
        enricher = JavaScriptEnricher(openai_key=OPENAI_KEY)  # JSON inferred automatically
        print("📁 Preparing to execute enricher in target directory...")
        executor.execute(enricher)  # ✅ Executes in directory from CurrentWorkingDir.txt
        print("✅ Parameters enriched and script executed successfully.")
    except Exception as e:
        print(f"❌ Enrichment or execution failed: {e}")
