package com.heydoc.gateway.lab;

/** A bookable slot as the lab reports it. */
public record Slot(String slotId, String startsAt, String labSite) {
}
