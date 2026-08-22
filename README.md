# Pharos Compliance Operations Dashboard — Project Skeleton

This repository is the clean starting point for the Phase 1 dashboard. It intentionally contains only the application foundation; dashboard pages, domain DTOs, repositories, and reconciliation SQL will be added incrementally.

## Structure

```text
pharos-compliance-dashboard/
├── backend/       Java 21, Maven, Spring Boot 3
├── frontend/      Angular 22
├── docker-compose.yml
└── pom.xml        IntelliJ-friendly Maven aggregator
```

## Prerequisites

- JDK 21
- Maven 3.8+
- Node.js 22.22.3+ (Node.js 24 LTS recommended)
- npm 10+
- Docker Desktop (optional, for local PostgreSQL)

## Open in IntelliJ IDEA

1. Open this repository's root directory.
2. When IntelliJ detects Maven, choose **Load Maven Project** for the root `pom.xml`.
3. Set the Project SDK and Maven Runner JRE to Java 21.
4. For the backend, run `ComplianceDashboardApplication`.
5. For the frontend, open IntelliJ's terminal in `frontend`, run `npm install`, then `npm start`.

If you use `nvm`, run `nvm install` and `nvm use` inside `frontend` to select the version recorded in `.nvmrc`.

The Angular development server is available at `http://localhost:4200`. It proxies `/api`, `/actuator`, and `/dashboardDetails` calls to Spring Boot at `http://localhost:8085`.

## Run from a terminal

Backend:

```bash
cd backend
mvn spring-boot:run
```

Frontend (in a second terminal):

```bash
cd frontend
npm install
npm start
```

Smoke checks:

```bash
curl http://localhost:8085/api/v1/health
curl http://localhost:8085/actuator/health
curl "http://localhost:8085/dashboardDetails?fromDate=2026-08-01&toDate=2026-08-31"
```

## Backend architecture

The backend uses a contract-first layered structure:

```text
OpenAPI API interface
        ↓
Controller implementation
        ↓
Service interface
        ↓
Service implementation
        ↓
Java 21 virtual-thread boundary
        ↓
Spring Data JPA repository
        ↓
JDBC + HikariCP
        ↓
PostgreSQL
```

The Java packages use feature-first organization:

```text
com.pharos.compliance
├── common
│   ├── error          Structured API errors and global exception handling
│   ├── exception      Application exceptions
│   └── tracing        Trace/span propagation and request logging
├── config             Database, OpenAPI, and JDBC execution configuration
├── dashboard
│   ├── api            Documented API contract
│   ├── controller     API implementation and delegation
│   ├── dto            Response contracts
│   ├── entity         Read-only JPA entity and composite identifier
│   ├── model          Dashboard domain values
│   ├── repository     Spring Data JPA queries and projections
│   └── service        Service contract and impl subpackage
└── health
    ├── api            Documented health API contract
    ├── controller     Health API implementation
    ├── dto            Health response contract
    ├── repository     Database health query
    └── service        Service contract and impl subpackage
```

Spring WebFlux exposes `Mono` responses for aggregate dashboard and health resources. Spring Data JPA executes the validated PostgreSQL-native aggregate queries through read-only interface projections. The service defers every blocking JPA call with `Mono.fromCallable(...)` and runs it on the dedicated `pharos-jdbc-*` Java 21 virtual-thread scheduler, so JDBC work never blocks a Netty event-loop thread.

The HTTP and service contracts remain reactive, while the mandated database integration is synchronous JDBC underneath. Reactor automatic context propagation preserves Micrometer trace context and MDC values when execution moves between Netty and the virtual-thread scheduler.

### API documentation

With the backend running:

- Swagger UI: `http://localhost:8085/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8085/v3/api-docs`

OpenAPI operation documentation lives on the API interfaces. The concrete controllers implement those interfaces and contain only delegation logic.

### Tracing and errors

Micrometer Tracing with Brave creates a trace and span for incoming requests. Logs include the application name, trace ID, span ID, thread, logger, and message. API responses also expose `X-Trace-Id` and `X-Span-Id`; structured error responses repeat both identifiers so an operator can correlate an error with its logs.

Set `TRACING_SAMPLING_PROBABILITY` to control sampling. Local development defaults to `1.0` so every request is traceable.

### Formatting

Spotless with Google Java Format is configured for backend Java sources and checked during Maven's `verify` phase:

```bash
cd backend
mvn spotless:apply
mvn spotless:check
mvn verify
```

## Database

PostgreSQL is the backend's only database; H2 is not included. Local development uses the same `common.postgres` JDBC configuration contract as higher environments. Spring Data JPA uses the PostgreSQL JDBC driver and a Hikari connection pool configured from those values. The local defaults match `docker-compose.yml`:

- URL: `jdbc:postgresql://localhost:5439/pharosRBT`
- Driver: `org.postgresql.Driver`
- User: `pharosRBT`
- Password: `pharosRBT`
- Connection timeout: `120000` milliseconds

To start a local PostgreSQL instance:

```bash
docker compose up -d postgres
```

Then start the backend:

```bash
mvn -f backend/pom.xml spring-boot:run
```

Override the connection using `DB_URL`, `DB_DRIVER`, `DB_USER`, `DB_PASSWORD`, `DB_CONNECTION_TIMEOUT`, `DB_MAX_POOL_SIZE`, and `DB_MIN_IDLE`. These environment variables feed the production-compatible `common.postgres` properties. The custom `/api/v1/health` endpoint executes a JPA native query on a virtual thread and includes the connected database and schema in its response. Database migrations remain external because the dashboard does not currently own the Pharos schema.

## Included foundation

- Java 21 compiler configuration
- Spring WebFlux, Spring Data JPA, validation, Actuator, OpenAPI, PostgreSQL JDBC, and test dependencies
- PostgreSQL-native Spring Data repository queries with read-only projections
- Dedicated Java 21 virtual-thread isolation for blocking JPA/JDBC operations
- Micrometer trace/span correlation across reactive execution
- Centralized structured API errors and trace response headers
- Spotless formatting and build-time formatting enforcement
- Production-compatible `common.postgres` JDBC configuration with Docker-matching defaults
- Angular standalone bootstrap and routing
- Angular-to-Spring development proxy
- Backend health API and a frontend connectivity screen
- Local PostgreSQL Docker Compose service
- Backend context and live PostgreSQL connection tests

## Deliberately deferred

- Overview, Report Group, Batch Control Room, and Transaction pages
- Authentication and authorization
- Observability conventions beyond the basic Actuator setup
- CI/CD and deployment packaging

Those pieces can now be designed and implemented one at a time without changing the project foundation.
