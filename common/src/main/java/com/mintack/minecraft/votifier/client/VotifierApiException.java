package com.mintack.minecraft.votifier.client;

import java.io.IOException;

/**
 * Thrown when the {@code votifier-python} HTTP API returns a non-success status code
 * or an unreadable/malformed response payload.
 */
public class VotifierApiException extends IOException {
    private final int statusCode;
    private final String errorCode;

    /**
     * Creates an exception capturing the HTTP status code and API error details.
     *
     * @param statusCode HTTP status code returned by the API
     * @param errorCode  structured {@code error} field from the JSON body, if present
     * @param message    human-readable detail message
     */
    public VotifierApiException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
