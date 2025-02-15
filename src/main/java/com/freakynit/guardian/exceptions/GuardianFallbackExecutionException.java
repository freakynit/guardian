package com.freakynit.guardian.exceptions;

/**
 * An exception thrown when the fallback execution fails after the original operation fails.
 */
public class GuardianFallbackExecutionException extends GuardianExecutionException {

    public GuardianFallbackExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
