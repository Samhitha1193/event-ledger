# Event Ledger

Two independent Spring Boot 3 / Java 21 microservices that together form a financial event ledger.

---

## Architecture

```
Client
  │
  │  POST /events          GET /events/{id}
  │  GET  /events?account  GET /accounts/{id}/balance
  ▼
┌─────────────────────────────────────────────────────┐
│                    gateway-api :8080                 │
│                                                      │
│  EventController  ──► EventService                  │
│  BalanceController                                   │
│                    ├── EventRepository (H2)         │
│                    │   Stores events + fingerprints │
│                    │                                 │
│                    └── AccountServiceClient          │
│                        Retry + CircuitBreaker        │
│                        + TimeLimiter                 │
└────────────────────────┬────────────────────────────┘
                         │ POST /accounts/{id}/transactions
                         │ GET  /accounts/{id}/balance
                         │ (HTTP + X-Trace-Id header)
                         ▼
┌─────────────────────────────────────────────────────┐
│                 account-service :8081                │
│                                                      │
│  TransactionController ──► TransactionService       │
│  AccountController     ──► AccountService           │
│                                                      │
│  AccountRepository  (H2)  ── auto-created on first  │
│  TransactionRepository     ── transaction per acct  │
└─────────────────────────────────────────────────────┘
```

### Service responsibilities

**gateway-api** is the public-facing API. It:
- Validates inbound event requests (field presence, positive amount, known type)
- Detects duplicate submissions via a SHA-256 fingerprint of the payload; returns `200 OK` for identical re-submissions and `409 Conflict` for the same `eventId` with a different payload
- Calls the Account Service to apply the financial transaction *before* writing to its own database, so the gateway never records a phantom event
- Proxies `GET /accounts/{id}/balance` to the Account Service
- Propagates the W3C `traceparent` header from the inbound request (or generates one via Micrometer Tracing) to every outbound call to the Account Service

**account-service** is the balance ledger. It:
- Auto-creates an account on the first transaction (no pre-registration required)
- Enforces idempotency on `eventId` — re-posting the same event ID is a no-op
- Rejects currency mismatches on an existing account (422)
- Computes balances dynamically (`SUM(credits) − SUM(debits)`) so out-of-order event arrival never corrupts the balance
- Enforces that a DEBIT cannot exceed the current balance (422)

### Data flow for `POST /events`

1. Gateway validates the request body (400 on failure)
2. Gateway computes a SHA-256 fingerprint and looks up `eventId` in its own H2 database
3. If found and fingerprint matches → `200 OK` (idempotent, no downstream call)
4. If found and fingerprint differs → `409 Conflict`
5. If not found → Gateway calls `POST /accounts/{accountId}/transactions` on the Account Service
6. On account-service success → Gateway persists the event and returns `201 Created`
7. On account-service failure → Gateway returns `503 Service Unavailable` and saves nothing

---

## Prerequisites

| Dependency | Required for | Minimum version |
|---|---|---|
| Java (JDK) | Running / testing locally | 21 |
| Maven | Building / testing locally | 3.9 |
| Docker + Docker Compose | Container-based startup | Docker 24 / Compose v2 |

Install Java 21 (Temurin):
```bash
# macOS
brew install --cask temurin@21

# or via SDKMAN
sdk install java 21.0.3-tem
```

Install Maven:
```bash
# macOS
brew install maven
```

---

## Start with Docker Compose

```bash
docker compose up --build
```

This builds both images from source and starts them in order (account-service first, then gateway-api once the account-service health check passes).

| Service | URL |
|---|---|
| gateway-api | http://localhost:8080 |
| account-service | http://localhost:8081 |

Health checks:
```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

Stop everything:
```bash
docker compose down
```

---

## Start locally (without Docker)

Open two terminals.

**Terminal 1 — account-service (start first)**
```bash
cd account-service
mvn spring-boot:run
# Listening on http://localhost:8081
```

**Terminal 2 — gateway-api**
```bash
cd gateway-api
mvn spring-boot:run
# Listening on http://localhost:8080
```

Both services use in-memory H2 databases that reset on restart.

---

## Run the tests

Each service has a self-contained test suite that needs no running infrastructure.

```bash
# Account Service (25 tests)
cd account-service
mvn test

# Gateway API (38 tests)
cd gateway-api
mvn test
```

### What the tests cover

**account-service** (`TransactionControllerTest`)
- Auto-creates an account on the first transaction
- Idempotency: re-posting the same `eventId` returns `200 OK`
- Balance computed correctly after credits and debits
- `GET /balance` on an unknown account returns `404`
- Currency mismatch returns `422`
- Out-of-order arrival: DEBIT before CREDIT still produces the correct balance

**gateway-api** — unit tests (`EventControllerTest`)
- New event → `201 Created`
- Identical re-submission → `200 OK` (idempotent)
- Same `eventId`, different payload → `409 Conflict`
- `GET /events/{id}` returns `200` / `404`
- Event list sorted by `eventTimestamp` ascending, regardless of submission order
- Missing required fields → `400` with per-field error map
- Negative amount → `400`; zero amount → `400`
- Account Service down → `503`
- `metadata` object field round-trips as real JSON (not double-encoded)
- Balance proxy returns account-service response; returns `503` when down

**gateway-api** — integration tests (`GatewayIntegrationTest`, WireMock)

Real HTTP gateway server → real `AccountServiceClient` → WireMock standing in as the account-service. No mocks in the Spring context.

| Test | What it proves |
|---|---|
| Full flow | Event is persisted and a real HTTP call reaches the account-service |
| Idempotency | Duplicate submission hits the DB, account-service called exactly once |
| Trace (propagated) | Incoming W3C `traceparent` is forwarded to account-service with the same trace ID |
| Trace (generated) | When no `traceparent` is supplied, gateway generates one and forwards it |
| Resiliency | Account-service `500` → gateway `503` |
| Circuit breaker | Five consecutive failures open the breaker; the sixth call is rejected immediately without reaching WireMock |
| TimeLimiter | A 2 s account-service delay exceeds the 1 s timeout → `503` |
| Retry (transient) | Account-service fails twice then succeeds; WireMock is called exactly 3 times and the request returns `201` |
| Balance proxy | `GET /accounts/{id}/balance` is proxied end-to-end |
| Balance fallback | Account-service `500` on a balance request → `503` |

---

## API quick reference

### Submit an event

```bash
curl -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -d '{
    "eventId":        "evt-001",
    "accountId":      "acct-123",
    "type":           "CREDIT",
    "amount":         500.00,
    "currency":       "USD",
    "eventTimestamp": "2026-06-23T10:00:00Z",
    "metadata":       {"source": "web", "region": "us-east-1"}
  }'
```

Response `201 Created`:
```json
{
  "eventId":           "evt-001",
  "accountId":         "acct-123",
  "type":              "CREDIT",
  "amount":            500.00,
  "currency":          "USD",
  "eventTimestamp":    "2026-06-23T10:00:00Z",
  "metadata":          {"source": "web", "region": "us-east-1"},
  "payloadFingerprint":"a3f9..."
}
```

### Get balance

```bash
curl http://localhost:8080/accounts/acct-123/balance
```

```json
{"accountId": "acct-123", "currency": "USD", "balance": 500.00}
```

---

## Resiliency pattern

Every outbound call from the gateway to the account-service passes through three Resilience4j patterns applied as nested AOP aspects on `AccountServiceClient`. The return type is `CompletableFuture<T>`, which is required for `@TimeLimiter` to function.

### Aspect order

The aspects are applied in a deliberate order — outermost to innermost:

```
@Retry  →  @CircuitBreaker  →  @TimeLimiter
```

`@Retry` is outermost so it can observe the raw `HttpServerErrorException` thrown by the HTTP layer. If `@TimeLimiter` (which attaches a `CompletableFuture` fallback) were outer, it would convert the exception type before `@Retry` could inspect it, silently defeating the retry logic.

### Retry with exponential backoff and jitter

Transient 5xx errors are retried up to two additional times (three total attempts). The wait between attempts grows exponentially and has ±50 % random jitter added so that a fleet of clients does not all retry in lock-step against a recovering service.

```
Attempt 1 fails → wait ~500 ms (±50 % jitter)
Attempt 2 fails → wait ~1 s   (±50 % jitter)
Attempt 3 fails → fallback: 503 Service Unavailable
```

Retry only fires for `HttpServerErrorException` (5xx responses). Client errors (4xx) and `TimeoutException` are not retried — retrying a timeout would only delay the caller further.

### Why a circuit breaker?

Without it, each request to a degraded account-service blocks a Tomcat thread for up to 5 seconds while waiting for the timeout. Under load, thread exhaustion cascades: the gateway itself becomes unresponsive even though reads from its own database are completely unaffected. The circuit breaker short-circuits with a `503` in microseconds once the failure rate exceeds the threshold, keeping the gateway healthy and shedding load from a service that is already struggling.

### Why a time limiter?

The time limiter enforces a hard per-call deadline (5 s). Without it, a hung account-service could hold a thread indefinitely. It works by cancelling the `CompletableFuture` returned by the HTTP call if it has not completed within the deadline.

### Configuration

**Circuit Breaker**

| Parameter | Value | Effect |
|---|---|---|
| `sliding-window-size` | 10 calls | Failure rate evaluated over the last 10 calls |
| `failure-rate-threshold` | 50 % | Circuit opens when ≥ 50 % of recent calls fail |
| `wait-duration-in-open-state` | 30 s | Breaker stays OPEN for 30 s, then moves to HALF-OPEN |
| `permitted-calls-in-half-open` | 3 | Three probe calls to test recovery before closing |

**Time Limiter**

| Parameter | Value | Effect |
|---|---|---|
| `timeout-duration` | 5 s | Hard per-call deadline; exceeded → `TimeoutException` |
| `cancel-running-future` | true | Interrupts the ForkJoinPool thread when the deadline fires |

**Retry**

| Parameter | Value | Effect |
|---|---|---|
| `max-attempts` | 3 | Up to 3 total attempts (1 original + 2 retries) |
| `wait-duration` | 500 ms | Base wait before the first retry |
| `exponential-backoff-multiplier` | 2 | Wait doubles on each subsequent retry |
| `exponential-max-wait-duration` | 10 s | Upper cap on the computed wait |
| `randomized-wait-factor` | 0.5 | ±50 % jitter on each computed wait |
| `retry-exceptions` | `HttpServerErrorException` | Only HTTP 5xx errors trigger a retry |

### State transitions (Circuit Breaker)

```
  Failure rate > 50%
CLOSED ──────────────► OPEN ──── 30 s ────► HALF-OPEN
  ▲                                              │
  │   3 probes succeed                           │
  └──────────────────────────────────────────────┘
          (3 probes fail → back to OPEN)
```

### Failure responses

| Condition | Cause | Gateway response |
|---|---|---|
| Retries exhausted | `HttpServerErrorException` (5xx) | `503` — Account Service unavailable |
| Circuit OPEN | `CallNotPermittedException` | `503` — circuit breaker is open |
| Call > 5 s | `TimeoutException` | `503` — call timed out |
| Network error | `RestClientException` | `503` — Account Service unavailable |

`GET /events/{id}` and `GET /events?account=…` read only from the gateway's own H2 database and are completely unaffected by account-service failures.

---

## Observability

Both services emit **ECS-formatted JSON logs** (`logging.structured.format.console=ecs`). Every log line includes the `traceId` from MDC so all log entries for a single request can be correlated across both services.

### Custom metrics

**gateway-api** records:
- `events.submitted` counter (tagged by `type`) — incremented on every `POST /events` call
- `account.service.call.duration` timer — end-to-end latency of each `AccountServiceClient` call

**account-service** records:
- `transactions.processed` counter (tagged by `type` and `outcome`: `new` or `duplicate`)

Resilience4j auto-publishes its own Micrometer metrics alongside these:
- `resilience4j.circuitbreaker.state` — current CB state (0 = CLOSED, 1 = OPEN, 2 = HALF-OPEN)
- `resilience4j.retry.calls` — retry attempt counts tagged by `kind` (`successful_with_retry`, `failed_with_retry`, `failed_without_retry`)
- `resilience4j.timelimiter.calls` — timeout outcomes

### Actuator endpoints

Actuator endpoints are exposed under `/actuator` on both services:

```
GET /actuator/health      # liveness + Resilience4j CB state
GET /actuator/metrics     # Micrometer metric names
GET /actuator/prometheus  # Prometheus text-format scrape endpoint
GET /actuator/info
```

A dedicated `/health` endpoint (outside `/actuator`) is also available at the root for Docker healthchecks and load-balancer probes:

```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

### Prometheus scraping

Both services expose a Prometheus-compatible scrape endpoint at `/actuator/prometheus`. To verify locally:

```bash
curl http://localhost:8080/actuator/prometheus | grep -E "events_submitted|resilience4j_retry"
curl http://localhost:8081/actuator/prometheus | grep transactions_processed
```

Example output:
```
# HELP events_submitted_total
# TYPE events_submitted_total counter
events_submitted_total{type="CREDIT"} 3.0

# HELP resilience4j_retry_calls_total
# TYPE resilience4j_retry_calls_total counter
resilience4j_retry_calls_total{kind="successful_with_retry",name="accountService"} 1.0
resilience4j_retry_calls_total{kind="failed_without_retry",name="accountService"} 2.0
```

To scrape from a Prometheus server, add a job to `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: gateway-api
    static_configs:
      - targets: ["localhost:8080"]
    metrics_path: /actuator/prometheus

  - job_name: account-service
    static_configs:
      - targets: ["localhost:8081"]
    metrics_path: /actuator/prometheus
```

---

## API contracts (OpenAPI)

Both services expose a machine-readable OpenAPI 3 spec generated automatically by springdoc-openapi.

| Service | JSON spec | Swagger UI |
|---|---|---|
| gateway-api | http://localhost:8080/v3/api-docs | http://localhost:8080/swagger-ui.html |
| account-service | http://localhost:8081/v3/api-docs | http://localhost:8081/swagger-ui.html |

The account-service spec documents the internal contract consumed by the gateway (`POST /accounts/{id}/transactions`, `GET /accounts/{id}/balance`, `GET /accounts/{id}`). The gateway spec documents the public-facing API.
