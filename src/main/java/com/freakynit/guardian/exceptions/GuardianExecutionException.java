package com.freakynit.guardian.exceptions;

/**
 * A custom exception thrown by Guardian when an operation fails after all retry attempts (and optional fallback)
 * have been exhausted.
 */
public class GuardianExecutionException extends Exception {

    public GuardianExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
