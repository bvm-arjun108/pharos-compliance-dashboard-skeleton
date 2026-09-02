# Pharos Compliance Operations Dashboard

This monorepo contains the Phase 1 compliance operations dashboard: an Angular frontend and a Java 21 Spring Boot API backed by PostgreSQL and jOOQ.

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
jOOQ read repository
        ↓
DSLContext + JDBC + HikariCP
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
│   ├── jooq           Shared PostgreSQL/jOOQ expressions
│   └── tracing        Trace/span propagation and request logging
├── config             Database, cache, OpenAPI, and application configuration
├── dashboard          Operational overview, KPIs, trends, and report-group priority
├── batch              Batch explorer, queue, control-room details, and evidence totals
├── reportgroup        Country catalog and report-group configuration workspace
├── transaction        Period and batch-scoped transaction evidence reports
└── health             Application and database health
```

The repositories use generated jOOQ table and field classes for schema-aware query construction. PostgreSQL-specific operations that are not represented directly by the fluent DSL are isolated in small documented SQL templates. Repository transactions are read-only, and API response DTOs remain independent of generated database records.

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
140-character line length and the `WIDE` wrapping style to keep jOOQ queries, method arguments, and fluent calls readable without excessive vertical
wrapping. Formatting remains deterministic in IntelliJ, local terminals, and CI because Maven owns the formatter configuration:

```bash
cd backend
mvn spotless:apply
mvn spotless:check
mvn verify
```

## Database

PostgreSQL is the backend's only database; H2 is not included. Local development uses the same `common.postgres` JDBC configuration contract as higher environments. jOOQ uses the Spring-managed `DSLContext`, PostgreSQL JDBC driver, and Hikari connection pool configured from those values. The local defaults match `docker-compose.yml`:

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

Override the runtime connection using `DB_URL`, `DB_DRIVER`, `DB_USER`, `DB_PASSWORD`, `DB_CONNECTION_TIMEOUT`, `DB_MAX_POOL_SIZE`, and `DB_MIN_IDLE`. These environment variables feed the production-compatible `common.postgres` properties. The custom `/api/v1/health` endpoint executes a jOOQ query and includes the connected database and schema in its response. Database migrations remain external because the dashboard does not currently own the Pharos schema.

### SQL debug logging

Every jOOQ statement is logged at `DEBUG` in formatted PostgreSQL syntax with its bind values inlined, followed by its operation type and execution duration. This is enabled locally by default. Because inlined values can include batch or transaction identifiers, disable it in shared or production environments unless actively troubleshooting:

```bash
JOOQ_SQL_LOG_LEVEL=OFF mvn -f backend/pom.xml spring-boot:run
```

Set `JOOQ_SQL_LOG_LEVEL=DEBUG` to enable it again. Application summary and HTTP access logs remain available at `INFO` independently of SQL logging.

### jOOQ code generation

Maven generates typed jOOQ sources from the `pharos` schema during `generate-sources`. The schema must therefore be reachable when compiling from a clean checkout. Local defaults match Docker Compose; CI or another database can override them without changing application runtime configuration:

```bash
mvn -f backend/pom.xml verify \
  -Djooq.codegen.jdbc.url=jdbc:postgresql://host:5432/pharos \
  -Djooq.codegen.jdbc.user=pharos_user \
  -Djooq.codegen.jdbc.password=secret
```

Generated sources are written under `backend/target/generated-sources/jooq` and are not edited manually. The code generator version is inherited from Spring Boot dependency management so generated code and the runtime library remain aligned.

## Included foundation

- Java 21 compiler configuration
- Spring MVC, jOOQ, validation, Actuator, OpenAPI, PostgreSQL JDBC, and test dependencies
- Generated, schema-aware jOOQ tables and PostgreSQL-native read queries
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
- Backend context and live PostgreSQL connection tests

## Deliberately deferred or incomplete

- Rules workspace implementation
- Report-group configuration write operations
- Authentication and authorization
- Observability conventions beyond the basic Actuator setup
- CI/CD and deployment packaging
