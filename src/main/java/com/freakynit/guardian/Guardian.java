package com.freakynit.guardian;

import com.freakynit.guardian.exceptions.GuardianAbortedExecutionException;
import com.freakynit.guardian.exceptions.GuardianExecutionException;
import com.freakynit.guardian.exceptions.GuardianFallbackExecutionException;

import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The Guardian class provides a fluent API to execute operations with built-in support for retries,
 * circuit breaking, fallback execution, and customizable abort conditions.
 *
 * <p>This class is designed to encapsulate common fault-tolerance patterns in a single interface.
 */
public class Guardian {
    private RetryPolicy retryPolicy;
    private Callable<?> fallback;
    private CircuitBreaker circuitBreaker;
    private Boolean abortWhenCondition;
    private Set<Class<? extends Throwable>> abortOnExceptions;
    private Predicate<Object> abortIfPredicate;

    private Guardian() {}

    /**
     * Creates a new Guardian builder.
     *
     * @return a new GuardianBuilder instance for configuring the execution behavior
     */
    public static GuardianBuilder builder() {
        return new GuardianBuilder();
    }

    /**
     * Builder class for configuring and executing an operation with Guardian.
     */
    public static class GuardianBuilder {
        private RetryPolicy retryPolicy;
        private Callable<?> fallback;
        private CircuitBreaker circuitBreaker;
        // Abort conditions
        private Boolean abortWhenCondition = null;
        private Set<Class<? extends Throwable>> abortOnExceptions = new HashSet<>();
        private Predicate<Object> abortIfPredicate;

        /**
         * Sets the retry policy to be used for the operation.
         *
         * @param policy the RetryPolicy instance
         * @return this builder instance for chaining
         */
        public GuardianBuilder withRetryPolicy(RetryPolicy policy) {
            this.retryPolicy = policy;
            return this;
        }

        /**
         * Sets the fallback action to be executed if all retry attempts fail.
         *
         * @param fallback a Callable representing the fallback action; cannot be null
         * @return this builder instance for chaining
         */
        public GuardianBuilder withFallback(Callable<?> fallback) {
            if (fallback == null) {
                throw new IllegalArgumentException("Fallback action cannot be null.");
            }
            this.fallback = fallback;
            return this;
        }

        /**
         * Sets the circuit breaker to be used for the operation.
         *
         * @param circuitBreaker the CircuitBreaker instance; cannot be null
         * @return this builder instance for chaining
         */
        public GuardianBuilder withCircuitBreaker(CircuitBreaker circuitBreaker) {
            if (circuitBreaker == null) {
                throw new IllegalArgumentException("CircuitBreaker cannot be null.");
            }
            this.circuitBreaker = circuitBreaker;
            return this;
        }

        /**
         * Configures an abort condition based on a Boolean value.
         *
         * @param abortWhenCondition if the operation result equals this value, execution is aborted; cannot be null
         * @return this builder instance for chaining
         */
        public GuardianBuilder abortWhen(Boolean abortWhenCondition) {
            if (abortWhenCondition == null) {
                throw new IllegalArgumentException("abortWhenCondition cannot be null.");
            }
            this.abortWhenCondition = abortWhenCondition;
            return this;
        }

        /**
         * Configures the Guardian to abort execution if the specified exception is thrown.
         *
         * @param exceptionClass the exception class that should abort the execution
         * @return this builder instance for chaining
         */
        public GuardianBuilder abortOn(Class<? extends Throwable> exceptionClass) {
            this.abortOnExceptions.add(exceptionClass);
            return this;
        }

        /**
         * Configures a predicate that, when evaluated to true on the operation result, aborts execution.
         *
         * @param predicate the predicate to evaluate the operation result
         * @return this builder instance for chaining
         */
        public GuardianBuilder abortIf(Predicate<Object> predicate) {
            this.abortIfPredicate = predicate;
            return this;
        }

        /**
         * Executes the given operation with the configured retry, circuit breaker, and fallback mechanisms.
         *
         * @param <T> the type of the result produced by the operation
         * @param operation the operation to execute
         * @return the result of the operation or fallback
         * @throws GuardianExecutionException if the operation fails after all retry attempts and no fallback is defined,
         *                                    or if the fallback execution fails
         * @throws InterruptedException if the thread is interrupted during execution
         */
        public <T> T get(Callable<T> operation) throws GuardianExecutionException, InterruptedException {
            Guardian guardian = new Guardian();
            guardian.retryPolicy = this.retryPolicy;
            guardian.fallback = this.fallback;
            guardian.circuitBreaker = this.circuitBreaker;
            guardian.abortWhenCondition = this.abortWhenCondition;
            guardian.abortOnExceptions = this.abortOnExceptions;
            guardian.abortIfPredicate = this.abortIfPredicate;
            return guardian.execute(operation);
        }

        /**
         * Runnable variant of `get` method. See documentation for `get` for details.
         */
        public void run(Runnable runnable) throws GuardianExecutionException, InterruptedException {
            get(() -> {
                runnable.run();
                return null;
            });
        }
    }

    /**
     * Executes the given operation using the configured retry policy, circuit breaker, and fallback mechanisms.
     *
     * @param <T> the type of the result produced by the operation
     * @param operation the operation to execute
     * @return the result of the operation or fallback
     * @throws GuardianExecutionException if the operation fails and no valid fallback is provided
     * @throws InterruptedException if the thread is interrupted during execution
     */
    public <T> T execute(Callable<T> operation) throws GuardianExecutionException, InterruptedException {
        int attempt = 0;
        Exception lastException = null;

        while (true) {
            try {
                if (circuitBreaker != null) {
                    circuitBreaker.beforeCall();
                }

                T result = operation.call();

                // Check abortWhen condition on result.
                if (abortWhenCondition != null) {
                    if (result instanceof Boolean && result.equals(abortWhenCondition)) {
                        throw new GuardianAbortedExecutionException("Aborted due to abortWhen condition with result: " + result, null);
                    }
                }

                // Check abortIf condition on result.
                if (abortIfPredicate != null && abortIfPredicate.test(result)) {
                    throw new GuardianAbortedExecutionException("Aborted due to abortIf condition met with result: " + result, null);
                }

                if (circuitBreaker != null) {
                    circuitBreaker.afterCallSuccess();
                }
                return result;
            } catch (Exception e) {
                if (e instanceof GuardianAbortedExecutionException) {
                    throw (GuardianAbortedExecutionException)e;
                }

                // Check abort conditions for exceptions.
                if (abortOnExceptions != null) {
                    for (Class<? extends Throwable> abortEx : abortOnExceptions) {
                        if (abortEx.isAssignableFrom(e.getClass())) {
                            throw new GuardianAbortedExecutionException("Aborted due to abortOn exception: " + e.getClass().getName(), e);
                        }
                    }
                }

                lastException = e;
                if (circuitBreaker != null) {
                    circuitBreaker.afterCallFailure(e);
                }
                attempt++;

                boolean canRetry = (retryPolicy != null) && retryPolicy.shouldRetry(attempt, e);
                long baseDelay = retryPolicy != null ? retryPolicy.getDelayInMillis() : 0;
                long computedDelay = baseDelay;
                if (retryPolicy != null && retryPolicy.getBackoffStrategy() == RetryPolicy.BackoffStrategy.EXPONENTIAL) {
                    computedDelay = (long) (baseDelay * Math.pow(retryPolicy.getMultiplier(), attempt - 1));
                }

                RetryPolicy.RetryAttemptContext context = new RetryPolicy.RetryAttemptContext(attempt, e, computedDelay);
                if (retryPolicy != null) {
                    for (Consumer<RetryPolicy.RetryAttemptContext> listener : retryPolicy.getOnFailedAttemptListeners()) {
                        listener.accept(context);
                    }
                }

                if (canRetry) {
                    if (retryPolicy != null) {
                        for (Consumer<RetryPolicy.RetryAttemptContext> listener : retryPolicy.getOnRetryListeners()) {
                            listener.accept(context);
                        }
                    }
                    if (computedDelay > 0) {
                        Thread.sleep(computedDelay);
                    }
                    continue;
                } else {
                    if (fallback != null) {
                        try {
                            T fallbackResult = (T) fallback.call();
                            return fallbackResult;
                        } catch (Exception fallbackEx) {
                            throw new GuardianFallbackExecutionException(
                                    "Fallback execution failed after operation failure. Last exception: " + e.getMessage(),
                                    fallbackEx);
                        }
                    } else {
                        throw new GuardianExecutionException(
                                "Operation failed after " + attempt + " attempt(s). Last exception: " + e.getMessage(),
                                e);
                    }
                }
            }
        }
    }
}
