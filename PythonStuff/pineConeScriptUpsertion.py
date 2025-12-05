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
            "Generates a delegate class that contains ONLY public fields defined in the spec JSON, using OpenAI. "
            "Use this script in the FIELD CREATION pipeline after you have appended new field descriptions into "
            "newFieldDescSpec (via SpecFieldLogicAppender). It reads the spec file, asks the OpenAI API to produce "
            "a <BaseClassName>Delegate that extends the original base class, and declares all requested fields as "
            "public so they can be cloned into the base using ClassFieldCloner. Typical natural language prompts "
            "that should map to this script include: \"generate a delegate with the new fields\", "
            "\"create a field-only delegate from this spec\", \"build a delegate that exposes the fields for "
            "cloning\", \"turn the field descriptions in the spec into a delegate class\", or "
            "\"produce a delegate so I can clone these fields into the base class\". This tool is the AI-powered "
            "step that materializes declarative field descriptions into an actual Java delegate, ready for "
            "bytecode-level transfer."
        )[:MAX_DESCRIPTION_LENGTH],

        "metadata": {
            "script_name": "OpenAiFieldDelegateGenerator",

            "usage": (
                "java OpenAiFieldDelegateGenerator "
                "--Target_spec <SpecFile.json>"
            ),

            "description": (
                "Uses OpenAI to generate a field-only delegate class from a spec JSON (public fields for cloning)."
            )
        }
    },
    {
        "description": (
            "Generates a delegate class containing NEW METHODS based on natural-language method logic stored in the "
            "spec JSON (newMethodLogicSpec), using OpenAI. Use this script in the METHOD CREATION pipeline after "
            "you have appended method descriptions via SpecMethodLogicAppender. It reads the spec, calls the OpenAI "
            "API, and produces a <BaseClassName>Delegate that extends the original base class and implements the "
            "described methods as real Java code. The generated methods are required to be public (unless otherwise "
            "stated), to follow the semantics of the description, and to use existing/public fields whenever state "
            "needs to be stored or updated. Typical natural language prompts that should map to this script include: "
            "\"generate a delegate with this method logic\", \"create a delegate that implements these methods\", "
            "\"turn the method descriptions in the spec into Java code\", \"build a delegate so I can clone these "
            "methods into the base class\", or \"use AI to materialize this behavior in a delegate class\". This "
            "tool is the AI-powered METHOD generator in STECHEN, producing compilable Java delegates ready for "
            "ClassMethodCloner or ClassMethodAdder."
        )[:MAX_DESCRIPTION_LENGTH],

        "metadata": {
            "script_name": "OpenAiMethodDelegateGenerator",

            "usage": (
                "java OpenAiMethodDelegateGenerator "
                "--Target_spec <SpecFile.json>"
            ),

            "description": (
                "Uses OpenAI to generate a method-focused delegate class from a spec JSON (methods for cloning)."
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
