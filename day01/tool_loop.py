"""Day 1: an AI agent with no framework, just the raw tool-use loop.

Run from the project folder:
    uv run python day01/tool_loop.py
    uv run python day01/tool_loop.py "Which services does the payments team own?"

What happens (this loop is the heart of every agent framework):
    1. We send Claude the question + a description of our tools.
    2. Claude either answers, or replies "please run tool X with inputs Y" (stop_reason == "tool_use").
    3. OUR code runs the tool (Claude never runs anything itself) and sends back the result.
    4. Repeat until Claude answers in plain text.
"""
import json
import os
import sys
from pathlib import Path

import anthropic
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent.parent
SERVICES_DIR = ROOT / "data" / "services"
load_dotenv(ROOT / ".env")
MODEL = os.getenv("CLAUDE_MODEL", "claude-sonnet-5-5")
client = anthropic.Anthropic()  # reads ANTHROPIC_API_KEY from the environment


# ---------------------------------------------------------------------------
# 1. THE TOOLS: ordinary Python functions. Nothing AI-specific here.
# ---------------------------------------------------------------------------
def list_services(team: str | None = None) -> list[dict]:
    """Return every service (optionally only one team's) with its team, runtime and tier."""
    services = []
    for f in sorted(SERVICES_DIR.glob("*.json")):
        s = json.loads(f.read_text())
        if team is None or s["team"] == team:
            services.append({"name": s["name"], "team": s["team"], "runtime": s["runtime"], "tier": s["tier"]})
    return services


def get_dependencies(service: str) -> dict:
    """Return what one service depends on: services it calls, libraries, and events."""
    path = SERVICES_DIR / f"{service}.json"
    if not path.exists():
        raise ValueError(f"Unknown service '{service}'. Call list_services to see valid names.")
    s = json.loads(path.read_text())
    return {
        "service": s["name"],
        "calls": s["calls"],
        "libraries": s["libraries"],      # entries may include "known_advisory"
        "publishes_events": s["publishes"],
        "consumes_events": s["consumes"],
    }


# ---------------------------------------------------------------------------
# 2. THE TOOL DESCRIPTIONS: what Claude sees. Claude decides which tool to call
#    purely from these names, descriptions and input schemas (JSON Schema),
#    so write them like documentation for a new colleague.
# ---------------------------------------------------------------------------
TOOLS = [
    {
        "name": "list_services",
        "description": (
            "List the services in the company's service catalog, with owning team, runtime "
            "(language and version) and criticality tier (1 = most critical). "
            "Use it to discover valid service names or to find services by team."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "team": {
                    "type": "string",
                    "description": "Optional team filter, e.g. 'payments', 'cards', 'platform'.",
                }
            },
            "required": [],
        },
    },
    {
        "name": "get_dependencies",
        "description": (
            "Get one service's dependencies: the services it calls synchronously, its third-party "
            "libraries with versions (a 'known_advisory' field means a known vulnerability), and "
            "the events it publishes and consumes with schema versions."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "service": {"type": "string", "description": "Exact service name, e.g. 'payment-orchestrator'."}
            },
            "required": ["service"],
        },
    },
]

# Maps the name Claude asks for -> the Python function we run.
HANDLERS = {"list_services": list_services, "get_dependencies": get_dependencies}

SYSTEM = (
    "You are a platform engineering assistant. Answer questions about the company's services "
    "using the tools. Never guess service names or versions: look them up. Be concise."
)


def run_tool(name: str, tool_input: dict) -> tuple[str, bool]:
    """Run the tool Claude asked for. Returns (result_as_text, is_error)."""
    try:
        result = HANDLERS[name](**tool_input)
        return json.dumps(result, indent=2), False
    except Exception as e:  # errors go BACK to Claude so it can recover, e.g. fix a typo
        return f"Error: {e}", True


# ---------------------------------------------------------------------------
# 3. THE AGENT LOOP
# ---------------------------------------------------------------------------
def ask(question: str, max_turns: int = 10) -> str:
    messages = [{"role": "user", "content": question}]   # the whole conversation, resent every turn
    total_in = total_out = 0

    for turn in range(1, max_turns + 1):
        response = client.messages.create(
            model=MODEL, max_tokens=1024, system=SYSTEM, tools=TOOLS, messages=messages
        )
        total_in += response.usage.input_tokens
        total_out += response.usage.output_tokens
        print(f"\n--- Turn {turn}: stop_reason={response.stop_reason} "
              f"(tokens in={response.usage.input_tokens}, out={response.usage.output_tokens})")

        # Claude's reply (text and/or tool requests) must go into the history as-is.
        messages.append({"role": "assistant", "content": response.content})

        if response.stop_reason != "tool_use":          # no more tools wanted: this is the answer
            answer = "".join(b.text for b in response.content if b.type == "text")
            print(f"\nTotal tokens: {total_in} in, {total_out} out")
            return answer

        # Claude may ask for SEVERAL tools in one turn (parallel tool use): run them all.
        tool_results = []
        for block in response.content:
            if block.type == "text" and block.text.strip():
                print(f"Claude says: {block.text.strip()}")
            elif block.type == "tool_use":
                print(f"Claude calls: {block.name}({json.dumps(block.input)})")
                result, is_error = run_tool(block.name, block.input)
                preview = result if len(result) < 300 else result[:300] + " ..."
                print(f"  -> {'ERROR ' if is_error else ''}{preview}")
                tool_results.append({
                    "type": "tool_result",
                    "tool_use_id": block.id,      # ties this result to the request it answers
                    "content": result,
                    "is_error": is_error,
                })

        # Tool results go back as a USER message, then we loop.
        messages.append({"role": "user", "content": tool_results})

    return "Stopped: reached max_turns without a final answer."


if __name__ == "__main__":
    q = " ".join(sys.argv[1:]) or (
        "What does payment-orchestrator depend on, and which of those dependencies "
        "(including the services it calls) have libraries with known security advisories?"
    )
    print(f"Question: {q}")
    print(f"\n=== Answer ===\n{ask(q)}")
