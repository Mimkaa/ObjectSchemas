import os
import openai
from pinecone import Pinecone

# --- Load API Keys from Environment Variables ---
OPENAI_KEY = os.getenv("OPENAI_API_KEY")
PINECONE_KEY = os.getenv("PINECONE_API_KEY")

if not OPENAI_KEY or not PINECONE_KEY:
    print("❌ Missing environment variables. Please set OPENAI_API_KEY and PINECONE_API_KEY.")
    exit(1)

openai.api_key = OPENAI_KEY
pc = Pinecone(api_key=PINECONE_KEY)

INDEX_NAME = "quickstartrar"
DIMENSION = 1536  # just for the dummy query vector

index = pc.Index(INDEX_NAME)

NEW_USAGE = (
    "java ClassMethodAdder --classNameToModify <FullyQualifiedClassName> "
    "--delegateclass <FullyQualifiedDelegateClassName>"
)

def main():
    # 🔍 Find all vectors whose metadata.script_name == "ClassMethodAdder"
    print("🔍 Querying Pinecone for script_name == 'ClassMethodAdder'...")
    query_result = index.query(
        vector=[0.0] * DIMENSION,
        top_k=50,
        filter={"script_name": "ClassMethodAdder"},
        include_values=True,
        include_metadata=True,
    )

    matches = query_result.matches
    if not matches:
        print("⚠️ No vectors found for script_name == 'ClassMethodAdder'.")
        return

    print(f"✅ Found {len(matches)} matching vector(s). Updating usage...")

    upserts = []
    for m in matches:
        vid = m.id
        old_meta = m.metadata or {}
        old_usage = old_meta.get("usage")

        new_meta = dict(old_meta)
        new_meta["usage"] = NEW_USAGE

        print(f"  • {vid}")
        print(f"    old usage: {old_usage}")
        print(f"    new usage: {NEW_USAGE}")

        upserts.append({
            "id": vid,
            "values": m.values,   # keep the same embedding
            "metadata": new_meta  # updated metadata
        })

    # 💾 Upsert updated metadata back into Pinecone
    index.upsert(upserts)
    print("✅ Usage updated for all ClassMethodAdder vectors.")

if __name__ == "__main__":
    main()
