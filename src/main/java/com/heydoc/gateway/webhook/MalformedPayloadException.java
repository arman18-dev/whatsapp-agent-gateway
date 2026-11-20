package com.heydoc.gateway.webhook;

public class MalformedPayloadException extends RuntimeException {

    public MalformedPayloadException(String message) {
        super(message);
    }

    public MalformedPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
