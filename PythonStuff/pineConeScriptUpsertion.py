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
        "Execute a compiled Java class directly from the command line.",
        "Run the main method of a Java class by specifying its name.",
        "Trigger execution of a Java class produced earlier in the STECHEN pipeline.",
        "Use this tool to run any generated .class file with a valid main method.",
        "Launch a Java program whose class name is provided as an argument.",
        "Invoke the main function of an already compiled Java class.",
        "Execute dynamically created classes such as those built by DynamicClassCreator.",
        "Run a Java class by its simple name using a helper wrapper.",
        "Start a Java program in a subprocess from a given class name.",
        "Use this script to test that a generated class runs successfully.",
        "Execute Java code after methods or fields were injected via ASM tools.",
        "Call the main method of a modified class after method/field cloning.",
        "Use this command to verify functional output of a generated delegate.",
        "Run a class that lives in the current working directory using Java CLI.",
        "Launch a class built during STECHEN workflows for debugging or inspection."
    ],
    "usage": "java RunClass --class <ClassName>",
    "effect": "executes the <ClassName> main method in a subprocess",
    "script_name": "RunClass"
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
