
# Guardian

Guardian is a lightweight Java library that provides robust fault-tolerance mechanisms such as retries, circuit breakers, and fallback strategies. With its fluent API, Guardian makes it easy to implement reliable and resilient operations in your Java applications.

## Features

- **Retry Policy**: Automatically retry operations when transient failures occur. Configure maximum retries, base delay, and backoff strategies (simple or exponential).
- **Circuit Breaker**: Prevents cascading failures by halting operations when a specified number of failures occur within a given period.
- **Fallback Execution**: Specify fallback logic to gracefully handle failures after all retry attempts have been exhausted.
- **Abort Conditions**: Define conditions or specific exceptions that will abort execution immediately.
- **Fluent API**: Easily chain configurations to build expressive and readable fault-tolerant code.
- **Thread Safe**: Overall usage is thread-safe, and you can reuse `RetryPolicy` and `CircuitBreaker` (after cloning it using `cloneWithFreshState()`) instances.

## Getting Started

### Prerequisites

- Java 8 or higher

### Installation

Clone the repository:

```bash
git clone https://github.com/freakynit/guardian.git
```

Build the project:

```bash
# Using maven
mvn clean install

# Using gradle
gradle build
```

Include the built jar as a dependency in your project:

```xml
<!-- maven -->
<dependency>
    <groupId>com.freakynit</groupId>
    <artifactId>guardian</artifactId>
    <version>1.0.1</version>
</dependency>
```

```groovy
// gradle
dependencies {
    implementation 'com.freakynit:guardian:1.0.1'
}
```

## Usage

Guardian is designed to wrap potentially failing operations with retry, circuit breaker, and fallback logic. Here's a simple example:

```java

import com.freakynit.guardian.CircuitBreaker;
import com.freakynit.guardian.Guardian;
import com.freakynit.guardian.RetryPolicy;
import com.freakynit.guardian.exceptions.*;

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
        } catch (GuardianExecutionException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
```

## Documentation
1. Refer to [USAGE](USAGE.md) for detailed usage guide.
2. You can also generate the API documentation by running following. The docs will be available at path: `/target/docs/apidocs`:
```bash
mvn clean javadoc:javadoc
```

## Contributing

Contributions are welcome! If you find any issues or have ideas for enhancements, please open an issue or submit a pull request.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
