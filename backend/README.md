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

```text
GitLab CI/CD → JFrog Artifactory (container image) → ECS service on Fargate
```

Runtime request path:

```text
Browser → F5 → API Gateway (Lambda authorizer) → VPC Link → internal NLB → Spring Boot (ECS/Fargate)
```

Runs in private VPC subnets across multiple AZs, connects to PostgreSQL (Amazon RDS) over JDBC/TLS, and pulls runtime configuration and database credentials from Spring Config and CyberArk. Environment-specific values (hosts, roles, secrets) are owned by the deployment pipeline, not this repository.
