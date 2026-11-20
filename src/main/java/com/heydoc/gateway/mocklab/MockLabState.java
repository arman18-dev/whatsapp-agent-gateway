package com.heydoc.gateway.mocklab;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The lab's own state. All operations are synchronized, so a slot can be committed to one key only.
 * A key that was looked up and found absent is fenced: a later booking with that key is refused.
 */
@Component
@Profile("mock-lab")
public class MockLabState {

    public enum BookResult { CREATED, EXISTING, SLOT_TAKEN, FENCED }

    public record Booking(BookResult result, String bookingRef) {
    }

    private final Map<String, String> refByKey = new HashMap<>();
    private final Map<String, String> slotByKey = new HashMap<>();
    private final Map<String, String> keyBySlot = new HashMap<>();
    private final Set<String> fencedKeys = new HashSet<>();

    public synchronized Booking book(String key, String slotId) {
        if (fencedKeys.contains(key)) {
            return new Booking(BookResult.FENCED, null);
        }
        if (refByKey.containsKey(key)) {
            return new Booking(BookResult.EXISTING, refByKey.get(key));
        }
        if (keyBySlot.containsKey(slotId)) {
            return new Booking(BookResult.SLOT_TAKEN, null);
        }
        String ref = "LAB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        refByKey.put(key, ref);
        slotByKey.put(key, slotId);
        keyBySlot.put(slotId, key);
        return new Booking(BookResult.CREATED, ref);
    }

    public synchronized Optional<String> lookup(String key) {
        String ref = refByKey.get(key);
        if (ref == null) {
            fencedKeys.add(key);  // the lab will never book this key later
        }
        return Optional.ofNullable(ref);
    }
}
