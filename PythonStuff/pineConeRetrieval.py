# script_retriever.py
import os
import requests
import openai
from pinecone import Pinecone

class ScriptRetriever:
    def __init__(self, openai_key: str, pinecone_key: str, index_name: str = "quickstartrar"):
        openai.api_key = openai_key
        self.pc = Pinecone(api_key=pinecone_key)
        self.index_name = index_name
        self.index = self.pc.Index(index_name)
        self.github_raw_base = "https://raw.githubusercontent.com/Mimkaa/ObjectSchemas/main/"

        # --- Attributes to store the last retrieved script info ---
        self.script_name = None
        self.prompt = None
        self.usage = None

    def _save_to_file(self, file_path: str, content: str):
        """Save content to a file with UTF-8 encoding and Unix-style line endings."""
        try:
            with open(file_path, "w", encoding="utf-8", newline='\n') as f:
                f.write(content)
            print(f"✅ File saved: {file_path}")
            return True
        except Exception as e:
            print(f"❌ Failed to save file {file_path}: {e}")
            return False

    def _update_last_loaded_script(self):
        """Update LastLoadedScript.txt with the latest script info."""
        if not all([self.script_name, self.prompt]):
            print("❌ Cannot update LastLoadedScript.txt: script_name or prompt missing.")
            return False

        last_loaded_path = os.path.join(os.getcwd(), "LastLoadedScript.txt")
        content = f"{self.script_name}\n{self.prompt}\n{self.usage or ''}\n"
        return self._save_to_file(last_loaded_path, content)

    def retrieve(self, prompt: str):
        """Retrieve the closest matching script for the given prompt."""
        print(f"🧠 Retrieving script for prompt: {prompt}")
        self.prompt = prompt

        # --- Generate embedding ---
        response = openai.embeddings.create(
            model="text-embedding-ada-002",
            input=prompt
        )
        query_vector = response.data[0].embedding

        # --- Query Pinecone ---
        results = self.index.query(
            vector=query_vector,
            top_k=1,
            include_metadata=True
        )

        if not results.matches:
            print("❌ No matching scripts found in Pinecone.")
            return None

        # --- Retrieve metadata ---
        best_match = results.matches[0]
        self.script_name = best_match.metadata.get("script_name")
        self.usage = best_match.metadata.get("usage", "")

        if not self.script_name:
            print("❌ Metadata for script_name missing.")
            return None

        # --- Download script ---
        script_url = f"{self.github_raw_base}{self.script_name}.java"
        try:
            response = requests.get(script_url, timeout=10)
            response.raise_for_status()
            response.encoding = 'utf-8'
        except Exception as e:
            print(f"❌ Failed to download script from {script_url}: {e}")
            return None

        file_path = os.path.join(os.getcwd(), f"{self.script_name}.java")
        if not self._save_to_file(file_path, response.text):
            return None

        # --- Update LastLoadedScript.txt ---
        if not self._update_last_loaded_script():
            return None

        print("✅ LastLoadedScript.txt updated.")
        return file_path

    def run(self):
        """
        Always retrieve the script for the current prompt.
        Ensures script_name, prompt, and LastLoadedScript.txt are updated.
        """
        if not self.prompt:
            print("❌ Cannot run: prompt not set. Please set retriever.prompt first.")
            return False

        print(f"🚀 Running retriever for prompt: {self.prompt}")
        result = self.retrieve(self.prompt)
        if result:
            print("✅ Script successfully retrieved and updated.")
            return True
        else:
            print("❌ Retrieval failed during run().")
            return False
