"""Verify tracked documentation and publication boundaries; no client tests."""
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit


root = Path(__file__).resolve().parents[1]
tracked = subprocess.check_output(
    ["git", "ls-files", "-z"], cwd=root
).decode("utf-8").split("\0")
errors = []
for name in filter(None, tracked):
    path = root / name
    forbidden = name.startswith((".research/", ".local/")) or path.suffix.lower() in {
        ".keystore", ".p12", ".pfx", ".pem", ".wav", ".opus", ".apk", ".rpk", ".exe"
    } or path.name == ".env"
    if forbidden and name != "updates/trust-root.pem":
        errors.append(f"Unexpected public source artifact: {name}")
    if name == "updates/trust-root.pem":
        public_key = path.read_text(encoding="utf-8").strip()
        if not public_key.startswith("-----BEGIN PUBLIC KEY-----") or not public_key.endswith("-----END PUBLIC KEY-----") or "PRIVATE KEY" in public_key:
            errors.append("Update trust root must contain only a public key")
    if path.suffix.lower() != ".md" or not path.is_file():
        continue
    content = path.read_text(encoding="utf-8")
    if len(re.findall(r"^```", content, re.MULTILINE)) % 2:
        errors.append(f"Unclosed code fence: {name}")
    for match in re.finditer(r"\]\(([^\s)]+)\)", content):
        target = match.group(1).strip("<>")
        parsed = urlsplit(target)
        if parsed.scheme or parsed.netloc or not parsed.path:
            continue
        resolved = (path.parent / unquote(parsed.path)).resolve()
        if not resolved.is_relative_to(root) or not resolved.exists():
            errors.append(f"Invalid local link in {name}: {target}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("Repository documentation checks passed (not client validation).")
