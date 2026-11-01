package com.buslink.exception;

/** Rate limit hit (mapped to 429 Too Many Requests). */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
