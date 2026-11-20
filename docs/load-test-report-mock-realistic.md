# Load test report

- Sends: 1000 (800 unique, 200 duplicate redeliveries), 50 concurrent senders
- Gateway: http://localhost:8080, LLM mode: mock
- Send phase: 10314 ms; drain: 109 s
- Transport failures: 0

## Webhook outcomes

| outcome | count |
|---|---|
| accepted | 750 |
| duplicate | 200 |
| rate_limited | 50 |

## Ack latency (client side, ms)

p50 73.5, p95 262.5, p99 1304.6, max 1615.9

## Inbound messages by status

| status | count |
|---|---|
| DONE | 750 |
| RATE_LIMITED | 50 |

## Bookings

| status | count |
|---|---|
| CONFIRMED | 212 |
| RELEASED | 1 |

- **Double bookings (must be 0): 0**
- UNKNOWN left after drain (must be 0): 0
- Handoffs: 0
- Outbound replies: 752

## LLM usage and cost

- Calls: 1600
- Input tokens: 0 (cache read 0, cache write 0)
- Output tokens: 0
- Cost (USD): 0.000000
