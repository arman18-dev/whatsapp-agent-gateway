# Load test report

- Sends: 1000 (800 unique, 200 duplicate redeliveries), 50 concurrent senders
- Gateway: http://localhost:8080, LLM mode: mock
- Send phase: 3389 ms; drain: 83 s
- Transport failures: 0

## Webhook outcomes

| outcome | count |
|---|---|
| accepted | 750 |
| duplicate | 200 |
| rate_limited | 50 |

## Ack latency (client side, ms)

p50 97.1, p95 410.0, p99 1333.0, max 1333.7

## Inbound messages by status

| status | count |
|---|---|
| DONE | 750 |
| RATE_LIMITED | 50 |

## Bookings

| status | count |
|---|---|
| CONFIRMED | 221 |

- **Double bookings (must be 0): 0**
- UNKNOWN left after drain (must be 0): 0
- Handoffs: 0
- Outbound replies: 751

## LLM usage and cost

- Calls: 1600
- Input tokens: 0 (cache read 0, cache write 0)
- Output tokens: 0
- Cost (USD): 0.000000
