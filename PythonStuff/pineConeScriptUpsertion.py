import os
import openai
from pinecone import Pinecone, ServerlessSpec

# --- Load API Keys from Environment Variables ---
OPENAI_KEY = os.getenv("OPENAI_API_KEY")
PINECONE_KEY = os.getenv("PINECONE_API_KEY")

# --- Safety check ---
if not OPENAI_KEY or not PINECONE_KEY:
    print("❌ Missing environment variables. Please set OPENAI_API_KEY and PINECONE_API_KEY.")
    exit(1)

openai.api_key = OPENAI_KEY
pc = Pinecone(api_key=PINECONE_KEY)

# --- Pinecone index settings ---
index_name = "quickstartrar"
dimension = 1536

# Ensure the index exists
existing_indexes = pc.list_indexes().names()
if index_name not in existing_indexes:
    pc.create_index(
        name=index_name,
        dimension=dimension,
        metric="cosine",
        spec=ServerlessSpec(cloud="aws", region="us-east-1")
    )

index = pc.Index(index_name)

# --- Maximum description length ---
MAX_DESCRIPTION_LENGTH = 1024

# --- New scripts/data to add ---
scripts_to_add = [
    {
        "description": (
            "A Java utility that dynamically generates a Java class at runtime using Byte Buddy. "
            "The user can specify the class name via command-line arguments, and the generated class "
            "includes a private field 'name' and a public method 'sayHello()' that returns a greeting. "
            "The class is saved as a .class file in the current directory. This tool is useful for "
            "creating temporary or runtime-generated classes for testing, dynamic behaviors, or educational purposes."
        )[:MAX_DESCRIPTION_LENGTH],
        "metadata": {
            "script_name": "DynamicClassCreator",
            "usage": (
                "java DynamicClassCreator --name MyGeneratedClass\n"
            )
        }
    }
]





# --- Find the highest vec number already in Pinecone ---
existing_vectors = index.query(vector=[0]*dimension, top_k=1000, include_metadata=False, include_values=False)
max_index = 0
for match in existing_vectors.matches:
    if match.id.startswith("vec"):
        try:
            num = int(match.id[3:])  # strip 'vec'
            if num > max_index:
                max_index = num
        except ValueError:
            continue

# --- Upsert new scripts with incremented IDs ---
for i, script in enumerate(scripts_to_add, start=1):
    vec_id = f"vec{max_index + i}"  # next available vec ID

    # Generate embedding for the description
    response = openai.embeddings.create(
        model="text-embedding-ada-002",
        input=script["description"]
    )
    embedding = response.data[0].embedding

    # Upsert into Pinecone with metadata
    index.upsert([{
        "id": vec_id,
        "values": embedding,
        "metadata": script["metadata"]
    }])
    print(f"✅ Upserted {vec_id}: {script['metadata']['script_name']}")
