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
│                        CircuitBreaker + TimeLimiter  │
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
- Propagates `X-Trace-Id` from the inbound request (or generates one) to every outbound call and back to the response

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
# Account Service (7 tests)
cd account-service
mvn test

# Gateway API (23 tests)
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
| Trace (custom) | `X-Trace-Id` sent by client is echoed in the response and forwarded to account-service |
| Trace (generated) | When no trace ID is supplied, gateway generates one and forwards it |
| Resiliency | Account-service `500` → gateway `503` |
| Circuit breaker | Five consecutive failures open the breaker; the sixth call is rejected immediately without reaching WireMock |
| TimeLimiter | A 2 s account-service delay exceeds the 1 s timeout → `503` |
| Balance proxy | `GET /accounts/{id}/balance` is proxied end-to-end |

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

Every outbound call from the gateway to the account-service is wrapped with a Resilience4j **CircuitBreaker** and **TimeLimiter** applied as AOP advice on `AccountServiceClient`. The return type is `CompletableFuture<T>`, which is required for both annotations to function together.

### Why a circuit breaker?

Without it, each request to a degraded account-service blocks a Tomcat thread for up to 5 seconds waiting for the timeout. Under load, thread exhaustion cascades: the gateway itself becomes unresponsive even though the rest of its functionality (reading events from its own database) is completely unaffected. The circuit breaker short-circuits with a `503` in microseconds once the failure rate exceeds the threshold, keeping the gateway healthy and shedding load from a service that is already struggling.

### Configuration

| Parameter | Value | Effect |
|---|---|---|
| `sliding-window-size` | 10 calls | Failure rate evaluated over the last 10 calls |
| `failure-rate-threshold` | 50 % | Circuit opens when ≥ 50 % of recent calls fail |
| `wait-duration-in-open-state` | 30 s | Breaker stays OPEN for 30 s, then moves to HALF-OPEN |
| `permitted-calls-in-half-open` | 3 | Three probe calls to test recovery before closing |
| `timeout-duration` | 5 s | Hard per-call deadline; exceeded → `TimeoutException` |
| `cancel-running-future` | true | Interrupts the ForkJoinPool thread when the deadline fires |

### State transitions

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
| Circuit OPEN | `CallNotPermittedException` | `503` — circuit breaker is open |
| Call > 5 s | `TimeoutException` | `503` — call timed out |
| Network / HTTP error | `RestClientException` | `503` — Account Service unavailable |

`GET /events/{id}` and `GET /events?account=…` read only from the gateway's own H2 database and are completely unaffected by account-service failures.

---

## Observability

Both services emit **ECS-formatted JSON logs** (`logging.structured.format.console=ecs`). Every log line includes the `traceId` from MDC so all log entries for a single request can be correlated across both services.

The gateway additionally records:
- `events.submitted` counter (tagged by `type`) via Micrometer
- `account.service.call.duration` timer

Actuator endpoints are exposed at the service root (`management.endpoints.web.base-path=/`):

```
GET /health    # liveness + component details
GET /metrics   # Micrometer metrics
GET /info
```
