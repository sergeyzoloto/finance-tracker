package com.example.financetracker.ledger;

/** A request the service refuses as malformed (400), with a code the client tells apart, such as a snapshot's name. */
public class InvalidRequestException extends IllegalArgumentException {

    private final String code;

    public InvalidRequestException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
