# event-ledger

Two independent Spring Boot 3 / Java 21 services sharing a single repository.

## Services

| Service           | Port | Description              |
|-------------------|------|--------------------------|
| `gateway-api`     | 8080 | Inbound event API        |
| `account-service` | 8081 | Account & balance ledger |

## Endpoints

### gateway-api (port 8080)

| Method | Path                       | Description                        |
|--------|----------------------------|------------------------------------|
| POST   | `/events`                  | Submit a new event                 |
| GET    | `/events/{id}`             | Fetch a single event by ID         |
| GET    | `/events?account={id}`     | List all events for an account     |
| GET    | `/health`                  | Health check                       |

### account-service (port 8081)

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| POST   | `/accounts/{id}/transactions`   | Post a transaction to an account   |
| GET    | `/accounts/{id}/balance`        | Get current balance for an account |
| GET    | `/accounts/{id}`                | Get account details                |
| GET    | `/health`                       | Health check                       |

## Internal Event Payload

When the gateway forwards an event to the account service it sends the
following JSON. All monetary values are `BigDecimal` — never `double` or
`float`.

```json
{
  "eventId":        "a1b2c3d4-...",
  "accountId":      "acc-001",
  "type":           "CREDIT",
  "amount":         "150.00",
  "currency":       "USD",
  "eventTimestamp": "2026-06-23T10:15:30Z"
}
```

| Field            | Type           | Notes                                    |
|------------------|----------------|------------------------------------------|
| `eventId`        | `String`       | UUID of the originating event            |
| `accountId`      | `String`       | Target account identifier                |
| `type`           | `String`       | `CREDIT` or `DEBIT`                      |
| `amount`         | `BigDecimal`   | Serialized as a string to preserve scale |
| `currency`       | `String`       | ISO 4217 code (e.g. `USD`)               |
| `eventTimestamp` | `String`       | ISO 8601 UTC timestamp                   |

> **Money rule:** use `java.math.BigDecimal` for every monetary field in both
> services. Never use `double` or `float` — they cannot represent decimal
> fractions exactly and will silently corrupt financial calculations.

## Architecture Decisions

### Circuit breaker and per-call timeout on Account Service calls

Every outbound call from the Gateway to the Account Service is wrapped with a
Resilience4j **CircuitBreaker** and a **TimeLimiter**. The decoration order is:
CircuitBreaker (outer) → TimeLimiter (inner) → HTTP call.

**Circuit Breaker — `accountService` instance**

| Parameter | Value | Meaning |
|---|---|---|
| `sliding-window-size` | 10 | Evaluate the last 10 calls |
| `failure-rate-threshold` | 50 % | Open after ≥ 50 % failures |
| `wait-duration-in-open-state` | 30 s | Stay open for 30 s, then move to HALF-OPEN |
| `permitted-calls-in-half-open` | 3 | Allow 3 probe calls to test recovery |

**Time Limiter — `accountService` instance**

| Parameter | Value | Meaning |
|---|---|---|
| `timeout-duration` | 5 s | Hard deadline per call; exceeded → `TimeoutException` |
| `cancel-running-future` | true | Interrupt the thread when the deadline fires |

**Why a circuit breaker?**

Without it, every `POST /events` while the Account Service is down blocks a
thread for up to 5 s waiting for the timeout. Under load that exhausts the
thread pool and takes down the Gateway too. The circuit breaker short-circuits
immediately with a `CallNotPermittedException` the moment the breaker is OPEN,
so the Gateway stays responsive and returns `503` in microseconds rather than
stacking up blocked threads.

**Failure path**

| Condition | Exception | Response |
|---|---|---|
| Circuit is OPEN | `CallNotPermittedException` | 503 — circuit breaker is open |
| Call exceeds 5 s | `TimeoutException` | 503 — call timed out |
| Network / HTTP error | `RestClientException` | 503 — Account Service unavailable |

The `GET /events/{id}` and `GET /events?account={id}` endpoints read from the
Gateway's own database and are not affected by any of the above — they keep
working regardless of the Account Service state.

### POST /events — account-first write order

When the Gateway receives a new event it must apply the financial transaction
to the Account Service **before** writing anything to its own database.

**Order of operations**

1. Validate the request (400 if invalid).
2. Look up `event_id` in the Gateway database to detect duplicates.
3. **Call `POST /accounts/{accountId}/transactions` on the Account Service.**
4. Only if that call returns 2xx: persist the event in the Gateway database and return 201.
5. If the Account Service call fails for any reason (network error, timeout, 4xx, 5xx): return **503 Service Unavailable** and save nothing.

**Why this order?**

The account balance is the source of truth. Writing the event locally first
and then failing to apply it to the Account Service would leave the Gateway
database with a record of a transaction that never actually changed any
balance — a phantom event. By calling the Account Service first we ensure
that the Gateway only records events that are known to have been applied.
The trade-off is that a crash between the Account Service success and the
Gateway write can produce the reverse problem (applied but unrecorded), but
this is recoverable via the duplicate-check on re-submission: the client
retries with the same `event_id`, the fingerprint matches, and the Gateway
returns 200 with the stored event.

## Requirements

- Java 21
- Maven 3.9+

## Build

Each service is built independently:

```bash
cd gateway-api   && mvn clean install
cd account-service && mvn clean install
```

## Run

**gateway-api**
```bash
cd gateway-api
mvn spring-boot:run
```

**account-service**
```bash
cd account-service
mvn spring-boot:run
```

Or run the packaged jars:
```bash
java -jar gateway-api/target/gateway-api-0.0.1-SNAPSHOT.jar
java -jar account-service/target/account-service-0.0.1-SNAPSHOT.jar
```
