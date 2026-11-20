package com.heydoc.gateway.lab;

/** A failure where the lab may or may not have acted (timeout, 5xx, dropped connection). Safe to retry with the same key. */
public class RetryableLabException extends RuntimeException {

    public RetryableLabException(String message) {
        super(message);
    }

    public RetryableLabException(String message, Throwable cause) {
        super(message, cause);
    }
}
