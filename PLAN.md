# WhatsApp Agent Gateway: PLAN

Clock: started 3:52 PM IST Thu 19 Nov 2026. Submission due 3:52 PM IST Fri 20 Nov 2026.

## 1. Problem

A diagnostics lab takes booking requests on WhatsApp. Cloud API webhooks arrive in bursts and sometimes twice. The lab's booking API is slow and flaky: about 20% of calls fail, and some bookings succeed but time out on the response. Requirements:

- Nobody is double-booked.
- The webhook acknowledges in under 200 ms.
- Every conversation's LLM cost is visible, and each phone number is rate-limited.

## 2. Users

- **Patient (WhatsApp):** asks for a test, picks a slot, gets a confirmation.
- **Lab front-desk staff:** receive a handoff when the agent cannot finish a booking.
- **Lab operator:** watches `/metrics`, failure rates and cost.

## 3. Scope

**In:** signed webhook with dedupe, Postgres-backed queue, worker with a Claude tool loop (`claude-haiku-5-5`), mock lab API (20% failures, slow, drops some responses after committing), retries with backoff, booking state machine, per-phone rate limit, token and cost tracking, `docker compose up`, a 1,000-message load test, tests.

**Out:** real lab integration, staff UI, patient identity beyond the WhatsApp number, real patient data. Outbound replies go through a sender interface; the default implementation logs the reply, and the Cloud API send is wired only if test credentials are available.

## 4. Architecture

```
WhatsApp Cloud API
   | POST /webhook  (bursts, duplicates)
   v
[gateway]  Spring Boot, profile=gateway
   - verify X-Hub-Signature-256 (HMAC-SHA256 over raw body)
   - parse entry[].changes[].value.messages[]
   - rate-limit check (per phone, one SQL upsert)
   - INSERT inbound_messages ... ON CONFLICT (wa_message_id) DO NOTHING
   - 200 OK                       (no LLM, no lab call on this path)
   |
   v  Postgres: inbound_messages is the queue (status + lease)
[worker]   same jar, profile=worker, N concurrent threads
   - claim a message with FOR UPDATE SKIP LOCKED
   - take a per-conversation advisory lock (messages for one phone run one at a time)
   - load conversation history, call Claude with tools
   - each tool call goes through LabClient (timeout, retry, Idempotency-Key)
   - persist messages, tool calls, usage and cost in one transaction
   - send reply via OutboundSender (logging stub by default)
   |
   v
[mock-lab]  fake booking API: 20% failures, 300-1500 ms latency,
            drops ~10% of successful booking responses after committing,
            honours Idempotency-Key
[gateway]   GET /metrics (Prometheus text), GET /conversations/{phone}/usage
```

**Stack:** Java 17, Spring Boot 3, Maven, PostgreSQL 16, Flyway migrations, Spring JdbcTemplate (explicit SQL), Anthropic Java SDK, Micrometer Prometheus registry, Testcontainers for integration tests, Docker Compose.

## 5. No double booking (the core design)

1. **One active booking per slot.** Partial unique index: `UNIQUE (slot_id) WHERE status IN ('HELD','CONFIRMED','UNKNOWN')`. The database enforces this even if the worker has a bug.
2. **Write intent before calling the lab.** Insert a `HELD` row first. If the unique index rejects it, the tool returns `SLOT_TAKEN` without calling the lab.
3. **Idempotent lab call.** `Idempotency-Key = bookings.id`, the same for every retry of the same user intent.
   - 2xx: `CONFIRMED`, store the lab booking reference.
   - 409 slot taken: `RELEASED`, tell the patient and offer other slots.
   - timeout, 5xx or network error: stay `UNKNOWN`. Do not create a new booking id. Retry with the same key, or look it up with `GET /bookings?idempotency_key=`.
4. **Reconciler.** A background task resolves `UNKNOWN` rows older than 10 s by lookup. If the lab has no record after the retry budget, the row becomes `RELEASED` and the patient is offered another slot or a handoff.
5. **Serialise per conversation.** Advisory lock on the phone number, so two quick messages from one patient cannot both book.
6. **Dedupe at three layers:** `wa_message_id` primary key (webhook), `tool_use_id` primary key (a replayed LLM tool call returns the stored result), booking idempotency key (lab).

**Tests that prove it:**
- 100 threads book the same slot, exactly one `CONFIRMED`.
- Mock lab commits the booking but drops the response, exactly one booking after retries.
- The same WhatsApp payload posted twice creates one message and one LLM run.

## 6. Failure handling

- **Webhook:** bad or missing signature returns 401 and stores nothing. A duplicate returns 200 with `"status":"duplicate"`. Malformed JSON returns 400.
- **LabClient:** timeout 2 s per call. Up to 4 attempts on timeout or 5xx, exponential backoff (base 200 ms, factor 2, cap 3 s, full jitter). `check_slots` (GET) always retries. `book_test` (POST) retries only with the same idempotency key.
- **Claude API:** SDK retries (`maxRetries = 3`) for 429 and 5xx. If the whole job fails, `attempts` increments with backoff. After 5 attempts the message becomes `DEAD`, and the patient gets a handoff.
- **Worker crash:** claimed rows carry `locked_until`. Expired leases are reclaimed. Every step is idempotent, so a rerun is safe.

## 7. Agent and tool design

Model: `claude-haiku-5-5` (chosen 19 Nov for cost). Prompting is kept short. The system prompt and tool definitions are fixed, so they are cached.

| Tool | Input | Output | Notes |
|---|---|---|---|
| `check_slots` | `test_code`, `date_from`, `date_to` | up to 5 `{slot_id, starts_at, lab_site}` | read-only |
| `book_test` | `slot_id`, `test_code`, `patient_name` | `{booking_ref}` or `{error: SLOT_TAKEN \| LAB_UNAVAILABLE}` | write, idempotent |
| `handoff_to_human` | `reason` | `{ticket_id}` | ends automated handling |

**Guardrails in code, not in the prompt:**
- `book_test` is rejected unless `slot_id` appeared in a `check_slots` result for this conversation. This stops invented IDs.
- Tool inputs are validated against the schema. Tools use `strict: true`.
- At most 6 tool iterations per inbound message.
- Parallel `tool_use` blocks run, and all results go back in one user message.

## 8. Data model

```sql
conversations (
  phone_e164 text primary key,
  state text not null,                    -- ACTIVE | HANDED_OFF
  created_at timestamptz not null,
  updated_at timestamptz not null
);

inbound_messages (
  wa_message_id text primary key,         -- dedupe key
  phone_e164 text not null references conversations,
  body text,
  status text not null,                   -- RECEIVED | PROCESSING | DONE | FAILED | DEAD | RATE_LIMITED
  attempts int not null default 0,
  locked_until timestamptz,
  received_at timestamptz not null,
  processed_at timestamptz
);

messages (                                -- conversation history
  id bigserial primary key,
  phone_e164 text not null,
  role text not null,                     -- user | assistant
  content jsonb not null,                 -- full content blocks, incl. tool_use / tool_result
  created_at timestamptz not null
);

llm_calls (
  id bigserial primary key,
  phone_e164 text not null,
  wa_message_id text not null,
  model text not null,
  input_tokens int not null,
  output_tokens int not null,
  cache_read_tokens int not null default 0,
  cache_write_tokens int not null default 0,
  cost_usd numeric(12,6) not null,
  created_at timestamptz not null
);

tool_calls (
  tool_use_id text primary key,           -- from Claude; replays return the stored result
  phone_e164 text not null,
  name text not null,
  input jsonb not null,
  result jsonb,
  created_at timestamptz not null
);

slots (
  slot_id text primary key,               -- lab's id
  test_code text not null,
  starts_at timestamptz not null,
  lab_site text not null,
  seen_at timestamptz not null
);

bookings (
  id uuid primary key,                    -- also the Idempotency-Key
  phone_e164 text not null,
  slot_id text not null references slots,
  test_code text not null,
  status text not null,                   -- HELD | CONFIRMED | UNKNOWN | RELEASED
  lab_booking_ref text,
  created_at timestamptz not null,
  updated_at timestamptz not null
);
create unique index one_active_booking_per_slot
  on bookings (slot_id)
  where status in ('HELD','CONFIRMED','UNKNOWN');

rate_limits (                             -- fixed window: 10 messages per 60 s per phone
  phone_e164 text primary key,
  window_start timestamptz not null,
  count int not null
);

handoffs (
  id bigserial primary key,
  phone_e164 text not null,
  reason text not null,
  created_at timestamptz not null
);
```

## 9. API contract

| Method | Path | Purpose | Responses |
|---|---|---|---|
| GET | `/webhook` | Meta verification handshake | 200 echoes `hub.challenge` when `hub.mode=subscribe` and the verify token matches; else 403 |
| POST | `/webhook` | inbound WhatsApp Cloud API payload | 200 `{"status":"accepted"\|"duplicate"\|"rate_limited"}`; 401 bad signature; 400 malformed |
| GET | `/healthz` | liveness | 200 |
| GET | `/metrics` | Prometheus text | see section 10 |
| GET | `/conversations/{phone}/usage` | per-conversation tokens, cost, call count | 200 JSON; synthetic numbers only |

Only `entry[].changes[].value.messages[]` is read (`from`, `id`, `type`, `text.body`). `statuses` events are acknowledged and ignored.

**Mock lab API** (separate service, `mock-lab`):
- `GET /slots?test_code=&from=&to=`
- `POST /bookings`, header `Idempotency-Key`: 201 `{booking_ref}`, 409 `SLOT_TAKEN`, or the injected failures
- `GET /bookings?idempotency_key=`: 200 or 404

**Configuration** (environment variables, no secrets in the repo): `WHATSAPP_APP_SECRET`, `WHATSAPP_VERIFY_TOKEN`, `ANTHROPIC_API_KEY`, `LAB_BASE_URL`, `LLM_MODE=real|mock`, `DATABASE_URL`.

## 10. Cost and observability

- Each LLM response gives `usage` (input, output, cache read and write tokens). Cost = tokens × the price table in `application.yml`. Every call is stored in `llm_calls`.
- `/metrics` exposes counters: tokens and USD by model, inbound messages by status, queue depth, job outcomes, lab call latency and failures by outcome, rate-limited count.
- Per-conversation cost is **not** a Prometheus label, to avoid unbounded cardinality. It is served from `/conversations/{phone}/usage`.

## 11. Cost estimate at 1 million messages per month

**Assumptions:** one LLM call per inbound message (tool iterations ignored in this estimate); average 5,000 input tokens (system prompt, tools, history); 300 output tokens; no caching. Prices from the Anthropic pricing table (per MTok, input / output):

| Model | Input cost | Output cost | Total per month |
|---|---|---|---|
| Haiku 5.5 ($0.10 / $0.50) | 5,000 × 1M × $0.10/MTok = $500 | 300 × 1M × $0.50/MTok = $150 | **≈ $650** |
| Sonnet 5.5 ($2 / $10), for comparison | $10,000 | $3,000 | ≈ $13,000 |

- **Prompt caching** on the fixed system prompt and tools should cut the input line. The size of the cut is to be measured, not assumed.
- **Infrastructure (estimate):** two small gateway and worker containers ($60–100), managed Postgres ($50–80), observability self-hosted or free ($0). Total ≈ $150–200 per month.
- **Not included:** WhatsApp Cloud API conversation charges from Meta. Check the current India rate card before quoting.
- **Throughput:** 1M messages a month averages 0.4 messages per second. At about 1.5 s per LLM call with 16 worker threads, capacity is about 10 messages per second, roughly 25× the average. Bursts queue in Postgres.

## 12. Load test

A Java main class in the same Maven project (`loadtest` package) sends 1,000 signed payloads to `/webhook`:
- about 20% duplicates (same `wa_message_id` re-sent)
- about 5% over the per-phone rate limit
- 50 concurrent senders, to simulate bursts

Report: accepted, duplicate and rate-limited counts; ack latency p50, p95, p99; then wait for the queue to drain and report `CONFIRMED`, `RELEASED`, `UNKNOWN` resolved, `DEAD`, and **double-bookings (must be 0)**.

**Spend:** the 1,000-message run uses the real Haiku model. At about $0.00065 per message that is about $0.65, roughly ₹55 at ~₹85/USD, well under the ₹500 limit. Record the actual figure in the README.

## 13. Timeline (24 hours from 3:52 PM IST Thu 19 Nov)

| Window | Work |
|---|---|
| 0–1 h | PLAN.md, repo, first commit (this step) |
| 1–4 h | Spring Boot skeleton, Flyway schema, Compose (postgres, gateway, worker, mock-lab), webhook with signature, dedupe, rate limit, tests |
| 4–8 h | Worker: claim and lock, Claude tool loop with Haiku, three tools, LabClient retries, booking state machine |
| 8–11 h | Reconciler, cost tracking, `/metrics`, usage endpoint |
| 11–14 h | Concurrency and failure tests; load test run; record results |
| 14–16 h | COST.md, AI_USAGE.md, README with run steps and real spend |
| 16–19 h | Deploy to a free tier if possible; record 3–5 min video |
| 19–22 h | Buffer and fixes |
| 22–24 h | Freeze, final test run, submit |

## 14. Risks and open questions

- **Real WhatsApp send** needs a test number and access token. The stub sender is the fallback, and the README says so.
- **Deployment target** is undecided. Render, Fly.io or Cloud Run free tiers, decided at hour 16.
- **Lab fault model** is my choice: 20% failures plus about 10% of successful responses dropped. This is the hardest case, so it is the one tested.
- **Patient identity** is the WhatsApp number plus a name entered in chat. Synthetic data only.
