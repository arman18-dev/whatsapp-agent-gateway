# Cost at 1 million messages a month

Two estimates: the planning estimate from PLAN.md (no caching), and a measured one from our real-model run (prompt caching on). Figures are USD per month.

## Model cost (Claude Haiku 5.5)

Prices: $0.10 per million input tokens, $0.50 per million output tokens, cache reads $0.01, cache writes $0.125. These apply to prompts up to 100K tokens.

| Basis | Input | Output | Total |
|---|---|---|---|
| Planning estimate: 5,000 input and 300 output tokens per message, no caching | $500 | $150 | **$650** |
| Measured in our real run: 750 messages, 1,271 model calls, $0.178 | | | **about $240** |

The measured figure is the one to use. In our run each message averaged about 1,350 uncached input tokens, about 2,000 input tokens read from the prompt cache, and about 160 output tokens. Our prompts are much shorter than the planning estimate assumed. Real traffic will vary with conversation length and how many tool rounds each message needs. The model call is capped at 6 rounds per message.

## Infrastructure (estimate, not measured)

| Item | Per month |
|---|---|
| Gateway and worker containers (2 small services, or one combined) | $60 to $100 |
| Managed Postgres | $50 to $80 |
| Observability (Prometheus-compatible, self-hosted) | $0 |
| **Total** | **$110 to $180** |

PLAN.md estimated $150 to $200. The lower figure reflects that the gateway does no model or lab calls on its request path.

## Not included

- **WhatsApp Cloud API conversation fees** charged by Meta. Check the current India rate card before quoting a figure.
- **Lab costs** (the lab's own API charges, if any).
- **Staff time** for handoffs.

## Throughput

One million messages a month averages about 0.4 messages per second. In our load test one message takes about 4 to 5 seconds with 2 to 3 model calls and lab round trips. With 16 worker threads that is about 3 to 4 messages per second, about 8 times the average. Bursts queue in Postgres and drain as capacity allows.

## Total

| | Per month |
|---|---|
| Measured model cost | about $240 |
| Infrastructure | $110 to $180 |
| **Total (excluding WhatsApp fees)** | **about $350 to $420** |

Using the planning estimate instead of the measured one gives about $760 to $830.

## What would reduce it

- A cheaper path for simple messages (greetings, out-of-scope requests) that doesn't call the model.
- Fewer tool rounds per message, by letting `check_slots` return a day's full choice in one call.
