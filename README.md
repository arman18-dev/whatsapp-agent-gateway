# WhatsApp Agent Gateway

A booking agent for a diagnostics lab. Patients message the lab on WhatsApp; the agent checks slots, books a test with the lab's API, and hands off to staff when it can't finish. The design and the decisions behind it are in [PLAN.md](PLAN.md).

**Status:** the gateway, worker, booking state machine, reconciler, outbox and mock lab are built and tested. The real-model path has been run against the Anthropic API. The deployment is a simulated WhatsApp webhook, not a live WhatsApp number. Replies are logged, not sent to WhatsApp.

## What runs

| Part | What it does |
|---|---|
| Gateway | `POST /webhook` checks the signature, dedupes on the WhatsApp message id, applies a per-phone rate limit (10 messages per minute), and stores the message. It makes no model or lab calls. |
| Worker | Claims stored messages from Postgres, runs the Claude tool loop (`check_slots`, `book_test`, `handoff_to_human`), and books through the lab client. |
| Booking | A slot can be held by one active booking. The database enforces it, and the lab is called with a key tied to the booking. |
| Reconciler | Resolves bookings the lab left unknown after a timeout. |
| Mock lab | A fake lab with a realistic calendar, 20% failures, latency, and dropped responses after commit. |

Reviewer endpoints:

- `GET /conversations/{phone}`: what a patient sent, what we replied, their bookings, and any handoff.
- `GET /conversations/{phone}/usage`: tokens and cost for one conversation.
- `GET /metrics`: Prometheus counters.
- `GET /healthz`: liveness.

## Run locally (Docker)

1. Copy `.env.example` to `.env` and set `WHATSAPP_APP_SECRET` and `WHATSAPP_VERIFY_TOKEN`. Leave `LLM_MODE=mock` to avoid model spend. To use Claude, set `LLM_MODE=real` and add `ANTHROPIC_API_KEY`.
2. Start everything:

   ```bash
   docker compose up --build
   ```

3. Send a simulated message:

   ```bash
   GATEWAY_URL=http://localhost:8080 WHATSAPP_APP_SECRET=... \
     scripts/send-webhook.sh 9988000001 "I want a thyroid test on 2026-11-30 at 09:00. My name is Ravi Kumar."
   ```

4. Read the result at `http://localhost:8080/conversations/9988000001`.

## Tests

```bash
mvn test
```

There are 22 tests. They cover signature checks, dedupe, rate limits, the mock calendar, slot selection, the message generator used by the load test, and end-to-end booking against a real Postgres (Testcontainers). The end-to-end race test checks that eight patients racing for one slot produce one booking.

## Load test

`LoadTest` sends 1,000 signed messages with 50 concurrent senders. Of those, 800 are unique and 200 are redeliveries. About 5% of sends exceed the rate limit. It then waits for the pipeline to drain and writes a report.

```bash
# with the stack running (docker compose up)
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
GATEWAY_URL=http://localhost:8080 WHATSAPP_APP_SECRET=... \
LOAD_DB_URL=jdbc:postgresql://localhost:5432/gateway LOAD_DB_USER=gateway LOAD_DB_PASSWORD=gateway \
LOAD_LLM_MODE=mock \
java -cp "target/classes;$(cat target/cp.txt)" com.heydoc.gateway.loadtest.LoadTest docs/report.md
```

Reports:

- [docs/load-test-report-mock-realistic.md](docs/load-test-report-mock-realistic.md): scripted model, realistic lab calendar. 212 bookings, 0 double bookings.
- [docs/load-test-report-real-spread.md](docs/load-test-report-real-spread.md): Claude Haiku 5.5, realistic calendar, requested times spread across the day. 93 bookings, 0 double bookings, 0 unresolved.

## Deploy on Render

`render.yaml` describes one web service (gateway, worker and mock lab in one process) and one Postgres database.

1. In Render, choose **New > Blueprint** and connect this GitHub repository.
2. Render creates the service and the database and generates `WHATSAPP_APP_SECRET` and `WHATSAPP_VERIFY_TOKEN`. Open the service's **Environment** tab to read the secret.
3. Once the service is live, send a simulated message:

   ```bash
   GATEWAY_URL=https://<your-service>.onrender.com WHATSAPP_APP_SECRET=<from Render> \
     scripts/send-webhook.sh 9988000001 "I want a thyroid test on 2026-11-30 at 09:00. My name is Ravi Kumar."
   ```

The Render free plan sleeps the service after about 15 minutes without traffic, so the first request after a pause can take a minute. The free Postgres database expires after 30 days.

## What is simulated

- **WhatsApp:** no Meta app or WhatsApp number is connected. Messages are posted to `/webhook` in the same format and signed the same way Meta signs them.
- **Outbound replies:** logged by `LoggingOutboundSender`. Nothing is sent to a phone.
- **Lab:** a mock. The real lab's API and its idempotency behaviour are not available, so the lab must agree to a fence (a key refused after it is reported absent) before go-live.
- **Model in the deployment:** scripted, not Claude, so the public service costs nothing. Setting `LLM_MODE=real` and an Anthropic key switches it to Claude Haiku 5.5, with a spend cap set by `LLM_BUDGET_USD`.

## Known gaps

- **Ack latency:** on this laptop, p95 ranges from about 110 to 260 ms across runs, so it sits on the 200 ms target. It hasn't been tuned further.
- **Real-model rate limits:** heavy real traffic hit the account's 500,000 input tokens per minute limit. Those messages were retried and completed.
- **Replies are at least once:** the outbox can resend if the process crashes between sending and marking a reply sent.
- **Test codes:** only THYROID exists in the calendar. A real lab would supply its test catalogue.
- **Cost:** spend is recorded in this database only. The Anthropic console is the source of truth.

## Cost

See [COST.md](COST.md) for the estimate at one million messages a month. [AI_USAGE.md](AI_USAGE.md) lists the AI tools used and where.
