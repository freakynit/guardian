## 1. Basic Usage

For simple operations, you can build a Guardian instance and execute an operation with minimal configuration. For example:

```java
String result = Guardian.builder()
    .get(() -> "Success");
```

This executes the supplied lambda and returns its result (see `testSuccessfulOperation` in [GuardianTest.java](src/test/java/com/freakynit/guardian/GuardianTest.java)).

## 2. Configuring Retry Policies

Guardian allows you to configure retries for operations that might transiently fail. Create a `RetryPolicy` by specifying:
- **Max retries:** The number of retry attempts.
- **Base delay:** The initial delay (in milliseconds) between retries.
- **Handled exceptions:** A set of exception types that should trigger a retry.

You can also configure a backoff strategy (for example, exponential backoff) and register callbacks to observe attempts and delays:

```java
RetryPolicy retryPolicy = new RetryPolicy(5, 500, 
        new HashSet<>(Arrays.asList(IOException.class)))
    .withBackoffStrategy(RetryPolicy.BackoffStrategy.EXPONENTIAL, 2.0)
    .onFailedAttempt(ctx -> System.out.println("Failed attempt: " + ctx.getAttemptNumber()))
    .onRetry(ctx -> System.out.println("Retrying in " + ctx.getDelay() + "ms"));

String result = Guardian.builder()
    .withRetryPolicy(retryPolicy)
    .get(() -> {
        // Your operation code here.
        // For example, throw new IOException("Temporary failure") for testing.
    });
```

In the test case `testRetryThenSuccess`, an operation fails for the first two attempts and then succeeds, and the callbacks record the attempt numbers and delays (see [GuardianTest.java](src/test/java/com/freakynit/guardian/GuardianTest.java)).

## 3. Fallback Execution

If an operation fails even after the configured retries, you can specify a fallback to be executed. This is useful to provide a graceful degradation of service.

```java
String fallbackResult = "Fallback executed";

String result = Guardian.builder()
    .withRetryPolicy(new RetryPolicy(2, 100, 
            new HashSet<>(Arrays.asList(IOException.class))))
    .withFallback(() -> fallbackResult)
    .get(() -> {
        // Simulate failure
        throw new IOException("Simulated failure");
    });
```

Here, after exhausting the allowed attempts (initial plus two retries), the fallback lambda is executed (as shown in `testFallbackExecution` in [GuardianTest.java](src/test/java/com/freakynit/guardian/GuardianTest.java)).

## 4. Circuit Breaker Integration

To prevent an operation from repeatedly being attempted when a downstream service is known to be failing, use a circuit breaker. Configure a circuit breaker with:
- **Failure threshold:** Number of consecutive failures before opening the circuit.
- **Reset timeout:** Time (in milliseconds) to wait before attempting to close the circuit again.
- **Handled exception types:** Which exceptions should contribute to the failure count.

```java
CircuitBreaker circuitBreaker = CircuitBreaker.builder()
    .failureThreshold(2)
    .resetTimeout(100)  // In milliseconds
    .handle(ConnectException.class)
    .build();

String result = Guardian.builder()
    .withRetryPolicy(new RetryPolicy(1, 100, 
            new HashSet<>(Arrays.asList(IOException.class))))
    .withCircuitBreaker(circuitBreaker)
    .get(() -> {
        // Operation that might trigger a circuit breaker (e.g., throw new ConnectException("Failure"))
    });
```

If the circuit breaker is open (as demonstrated in `testCircuitBreakerOpenAndReset` in [GuardianTest.java](src/test/java/com/freakynit/guardian/GuardianTest.java)), any call will be rejected immediately with a `GuardianExecutionException` stating that the circuit breaker is open.

## 5. Aborting Execution

Sometimes you need to abort an operation early:
- **Abort based on result:** Use `abortIf(Predicate)` to abort if the result meets a condition.
- **Abort on specific exception:** Use `abortOn(ExceptionType.class)` to immediately abort when a specified exception is thrown.
- **Abort based on flag:** Use `abortWhen(boolean)` to conditionally abort on a boolean condition.

Examples:

```java
// Abort if the result equals "ABORT"
Guardian.builder()
    .abortIf(result -> result != null && result.equals("ABORT"))
    .get(() -> "ABORT");

// Abort if a specific exception (e.g., NoRouteToHostException) is thrown
Guardian.builder()
    .abortOn(NoRouteToHostException.class)
    .get(() -> {
        throw new NoRouteToHostException("No route available");
    });

// Unconditionally abort by setting abortWhen to true
Guardian.builder()
    .abortWhen(true)
    .get(() -> true);
```

These approaches will throw a `GuardianAbortedExecutionException`, as verified in `testAbortIfCondition`, `testAbortOnException`, and `testAbortWhenCondition` (see [GuardianTest.java](src/test/java/com/freakynit/guardian/GuardianTest.java)).

## 6. Handling Non-Retryable Exceptions

If an operation throws an exception that is not specified in the retry policy’s handled exceptions, the operation will not be retried. For example, if the policy only handles `IOException` but a `NullPointerException` is thrown, the operation is attempted only once (as shown in `testNoRetryForNonMatchingException`).

## 7. Parameter Validation and Error Handling

The Guardian library performs validations to ensure robust configuration:
- **RetryPolicy:** Negative `maxRetries` or negative delay values throw an `IllegalArgumentException` (`testInvalidRetryPolicyParameters`).
- **CircuitBreaker:** A failure threshold or reset timeout that is zero or negative throws an exception (`testInvalidCircuitBreakerParameters`).
- **Null Values:** Passing null for fallback, circuit breaker, abort conditions, or backoff strategy will result in `IllegalArgumentException` (see tests such as `testNullFallback`, `testNullCircuitBreaker`, `testNullAbortWhenCondition`, and `testRetryPolicyNullBackoffStrategy`).
- **Fallback Failures:** If the fallback itself fails (e.g., throws an exception), a `GuardianFallbackExecutionException` is raised (`testFallbackExecutionFailure`).

## 8. Runnable Variant

The Guardian library also exposes `run(Runnable runnable)` method for instances where your executable code does not return any value.

Usage is same as for `get()`, except that now `abortWhen` and `abortIf` conditions does not make sense (since no value is being returned).

Check out `testSuccessfulRunExecution` and `testRunExceptionHandling` test cases in [GuardianTest.java](src/test/java/com/freakynit/guardian/GuardianTest.java).

## Conclusion

By combining these features such as retry policies with backoff, fallback executions, circuit breaker patterns, and abort conditions, Guardian enables you to create robust and fault-tolerant operations with a clean, fluent API.

Feel free to adapt these examples to your needs and consult the test cases ([GuardianTest.java](src/test/java/com/freakynit/guardian/GuardianTest.java)) for further details on the expected behavior and error handling of the various components.