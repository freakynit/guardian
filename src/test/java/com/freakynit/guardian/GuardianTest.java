package com.freakynit.guardian;

import com.freakynit.guardian.exceptions.GuardianAbortedExecutionException;
import com.freakynit.guardian.exceptions.GuardianExecutionException;
import com.freakynit.guardian.exceptions.GuardianFallbackExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class GuardianTest {

    @Test
    public void testSuccessfulOperation() throws GuardianExecutionException, InterruptedException {
        String expected = "Success";
        String result = Guardian.builder()
                .get(() -> expected);
        Assertions.assertEquals(expected, result);
    }

    @Test
    public void testRetryThenSuccess() throws GuardianExecutionException, InterruptedException {
        // Operation fails for the first two attempts, then succeeds.
        AtomicInteger counter = new AtomicInteger(0);
        List<Integer> failedAttempts = new ArrayList<>();
        List<Long> retryDelays = new ArrayList<>();

        RetryPolicy retryPolicy = new RetryPolicy(5, 500, new HashSet<>(Arrays.asList(IOException.class)))
                .withBackoffStrategy(RetryPolicy.BackoffStrategy.EXPONENTIAL, 2.0)
                .onFailedAttempt(ctx -> failedAttempts.add(ctx.getAttemptNumber()))
                .onRetry(ctx -> retryDelays.add(ctx.getDelay()));

        String result = Guardian.builder()
                .withRetryPolicy(retryPolicy)
                .get(() -> {
                    int attempt = counter.incrementAndGet();
                    if (attempt < 3) {
                        throw new IOException("Failure at attempt " + attempt);
                    }
                    return "Success on attempt " + attempt;
                });

        Assertions.assertEquals("Success on attempt 3", result);
        // Ensure two failures were recorded.
        Assertions.assertEquals(2, failedAttempts.size());
        // With exponential backoff, delays should be baseDelay and baseDelay * 2.
        Assertions.assertEquals(500L, (long) retryDelays.get(0));
        Assertions.assertEquals(1000L, (long) retryDelays.get(1));
    }

    @Test
    public void testFallbackExecution() throws GuardianExecutionException, InterruptedException {
        // Operation always fails and fallback should be executed.
        String fallbackResult = "Fallback";
        AtomicInteger counter = new AtomicInteger(0);

        String result = Guardian.builder()
                .withRetryPolicy(new RetryPolicy(2, 100, new HashSet<>(Arrays.asList(IOException.class))))
                .withFallback(() -> {
                    return fallbackResult;
                })
                .get(() -> {
                    counter.incrementAndGet();
                    throw new IOException("Simulated failure");
                });

        // The operation is attempted (initial + 2 retries) then fallback is executed.
        Assertions.assertEquals(fallbackResult, result);
        Assertions.assertEquals(3, counter.get());
    }

    @Test
    public void testExhaustedRetriesWithoutFallback() {
        // Operation always fails; without fallback, a GuardianExecutionException should be thrown.
        AtomicInteger counter = new AtomicInteger(0);
        GuardianExecutionException ex = Assertions.assertThrows(GuardianExecutionException.class, () -> {
            Guardian.builder()
                    .withRetryPolicy(new RetryPolicy(2, 100, new HashSet<>(Arrays.asList(IOException.class))))
                    .get(() -> {
                        counter.incrementAndGet();
                        throw new IOException("Persistent failure");
                    });
        });
        // Should attempt the operation (initial attempt + 2 retries = 3 attempts)
        Assertions.assertEquals(3, counter.get());
        Assertions.assertTrue(ex.getMessage().contains("Operation failed after"));
    }

    @Test
    public void testCircuitBreakerOpenAndReset() throws Exception {
        // Configure a circuit breaker that opens after 2 consecutive failures.
        AtomicInteger counter = new AtomicInteger(0);
        // Use a small reset timeout for testing purposes.
        CircuitBreaker circuitBreaker = CircuitBreaker.builder()
                .failureThreshold(2)
                .resetTimeout(100) // milliseconds
                .handle(ConnectException.class)
                .build();

        // Use a simple retry policy that allows one retry.
        RetryPolicy retryPolicy = new RetryPolicy(1, 100, new HashSet<>(Arrays.asList(IOException.class)));

        // First call: fails.
        try {
            Guardian.builder()
                    .withRetryPolicy(retryPolicy)
                    .withCircuitBreaker(circuitBreaker)
                    .get(() -> {
                        counter.incrementAndGet();
                        throw new ConnectException("Failure 1");
                    });
        } catch (GuardianExecutionException e) {
            // Expected failure.
        }

        // Second call: fails and should trigger the circuit breaker to open.
        try {
            Guardian.builder()
                    .withRetryPolicy(retryPolicy)
                    .withCircuitBreaker(circuitBreaker)
                    .get(() -> {
                        counter.incrementAndGet();
                        throw new ConnectException("Failure 2");
                    });
        } catch (GuardianExecutionException e) {
            // Expected failure.
        }

        // Third call: circuit breaker is open so the call should be rejected immediately.
        GuardianExecutionException ex = Assertions.assertThrows(GuardianExecutionException.class, () -> {
            Guardian.builder()
                    .withRetryPolicy(retryPolicy)
                    .withCircuitBreaker(circuitBreaker)
                    .get(() -> {
                        counter.incrementAndGet();
                        return "Should not succeed";
                    });
        });
        Assertions.assertTrue(ex.getMessage().contains("Circuit breaker is open"));

        // Wait for reset timeout to expire.
        Thread.sleep(150);

        // Now the circuit breaker should allow a trial call (HALF_OPEN state) and then reset upon success.
        String result = Guardian.builder()
                .withRetryPolicy(retryPolicy)
                .withCircuitBreaker(circuitBreaker)
                .get(() -> "Recovered");
        Assertions.assertEquals("Recovered", result);
    }

    @Test
    public void testCircuitBreakerCloneWithFreshState() throws Exception {
        // Build sample circuit breaker
        CircuitBreaker circuitBreaker = CircuitBreaker.builder()
                .failureThreshold(2)
                .resetTimeout(1000)
                .handle(ConnectException.class)
                .build();

        // Execute to change internal state of the circuit breaker
        RetryPolicy retryPolicy = new RetryPolicy(3, 100, new HashSet<>(Arrays.asList(IOException.class)));
        try {
            Guardian.builder()
                    .withRetryPolicy(retryPolicy)
                    .withCircuitBreaker(circuitBreaker)
                    .get(() -> {
                        throw new ConnectException("Failure 1");
                    });
        } catch (GuardianExecutionException e) {
            // Expected failure.
        }

        // Clone with fresh state while keeping intact any registered handlers
        CircuitBreaker clonedCircuitBreaker = circuitBreaker.cloneWithFreshState();

        // Making private state fields accessible to assert their values for initial state of circuit breaker
        Class<? extends CircuitBreaker> circuitBreakerClass = clonedCircuitBreaker.getClass();

        // this should be "CLOSED"
        Field stateField = circuitBreakerClass.getDeclaredField("state");
        stateField.setAccessible(true);

        // this should be 0
        Field failureCountField = circuitBreakerClass.getDeclaredField("failureCount");
        failureCountField.setAccessible(true);

        // this should be 0
        Field lastFailureTimeField = circuitBreakerClass.getDeclaredField("lastFailureTime");
        lastFailureTimeField.setAccessible(true);

        // this should be 1
        Field handledExceptionsField = circuitBreakerClass.getDeclaredField("handledExceptions");
        handledExceptionsField.setAccessible(true);

        // this should be 0
        Field onOpenListenersField = circuitBreakerClass.getDeclaredField("onOpenListeners");
        onOpenListenersField.setAccessible(true);

        // Assert internal state conditions
        Assertions.assertAll(
                () -> Assertions.assertEquals(stateField.get(clonedCircuitBreaker).toString(), "CLOSED"),
                () -> Assertions.assertEquals(failureCountField.get(clonedCircuitBreaker), 0),
                () -> Assertions.assertEquals(lastFailureTimeField.get(clonedCircuitBreaker), 0l),
                () -> Assertions.assertEquals(((Set)handledExceptionsField.get(clonedCircuitBreaker)).size(), 1),
                () -> Assertions.assertEquals(((List)onOpenListenersField.get(clonedCircuitBreaker)).size(), 0)
        );
    }

    @Test
    public void testAbortIfCondition() {
        // The operation returns a value that meets the abortIf predicate.
        GuardianAbortedExecutionException ex = Assertions.assertThrows(GuardianAbortedExecutionException.class, () -> {
            Guardian.builder()
                    .abortIf(result -> result != null && result.equals("ABORT"))
                    .get(() -> "ABORT");
        });
        Assertions.assertTrue(ex.getMessage().contains("Aborted due to abortIf condition"));
    }

    @Test
    public void testAbortOnException() {
        // The operation throws an exception that is configured via abortOn.
        GuardianAbortedExecutionException ex = Assertions.assertThrows(GuardianAbortedExecutionException.class, () -> {
            Guardian.builder()
                    .abortOn(NoRouteToHostException.class)
                    .get(() -> {
                        throw new NoRouteToHostException("No route available");
                    });
        });
        Assertions.assertTrue(ex.getMessage().contains("Aborted due to abortOn exception"));
    }

    @Test
    public void testAbortWhenCondition() {
        // With abortWhen set to true, the operation aborts on the first encountered exception.
        GuardianAbortedExecutionException ex = Assertions.assertThrows(GuardianAbortedExecutionException.class, () -> {
            Guardian.builder()
                    .abortWhen(true)
                    .get(() -> true);
        });
        Assertions.assertTrue(ex.getMessage().contains("Aborted due to abortWhen condition"));
    }

    @Test
    public void testNoRetryForNonMatchingException() {
        // Retry policy is configured to retry only on IOException; a NullPointerException should not be retried.
        AtomicInteger counter = new AtomicInteger(0);
        GuardianExecutionException ex = Assertions.assertThrows(GuardianExecutionException.class, () -> {
            Guardian.builder()
                    .withRetryPolicy(new RetryPolicy(3, 5, new HashSet<>(Arrays.asList(IOException.class))))
                    .get(() -> {
                        counter.incrementAndGet();
                        throw new NullPointerException("NPE encountered");
                    });
        });
        // The operation should be attempted only once.
        Assertions.assertEquals(1, counter.get());
    }

    @Test
    public void testInvalidRetryPolicyParameters() {
        // Negative maxRetries should throw IllegalArgumentException.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            new RetryPolicy(-1, 100, null);
        });
        // Negative delay should throw IllegalArgumentException.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            new RetryPolicy(3, -10, null);
        });
    }

    @Test
    public void testInvalidCircuitBreakerParameters() {
        // A failure threshold of 0 or less should throw an exception.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            CircuitBreaker.builder().failureThreshold(0);
        });
        // A reset timeout of 0 or less should throw an exception.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            CircuitBreaker.builder().resetTimeout(0);
        });
    }

    // Additional tests for error conditions

    @Test
    public void testNullFallback() {
        // withFallback should throw IllegalArgumentException if fallback is null.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            Guardian.builder().withFallback(null);
        });
    }

    @Test
    public void testNullCircuitBreaker() {
        // withCircuitBreaker should throw IllegalArgumentException if circuitBreaker is null.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            Guardian.builder().withCircuitBreaker(null);
        });
    }

    @Test
    public void testNullAbortWhenCondition() {
        // abortWhen should throw IllegalArgumentException if passed null.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            Guardian.builder().abortWhen(null);
        });
    }

    @Test
    public void testRetryPolicyNullBackoffStrategy() {
        // withBackoffStrategy should throw IllegalArgumentException if strategy is null.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            new RetryPolicy(3, 10, new HashSet<>(Arrays.asList(IOException.class)))
                    .withBackoffStrategy(null, 2.0);
        });
    }

    @Test
    public void testRetryPolicyInvalidMultiplier() {
        // withBackoffStrategy should throw IllegalArgumentException if multiplier is less than 1.0.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            new RetryPolicy(3, 10, new HashSet<>(Arrays.asList(IOException.class)))
                    .withBackoffStrategy(RetryPolicy.BackoffStrategy.SIMPLE, 0.5);
        });
    }

    @Test
    public void testCircuitBreakerBuilderNullHandle() {
        // handle() should throw IllegalArgumentException if exceptionClass is null.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            CircuitBreaker.builder().handle(null);
        });
    }

    @Test
    public void testCircuitBreakerBuilderNullOnOpen() {
        // onOpen() should throw IllegalArgumentException if listener is null.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            CircuitBreaker.builder().onOpen(null);
        });
    }

    @Test
    public void testCircuitBreakerBuilderNullOnClose() {
        // onClose() should throw IllegalArgumentException if listener is null.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            CircuitBreaker.builder().onClose(null);
        });
    }

    @Test
    public void testCircuitBreakerBuilderNullOnHalfOpen() {
        // onHalfOpen() should throw IllegalArgumentException if listener is null.
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            CircuitBreaker.builder().onHalfOpen(null);
        });
    }

    @Test
    public void testFallbackExecutionFailure() {
        // If fallback execution itself fails, GuardianFallbackExecutionException should be thrown.
        Assertions.assertThrows(GuardianFallbackExecutionException.class, () -> {
            Guardian.builder()
                    .withRetryPolicy(new RetryPolicy(1, 100, new HashSet<>(Arrays.asList(IOException.class))))
                    .withFallback(() -> { throw new RuntimeException("Fallback failed"); })
                    .get(() -> {
                        throw new IOException("Operation failure");
                    });
        });
    }

    @Test
    public void testSuccessfulRunExecution() throws GuardianExecutionException, InterruptedException {
        AtomicBoolean executed = new AtomicBoolean(false);
        Guardian.builder().run(() -> executed.set(true));
        Assertions.assertTrue(executed.get(), "The runnable should have been executed successfully.");
    }

    @Test
    public void testRunExceptionHandling() {
        RuntimeException failure = new RuntimeException("Runnable failed");
        GuardianExecutionException ex = Assertions.assertThrows(GuardianExecutionException.class, () -> {
            Guardian.builder().run(() -> { throw failure; });
        });
        Assertions.assertNotNull(ex.getCause(), "The cause of the GuardianExecutionException should not be null.");
        Assertions.assertEquals(failure, ex.getCause(), "The underlying exception should match the one thrown by the runnable.");
    }
}
