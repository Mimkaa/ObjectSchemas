import os
from pinecone import Pinecone

# --- Load API Key from environment variable ---
PINECONE_KEY = os.getenv("PINECONE_API_KEY")

if not PINECONE_KEY:
    print("❌ Missing environment variable: PINECONE_API_KEY")
    exit(1)

# --- Initialize Pinecone client ---
pc = Pinecone(api_key=PINECONE_KEY)

# --- Your index name ---
INDEX_NAME = "quickstartrar"

# --- Check if the index exists ---
existing_indexes = pc.list_indexes().names()
if INDEX_NAME not in existing_indexes:
    print(f"❌ Index '{INDEX_NAME}' not found. Available indexes: {existing_indexes}")
    exit(1)

# --- Connect to the index ---
index = pc.Index(INDEX_NAME)

# --- Confirm deletion ---
confirm = input(f"⚠️ Are you sure you want to delete ALL entries from '{INDEX_NAME}'? (yes/no): ").strip().lower()
if confirm != "yes":
    print("🛑 Deletion cancelled.")
    exit(0)

# --- Delete all vectors ---
print(f"🧹 Deleting all vectors from index '{INDEX_NAME}'...")
index.delete(delete_all=True)

print("✅ All vectors have been deleted from the Pinecone index.")
