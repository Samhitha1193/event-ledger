# event-ledger

A multi-module Spring Boot 3 / Java 21 project.

## Modules

| Module            | Port | Description                  |
|-------------------|------|------------------------------|
| `gateway-api` | 8080 | API gateway service |
| `account-service` | 8081 | Account management service |

## Requirements

- Java 21
- Maven 3.9+

## Build

Build all modules from the root:

```bash
mvn clean install
```

## Run

Each service can be started independently.

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

Or run the packaged jar:
```bash
java -jar gateway-api/target/gateway-api-0.0.1-SNAPSHOT.jar
java -jar account-service/target/account-service-0.0.1-SNAPSHOT.jar
```
