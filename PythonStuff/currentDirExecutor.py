import os
import inspect

class CurrentDirExecutor:
    """
    Executes any instance that defines a `run()` method
    in the directory specified by the first line of CurrentWorkingDir.txt,
    then restores the original working directory afterward.
    """

    def __init__(self, txt_file="CurrentWorkingDir.txt"):
        self.txt_file = txt_file

    def get_target_directory(self):
        """Reads and validates the directory path from CurrentWorkingDir.txt."""
        if not os.path.exists(self.txt_file):
            print(f"⚠️  {self.txt_file} not found. Using current working directory.")
            return os.getcwd()

        with open(self.txt_file, "r", encoding="utf-8") as f:
            first_line = f.readline().strip()

        if not first_line:
            print(f"⚠️  {self.txt_file} is empty. Using current working directory.")
            return os.getcwd()

        abs_path = os.path.abspath(first_line)
        if not os.path.isdir(abs_path):
            print(f"⚠️  '{abs_path}' is not a valid directory. Using current working directory.")
            return os.getcwd()

        return abs_path

    def execute(self, instance):
        """
        Executes the `run()` method of any class instance inside
        the directory specified by CurrentWorkingDir.txt, then restores
        the original working directory.
        """
        if not hasattr(instance, "run") or not callable(getattr(instance, "run")):
            print(f"❌ The provided instance of type '{type(instance).__name__}' "
                  f"does not have a callable 'run()' method.")
            return

        original_dir = os.getcwd()
        target_dir = self.get_target_directory()

        print(f"📂 Switching to directory: {target_dir}")
        os.chdir(target_dir)

        try:
            print(f"🚀 Executing {type(instance).__name__}.run() ...")
            instance.run()
            print("✅ Execution completed successfully.")
        except Exception as e:
            print(f"❌ Execution failed: {e}")
        finally:
            os.chdir(original_dir)
            print(f"↩️  Returned to: {original_dir}")
