package com.heydoc.gateway.lab;

/**
 * Result of one booking attempt at the lab.
 * UNKNOWN means retries ran out after the request may have reached the lab: reconcile, do not rebook.
 */
public record BookOutcome(Kind kind, String bookingRef) {

    public enum Kind { CONFIRMED, SLOT_TAKEN, REFUSED, UNKNOWN }

    public static BookOutcome confirmed(String ref) {
        return new BookOutcome(Kind.CONFIRMED, ref);
    }

    public static BookOutcome slotTaken() {
        return new BookOutcome(Kind.SLOT_TAKEN, null);
    }

    public static BookOutcome refused() {
        return new BookOutcome(Kind.REFUSED, null);
    }

    public static BookOutcome unknown() {
        return new BookOutcome(Kind.UNKNOWN, null);
    }
}
