# compliance-dashboard

Spring Boot backend for Pharos Compliance Operations. Provides read APIs for the batch dashboard, transaction dashboard, batch investigation, transaction evidence, and report configuration.

Companion frontend repository: **`compliance-dashboard-spa`**.

## Technology

- Java 21, Maven
- Spring Boot, Spring MVC, Actuator
- Spring JDBC (`NamedParameterJdbcTemplate`) + HikariCP — no ORM or code-generation step
- PostgreSQL, parameterized native SQL
- OpenAPI / Swagger UI
- JUnit, Mockito, PostgreSQL Testcontainers

## Getting started

```bash
mvn spring-boot:run
```

Runs on `http://localhost:8085`. Requires access to a PostgreSQL database with the Pharos schema (configure via `DB_URL`/`DB_USER`/`DB_PASSWORD`, or your approved local mechanism).

```bash
curl --fail http://localhost:8085/actuator/health
```

Full API surface: `http://localhost:8085/swagger-ui.html`.

## Build and test

```bash
mvn test                              # unit/service tests
mvn clean verify -Pintegration-test   # + PostgreSQL Testcontainers tests (needs Docker)
mvn spotless:apply                    # formatting
```

## Architecture

```text
API interface → controller → service → JDBC repository → SQL resources → PostgreSQL
```

SQL lives in versioned resource files under `src/main/resources/sql/`, composed by repository code at request time. No code-generation step and no database connection is needed to compile.

## Deployment

Built and deployed via GitLab CI/CD as a container image to ECS. See the deployment runbook for environment configuration, health checks, and the security/authorization integration — those are environment-owned, not part of this repository.
