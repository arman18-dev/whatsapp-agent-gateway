# Load test report

- Sends: 1000 (800 unique, 200 duplicate redeliveries), 50 concurrent senders
- Gateway: http://localhost:8080, LLM mode: real-haiku-5-5
- Send phase: 2094 ms; drain: 197 s
- Transport failures: 0

## Webhook outcomes

| outcome | count |
|---|---|
| accepted | 750 |
| duplicate | 200 |
| rate_limited | 50 |

## Ack latency (client side, ms)

p50 71.7, p95 244.3, p99 595.0, max 595.5

## Inbound messages by status

| status | count |
|---|---|
| DONE | 750 |
| RATE_LIMITED | 50 |

## Bookings

| status | count |
|---|---|
| CONFIRMED | 93 |

- **Double bookings (must be 0): 0**
- UNKNOWN left after drain (must be 0): 0
- Handoffs: 2
- Outbound replies: 751

## LLM usage and cost

- Calls: 1271
- Input tokens: 1015128 (cache read 1488341, cache write 0)
- Output tokens: 122628
- Cost (USD): 0.177759
