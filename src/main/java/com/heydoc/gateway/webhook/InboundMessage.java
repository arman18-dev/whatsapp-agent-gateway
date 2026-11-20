package com.heydoc.gateway.webhook;

/**
 * One inbound WhatsApp message, as read from entry[].changes[].value.messages[].
 *
 * @param waMessageId WhatsApp message id; the dedupe key
 * @param from        sender phone number (E.164 without "+", as WhatsApp sends it)
 * @param type        message type, e.g. "text"
 * @param body        text body, or null when the message is not text
 */
public record InboundMessage(String waMessageId, String from, String type, String body) {
}
