package com.heydoc.gateway.ingest;

import java.util.Locale;

/**
 * Outcome of one webhook POST. Declared in increasing priority: when one payload holds several
 * messages, the highest-priority outcome is returned (so {@code max(ordinal)} is the combined result).
 */
public enum IngestResult {
    IGNORED,       // status events or no messages
    DUPLICATE,     // already stored; no second reply
    RATE_LIMITED,  // stored, silent, no LLM call
    ACCEPTED;      // stored and queued for the worker

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public IngestResult combine(IngestResult other) {
        return other.ordinal() > this.ordinal() ? other : this;
    }
}
