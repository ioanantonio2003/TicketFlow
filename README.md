# TicketFlow

Ticket booking platform for events with numbered seats. Customers pick seats, hold them for
10 minutes, pay through a simulated provider and receive tickets with QR codes. Seat holds are
concurrency-safe: two customers can never hold or buy the same seat.

> Work in progress.

## Tech stack

Java 21, Spring Boot 4, PostgreSQL 17, Flyway, Spring Security (JWT), Testcontainers.

## Run locally

Requirements: JDK 21, Docker.

```bash
cp .env.example .env                 # optional, defaults work for local development
docker compose up -d                 # PostgreSQL and Mailpit
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
curl localhost:8080/actuator/health  # {"status":"UP", ...}
```

## Tests

```bash
./mvnw verify   # unit and integration tests (Docker must be running for Testcontainers)
```
