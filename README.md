# Platform Dependency Agent

An AI agent that answers the questions an engineering platform team asks every week:

- "Which services break if we upgrade `jackson-databind`?"
- "Who still consumes v1 of the `payment.initiated` event?"
- "Draft an upgrade plan for every service still on Java 11."

It reaches the systems it needs through custom **MCP servers** (a service catalog and a schema registry), is orchestrated with **LangGraph**, and every run is **traced and evaluated** (Langfuse and an eval suite in CI).

> Built as a two-week learning project. Progress is logged day by day below.

## Project layout

```
data/                      synthetic "company": 30 services, 7 versioned event schemas
  services/<name>.json     one manifest per service (team, runtime, calls, events, libraries)
  schemas/<event>/vN.json  JSON Schema per event version
  catalog.json             index of all services
scripts/
  generate_sample_data.py  regenerates data/ (deterministic)
  check_setup.py           verifies your machine is ready
```

## Setup (macOS)

1. **Homebrew** (skip if `brew --version` works):
   `/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"`
2. **Tools:** `brew install uv git node gh`
3. **Docker Desktop** (needed from Day 8): `brew install --cask docker`, then open Docker once from Applications.
4. **Claude Desktop** (MCP client, Day 4): download from https://claude.ai/download
5. **Python environment:** in this folder, run `uv sync`. It installs Python 3.11+ if needed, plus all libraries, into `.venv`.
6. **API key:** `cp .env.example .env`, then paste your key from https://console.anthropic.com. Set a spend limit of about $20.
7. **Check everything:** `uv run python scripts/check_setup.py --api`
8. **Publish to GitHub:** `gh auth login`, then
   `git init && git add . && git commit -m "Day 0: project setup" && gh repo create platform-dependency-agent --public --source . --push`

## Progress log

| Day | What I built | What I learned |
| --- | --- | --- |
| 0 | Project setup, sample data | |
