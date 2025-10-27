import os
import requests
import openai
from pinecone import Pinecone, ServerlessSpec

# === API keys ===
openai.api_key = "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"
pc = Pinecone(api_key="pcsk_3FJzvR_3w7fCzPaidjw3usmFC9Fu6zpvfUwjWkYn31bLHa1Ag5KJiSgFo1BBRQYMJHRBec")

# === Pinecone index settings ===
index_name = "quickstartrar"
dimension = 1536
index = pc.Index(index_name)

# === GitHub raw URL base ===
github_raw_base = "https://raw.githubusercontent.com/Mimkaa/ObjectSchemas/main/"

# === Query ===
query_description = "Create a directory using command-line arguments"

# Generate embedding for the query
response = openai.embeddings.create(
    model="text-embedding-ada-002",
    input=query_description
)
query_vector = response.data[0].embedding

# Search Pinecone for the closest match
results = index.query(
    vector=query_vector,
    top_k=1,
    include_metadata=True
)

if not results.matches:
    print("❌ No matching scripts found in Pinecone.")
    exit()

# Retrieve the script metadata
best_match = results.matches[0]
script_name = best_match.metadata.get("script_name")
usage = best_match.metadata.get("usage", "")

if not script_name:
    print("❌ Metadata for script_name missing.")
    exit()

# Download the script from GitHub into the current folder
script_url = f"{github_raw_base}{script_name}.java"
response = requests.get(script_url)
if response.status_code == 200:
    file_path = os.path.join(os.getcwd(), f"{script_name}.java")
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(response.text)
    print(f"✅ Script downloaded to current folder: {file_path}")

    # Update LastLoadedScript.txt
    last_loaded_path = os.path.join(os.getcwd(), "LastLoadedScript.txt")
    with open(last_loaded_path, "w", encoding="utf-8") as f:
        f.write(script_name + "\n")          # first line: script name
        f.write(query_description + "\n")    # second line: prompt/query
        f.write(usage + "\n")                # third line: usage
    print(f"✅ LastLoadedScript.txt updated with script name, prompt, and usage.")

else:
    print(f"❌ File not found at {script_url}")
