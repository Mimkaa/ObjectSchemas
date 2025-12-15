import base64
from pathlib import Path

IN_FILE = "pipeline.txt"
OUT_FILE = "pipeline_b64.txt"

def b64(s: str) -> str:
    return base64.b64encode(s.encode("utf-8")).decode("ascii")

def encode_command(cmd: str) -> str:
    """
    Converts:
      Script --a X --b Y
    to:
      Script --aB64 <b64(X)> --bB64 <b64(Y)>

    Also supports multi-token values:
      --method public static void ... }   (until next --flag)
    """
    raw = cmd.strip().split()
    if not raw:
        return cmd

    script = raw[0]
    rest = raw[1:]

    out = [script]
    i = 0
    while i < len(rest):
        tok = rest[i]

        # keep --flag=value form, but encode the value part
        if tok.startswith("--") and "=" in tok:
            flag, val = tok.split("=", 1)
            out.append(flag + "B64")
            out.append(b64(val))
            i += 1
            continue

        if tok.startswith("--"):
            flag = tok
            i += 1
            value_tokens = []
            while i < len(rest) and not rest[i].startswith("--"):
                value_tokens.append(rest[i])
                i += 1

            # boolean flag (no value)
            if not value_tokens:
                out.append(flag)
                continue

            value = " ".join(value_tokens)
            out.append(flag + "B64")
            out.append(b64(value))
        else:
            # stray token (rare) -> encode as a generic value token
            out.append("ARG_B64")
            out.append(b64(tok))
            i += 1

    return " ".join(out)

def main():
    text = Path(IN_FILE).read_text(encoding="utf-8")
    lines = text.splitlines()

    out_lines = []
    for line in lines:
        if not line.strip():
            out_lines.append(line)
            continue
        if line.lstrip().startswith("#"):
            out_lines.append(line)
            continue

        out_lines.append(encode_command(line))

    Path(OUT_FILE).write_text("\n".join(out_lines), encoding="utf-8")
    print(f"✅ Wrote {OUT_FILE}")

if __name__ == "__main__":
    main()
