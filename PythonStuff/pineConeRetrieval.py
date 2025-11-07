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
        try:
            with open(file_path, "w", encoding="utf-8", newline='\n') as f:
                f.write(content)
            print(f"✅ File saved: {file_path}")
            return True
        except Exception as e:
            print(f"❌ Failed to save file {file_path}: {e}")
            return False

    def _update_last_loaded_script(self):
        """Update LastLoadedScript.txt with the chosen script info."""
        if not all([self.script_name, self.prompt]):
            print("❌ Cannot update LastLoadedScript.txt: script_name or prompt missing.")
            return False

        last_loaded_path = os.path.join(os.getcwd(), "LastLoadedScript.txt")
        content = f"{self.script_name}\n{self.prompt}\n{self.usage or ''}\n"
        return self._save_to_file(last_loaded_path, content)

    def _download_script(self, script_name: str):
        """Download a single script from GitHub."""
        url = f"{self.github_raw_base}{script_name}.java"
        try:
            response = requests.get(url, timeout=10)
            response.raise_for_status()
            response.encoding = 'utf-8'
            file_path = os.path.join(os.getcwd(), f"{script_name}.java")
            if self._save_to_file(file_path, response.text):
                return file_path, response.text
        except Exception as e:
            print(f"❌ Failed to download {script_name} from {url}: {e}")
        return None, None

    def retrieve(self, prompt: str):
        """Retrieve the best matching script for the given prompt using GPT evaluation."""
        print(f"🧠 Retrieving top 3 scripts for prompt: {prompt}")
        self.prompt = prompt

        # --- Generate embedding ---
        response = openai.embeddings.create(
            model="text-embedding-ada-002",
            input=prompt
        )
        query_vector = response.data[0].embedding

        # --- Query Pinecone for top 3 matches ---
        results = self.index.query(
            vector=query_vector,
            top_k=3,
            include_metadata=True
        )

        if not results.matches:
            print("❌ No matching scripts found in Pinecone.")
            return None

        scripts_info = []
        for match in results.matches:
            script_name = match.metadata.get("script_name")
            usage = match.metadata.get("usage", "")
            if not script_name:
                continue
            file_path, content = self._download_script(script_name)
            if file_path:
                scripts_info.append({
                    "script_name": script_name,
                    "usage": usage,
                    "file_path": file_path,
                    "content": content
                })

        if not scripts_info:
            print("❌ No scripts could be downloaded.")
            return None

        # --- Ask GPT to choose the best script ---
        gpt_prompt = "You are a helpful assistant. Given the user's request and descriptions of 3 Java scripts, choose the one that best fits the prompt.\n\n"
        gpt_prompt += f"User prompt:\n{prompt}\n\n"
        gpt_prompt += "Scripts descriptions:\n"
        for i, script in enumerate(scripts_info, 1):
            gpt_prompt += f"{i}. Name: {script['script_name']}, Description/Usage: {script['usage']}\n"

        gpt_prompt += "\nReply with the number of the best script choice."

        try:
            completion = openai.chat.completions.create(
                model="gpt-4",
                messages=[{"role": "user", "content": gpt_prompt}],
                temperature=0
            )
            choice_text = completion.choices[0].message.content.strip()
            choice_index = int(choice_text) - 1
        except Exception as e:
            print(f"❌ GPT evaluation failed: {e}")
            choice_index = 0  # fallback to first script

        if choice_index < 0 or choice_index >= len(scripts_info):
            choice_index = 0

        chosen_script = scripts_info[choice_index]
        self.script_name = chosen_script["script_name"]
        self.usage = chosen_script["usage"]

        # --- Update LastLoadedScript.txt ---
        if not self._update_last_loaded_script():
            return None

        print(f"✅ Chosen script: {self.script_name}")
        return chosen_script["file_path"]

    def run(self):
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
