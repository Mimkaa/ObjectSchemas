import os
import subprocess
import openai
import json

class JavaScriptRunner:
    def __init__(self, openai_key: str):
        openai.api_key = openai_key
        self.script_name = None
        self.script_path = None
        self.params = {}
        self.args = []
        self.param_file = None
        self.prompt = ""
        self.usage = ""
        self.description = ""  # ✅ new
        self.script_dir = ""

    # ---------------------------------------------------------------
    # Load info from LastLoadedScript.txt
    # ---------------------------------------------------------------
    def load_last_script(self, last_loaded_file: str = "LastLoadedScript.txt"):
        """
        Load last downloaded script info from LastLoadedScript.txt

        Expected format (one line each):
        1) script_name
        2) prompt
        3) usage
        4) description   (optional but preferred; written by ScriptRetriever)
        """
        path = os.path.join(os.getcwd(), last_loaded_file)
        if not os.path.exists(path):
            raise FileNotFoundError(f"{last_loaded_file} not found.")

        with open(path, "r", encoding="utf-8") as f:
            lines = f.read().splitlines()

        if len(lines) < 3:
            raise ValueError(f"{last_loaded_file} needs at least 3 lines (script name, prompt, usage).")

        self.script_name = lines[0]
        self.prompt = lines[1]
        self.usage = lines[2]
        if len(lines) >= 4:
            self.description = lines[3]

        self.script_dir = os.getcwd()
        self.script_path = os.path.join(self.script_dir, f"{self.script_name}.java")
        self.param_file = os.path.join(self.script_dir, f"{self.script_name}_params_to_adjust.json")

        if not os.path.exists(self.script_path):
            raise FileNotFoundError(f"{self.script_name}.java not found in current folder.")

        print(f"📄 Loaded script info:")
        print(f"  Script: {self.script_name}")
        print(f"  Prompt: {self.prompt}")
        print(f"  Usage : {self.usage}")
        if self.description:
            print(f"  Desc  : {self.description}")

    # ---------------------------------------------------------------
    # Parameter extraction (GPT)
    # ---------------------------------------------------------------
    def extract_parameters(self):
        """Infer CLI parameters strictly from usage and prompt — no unnecessary guesses."""
        print("🤖 Analyzing script usage and prompt for parameters...")

        gpt_prompt = f"""
        You are analyzing a Java command-line program.

        Your task:
        - Identify ONLY the parameters explicitly mentioned in the usage string or clearly implied in the prompt.
        - Do NOT invent any extra fields (like 'path', 'content', or 'command') unless they are clearly required.
        - Prefer simple, logical default values that exist in the current directory or that make sense in context.
          For example:
            * if it's a file name, use './example.txt'
            * if it's a directory, use '.'
            * if it's a library or dependency, use a realistic Maven coordinate
        - Output a FLAT JSON object (no nesting, no metadata).

        Example:
        Usage: java DynamicJarLoader --library <group:artifact:version>
        Prompt: i need a library that has to do with json in java
        Output:
        {{
          "library": "org.json:json:20210307"
        }}

        Now analyze this script:

        Script name: {self.script_name}
        Description: {self.description}
        Original user prompt: {self.prompt}
        Script usage: {self.usage}
        """

        try:
            response = openai.chat.completions.create(
                model="gpt-4o-mini",
                response_format={"type": "json_object"},
                messages=[
                    {
                        "role": "system",
                        "content": (
                            "You are a precise and grounded Java CLI parameter inference engine. "
                            "Never guess or invent new parameter names. Only return parameters that "
                            "are clearly justified by the usage string or the prompt."
                        )
                    },
                    {"role": "user", "content": gpt_prompt}
                ],
                temperature=0
            )
            raw_output = response.choices[0].message.content
            data = json.loads(raw_output)
        except Exception as e:
            print(f"⚠️ GPT parameter extraction failed: {e}")
            data = {}

        # 🧹 Flatten if GPT still nests "parameters"
        if isinstance(data, dict) and "parameters" in data and isinstance(data["parameters"], dict):
            self.params = data["parameters"]
        else:
            self.params = data if isinstance(data, dict) else {}

        if self.params:
            print("\n🧠 Detected parameters:")
            for k, v in self.params.items():
                print(f"  --{k}: '{v}'")
        else:
            print("⚙️ No parameters inferred; running without arguments.")
            self.params = {}

    # ---------------------------------------------------------------
    # Save extracted parameters to JSON (includes original prompt + description)
    # ---------------------------------------------------------------
    def save_parameters_to_json(self):
        """Save extracted parameters and metadata — including the original user prompt & description — to a JSON file."""
        data = {
            "script_name": self.script_name,
            "description": self.description,  # ✅ included
            "prompt": self.prompt,
            "usage": self.usage,
            "parameters": self.params
        }
        try:
            with open(self.param_file, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
            print(f"\n💾 Saved parameters (with prompt & description) to {self.param_file}")
        except Exception as e:
            print(f"❌ Failed to save parameters JSON: {e}")

    # ---------------------------------------------------------------
    # Extract only (no compile/run)
    # ---------------------------------------------------------------
    def extract_parameters_only(self):
        """Load the last script, extract parameters, and save them — no execution."""
        self.load_last_script()
        self.extract_parameters()
        self.save_parameters_to_json()
        print(self.params)
        print("✅ Parameter extraction complete (no execution).")

    def run(self):
        """Convenience: just run the extraction-only pipeline."""
        self.extract_parameters_only()

    # ---------------------------------------------------------------
    # Full pipeline (optional legacy)
    # ---------------------------------------------------------------
    def run_last_script(self):
        """
        Full pipeline (for legacy use):
        load last script → extract → save params JSON → compile & run Java.
        """
        try:
            self.load_last_script()
            self.extract_parameters()
            self.save_parameters_to_json()
            self.compile_and_run()
        except Exception as e:
            print(f"❌ Error while running last script: {e}")

    # ---------------------------------------------------------------
    # Compile and run
    # ---------------------------------------------------------------
    def compile_and_run(self):
        """Compile and run the Java script using inferred parameters."""
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

        # Build CLI args from params dict
        args = []
        for k, v in self.params.items():
            if v:
                args.append(f"--{k}")
                args.append(str(v))

        print(f"🚀 Running {self.script_name} with args: {' '.join(args) if args else '(none)'}")

        run_result = subprocess.run(
            ["java", self.script_name] + args,
            capture_output=True,
            text=True,
            cwd=script_dir
        )

        print("\n=== Script Output ===")
        print(run_result.stdout.strip())

        if run_result.stderr.strip():
            print("\n=== Script Errors ===")
            print(run_result.stderr.strip())
