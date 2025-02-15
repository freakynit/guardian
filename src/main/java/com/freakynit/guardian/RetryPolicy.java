package com.freakynit.guardian;

import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Represents a retry policy that defines how many retry attempts to make, the delay between attempts,
 * the type of exceptions to retry on, and the backoff strategy.
 */
public class RetryPolicy {

    /**
     * Enum representing the supported backoff strategies.
     */
    public enum BackoffStrategy {
        SIMPLE,
        EXPONENTIAL
    }

    private final int maxRetries;
    private final long delayInMillis;
    private final Set<Class<? extends Throwable>> retryOnExceptions;

    // Fields for exponential backoff and event listeners
    private BackoffStrategy backoffStrategy = BackoffStrategy.SIMPLE;
    private double multiplier = 1.0;
    private List<Consumer<RetryAttemptContext>> onFailedAttemptListeners = new ArrayList<>();
    private List<Consumer<RetryAttemptContext>> onRetryListeners = new ArrayList<>();

    /**
     * Constructs a RetryPolicy with the specified maximum number of retries, base delay, and exceptions to retry on.
     *
     * @param maxRetries the maximum number of retry attempts; must be non-negative
     * @param delayInMillis the base delay in milliseconds between retries; must be non-negative
     * @param retryOnExceptions the set of exception types that should trigger a retry; if null, no exceptions are specified
     */
    public RetryPolicy(int maxRetries, long delayInMillis, Set<Class<? extends Throwable>> retryOnExceptions) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative.");
        }
        if (delayInMillis < 0) {
            throw new IllegalArgumentException("delayInMillis must be non-negative.");
        }
        this.maxRetries = maxRetries;
        this.delayInMillis = delayInMillis;
        if (retryOnExceptions != null) {
            this.retryOnExceptions = new HashSet<>(retryOnExceptions);
        } else {
            this.retryOnExceptions = new HashSet<>();
        }
    }

    /**
     * Determines whether a retry should be attempted based on the current attempt number and last failure.
     *
     * @param attempt the current attempt number
     * @param lastFailure the exception encountered on the last attempt
     * @return true if a retry should be attempted; false otherwise
     */
    public boolean shouldRetry(int attempt, Throwable lastFailure) {
        if (attempt > maxRetries) {
            return false;
        }
        // If specific exception types are provided, only retry for those.
        if (!retryOnExceptions.isEmpty()) {
            for (Class<? extends Throwable> exClass : retryOnExceptions) {
                if (exClass.isAssignableFrom(lastFailure.getClass())) {
                    return true;
                }
            }
            return false;
        }
        return true; // If no specific exceptions are set, retry on any exception.
    }

    /**
     * Gets the maximum number of retries allowed.
     *
     * @return the maximum retry count
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Gets the base delay in milliseconds between retry attempts.
     *
     * @return the base delay in milliseconds
     */
    public long getDelayInMillis() {
        return delayInMillis;
    }

    /**
     * Configures the backoff strategy and multiplier.
     *
     * @param strategy the backoff strategy to use; cannot be null
     * @param multiplier the multiplier for delay calculation; must be at least 1.0
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy withBackoffStrategy(BackoffStrategy strategy, double multiplier) {
        if (strategy == null) {
            throw new IllegalArgumentException("Backoff strategy cannot be null.");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("Multiplier must be at least 1.0.");
        }
        this.backoffStrategy = strategy;
        this.multiplier = multiplier;
        return this;
    }

    /**
     * Gets the current backoff strategy.
     *
     * @return the backoff strategy
     */
    public BackoffStrategy getBackoffStrategy() {
        return backoffStrategy;
    }

    /**
     * Gets the multiplier used in the exponential backoff calculation.
     *
     * @return the multiplier
     */
    public double getMultiplier() {
        return multiplier;
    }

    /**
     * Adds a listener to be invoked on each failed attempt.
     *
     * @param listener a Consumer that accepts a RetryAttemptContext
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy onFailedAttempt(Consumer<RetryAttemptContext> listener) {
        onFailedAttemptListeners.add(listener);
        return this;
    }

    /**
     * Adds a listener to be invoked before each retry.
     *
     * @param listener a Consumer that accepts a RetryAttemptContext
     * @return this RetryPolicy instance for chaining
     */
    public RetryPolicy onRetry(Consumer<RetryAttemptContext> listener) {
        onRetryListeners.add(listener);
        return this;
    }

    /**
     * Gets the list of listeners for failed attempts.
     *
     * @return a list of Consumers for failed attempt events
     */
    public List<Consumer<RetryAttemptContext>> getOnFailedAttemptListeners() {
        return onFailedAttemptListeners;
    }

    /**
     * Gets the list of listeners for retry events.
     *
     * @return a list of Consumers for retry events
     */
    public List<Consumer<RetryAttemptContext>> getOnRetryListeners() {
        return onRetryListeners;
    }

    /**
     * Provides contextual information about a retry attempt.
     */
    public static class RetryAttemptContext {
        private final int attemptNumber;
        private final Throwable lastFailure;
        private final long delay;

        /**
         * Constructs a new RetryAttemptContext.
         *
         * @param attemptNumber the current attempt number
         * @param lastFailure the exception from the last attempt
         * @param delay the computed delay before the next attempt
         */
        public RetryAttemptContext(int attemptNumber, Throwable lastFailure, long delay) {
            this.attemptNumber = attemptNumber;
            this.lastFailure = lastFailure;
            this.delay = delay;
        }

        /**
         * Gets the current attempt number.
         *
         * @return the attempt number
         */
        public int getAttemptNumber() {
            return attemptNumber;
        }

        /**
         * Gets the exception encountered on the last attempt.
         *
         * @return the last failure exception
         */
        public Throwable getLastFailure() {
            return lastFailure;
        }

        /**
         * Gets the delay before the next retry attempt.
         *
         * @return the delay in milliseconds
         */
        public long getDelay() {
            return delay;
        }
    }
}
