-- Schema from PLAN.md section 8, with offered_slots and outbound_messages (design review, section 15).

CREATE TABLE conversations (
  phone_e164  TEXT PRIMARY KEY,
  state       TEXT NOT NULL CHECK (state IN ('ACTIVE', 'HANDED_OFF')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inbound_messages (
  wa_message_id  TEXT PRIMARY KEY,                -- dedupe key
  phone_e164     TEXT NOT NULL REFERENCES conversations (phone_e164),
  body           TEXT,
  status         TEXT NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSING', 'DONE', 'FAILED', 'DEAD', 'RATE_LIMITED')),
  attempts       INT NOT NULL DEFAULT 0,
  locked_until   TIMESTAMPTZ,
  received_at    TIMESTAMPTZ NOT NULL,
  processed_at   TIMESTAMPTZ
);
CREATE INDEX inbound_messages_claim_idx ON inbound_messages (status, locked_until);

CREATE TABLE messages (                           -- conversation history
  id             BIGSERIAL PRIMARY KEY,
  phone_e164     TEXT NOT NULL REFERENCES conversations (phone_e164),
  role           TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
  content        JSONB NOT NULL,                  -- content parts, incl. tool_use / tool_result
  wa_message_id  TEXT UNIQUE,                     -- set on the user turn; a redelivered job cannot store it twice
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX messages_phone_idx ON messages (phone_e164, id);

CREATE TABLE llm_calls (
  id                  BIGSERIAL PRIMARY KEY,
  phone_e164          TEXT NOT NULL,
  wa_message_id       TEXT NOT NULL,
  model               TEXT NOT NULL,
  input_tokens        INT NOT NULL,
  output_tokens       INT NOT NULL,
  cache_read_tokens   INT NOT NULL DEFAULT 0,
  cache_write_tokens  INT NOT NULL DEFAULT 0,
  cost_usd            NUMERIC(12, 6) NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX llm_calls_phone_idx ON llm_calls (phone_e164);

CREATE TABLE tool_calls (                         -- replay store, see ToolExecutor
  tool_use_id  TEXT PRIMARY KEY,                  -- from Claude; a replay reuses the stored result
  phone_e164   TEXT NOT NULL,
  name         TEXT NOT NULL,
  input        JSONB NOT NULL,
  result       JSONB,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE slots (
  slot_id     TEXT PRIMARY KEY,                   -- lab's id
  test_code   TEXT NOT NULL,
  starts_at   TIMESTAMPTZ NOT NULL,
  lab_site    TEXT NOT NULL,
  seen_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE offered_slots (                      -- slots shown to a phone; book_test may only use these
  phone_e164  TEXT NOT NULL,
  slot_id     TEXT NOT NULL,
  offered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (phone_e164, slot_id)
);

CREATE TABLE bookings (
  id                UUID PRIMARY KEY,             -- also the Idempotency-Key
  phone_e164        TEXT NOT NULL,
  slot_id           TEXT NOT NULL REFERENCES slots (slot_id),
  test_code         TEXT NOT NULL,
  status            TEXT NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'UNKNOWN', 'RELEASED')),
  lab_booking_ref   TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- No double booking: at most one active booking per slot, enforced by the database.
CREATE UNIQUE INDEX one_active_booking_per_slot
  ON bookings (slot_id)
  WHERE status IN ('HELD', 'CONFIRMED', 'UNKNOWN');

CREATE TABLE rate_limits (                        -- fixed window, 10 messages per 60 s per phone
  phone_e164    TEXT PRIMARY KEY,
  window_start  TIMESTAMPTZ NOT NULL,
  count         INT NOT NULL
);

CREATE TABLE handoffs (
  id          BIGSERIAL PRIMARY KEY,
  phone_e164  TEXT NOT NULL,
  reason      TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbound_messages (                  -- outbox: each reply is sent once
  id          BIGSERIAL PRIMARY KEY,
  phone_e164  TEXT NOT NULL,
  body        TEXT NOT NULL,
  status      TEXT NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at     TIMESTAMPTZ
);
