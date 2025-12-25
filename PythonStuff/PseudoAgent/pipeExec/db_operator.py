# db_operator.py
#
# DBOperator for STECHEN "pipeline(step_id)" schema.
# - pipeline: one row per step (script_name + created_at + optional run_id, step_index)
# - per-script tables: 1:1 payload rows keyed by step_id (PRIMARY KEY + FK -> pipeline.step_id)
# - payload_store: store large blobs (Java source etc) and reference by content_ref
#
# Usage:
#   python db_operator.py
#   python db_operator.py stechen.db

import sys
import sqlite3
import hashlib
from typing import Any, Dict, List, Optional


class DBOperator:
    def __init__(self, db_path: str = "stechen.db"):
        self.db_path = db_path
        self.conn = sqlite3.connect(self.db_path, timeout=30.0)
        self.conn.row_factory = sqlite3.Row

        # good concurrent behavior on Windows
        try:
            self.conn.execute("PRAGMA foreign_keys = ON;")
            self.conn.execute("PRAGMA journal_mode = WAL;")
            self.conn.execute("PRAGMA synchronous = NORMAL;")
        except Exception:
            pass

    # --------------------------------------------------
    # PAYLOAD STORE (for huge text, no base64)
    # --------------------------------------------------
    def put_payload(self, text: str, mime: str = "text/plain", payload_id: Optional[str] = None) -> str:
        """
        Stores text in payload_store and returns payload_id.
        If payload_id is None, generates a deterministic hash id.
        """
        cur = self.conn.cursor()
        if payload_id is None:
            h = hashlib.sha256(text.encode("utf-8", errors="replace")).hexdigest()[:16]
            payload_id = f"payload:{h}"

        cur.execute(
            """
            INSERT OR REPLACE INTO payload_store(payload_id, mime, text_content)
            VALUES (?, ?, ?)
            """,
            (payload_id, mime, text),
        )
        self.conn.commit()
        return payload_id

    def get_payload(self, payload_id: str) -> str:
        cur = self.conn.cursor()
        row = cur.execute(
            "SELECT text_content FROM payload_store WHERE payload_id=?",
            (payload_id,),
        ).fetchone()
        if not row:
            raise KeyError(f"No payload_store row for payload_id={payload_id}")
        return row["text_content"]

    # --------------------------------------------------
    # INSERT STEP
    # --------------------------------------------------
    def insert_step(
        self,
        script_name: str,
        params: Dict[str, Any],
        run_id: Optional[str] = None,
        step_index: Optional[int] = None,
    ) -> int:
        """
        Inserts:
          1) one row into pipeline
          2) one row into the per-script table named `script_name` (keyed by step_id)

        Returns:
          step_id
        """
        cur = self.conn.cursor()

        # 1) pipeline row
        cur.execute(
            "INSERT INTO pipeline(script_name, run_id, step_index) VALUES (?,?,?)",
            (script_name, run_id, step_index),
        )
        step_id = cur.lastrowid

        # 2) per-script payload row
        cols = [r["name"] for r in cur.execute(f"PRAGMA table_info({script_name})").fetchall()]
        if "step_id" not in cols:
            raise RuntimeError(f"Table {script_name} must contain column step_id")

        payload_cols = [k for k in params.keys() if k in cols and k != "step_id"]

        sql_cols = ["step_id"] + payload_cols
        sql_vals = [step_id] + [params[c] for c in payload_cols]

        placeholders = ",".join("?" for _ in sql_cols)
        col_list = ",".join(sql_cols)

        cur.execute(
            f"INSERT INTO {script_name}({col_list}) VALUES ({placeholders})",
            sql_vals,
        )

        self.conn.commit()
        return step_id

    # --------------------------------------------------
    # GET ONE STEP (with payload)
    # --------------------------------------------------
    def get_step(self, step_id: int) -> Dict[str, Any]:
        cur = self.conn.cursor()

        row = cur.execute(
            "SELECT step_id, script_name, created_at, run_id, step_index FROM pipeline WHERE step_id=?",
            (step_id,),
        ).fetchone()

        if row is None:
            raise KeyError(f"No pipeline step {step_id}")

        script_name = row["script_name"]

        payload = cur.execute(
            f"SELECT * FROM {script_name} WHERE step_id=?",
            (step_id,),
        ).fetchone()

        payload_dict = dict(payload) if payload else {}

        # Optional convenience: auto-resolve content_ref into content_text if desired
        if "content_ref" in payload_dict and payload_dict.get("content_ref"):
            ref = payload_dict["content_ref"]
            try:
                payload_dict["_resolved_content"] = self.get_payload(ref)
            except Exception:
                payload_dict["_resolved_content"] = None

        return {
            "step_id": step_id,
            "script_name": script_name,
            "created_at": row["created_at"],
            "run_id": row["run_id"],
            "step_index": row["step_index"],
            "payload": payload_dict,
        }

    # --------------------------------------------------
    # GET LAST N (newest first by default)
    # --------------------------------------------------
    def get_last_n(self, n: int, chronological: bool = False) -> List[Dict[str, Any]]:
        cur = self.conn.cursor()

        rows = cur.execute(
            "SELECT step_id FROM pipeline ORDER BY step_id DESC LIMIT ?",
            (n,),
        ).fetchall()

        steps = [self.get_step(r["step_id"]) for r in rows]
        if chronological:
            steps.reverse()
        return steps

    # --------------------------------------------------
    # DELETE STEP (cascade will remove per-script row)
    # --------------------------------------------------
    def delete_step(self, step_id: int) -> None:
        cur = self.conn.cursor()
        cur.execute("DELETE FROM pipeline WHERE step_id=?", (step_id,))
        self.conn.commit()

    # --------------------------------------------------
    # CLOSE
    # --------------------------------------------------
    def close(self) -> None:
        self.conn.close()


# ----------------------------------------------------------------------
# Example: insert the "GuessGame" pipeline from your spec into stechen.db
# (stores Java source in payload_store and references it via CreateTextFile.content_ref)
# ----------------------------------------------------------------------
def insert_guessgame_example(db: DBOperator, run_id: str = "spec:GuessGame") -> None:
    idx = 1

    def step(script: str, params: Dict[str, Any]):
        nonlocal idx
        db.insert_step(script, params, run_id=run_id, step_index=idx)
        idx += 1

    # Mandatory libraries
    step("DynamicJarLoader", {"library": "net.bytebuddy:byte-buddy:1.15.3"})
    step("DynamicJarLoader", {"library": "org.ow2.asm:asm:9.8"})
    step("DynamicJarLoader", {"library": "org.ow2.asm:asm-tree:9.8"})
    step("DynamicJarLoader", {"library": "org.ow2.asm:asm-commons:9.8"})
    step("DynamicJarLoader", {"library": "org.ow2.asm:asm-util:9.8"})
    step("DynamicJarLoader", {"library": "org.json:json:20240303"})

    # Base class
    step("DynamicClassCreator", {"class_name": "GuessGame"})

    # Field file (store content in payload_store)
    field_src = "public int target;"
    field_ref = db.put_payload(field_src, payload_id="CreateTextFile:FieldGuessGameTarget")
    step("CreateTextFile", {"file_name": "FieldGuessGameTarget", "content_ref": field_ref})
    step("DynamicDelegateCreator", {"parent": "GuessGame", "field_file": "FieldGuessGameTarget.txt", "output_dir": "."})
    step("ClassFieldCloner", {"class_name_to_modify": "GuessGame", "delegate_class": "DynamicDelegate", "field_name": "target"})

    # initTarget()
    init_src = "public void initTarget(){java.util.Random r=new java.util.Random();target=1+r.nextInt(100);}"
    init_ref = db.put_payload(init_src, payload_id="CreateTextFile:MethodInitTarget")
    step("CreateTextFile", {"file_name": "MethodInitTarget", "content_ref": init_ref})
    step("DynamicDelegateCreator", {"parent": "GuessGame", "method_file": "MethodInitTarget.txt", "output_dir": "."})
    step("ClassMethodCloner", {"class_name_to_modify": "GuessGame", "delegate_class": "DynamicDelegate", "method_name": "initTarget"})

    # play()
    play_src = (
        "public void play(){java.util.Scanner sc=new java.util.Scanner(System.in);"
        "System.out.println(\"Guess a number 1..100\");"
        "while(true){System.out.print(\"> \");"
        "if(!sc.hasNextInt()){sc.nextLine();System.out.println(\"Type an integer.\");continue;}"
        "int g=sc.nextInt();"
        "if(g<target){System.out.println(\"Higher\");}"
        "else if(g>target){System.out.println(\"Lower\");}"
        "else{System.out.println(\"Correct!\");break;}}}"
    )
    play_ref = db.put_payload(play_src, payload_id="CreateTextFile:MethodPlay")
    step("CreateTextFile", {"file_name": "MethodPlay", "content_ref": play_ref})
    step("DynamicDelegateCreator", {"parent": "GuessGame", "method_file": "MethodPlay.txt", "output_dir": "."})
    step("ClassMethodCloner", {"class_name_to_modify": "GuessGame", "delegate_class": "DynamicDelegate", "method_name": "play"})

    # main()
    main_src = "public static void main(String[] args){GuessGame game=new GuessGame();game.initTarget();game.play();}"
    main_ref = db.put_payload(main_src, payload_id="CreateTextFile:MethodMain")
    step("CreateTextFile", {"file_name": "MethodMain", "content_ref": main_ref})
    step("DynamicDelegateCreator", {"parent": "GuessGame", "method_file": "MethodMain.txt", "output_dir": "."})
    step("ClassMethodCloner", {"class_name_to_modify": "GuessGame", "delegate_class": "DynamicDelegate", "method_name": "main"})

    # Run
    step("RunClass", {"class_name": "GuessGame"})


def main():
    db_path = sys.argv[1] if len(sys.argv) > 1 else "stechen.db"
    db = DBOperator(db_path)

    # Insert the example pipeline
    insert_guessgame_example(db)

    # Print last 5 steps (chronological)
    last5 = db.get_last_n(5, chronological=True)
    print("[OK] Inserted GuessGame example. Last 5 steps:")
    for s in last5:
        print(f"- step_id={s['step_id']} script={s['script_name']} step_index={s['step_index']} run_id={s['run_id']}")

    db.close()


if __name__ == "__main__":
    main()
