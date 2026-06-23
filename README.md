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
