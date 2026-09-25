# Pharos Compliance Operations Dashboard

This monorepo contains the Phase 1 compliance operations dashboard: an Angular frontend and a Java 21 Spring Boot API backed by PostgreSQL, accessed through hand-written parameterized SQL over Spring JDBC.

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
JDBC read repository
        ↓
NamedParameterJdbcTemplate + JDBC + HikariCP
        ↓
PostgreSQL
```

Spring MVC handles requests on Java 21 virtual threads. Database access remains synchronous JDBC, which matches the required `common.postgres` configuration and PostgreSQL/RDS connection contract while avoiding platform-thread starvation under concurrent read traffic.

The Java packages use feature-first organization:

```text
com.pharos.compliance
├── common
│   ├── error          Structured API errors and global exception handling
│   ├── exception      Application exceptions
│   ├── jdbc           Hand-written SQL support: SqlFragment composition, .sql resource loading,
│   │                  the tracing NamedParameterJdbcTemplate wrapper, shared query fragments
│   ├── metrics        Per-request database query timing and performance summaries
│   └── tracing        Trace/span propagation and request logging
├── config             Database, cache, OpenAPI, and application configuration
├── dashboard          Operational overview, KPIs, trends, and report-group priority
├── batch              Batch explorer, queue, control-room details, and evidence totals
├── reportgroup        Country catalog and report-group configuration workspace
├── transaction        Period and batch-scoped transaction evidence reports
└── health             Application and database health
```

The repositories use hand-written, parameterized SQL loaded from `.sql` resource files under `src/main/resources/sql/`, composed via `SqlFragment` (the project's lightweight replacement for jOOQ's composable query objects) and executed through a tracing `NamedParameterJdbcTemplate` wrapper. Repository transactions are read-only, and API response DTOs remain independent of raw database records.

### API documentation

With the backend running:

- Swagger UI: `http://localhost:8085/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8085/v3/api-docs`

OpenAPI operation documentation lives on the API interfaces. The concrete controllers implement those interfaces and contain only delegation logic.

### Tracing and errors

Micrometer Tracing with Brave creates a trace and span for incoming requests. Logs include the application name, trace ID, span ID, thread, logger, and message. API responses also expose `X-Trace-Id` and `X-Span-Id`; structured error responses repeat both identifiers so an operator can correlate an error with its logs.

Set `TRACING_SAMPLING_PROBABILITY` to control sampling. Local development defaults to `1.0` so every request is traceable.

### Formatting

Spotless uses the pinned Prince of Space formatter for backend Java sources and checks formatting during Maven's `verify` phase. The project uses a
140-character line length and the `WIDE` wrapping style to keep hand-written SQL-building code, method arguments, and fluent calls readable without
excessive vertical wrapping. Formatting remains deterministic in IntelliJ, local terminals, and CI because Maven owns the formatter configuration:

```bash
cd backend
mvn spotless:apply
mvn spotless:check
mvn verify
```

## Database

PostgreSQL is the backend's only database; H2 is not included. Local development uses the same `common.postgres` JDBC configuration contract as higher environments. A Spring-managed `NamedParameterJdbcTemplate` uses the PostgreSQL JDBC driver and Hikari connection pool configured from those values. The local defaults match `docker-compose.yml`:

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

Override the runtime connection using `DB_URL`, `DB_DRIVER`, `DB_USER`, `DB_PASSWORD`, `DB_CONNECTION_TIMEOUT`, `DB_MAX_POOL_SIZE`, and `DB_MIN_IDLE`. These environment variables feed the production-compatible `common.postgres` properties. The custom `/api/v1/health` endpoint runs a hand-written SQL query and includes the connected database and schema in its response. Database migrations remain external because the dashboard does not currently own the Pharos schema.

### SQL debug logging

Every statement run through the tracing `NamedParameterJdbcTemplate` wrapper is logged at `DEBUG` in formatted PostgreSQL syntax with its bind values inlined, followed by its operation type and execution duration. This is enabled locally by default. Because inlined values can include batch or transaction identifiers, disable it in shared or production environments unless actively troubleshooting:

```bash
SQL_LOG_LEVEL=OFF mvn -f backend/pom.xml spring-boot:run
```

Set `SQL_LOG_LEVEL=DEBUG` to enable it again. Application summary and HTTP access logs remain available at `INFO` independently of SQL logging.

### SQL organization

Query SQL lives as plain `.sql` files under `backend/src/main/resources/sql/<feature>/`, loaded once and cached by `SqlResourceLoader`, and composed into full statements via `SqlFragment` (named-parameter SQL text plus its bind values) — the project's lightweight replacement for a query-building DSL. Keeping queries as plain SQL files, rather than embedded in Java text blocks, keeps them pasteable directly into a Postgres client for `EXPLAIN`. There is no build-time schema introspection or code generation step: the backend compiles and packages without a live database connection.

### Backend tests

- Unit and service tests do not start Spring; some use deterministic fixture rows under `backend/src/test/resources/fixtures` with mocked repositories (e.g. `DashboardServiceImplTest`).
- Repository-level tests run hand-written SQL against a real, ephemeral PostgreSQL instance via Testcontainers (see `PostgresIntegrationTest`), schema-seeded from `backend/src/test/resources/testcontainers/pharos-schema.sql` — proving execution semantics against Postgres itself, not just query shape. These are tagged `integration` and excluded from the default `mvn test` run (they need a Docker daemon); run them with:

```bash
mvn -f backend/pom.xml test -Pintegration-test
```

CI and any change to a repository's SQL should run this profile.

## Included foundation

- Java 21 compiler configuration
- Spring MVC, Spring JDBC, validation, Actuator, OpenAPI, PostgreSQL JDBC, and test dependencies
- Hand-written, parameterized PostgreSQL-native read queries composed via `SqlFragment`
- Java 21 virtual-thread request handling for synchronous JDBC operations
- Micrometer trace/span correlation and structured request logging
- Centralized structured API errors and trace response headers
- Spotless formatting and build-time formatting enforcement
- Production-compatible `common.postgres` JDBC configuration with Docker-matching defaults
- Angular standalone bootstrap and routing
- Angular-to-Spring development proxy
- Dashboard, batch explorer/control room, report configuration, and transaction evidence views
- Backend health API and frontend connectivity handling
- Local PostgreSQL Docker Compose service
- Fixture-backed backend service tests that do not depend on live PostgreSQL data
- Testcontainers-backed repository tests that run hand-written SQL against a real, ephemeral PostgreSQL instance

## Deliberately deferred or incomplete

- Rules workspace implementation
- Report-group configuration write operations
- Authentication and authorization
- Observability conventions beyond the basic Actuator setup
- CI/CD and deployment packaging
