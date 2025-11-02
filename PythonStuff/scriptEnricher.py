import os
import subprocess
import openai
import json
import requests

class JavaScriptEnricher:
    def __init__(self, openai_key: str, brave_api_key: str = None, last_loaded_file: str = "LastLoadedScript.txt"):
        """
        openai_key: OpenAI API key for GPT reasoning.
        brave_api_key: Optional Brave Search API key for web enrichment.
        last_loaded_file: Path to LastLoadedScript.txt (used to infer JSON file name).
        """
        openai.api_key = openai_key
        self.brave_api_key = brave_api_key
        self.last_loaded_file = last_loaded_file

        self.script_name = None
        self.script_path = None
        self.prompt = ""   # ✅ now stored here
        self.usage = ""
        self.params = {}
        self.args = []
        self.last_json_file = None

    # ---------------------------------------------------------------
    # Infer script name & JSON file
    # ---------------------------------------------------------------
    def infer_last_json(self):
        if not os.path.exists(self.last_loaded_file):
            raise FileNotFoundError(f"{self.last_loaded_file} not found.")

        with open(self.last_loaded_file, "r", encoding="utf-8") as f:
            lines = f.read().splitlines()

        if len(lines) < 3:
            raise ValueError(f"{self.last_loaded_file} is malformed (needs at least 3 lines).")

        self.script_name = lines[0].strip()
        self.usage = lines[2].strip()
        self.last_json_file = f"{self.script_name}_params_to_adjust.json"
        self.script_path = os.path.join(os.getcwd(), f"{self.script_name}.java")

        if not os.path.exists(self.last_json_file):
            raise FileNotFoundError(f"Parameter JSON not found: {self.last_json_file}")
        if not os.path.exists(self.script_path):
            raise FileNotFoundError(f"Java file not found: {self.script_path}")

        print(f"🧩 Inferred JSON file: {self.last_json_file}")

    # ---------------------------------------------------------------
    # Load parameters JSON (including original prompt)
    # ---------------------------------------------------------------
    def load_json(self):
        self.infer_last_json()
        with open(self.last_json_file, "r", encoding="utf-8") as f:
            data = json.load(f)

        # Load prompt if available
        self.prompt = data.get("prompt", "")
        self.params = data.get("parameters", {})
        self.usage = data.get("usage", self.usage)

        if self.prompt:
            print(f"💬 Loaded original prompt from JSON.")
        else:
            print("⚠️ No prompt found in JSON — continuing without it.")

    # ---------------------------------------------------------------
    # Decide whether enrichment is necessary
    # ---------------------------------------------------------------
    def should_enrich(self) -> bool:
        decision_prompt = f"""
        You are to decide if the following Java script likely needs parameter enrichment.

        Script name: {self.script_name}
        Original prompt: {self.prompt}
        Usage: {self.usage}
        Parameters: {json.dumps(self.params, indent=2)}

        Enrichment is needed when:
        - The script references external libraries or dependencies (like JSON, HTTP, API, XML).
        - The parameters are vague or could map to Maven artifacts.

        Respond only with one word:
        - "true"  → enrichment is needed
        - "false" → enrichment is not needed
        """

        try:
            response = openai.chat.completions.create(
                model="gpt-4o-mini",
                messages=[
                    {"role": "system", "content": "You are a Java build and CLI analysis expert."},
                    {"role": "user", "content": decision_prompt}
                ],
                temperature=0
            )
            decision = response.choices[0].message.content.strip().lower()
            return decision == "true"
        except Exception as e:
            print(f"⚠️ Failed to decide if enrichment is needed: {e}")
            return False

    # ---------------------------------------------------------------
    # Optional web enrichment
    # ---------------------------------------------------------------
    def web_enrich(self, query: str) -> str:
        if not self.brave_api_key:
            print("🌐 Brave API key not set — skipping web search.")
            return ""

        url = "https://api.search.brave.com/res/v1/web/search"
        headers = {"Accept": "application/json", "X-Subscription-Token": self.brave_api_key}
        params = {"q": query, "count": 3}

        try:
            print(f"🌍 Searching web for: {query}")
            response = requests.get(url, headers=headers, params=params)
            data = response.json()
            if "web" in data and "results" in data["web"] and data["web"]["results"]:
                first = data["web"]["results"][0]
                snippet = first.get("snippet", "")
                title = first.get("title", "")
                return f"{title} - {snippet}"
        except Exception as e:
            print(f"⚠️ Web search failed: {e}")
        return ""

    # ---------------------------------------------------------------
    # Enrichment pipeline
    # ---------------------------------------------------------------
    def enrich_parameters(self):
        print("🔍 Enriching parameters based on prompt, usage, and context...")
        enriched = {}

        for key, value in self.params.items():
            if not value or str(value).strip() == "":
                enriched[key] = value
                continue

            # Decide whether to use web search
            decision_prompt = f"""
            Original prompt: {self.prompt}
            Script usage: {self.usage}
            Parameter: {key} = {value}

            Should enrichment require a web search?
            Respond with "web" or "local".
            """
            try:
                decision = openai.chat.completions.create(
                    model="gpt-4o-mini",
                    messages=[
                        {"role": "system", "content": "You are an expert Java and Maven assistant."},
                        {"role": "user", "content": decision_prompt}
                    ],
                    temperature=0
                ).choices[0].message.content.strip().lower()
            except Exception as e:
                print(f"⚠️ GPT decision failed for {key}: {e}")
                decision = "local"

            if decision == "web":
                query = f"latest Maven dependency coordinates for {value}"
                web_result = self.web_enrich(query)
                if web_result:
                    enriched[key] = web_result.splitlines()[0].strip()
                    print(f"🌐 Web enrichment for {key}: {enriched[key]}")
                    continue

            # Fallback to GPT local enrichment
            enrich_prompt = f"""
            You are enriching a Java CLI parameter for a script.

            Original prompt: {self.prompt}
            Usage: {self.usage}
            Parameter: {key} = {value}

            Example: 'json' -> 'org.json:json'
            ⚠️ Return ONLY the enriched value (no explanation).
            """
            try:
                enriched_value = openai.chat.completions.create(
                    model="gpt-4o-mini",
                    messages=[
                        {"role": "system", "content": "You are a Java and Maven enrichment expert."},
                        {"role": "user", "content": enrich_prompt}
                    ],
                    temperature=0
                ).choices[0].message.content.strip()

                enriched[key] = enriched_value.splitlines()[0].strip() if enriched_value else value
            except Exception as e:
                print(f"⚠️ GPT enrichment failed for {key}: {e}")
                enriched[key] = value

        # Keep original parameter names intact
        self.params = {k: enriched.get(k, v) for k, v in self.params.items()}

        print("\n🧠 Final enriched parameters:")
        for k, v in self.params.items():
            print(f"  --{k}: '{v}'")

    # ---------------------------------------------------------------
    # Build CLI args
    # ---------------------------------------------------------------
    def build_args(self):
        self.args = []
        for key, value in self.params.items():
            if value:
                self.args.append(f"--{key}")
                self.args.append(str(value))

    # ---------------------------------------------------------------
    # Compile & run
    # ---------------------------------------------------------------
    def compile_and_run(self):
        script_dir = os.path.dirname(self.script_path)
        print(f"\n⚡ Compiling {self.script_name}.java...")
        compile_result = subprocess.run(
            ["javac", self.script_path],
            capture_output=True,
            text=True,
            cwd=script_dir
        )
        if compile_result.returncode != 0:
            print("❌ Compilation failed:\n", compile_result.stderr)
            return

        print("✅ Compilation successful.")
        print(f"🚀 Running {self.script_name} with args: {' '.join(self.args) if self.args else '(none)'}")

        run_result = subprocess.run(
            ["java", self.script_name] + self.args,
            capture_output=True,
            text=True,
            cwd=script_dir
        )
        print("\n=== Script Output ===")
        print(run_result.stdout.strip())
        if run_result.stderr.strip():
            print("\n=== Script Errors ===")
            print(run_result.stderr.strip())

    # ---------------------------------------------------------------
    # Full pipeline
    # ---------------------------------------------------------------
    def run(self):
        """Auto-infer JSON → load prompt → decide enrichment → enrich (if needed) → build args → run."""
        try:
            self.load_json()
            if self.should_enrich():
                print("✨ Enrichment deemed necessary based on script analysis.")
                self.enrich_parameters()
            else:
                print("🟢 Skipping enrichment — script appears self-contained.")
            self.build_args()
            self.compile_and_run()
        except Exception as e:
            print(f"❌ Error: {e}")
