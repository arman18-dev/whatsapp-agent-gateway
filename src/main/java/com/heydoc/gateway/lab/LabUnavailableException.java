package com.heydoc.gateway.lab;

/** The lab could not answer within the retry budget, or refused a request that cannot succeed. */
public class LabUnavailableException extends RuntimeException {

    public LabUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
