"""Check that everything the 2-week plan needs is installed and working.

Run from the project folder:
    uv run python scripts/check_setup.py          # local checks only
    uv run python scripts/check_setup.py --api    # also makes one tiny Claude API call (about $0.001)
"""
import importlib
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
results: list[tuple[str, bool, str, bool]] = []


def check(name: str, ok: bool, detail: str = "", later: bool = False) -> None:
    """later=True: not needed until Week 2, so a failure is reported but doesn't block Day 1."""
    results.append((name, ok, detail, later))


def run(cmd: list[str]) -> tuple[bool, str]:
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=20)
        text = (out.stdout or out.stderr).strip().splitlines()
        return out.returncode == 0, text[0] if text else ""
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        return False, str(e)


# 1. Python
v = sys.version_info
check("Python 3.11+", v >= (3, 11), platform.python_version())

# 2. Command-line tools
for tool, args, needed_for in [
    ("uv", ["--version"], "environments (Setup)"),
    ("git", ["--version"], "GitHub repo (Setup)"),
    ("node", ["--version"], "MCP Inspector (Day 5)"),
    ("npx", ["--version"], "MCP Inspector (Day 5)"),
    ("docker", ["--version"], "Langfuse (Day 8)"),
]:
    if shutil.which(tool):
        ok, out = run([tool, *args])
        check(f"{tool}", ok, f"{out}  [{needed_for}]", later=tool == "docker")
    else:
        check(f"{tool}", False, f"not found  [{needed_for}]", later=tool == "docker")

# Docker daemon running (Docker Desktop open)
if shutil.which("docker"):
    ok, out = run(["docker", "info", "--format", "{{.ServerVersion}}"])
    check("Docker Desktop running", ok, out if ok else "open Docker Desktop  [Day 8]", later=True)

# 3. Claude apps (MCP clients)
claude_desktop = Path("/Applications/Claude.app").exists()
claude_code = shutil.which("claude") is not None
check("Claude Desktop or Claude Code", claude_desktop or claude_code,
      ", ".join(n for n, f in [("Claude Desktop", claude_desktop), ("Claude Code CLI", claude_code)] if f) or "install one  [Day 4]")

# 4. Python packages
for pkg in ["anthropic", "mcp", "langgraph", "langchain_anthropic", "langchain_mcp_adapters", "langfuse", "dotenv"]:
    try:
        mod = importlib.import_module(pkg)
        check(f"package {pkg}", True, getattr(mod, "__version__", "installed"))
    except Exception as e:  # noqa: BLE001
        check(f"package {pkg}", False, f"run `uv sync` ({type(e).__name__})")

# 5. API key
try:
    from dotenv import load_dotenv
    load_dotenv(ROOT / ".env")
except Exception:  # noqa: BLE001
    pass
key = os.getenv("ANTHROPIC_API_KEY", "")
check("ANTHROPIC_API_KEY in .env", key.startswith("sk-"), "found" if key else "copy .env.example to .env and paste your key")

# 6. Sample data
services = list((ROOT / "data" / "services").glob("*.json"))
check("Sample data", len(services) >= 30, f"{len(services)} service manifests" if services else "run `uv run python scripts/generate_sample_data.py`")

# 7. Optional live API call
if "--api" in sys.argv:
    try:
        import anthropic
        client = anthropic.Anthropic()
        model = os.getenv("CLAUDE_MODEL", "claude-sonnet-5-5")
        msg = client.messages.create(model=model, max_tokens=20,
                                     messages=[{"role": "user", "content": "Reply with exactly: setup ok"}])
        text = msg.content[0].text.strip()
        check(f"Claude API call ({model})", "ok" in text.lower(),
              f"'{text}'  ({msg.usage.input_tokens} in / {msg.usage.output_tokens} out tokens)")
    except Exception as e:  # noqa: BLE001
        check("Claude API call", False, f"{type(e).__name__}: {e}")

# Report
width = max(len(n) for n, *_ in results)
print()
for name, ok, detail, later in results:
    status = "PASS" if ok else ("LATER" if later else "FAIL")
    print(f"  {status:<5}  {name.ljust(width)}  {detail}")
failed = [n for n, ok, _, later in results if not ok and not later]
deferred = [n for n, ok, _, later in results if not ok and later]
print()
if failed:
    print(f"{len(failed)} item(s) to fix before Day 1: {', '.join(failed)}")
else:
    print("All set for Day 1." + (f" Before Day 8, also fix: {', '.join(deferred)}." if deferred else ""))
sys.exit(1 if failed else 0)
