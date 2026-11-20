package com.heydoc.gateway.outbox;

/** Delivers one reply to a patient. The default implementation only logs (see PLAN.md section 3). */
public interface OutboundSender {

    void send(String phone, String body);
}
