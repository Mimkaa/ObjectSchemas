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
    # Infer script + JSON name from LastLoadedScript.txt
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
    # Load JSON containing script params
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
        print("📄 Loaded description.")


    # ---------------------------------------------------------------
    # Decide whether parameter enrichment needed
    # ---------------------------------------------------------------
    def should_enrich(self) -> bool:
        decision_prompt = f"""
        Determine whether this script requires parameter enrichment.

        Script: {self.script_name}
        Description: {self.description}
        Usage: {self.usage}
        Parameters: {json.dumps(self.params, indent=2)}
        Prompt: {self.prompt}

        Respond ONLY:
        true
        or
        false
        """

        try:
            response = openai.chat.completions.create(
                model="gpt-4o-mini",
                messages=[
                    {"role": "system", "content": "Respond only 'true' or 'false'."},
                    {"role": "user", "content": decision_prompt}
                ],
                temperature=0
            )
            decision = response.choices[0].message.content.strip()
            return decision.lower() == "true"
        except:
            return False


    # ---------------------------------------------------------------
    # Enrich dependency-style parameters (currently a no-op passthrough)
    # ---------------------------------------------------------------
    def enrich_parameters(self):
        print("🔍 Enriching parameters...")
        enriched = {}

        for key, raw in self.params.items():
            if raw.strip():
                enriched[key] = raw
            else:
                enriched[key] = raw  # keep unchanged

        self.params = enriched

        print("\n🧠 Final parameters:")
        for k, v in self.params.items():
            print(f"  --{k}: {v}")


    # ---------------------------------------------------------------
    # Convert dict → CLI arg list
    # ---------------------------------------------------------------
    def build_args(self):
        self.args = []
        for key, value in self.params.items():
            if value:
                self.args.append(f"--{key}")
                self.args.append(str(value))


    # ---------------------------------------------------------------
    # UPDATED — auto-jar classpath + force Java 17 bytecode
    # ---------------------------------------------------------------
    def compile_and_run(self):
        script_dir = os.path.dirname(self.script_path)

        # detect all *.jar files in folder
        jars = [f for f in os.listdir(script_dir) if f.endswith(".jar")]
        # Windows-style classpath; adjust manually if you ever run on Linux/macOS
        cp = ".;" + ";".join(jars) if jars else "."

        print("\n📦 Detected JARs:", jars if jars else "NONE")

        print(f"\n⚡ Compiling {self.script_name}.java ...")
        # 🔑 Force Java 17 classfile version regardless of installed JDK
        result = subprocess.run(
            ["javac", "--release", "17", "-cp", cp, self.script_path],
            capture_output=True,
            text=True,
            cwd=script_dir
        )

        if result.returncode != 0:
            print("❌ Compilation failed:\n", result.stderr)
            return

        print("✅ Compilation successful.")
        print("🚀 Running", self.script_name)

        run = subprocess.run(
            ["java", "-cp", cp, self.script_name] + self.args,
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
                self.enrich_parameters()
            else:
                print("🟢 Enrichment skipped — script self-contained.")

            self.build_args()
            self.compile_and_run()

        except Exception as e:
            print(f"❌ Error: {e}")
