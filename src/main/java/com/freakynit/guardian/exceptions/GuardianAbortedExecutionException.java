package com.freakynit.guardian.exceptions;

/**
 * An exception thrown when the operation execution is aborted due to specific abort conditions.
 */
public class GuardianAbortedExecutionException extends GuardianExecutionException {

    public GuardianAbortedExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
