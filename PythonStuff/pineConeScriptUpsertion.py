import os
import openai
from pinecone import Pinecone, ServerlessSpec

# --- Load API Keys from Environment Variables ---
OPENAI_KEY = os.getenv("OPENAI_API_KEY")
PINECONE_KEY = os.getenv("PINECONE_API_KEY")

# --- Safety check ---
if not OPENAI_KEY or not PINECONE_KEY:
    print("❌ Missing environment variables. Please set OPENAI_API_KEY and PINECONE_API_KEY.")
    print("   Make sure OPENAI_API_KEY and PINECONE_API_KEY are exported in your environment.")
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

# --- New scripts/data to add ---
scripts_to_add = [
    {
        "sentences_to_retrieve": [
            "Create a text file whose contents are provided in Base64 encoding.",
            "Safely write large or complex text payloads to a .txt file using Base64.",
            "Generate a text file from Base64-decoded content to avoid CLI wrapping issues.",
            "Use this tool to store Java method bodies encoded in Base64 into Method.txt.",
            "Create a .txt file by decoding Base64 content inside the Java process.",
            "Write decoded UTF-8 text to a file using a Base64-safe pipeline step.",
            "Avoid command-line escaping problems by passing file contents as Base64.",
            "Store dynamically generated source code in a text file via Base64 input.",
            "Create Method.txt for DynamicDelegateCreator using Base64-encoded content.",
            "Use this script when raw text is too large or unsafe for CLI arguments.",
            "Write arbitrary text files from Base64 strings during STECHEN workflows.",
            "Safely persist multi-line code snippets into text files using Base64.",
            "Generate text files without exposing raw content to shell parsing.",
            "Create a .txt file with decoded Base64 payload inside the workspace.",
            "Use this script to reliably inject large text blocks into the pipeline."
        ],
        "usage": "java CreateTextFileFromBase64 --name <fileName> [--path <targetPath>] --contentB64 <base64Text>",
        "effect": "decodes <base64Text> as UTF-8 and writes it to <fileName>.txt at the specified path",
        "script_name": "CreateTextFileFromBase64"
    }
]


# --- Find the highest vec number already in Pinecone ---
existing_vectors = index.query(
    vector=[0] * dimension,
    top_k=1000,
    include_metadata=False,
    include_values=False
)

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

    # Build the text used for retrieval (combine all sentences)
    retrieve_text = " ".join(script["sentences_to_retrieve"])

    # Generate embedding for the retrieval text
    response = openai.embeddings.create(
        model="text-embedding-ada-002",
        input=retrieve_text
    )
    embedding = response.data[0].embedding

    # Upsert into Pinecone with metadata: script_name + usage + effect
    index.upsert([{
        "id": vec_id,
        "values": embedding,
        "metadata": {
            "script_name": script["script_name"],
            "usage": script["usage"],
            "effect": script["effect"]
        }
    }])

    print(f"✅ Upserted {vec_id}: {script['script_name']}")
