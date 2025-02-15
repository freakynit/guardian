package com.freakynit.guardian;

import com.freakynit.guardian.exceptions.GuardianExecutionException;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;

/**
 * Implements the circuit breaker pattern to prevent an application from performing operations that are likely to fail.
 * The circuit breaker can be in one of three states: CLOSED, OPEN, or HALF_OPEN.
 */
public class CircuitBreaker {

    /**
     * Internal states of the circuit breaker.
     */
    private enum State { CLOSED, OPEN, HALF_OPEN }

    private State state = State.CLOSED;
    private final int failureThreshold;
    private final long resetTimeout; // in milliseconds
    private int failureCount = 0;
    private long lastFailureTime = 0;
    private final Set<Class<? extends Throwable>> handledExceptions;
    private final List<Consumer<CircuitBreakerEvent>> onOpenListeners;
    private final List<Consumer<CircuitBreakerEvent>> onCloseListeners;
    private final List<Consumer<CircuitBreakerEvent>> onHalfOpenListeners;

    /**
     * Constructs a CircuitBreaker with the specified failure threshold and reset timeout.
     *
     * @param failureThreshold the number of failures before opening the circuit; must be greater than zero
     * @param resetTimeout the time in milliseconds after which the circuit breaker will try to half-open; must be greater than zero
     */
    public CircuitBreaker(int failureThreshold, long resetTimeout) {
        this(failureThreshold, resetTimeout, new HashSet<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private CircuitBreaker(int failureThreshold, long resetTimeout,
                           Set<Class<? extends Throwable>> handledExceptions,
                           List<Consumer<CircuitBreakerEvent>> onOpenListeners,
                           List<Consumer<CircuitBreakerEvent>> onCloseListeners,
                           List<Consumer<CircuitBreakerEvent>> onHalfOpenListeners) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be greater than zero.");
        }
        if (resetTimeout <= 0) {
            throw new IllegalArgumentException("resetTimeout must be greater than zero.");
        }
        this.failureThreshold = failureThreshold;
        this.resetTimeout = resetTimeout;
        this.handledExceptions = handledExceptions;
        this.onOpenListeners = onOpenListeners;
        this.onCloseListeners = onCloseListeners;
        this.onHalfOpenListeners = onHalfOpenListeners;
    }

    /**
     * Creates a new CircuitBreaker instance with a fresh state but the same configuration.
     *
     * @return a new CircuitBreaker instance with reset state
     */
    public CircuitBreaker cloneWithFreshState() {
        return new CircuitBreaker(this.failureThreshold, this.resetTimeout, this.handledExceptions, this.onOpenListeners, this.onCloseListeners, this.onHalfOpenListeners);
    }

    /**
     * Creates a new builder for configuring a CircuitBreaker.
     *
     * @return a new CircuitBreakerBuilder instance
     */
    public static CircuitBreakerBuilder builder() {
        return new CircuitBreakerBuilder();
    }

    /**
     * Invoked before an operation call to check if the circuit breaker permits the call.
     *
     * @throws GuardianExecutionException if the circuit breaker is open and the reset timeout has not elapsed
     */
    public synchronized void beforeCall() throws GuardianExecutionException {
        if (state == State.OPEN) {
            long elapsed = System.currentTimeMillis() - lastFailureTime;
            if (elapsed > resetTimeout) {
                state = State.HALF_OPEN;
                triggerHalfOpenEvent();
            } else {
                throw new GuardianExecutionException("Circuit breaker is open. Operation not permitted.", null);
            }
        }
    }

    /**
     * Invoked after a successful operation call to reset the circuit breaker.
     */
    public synchronized void afterCallSuccess() {
        if (state == State.HALF_OPEN) {
            reset();
        } else if (state == State.CLOSED) {
            failureCount = 0;
        }
    }

    /**
     * Invoked after a failed operation call to update the circuit breaker state.
     *
     * @param failure the exception that caused the failure
     */
    public synchronized void afterCallFailure(Throwable failure) {
        if (!handledExceptions.isEmpty()) {
            boolean isHandled = false;
            for (Class<? extends Throwable> cls : handledExceptions) {
                if (cls.isAssignableFrom(failure.getClass())) {
                    isHandled = true;
                    break;
                }
            }
            if (!isHandled) {
                return; // Ignore this failure.
            }
        }
        failureCount++;
        lastFailureTime = System.currentTimeMillis();
        if (failureCount >= failureThreshold && state != State.OPEN) {
            state = State.OPEN;
            triggerOpenEvent();
        }
    }

    /**
     * Resets the circuit breaker to the CLOSED state and clears the failure count.
     */
    public synchronized void reset() {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            failureCount = 0;
            triggerCloseEvent();
        } else {
            failureCount = 0;
        }
    }

    // Private helper methods to trigger events on state transitions

    private void triggerOpenEvent() {
        CircuitBreakerEvent event = new CircuitBreakerEvent(this);
        for (Consumer<CircuitBreakerEvent> listener : onOpenListeners) {
            listener.accept(event);
        }
    }

    private void triggerCloseEvent() {
        CircuitBreakerEvent event = new CircuitBreakerEvent(this);
        for (Consumer<CircuitBreakerEvent> listener : onCloseListeners) {
            listener.accept(event);
        }
    }

    private void triggerHalfOpenEvent() {
        CircuitBreakerEvent event = new CircuitBreakerEvent(this);
        for (Consumer<CircuitBreakerEvent> listener : onHalfOpenListeners) {
            listener.accept(event);
        }
    }

    /**
     * Represents an event that is triggered when the circuit breaker state changes.
     */
    public static class CircuitBreakerEvent {
        private final CircuitBreaker circuitBreaker;

        /**
         * Constructs a new CircuitBreakerEvent.
         *
         * @param circuitBreaker the circuit breaker that triggered the event
         */
        public CircuitBreakerEvent(CircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
        }

        /**
         * Gets the circuit breaker associated with this event.
         *
         * @return the CircuitBreaker instance
         */
        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }
    }

    /**
     * Builder class for constructing a CircuitBreaker with custom configuration.
     */
    public static class CircuitBreakerBuilder {
        private int failureThreshold;
        private long resetTimeout;
        private final Set<Class<? extends Throwable>> handledExceptions = new HashSet<>();
        private final List<Consumer<CircuitBreakerEvent>> onOpenListeners = new ArrayList<>();
        private final List<Consumer<CircuitBreakerEvent>> onCloseListeners = new ArrayList<>();
        private final List<Consumer<CircuitBreakerEvent>> onHalfOpenListeners = new ArrayList<>();

        /**
         * Sets the failure threshold.
         *
         * @param threshold the number of failures required to open the circuit; must be greater than zero
         * @return this builder instance for chaining
         */
        public CircuitBreakerBuilder failureThreshold(int threshold) {
            if (threshold <= 0) {
                throw new IllegalArgumentException("failureThreshold must be greater than zero.");
            }
            this.failureThreshold = threshold;
            return this;
        }

        /**
         * Sets the reset timeout in milliseconds.
         *
         * @param timeout the timeout in milliseconds; must be greater than zero
         * @return this builder instance for chaining
         */
        public CircuitBreakerBuilder resetTimeout(long timeout) {
            if (timeout <= 0) {
                throw new IllegalArgumentException("resetTimeout must be greater than zero.");
            }
            this.resetTimeout = timeout;
            return this;
        }

        /**
         * Specifies an exception type that the circuit breaker should handle.
         *
         * @param exceptionClass the exception class to handle; cannot be null
         * @return this builder instance for chaining
         */
        public CircuitBreakerBuilder handle(Class<? extends Throwable> exceptionClass) {
            if (exceptionClass == null) {
                throw new IllegalArgumentException("Exception class cannot be null.");
            }
            this.handledExceptions.add(exceptionClass);
            return this;
        }

        /**
         * Adds a listener to be invoked when the circuit breaker opens.
         *
         * @param listener a Consumer that accepts a CircuitBreakerEvent; cannot be null
         * @return this builder instance for chaining
         */
        public CircuitBreakerBuilder onOpen(Consumer<CircuitBreakerEvent> listener) {
            if (listener == null) {
                throw new IllegalArgumentException("Listener cannot be null.");
            }
            this.onOpenListeners.add(listener);
            return this;
        }

        /**
         * Adds a listener to be invoked when the circuit breaker closes.
         *
         * @param listener a Consumer that accepts a CircuitBreakerEvent; cannot be null
         * @return this builder instance for chaining
         */
        public CircuitBreakerBuilder onClose(Consumer<CircuitBreakerEvent> listener) {
            if (listener == null) {
                throw new IllegalArgumentException("Listener cannot be null.");
            }
            this.onCloseListeners.add(listener);
            return this;
        }

        /**
         * Adds a listener to be invoked when the circuit breaker transitions to the half-open state.
         *
         * @param listener a Consumer that accepts a CircuitBreakerEvent; cannot be null
         * @return this builder instance for chaining
         */
        public CircuitBreakerBuilder onHalfOpen(Consumer<CircuitBreakerEvent> listener) {
            if (listener == null) {
                throw new IllegalArgumentException("Listener cannot be null.");
            }
            this.onHalfOpenListeners.add(listener);
            return this;
        }

        /**
         * Builds the CircuitBreaker instance with the configured properties.
         *
         * @return a new CircuitBreaker instance
         * @throws IllegalStateException if the failureThreshold or resetTimeout have not been properly set
         */
        public CircuitBreaker build() {
            if (failureThreshold <= 0) {
                throw new IllegalStateException("failureThreshold must be set and greater than zero.");
            }
            if (resetTimeout <= 0) {
                throw new IllegalStateException("resetTimeout must be set and greater than zero.");
            }
            return new CircuitBreaker(failureThreshold, resetTimeout, handledExceptions,
                    onOpenListeners, onCloseListeners, onHalfOpenListeners);
        }
    }
}
