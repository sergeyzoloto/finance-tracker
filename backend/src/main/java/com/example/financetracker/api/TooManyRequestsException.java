package com.example.financetracker.api;

import java.time.Duration;

/** A caller made too many attempts of a rate-limited kind (429); {@code retryAfter} is when the next one may come. */
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
