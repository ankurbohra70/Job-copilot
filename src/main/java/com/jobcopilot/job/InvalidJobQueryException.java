package com.jobcopilot.job;

public class InvalidJobQueryException extends RuntimeException {

    public InvalidJobQueryException(String message) {
        super(message);
    }
}
