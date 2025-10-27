import openai
from pinecone import Pinecone, ServerlessSpec

# === API keys ===
openai.api_key = "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"
pc = Pinecone(api_key="pcsk_3FJzvR_3w7fCzPaidjw3usmFC9Fu6zpvfUwjWkYn31bLHa1Ag5KJiSgFo1BBRQYMJHRBec")

# === Pinecone index settings ===
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

# === Maximum description length ===
MAX_DESCRIPTION_LENGTH = 1024

# === New scripts/data to add (descriptions truncated at definition) ===
scripts_to_add = [
    {
        "description": ("A Java program that creates a directory using command-line arguments --name "
                        "and optional --path.")[:MAX_DESCRIPTION_LENGTH],
        "metadata": {
            "script_name": "CreateDirectory",
            "usage": "java CreateDirectory --name <directoryName> [--path <targetPath>]"
        }
    },
]

# === Find the highest vec number already in Pinecone ===
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

# === Upsert new scripts with incremented IDs ===
for i, script in enumerate(scripts_to_add, start=1):
    vec_id = f"vec{max_index + i}"  # next available vec ID

    # Generate embedding for the description
    response = openai.embeddings.create(
        model="text-embedding-ada-002",
        input=script["description"]
    )
    embedding = response.data[0].embedding

    # Upsert into Pinecone with metadata
    index.upsert([
        {
            "id": vec_id,
            "values": embedding,
            "metadata": script["metadata"]
        }
    ])
    print(f"✅ Upserted {vec_id}: {script['metadata']['script_name']}")
