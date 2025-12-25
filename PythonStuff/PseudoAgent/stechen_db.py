import sqlite3
from dataclasses import dataclass
from typing import Optional, List, Literal

Status = Literal["SUCCESS", "FAIL", "SKIPPED"]

SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS commands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    command_text TEXT NOT NULL,
    executed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    status TEXT NOT NULL CHECK(status IN ('SUCCESS','FAIL','SKIPPED')),
    output TEXT
);

CREATE INDEX IF NOT EXISTS idx_commands_executed_at ON commands(executed_at);
CREATE INDEX IF NOT EXISTS idx_commands_status ON commands(status);
"""

@dataclass(frozen=True)
class CommandRow:
    id: int
    command_text: str
    executed_at: str
    status: str
    output: Optional[str]


class StechenDB:
    """
    Database utility only.
    - No pipe parsing
    - No command execution
    Just persistence + queries.
    """

    def __init__(self, db_path: str = "stechen.db"):
        self.db_path = db_path
        self._con: Optional[sqlite3.Connection] = None

    def connect(self) -> sqlite3.Connection:
        if self._con is None:
            con = sqlite3.connect(self.db_path)
            con.row_factory = sqlite3.Row
            self._con = con
        return self._con

    def close(self) -> None:
        if self._con is not None:
            self._con.close()
            self._con = None

    def init(self) -> None:
        con = self.connect()
        con.executescript(SCHEMA)
        con.commit()

    # -------------------------
    # Writes
    # -------------------------

    def log_command(self, command_text: str, status: Status, output: Optional[str] = None) -> int:
        if status not in ("SUCCESS", "FAIL", "SKIPPED"):
            raise ValueError("status must be SUCCESS, FAIL, or SKIPPED")

        con = self.connect()
        cur = con.execute(
            "INSERT INTO commands (command_text, status, output) VALUES (?, ?, ?)",
            (command_text, status, output),
        )
        con.commit()
        return int(cur.lastrowid)

    # -------------------------
    # Reads
    # -------------------------

    def get_last_command(self) -> Optional[CommandRow]:
        con = self.connect()
        r = con.execute(
            """
            SELECT id, command_text, executed_at, status, output
            FROM commands
            ORDER BY id DESC
            LIMIT 1
            """
        ).fetchone()
        return self._row_to_command(r) if r else None

    def get_last_n_commands(self, n: int = 5, chronological: bool = True) -> List[CommandRow]:
        con = self.connect()
        rows = con.execute(
            """
            SELECT id, command_text, executed_at, status, output
            FROM commands
            ORDER BY id DESC
            LIMIT ?
            """,
            (n,),
        ).fetchall()

        cmds = [self._row_to_command(r) for r in rows]
        if chronological:
            cmds.reverse()
        return cmds

    def get_commands_after_id(self, after_id: int, limit: int = 50) -> List[CommandRow]:
        con = self.connect()
        rows = con.execute(
            """
            SELECT id, command_text, executed_at, status, output
            FROM commands
            WHERE id > ?
            ORDER BY id
            LIMIT ?
            """,
            (after_id, limit),
        ).fetchall()
        return [self._row_to_command(r) for r in rows]

    def get_last_failure(self) -> Optional[CommandRow]:
        con = self.connect()
        r = con.execute(
            """
            SELECT id, command_text, executed_at, status, output
            FROM commands
            WHERE status = 'FAIL'
            ORDER BY id DESC
            LIMIT 1
            """
        ).fetchone()
        return self._row_to_command(r) if r else None

    def get_status_counts(self) -> dict:
        con = self.connect()
        rows = con.execute(
            """
            SELECT status, COUNT(*) AS cnt
            FROM commands
            GROUP BY status
            """
        ).fetchall()
        return {r["status"]: int(r["cnt"]) for r in rows}

    # -------------------------
    # Maintenance (optional)
    # -------------------------

    def clear_commands(self) -> None:
        """Useful for testing. Deletes all command rows."""
        con = self.connect()
        con.execute("DELETE FROM commands")
        con.commit()

    # -------------------------
    # Helpers
    # -------------------------

    @staticmethod
    def _row_to_command(r: sqlite3.Row) -> CommandRow:
        return CommandRow(
            id=int(r["id"]),
            command_text=str(r["command_text"]),
            executed_at=str(r["executed_at"]),
            status=str(r["status"]),
            output=r["output"],
        )
