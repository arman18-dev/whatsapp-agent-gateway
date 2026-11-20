# AI usage

Every AI tool used in this project, and where it was used.

| Tool | Model | Where | What it did |
|---|---|---|---|
| Claude Code (desktop app) | Claude Opus 5.5 for the design review; Claude Haiku 5.5 for the coding session | Whole repo | Design review of `PLAN.md` (section 15 records the decisions); wrote the Spring Boot code, Flyway schema, Docker files, tests and load test, under my direction |
| Claude API (runtime) | `claude-haiku-5-5` | `worker` service, only when `LLM_MODE=real` | The booking agent in production code: `ClaudeLlmClient`. The default run uses `LLM_MODE=mock` (a scripted model, zero cost) |
| Claude API docs skill (`claude-api`) | — | Checked the Anthropic Java SDK (3.3.0) and Haiku pricing before writing `ClaudeLlmClient` | Used to confirm method names and prices instead of guessing them |

## What I checked by hand

- All code was compiled and run. The booking flow, webhook, and load test are verified against real Postgres (Testcontainers) and the Docker stack.
- The real Claude path (`ClaudeLlmClient`) compiles against the SDK but had **not been run against the live API** when this file was written. See the README for the status and the real-run spend.
- Commit attribution follows the configured `Co-Authored-By` line.
