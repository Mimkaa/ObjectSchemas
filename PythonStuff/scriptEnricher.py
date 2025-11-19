import os
import subprocess
import openai
import json
import requests

class JavaScriptEnricher:
    def __init__(self, openai_key: str, brave_api_key: str = None, last_loaded_file: str = "LastLoadedScript.txt"):
        openai.api_key = openai_key
        self.brave_api_key = brave_api_key
        self.last_loaded_file = last_loaded_file

        self.script_name = None
        self.script_path = None
        self.prompt = ""
        self.usage = ""
        self.description = ""
        self.params = {}
        self.args = []
        self.last_json_file = None
    

    # ---------------------------------------------------------------
    # Infer script name and parameter JSON from LastLoadedScript.txt
    # ---------------------------------------------------------------
    def infer_last_json(self):
        if not os.path.exists(self.last_loaded_file):
            raise FileNotFoundError(f"{self.last_loaded_file} not found.")

        with open(self.last_loaded_file, "r", encoding="utf-8") as f:
            lines = f.read().splitlines()

        if len(lines) < 3:
            raise ValueError(f"{self.last_loaded_file} is malformed.")

        self.script_name = lines[0].strip()
        self.usage = lines[2].strip()

        if len(lines) >= 4:
            self.description = lines[3].strip()

        self.last_json_file = f"{self.script_name}_params_to_adjust.json"
        self.script_path = os.path.join(os.getcwd(), f"{self.script_name}.java")

        if not os.path.exists(self.last_json_file):
            raise FileNotFoundError(f"Missing JSON: {self.last_json_file}")

        if not os.path.exists(self.script_path):
            raise FileNotFoundError(f"Missing Java file: {self.script_path}")

        print(f"🧩 Inferred JSON file: {self.last_json_file}")


    # ---------------------------------------------------------------
    # Load the JSON produced by JavaScriptRunner
    # ---------------------------------------------------------------
    def load_json(self):
        self.infer_last_json()

        with open(self.last_json_file, "r", encoding="utf-8") as f:
            data = json.load(f)

        self.prompt = data.get("prompt", "")
        self.params = data.get("parameters", {})
        self.usage = data.get("usage", self.usage)
        self.description = data.get("description", self.description)

        print("💬 Loaded prompt from JSON.")
        if self.description:
            print("📄 Loaded description from JSON.")
        else:
            print("ℹ No description provided.")


    # ---------------------------------------------------------------
    # Decide whether enrichment is required
    # ---------------------------------------------------------------
    def should_enrich(self) -> bool:
        decision_prompt = f"""
        Determine whether this script requires parameter enrichment.

        Script: {self.script_name}
        Description: {self.description}
        Usage: {self.usage}
        Parameters: {json.dumps(self.params, indent=2)}
        Prompt: {self.prompt}

        Enrichment needed when:
        - The parameters are vague (e.g. 'json', 'file', 'data').
        - A parameter requires Maven coordinates.
        - A parameter refers to an external resource.

        Respond ONLY with:
        true
        or
        false
        """

        try:
            response = openai.chat.completions.create(
                model="gpt-4o-mini",
                messages=[
                    {"role": "system", "content": "Respond only 'true' or 'false'. Nothing else."},
                    {"role": "user", "content": decision_prompt}
                ],
                temperature=0
            )

            decision = response.choices[0].message.content.strip().lower()
            return decision == "true"

        except Exception as e:
            print(f"⚠️ Decision failed: {e}")
            return False


    # ---------------------------------------------------------------
    # Web search for dependency enrichment
    # ---------------------------------------------------------------
    def web_enrich(self, query: str) -> str:
        if not self.brave_api_key:
            return ""

        try:
            url = "https://api.search.brave.com/res/v1/web/search"
            headers = {
                "Accept": "application/json",
                "X-Subscription-Token": self.brave_api_key
            }
            params = {"q": query, "count": 1}

            response = requests.get(url, headers=headers, params=params)
            data = response.json()

            if "web" in data and "results" in data["web"] and data["web"]["results"]:
                first = data["web"]["results"][0]
                return first.get("title", "") + " - " + first.get("snippet", "")

        except Exception as e:
            print(f"🌐 Web enrichment failed: {e}")

        return ""


    # ---------------------------------------------------------------
    # Parameter enrichment (web + GPT local)
    # ---------------------------------------------------------------
    def enrich_parameters(self):
        print("🔍 Enriching parameters...")
        enriched = {}

        for key, raw_value in self.params.items():
            if not raw_value.strip():
                enriched[key] = raw_value
                continue

            # Decide whether to web-search
            decision_prompt = f"""
            Parameter: {key} = {raw_value}

            Should we web-search this value?
            Respond ONLY:
            web
            or
            local
            """

            try:
                decision = openai.chat.completions.create(
                    model="gpt-4o-mini",
                    messages=[
                        {"role": "system", "content": "Respond only 'web' or 'local'."},
                        {"role": "user", "content": decision_prompt}
                    ],
                    temperature=0
                ).choices[0].message.content.strip().lower()
            except:
                decision = "local"

            # Web enrichment branch
            if decision == "web":
                query = f"maven dependency {raw_value}"
                web = self.web_enrich(query)
                if web:
                    cleaned = web.split("-")[0].strip()
                    print(f"🌐 Web enriched {key}: {raw_value} → {cleaned}")
                    enriched[key] = cleaned
                    continue

            # Local GPT enrichment
            enrich_prompt = f"""
            Enrich this Java CLI parameter if and only if it is a dependency.

            Parameter value: {raw_value}

            If it's a dependency name, convert it to a Maven coordinate.
            If not, return it unchanged.

            Return ONLY the final value.
            """

            try:
                enriched_value = openai.chat.completions.create(
                    model="gpt-4o-mini",
                    messages=[
                        {"role": "system",
                         "content": "Return only the enriched parameter. If uncertain, return the original unchanged."},
                        {"role": "user", "content": enrich_prompt}
                    ],
                    temperature=0
                ).choices[0].message.content.strip()

                enriched[key] = enriched_value

            except Exception:
                enriched[key] = raw_value

        self.params = enriched

        print("\n🧠 Final enriched parameters:")
        for k, v in self.params.items():
            print(f"  --{k}: {v}")


    # ---------------------------------------------------------------
    # Build arguments list
    # ---------------------------------------------------------------
    def build_args(self):
        self.args = []
        for key, value in self.params.items():
            if value:
                self.args.append(f"--{key}")
                self.args.append(str(value))


    # ---------------------------------------------------------------
    # Compile and run Java script
    # ---------------------------------------------------------------
    def compile_and_run(self):
        script_dir = os.path.dirname(self.script_path)

        print(f"\n⚡ Compiling {self.script_name}.java...")
        result = subprocess.run(
            ["javac", self.script_path],
            capture_output=True,
            text=True,
            cwd=script_dir
        )

        if result.returncode != 0:
            print("❌ Compilation failed:\n", result.stderr)
            return

        print("✅ Compilation successful.")

        print(f"🚀 Running {self.script_name} with args:", " ".join(self.args))

        run = subprocess.run(
            ["java", self.script_name] + self.args,
            capture_output=True,
            text=True,
            cwd=script_dir
        )

        print("\n=== OUTPUT ===")
        print(run.stdout)
        if run.stderr.strip():
            print("\n=== ERRORS ===")
            print(run.stderr)


    # ---------------------------------------------------------------
    # Full pipeline
    # ---------------------------------------------------------------
    def run(self):
        try:
            self.load_json()

            if self.should_enrich():
                print("✨ Enrichment is needed.")
                self.enrich_parameters()
            else:
                print("🟢 Enrichment skipped — script appears self-contained.")

            self.build_args()
            self.compile_and_run()

        except Exception as e:
            print(f"❌ Error: {e}")
