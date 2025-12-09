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
        "Create a new directory in the current working folder or a specified path.",
        "Make a folder that other STECHEN scripts can use as a workspace.",
        "Generate a directory structure for a new project or submodule.",
        "Programmatically create a folder by specifying its name and optional path.",
        "Ensure a directory exists before writing files into it.",
        "Create a new filesystem directory to hold class files or specs.",
        "Set up a working directory for the STECHEN pipeline execution.",
        "Create a folder that will store generated .class files or temp data.",
        "Produce a directory under a target path, including parent folders if needed.",
        "Make a project directory so scripts can operate inside it.",
        "Initialize a folder required for further file creation or code generation.",
        "Create a named directory that acts as a container for generated artifacts.",
        "Generate a filesystem location where future operations will take place.",
        "Use CreateDirectory to bootstrap a structure for a new STECHEN workflow.",
        "Make sure a directory exists before switching into it with CurrentDirUpdate."
    ],
    "usage": (
        "java CreateDirectory "
        "--name <directoryName> "
        "[--path <targetPath>]"
    ),
    "effect": "creates folder <directoryName> at the specified path",
    "script_name": "CreateDirectory"
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
