package com.freakynit.guardian;

import com.freakynit.guardian.exceptions.GuardianExecutionException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.Callable;

public class Main {
    public static void main(String[] args) {
        try {
            String output = Guardian.builder()
                    .withRetryPolicy(new RetryPolicy(3, 500, new HashSet<>(Arrays.asList(IOException.class)))
                            .withBackoffStrategy(RetryPolicy.BackoffStrategy.EXPONENTIAL, 2.0)
                            .onFailedAttempt(ctx -> System.out.println("Failed attempt #" + ctx.getAttemptNumber()))
                            .onRetry(ctx -> System.out.println("Retrying after " + ctx.getDelay() + "ms")))
                    .withCircuitBreaker(CircuitBreaker.builder()
                            .failureThreshold(3)
                            .resetTimeout(2000)
                            .handle(ConnectException.class)
                            .onOpen(e -> System.out.println("The circuit breaker was opened"))
                            .onHalfOpen(e -> System.out.println("The circuit breaker was half-opened"))
                            .onClose(e -> System.out.println("The circuit breaker was closed"))
                            .build())
                    .withFallback(() -> {
                        System.out.println("Executing fallback...");
                        return "Fallback Result";
                    })
                    .abortWhen(false)
                    .abortOn(NoRouteToHostException.class)
                    .abortIf(result -> result == Boolean.TRUE)
                    .get(new Callable<String>() {
                        private int count = 0;
                        @Override
                        public String call() throws Exception {
                            count++;
                            System.out.println("Attempt " + count + ": Executing risky operation...");
                            // Simulate failure for the first few attempts.
                            if (count < 3) {
                                throw new IOException("Simulated I/O failure.");
                            }
                            return "Successful Result on attempt " + count;
                        }
                    });

            System.out.println("Operation output: " + output);
        } catch (GuardianExecutionException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}