# stechen_gpt_summarize.py
#
# Purpose:
#   Read the last N executed STECHEN commands from stechen.db
#   + include your STECHEN rules/spec text
#   + ask GPT-5.2 to produce a planner-ready summary
#   + MUST include the LAST RAN COMMAND explicitly in the summary output.
#
# Requirements:
#   pip install openai
#   set OPENAI_API_KEY in your environment
#
# Notes:
#   - This is a DB summarizer utility (no pipeline execution).
#   - Put your STECHEN rules/spec in a text file (default: stechen_rules.txt).
#   - Rules and outputs are truncated to avoid blowing context.

import os
from pathlib import Path
from typing import List, Optional

from openai import OpenAI

from stechen_db import StechenDB, CommandRow


# ----------------------------
# CONFIG
# ----------------------------

DB_PATH = "stechen.db"
RULES_FILE = "stechen_rules.txt"

MODEL = "gpt-5.2"

# Keep payload small & stable
N_COMMANDS = 10
MAX_RULES_CHARS = 80_000
MAX_COMMAND_CHARS = 500
MAX_OUTPUT_CHARS_PER_CMD = 3_000


# ----------------------------
# HELPERS
# ----------------------------

def _cap(s: Optional[str], n: int) -> str:
    if not s:
        return ""
    s = s.strip()
    if len(s) <= n:
        return s
    return s[:n] + "\n... [truncated]"


def _read_rules_text(path: str) -> str:
    p = Path(path)
    if not p.exists():
        raise FileNotFoundError(
            f"Rules file not found: {p.resolve()}\n"
            f"Create it (e.g. {RULES_FILE}) and paste your STECHEN rules/spec into it."
        )
    return p.read_text(encoding="utf-8", errors="replace")


def _one_line(s: str) -> str:
    return (s or "").replace("\n", " ").strip()


def _format_commands(rows: List[CommandRow]) -> str:
    """
    Structured evidence for the model.
    Keep it consistent so summaries are consistent.
    """
    chunks: List[str] = []
    for r in rows:
        cmd = _cap(_one_line(r.command_text), MAX_COMMAND_CHARS)
        out = _cap(r.output, MAX_OUTPUT_CHARS_PER_CMD)

        chunks.append(f"ID: {r.id}")
        chunks.append(f"TIME: {r.executed_at}")
        chunks.append(f"STATUS: {r.status}")
        chunks.append(f"COMMAND: {cmd}")
        if out:
            chunks.append("OUTPUT:")
            chunks.append(out)
        chunks.append("----")
    return "\n".join(chunks)


def _build_instructions() -> str:
    """
    Keep this short and stable.
    Put ALL STECHEN rules/spec in the input as reference material.
    """
    return (
        "You are a STECHEN pipeline execution summarizer.\n"
        "You will be given:\n"
        "(A) STECHEN_SYSTEM_RULES (authoritative reference)\n"
        "(B) LAST_COMMAND (the most recent single executed command)\n"
        "(C) LAST_COMMANDS (SQLite execution log)\n\n"
        "Task:\n"
        "- Summarize what happened in the provided command log.\n"
        "- Use STECHEN rules to interpret what each command means.\n"
        "- Identify failures (if any) and likely causes.\n"
        "- Infer current state only from evidence; do not invent steps.\n"
        "- Suggest the next best actions that follow STECHEN rules.\n\n"
        "Hard rules:\n"
        "- Do NOT invent commands that were not executed.\n"
        "- If something is unknown/unclear, explicitly say what is unknown.\n"
        "- In 'LAST COMMAND', print exactly the most recent command (ID + STATUS + COMMAND).\n"
        "- Be concise and high-signal.\n\n"
        "Output format (exact headings):\n"
        "1) LAST COMMAND\n"
        "2) WHAT HAPPENED\n"
        "3) FAILURES\n"
        "4) CURRENT STATE\n"
        "5) NEXT BEST ACTION\n"
    )


# ----------------------------
# MAIN SUMMARIZER
# ----------------------------

def summarize_last_n_commands(
    db_path: str = DB_PATH,
    rules_file: str = RULES_FILE,
    n: int = N_COMMANDS,
    model: str = MODEL,
) -> str:
    # Load STECHEN rules/spec
    rules_text = _read_rules_text(rules_file)
    rules_text = _cap(rules_text, MAX_RULES_CHARS)

    # Load last N commands
    db = StechenDB(db_path)
    db.init()
    rows = db.get_last_n_commands(n, chronological=True)

    if not rows:
        return f"No commands found in {db_path}."

    # Explicit last command (most recent)
    last = rows[-1]
    last_cmd_line = (
        f"ID: {last.id} | STATUS: {last.status} | COMMAND: "
        f"{_cap(_one_line(last.command_text), MAX_COMMAND_CHARS)}"
    )

    commands_text = _format_commands(rows)

    # Build model input
    input_text = (
        "STECHEN_SYSTEM_RULES:\n"
        f"{rules_text}\n\n"
        "LAST_COMMAND:\n"
        f"{last_cmd_line}\n\n"
        "LAST_COMMANDS:\n"
        f"{commands_text}\n"
    )

    # Call GPT-5.2 using Responses API
    client = OpenAI()  # reads OPENAI_API_KEY from env
    response = client.responses.create(
        model=model,
        instructions=_build_instructions(),
        input=input_text,
        reasoning={"effort": "low"},
    )

    return response.output_text


if __name__ == "__main__":
    # Usage:
    #   1) Put your STECHEN rules/spec into: stechen_rules.txt
    #   2) Ensure stechen.db exists (your runner writes to it)
    #   3) Set OPENAI_API_KEY
    #   4) Run: python stechen_gpt_summarize.py
    #
    # Optional env overrides:
    #   STECHEN_DB_PATH, STECHEN_RULES_FILE, STECHEN_N_COMMANDS, STECHEN_MODEL

    db_path = os.getenv("STECHEN_DB_PATH", DB_PATH)
    rules_file = os.getenv("STECHEN_RULES_FILE", RULES_FILE)
    n = int(os.getenv("STECHEN_N_COMMANDS", str(N_COMMANDS)))
    model = os.getenv("STECHEN_MODEL", MODEL)

    print(summarize_last_n_commands(db_path=db_path, rules_file=rules_file, n=n, model=model))
