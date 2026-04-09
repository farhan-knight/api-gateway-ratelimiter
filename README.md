# API Gateway Rate Limiter

A rate limiting gateway built with Spring Cloud Gateway and Redis. Sits in front of your backend services and throttles incoming requests per client IP using configurable algorithms.

---

## What It Does

- Intercepts all requests hitting `/api/**`
- Identifies clients by IP address
- Checks against the configured rate limit
- Forwards allowed requests to the backend
- Returns `429 Too Many Requests` when limit is exceeded
- Adds `X-RateLimit-*` headers to every response

---

## Supported Algorithms

| Algorithm | How It Works | Best For |
|-----------|-------------|----------|
| `token-bucket` | Bucket starts full. Each request uses one token. Tokens refill at a steady rate. | APIs that want to allow short bursts |
| `leaky-bucket` | Requests fill a bucket that drains at a constant rate. Full bucket means rejection. | Smoothing out traffic spikes |
| `fixed` | Counts requests in fixed time windows. Counter resets when window expires. | Simple use cases |
| `sliding` | Tracks each request timestamp in a Redis sorted set. Looks back exactly N milliseconds. | Precision without boundary burst issues |

---

## Architecture

```text
                         ┌─────────────────────┐
                         │      Client         │
                         │  (Browser/Postman)  │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │   Gateway (9090)    │
                         │                     │
                         │  ┌───────────────┐  │
                         │  │ RateLimiter   │  │
                         │  │ Filter        │  │
                         │  └───────┬───────┘  │
                         │          │          │
                         │    ┌─────▼─────┐    │
                         │    │RateLimiter│    │
                         │    │ Service   │    │
                         │    └─────┬─────┘    │
                         │          │          │
                         └──────────┼──────────┘
                                    │
                       ┌────────────┼────────────┐
                       │            │            │
                       ▼            ▼            ▼
              ┌──────────────┐ ┌────────┐ ┌──────────────┐
              │ Redis (6379) │ │ 429    │ │ Backend      │
              │ Rate Limit   │ │ if     │ │ (8080)       │
              │ State        │ │rejected│ │ /api/**      │
              └──────────────┘ └────────┘ └──────────────┘


```

## Project Structure

```text
src/main/java/com/example/ratelimiter/
│
├── config/
│   ├── RedisProperties.java            # Redis connection pool setup
│   ├── RateLimiterProperties.java      # maxRequests, windowSize, algorithm
│   └── GatewayConfig.java              # Gateway route definitions
│
├── service/
│   ├── RateLimiter.java                # Interface for all algorithms
│   ├── RateLimiterFactory.java         # Creates limiter based on config
│   ├── RateLimiterService.java         # Main service used by filter
│   ├── RedisTokenBucketService.java    # Token bucket implementation
│   ├── RedisLeakyBucketService.java    # Leaky bucket implementation
│   ├── RedisFixedWindowService.java    # Fixed window implementation
│   └── RedisSlidingWindowService.java  # Sliding window implementation
│
├── filter/
│   └── RateLimiterFilter.java          # Gateway filter that intercepts requests
│
└── controller/
    └── StatusController.java           # Health and rate limit status endpoints
```


# Prerequisites

- Java 21
- Maven
- Redis running on localhost:6379

# Setup
Step 1 - Start Redis

```bash
docker run -d --name redis -p 6379:6379 redis:7.2-alpine

```

Verify it is running:

```bash
redis-cli ping
```

Should return PONG.

Step 2 - Create a Backend Service
```
The gateway forwards requests to a backend at http://localhost:8080.
```
You need any Spring Boot application with endpoints under /api/**.

Here is a minimal example.

pom.xml dependencies:

```

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>

```
application.properties:

```

server.port=8080


```
Controller:

```
java
package com.example.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

    @GetMapping("/test")
    public ResponseEntity<Map<String, String>> test() {
        return ResponseEntity.ok(Map.of(
            "message", "Hello from backend",
            "timestamp", Instant.now().toString()
        ));
    }

    @PostMapping("/area")
    public ResponseEntity<Map<String, Object>> area(@RequestBody Map<String, Double> body) {
        double length = body.get("length");
        double width = body.get("width");
        return ResponseEntity.ok(Map.of(
            "length", length,
            "width", width,
            "area", length * width,
            "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
```
Run it:
```
bash
cd backend
mvn spring-boot:run
```
Verify:

```text
GET http://localhost:8080/api/test
```
Should return:
```
json
{
    "message": "Hello from backend",
    "timestamp": "2024-01-15T10:30:00Z"
}
```
Step 3 - Configure the Gateway
```
application.properties:

properties
server.port=9090

spring.main.web-application-type=reactive

spring.cloud.gateway.server.webflux.discovery.locator.enabled=false

spring.redis.host=localhost
spring.redis.port=6379
spring.redis.timeout=2000

rate-limiter.max-requests=10
rate-limiter.window-size-millis=10000
rate-limiter.algorithm=token-bucket
rate-limiter.api-server-url=http://localhost:8080

spring.jmx.enabled=false
```
To change algorithm, set rate-limiter.algorithm to one of:

- token-bucket
- leaky-bucket
- fixed
- sliding
   
Step 4 - Run the Gateway
```
bash
cd api-gateway-ratelimiter
mvn clean install
mvn spring-boot:run

```
You should see in the logs:

```
RateLimiterService started | algo=token-bucket | maxRequests=10 | windowSizeMillis=10000
```

# Testing
Through the Gateway (rate limited)
```
POST http://localhost:9090/api/area
```
Content-Type: application/json
```
{
    "length": 5,
    "width": 3
}
```
First 10 requests within 10 seconds return HTTP 200:

```
json
{
    "length": 5.0,
    "width": 3.0,
    "area": 15.0,
    "timestamp": "2024-01-15T10:30:00Z"
}
```
11th request returns HTTP 429:
```
json
{
    "error": "Rate limit exceeded",
    "clientId": "127.0.0.1",
    "algorithm": "token-bucket"
}
```
