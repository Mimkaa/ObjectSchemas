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

        self.script_name = None
        self.prompt = None
        self.usage = None
        self.description = ""

    def _save_to_file(self, file_path: str, content: str):
        try:
            with open(file_path, "w", encoding="utf-8", newline="\n") as f:
                f.write(content)
            print(f"✅ File saved: {file_path}")
            return True
        except Exception as e:
            print(f"❌ Failed to save file {file_path}: {e}")
            return False

    def _update_last_loaded_script(self):
        if not all([self.script_name, self.prompt]):
            print("❌ Cannot update LastLoadedScript.txt: script_name or prompt missing.")
            return False

        path = os.path.join(os.getcwd(), "LastLoadedScript.txt")
        content = (
            f"{self.script_name}\n"
            f"{self.prompt}\n"
            f"{self.usage or ''}\n"
            f"{self.description or ''}\n"
        )
        return self._save_to_file(path, content)

    def _download_script(self, script_name: str):
        url = f"{self.github_raw_base}{script_name}.java"
        try:
            resp = requests.get(url, timeout=10)
            resp.raise_for_status()
            resp.encoding = 'utf-8'

            file_path = os.path.join(os.getcwd(), f"{script_name}.java")
            if self._save_to_file(file_path, resp.text):
                return file_path, resp.text
        except Exception as e:
            print(f"❌ Failed to download {script_name}: {e}")

        return None, None

    def retrieve(self, prompt: str):
        print(f"🧠 Retrieving top 3 scripts for prompt: {prompt}")
        self.prompt = prompt

        # --- embeddings ---
        emb = openai.embeddings.create(
            model="text-embedding-ada-002",
            input=prompt
        )
        vec = emb.data[0].embedding

        # --- Pinecone ---
        results = self.index.query(
            vector=vec,
            top_k=3,
            include_metadata=True
        )

        if not results.matches:
            print("❌ No matches found.")
            return None

        # ------------------------------
        # ✅ ALWAYS TAKE FIRST MATCH
        # ------------------------------
        top = results.matches[0]
        script_name = top.metadata.get("script_name")
        usage = top.metadata.get("usage", "")
        description = top.metadata.get("description", "")

        if not script_name:
            print("❌ Top result missing script_name.")
            return None

        print(f"🎯 Choosing FIRST Pinecone result: {script_name}")

        file_path, content = self._download_script(script_name)
        if not file_path:
            print("❌ Failed to download chosen script.")
            return None

        self.script_name = script_name
        self.usage = usage
        self.description = description

        self._update_last_loaded_script()

        print(f"✅ Chosen script: {self.script_name}")
        return file_path

    def run(self):
        if not self.prompt:
            print("❌ No prompt set.")
            return False

        print(f"🚀 Running retriever for prompt: {self.prompt}")
        return self.retrieve(self.prompt) is not None
