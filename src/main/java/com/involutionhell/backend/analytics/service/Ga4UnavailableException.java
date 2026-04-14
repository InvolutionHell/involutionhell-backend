package com.involutionhell.backend.analytics.service;

public class Ga4UnavailableException extends RuntimeException {
    public Ga4UnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
