# Pharos mock-data loaders

These scripts load the four JSON arrays in `database/mockData` into their matching tables. They use parameterized SQL and primary-key upserts, so rerunning a loader updates existing rows instead of creating duplicates.

## Setup

From the repository root:

```bash
uv sync --project database/loaders
```

`uv` reads `database/loaders/.python-version`, installs Python 3.12 when needed, creates the isolated environment, and installs the locked PostgreSQL driver dependency.

Start PostgreSQL and apply the DDL if needed:

```bash
docker compose up -d postgres
psql -h localhost -p 5439 -U pharosRBT -d pharosRBT -f database/sqlScripts/ddl.sql
```

## Validate without inserting

```bash
uv run --project database/loaders python3 database/loaders/load_record_transformation_journey.py --dry-run
uv run --project database/loaders python3 database/loaders/load_report_transformation_reconciliation.py --dry-run
uv run --project database/loaders python3 database/loaders/load_rule_hit_exclusion_audit.py --dry-run
uv run --project database/loaders python3 database/loaders/load_report_group_config.py --dry-run
```

## Insert or update the data

```bash
uv run --project database/loaders python3 database/loaders/load_record_transformation_journey.py
uv run --project database/loaders python3 database/loaders/load_report_transformation_reconciliation.py
uv run --project database/loaders python3 database/loaders/load_rule_hit_exclusion_audit.py
uv run --project database/loaders python3 database/loaders/load_report_group_config.py
```

Defaults match `docker-compose.yml`:

- Host: `localhost`
- Port: `5439`
- Database: `pharosRBT`
- User: `pharosRBT`
- Password: `pharosRBT`

Override them with command-line flags or `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASSWORD`. Run any script with `--help` for all options.
