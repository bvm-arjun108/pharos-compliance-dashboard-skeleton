"""Shared, small utilities for the Pharos JSON-to-PostgreSQL loaders."""

from __future__ import annotations

import argparse
import json
import os
from collections.abc import Callable, Iterable, Sequence
from pathlib import Path
from typing import Any


Row = dict[str, Any]


def build_parser(description: str, default_file: Path) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--file", type=Path, default=default_file, help="JSON array to load")
    parser.add_argument("--host", default=os.getenv("DB_HOST", "localhost"))
    parser.add_argument("--port", type=int, default=int(os.getenv("DB_PORT", "5439")))
    parser.add_argument("--database", default=os.getenv("DB_NAME", "pharosRBT"))
    parser.add_argument("--user", default=os.getenv("DB_USER", "pharosRBT"))
    parser.add_argument("--password", default=os.getenv("DB_PASSWORD", "pharosRBT"))
    parser.add_argument(
        "--batch-size",
        type=int,
        default=500,
        help="Rows sent to PostgreSQL per executemany call (default: 500)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate the input without connecting to PostgreSQL",
    )
    return parser


def read_json_rows(path: Path, required_columns: Sequence[str]) -> list[Row]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"Input file does not exist: {path}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Invalid JSON in {path}: {exc}") from exc

    if not isinstance(payload, list):
        raise SystemExit(f"Expected a JSON array in {path}, got {type(payload).__name__}")

    errors: list[str] = []
    for index, row in enumerate(payload):
        if not isinstance(row, dict):
            errors.append(f"row {index + 1}: expected an object")
            continue
        missing = [column for column in required_columns if row.get(column) is None]
        if missing:
            errors.append(f"row {index + 1}: missing required value(s): {', '.join(missing)}")

    if errors:
        preview = "\n".join(errors[:20])
        suffix = f"\n...and {len(errors) - 20} more" if len(errors) > 20 else ""
        raise SystemExit(f"Input validation failed:\n{preview}{suffix}")

    return payload


def chunks(rows: Sequence[Row], size: int) -> Iterable[Sequence[Row]]:
    if size < 1:
        raise SystemExit("--batch-size must be at least 1")
    for start in range(0, len(rows), size):
        yield rows[start : start + size]


def jsonb(value: Any) -> Any:
    if value is None:
        return None
    from psycopg.types.json import Jsonb

    return Jsonb(value)


def run_loader(
    *,
    args: argparse.Namespace,
    rows: list[Row],
    table: str,
    statement: str,
    to_values: Callable[[Row], tuple[Any, ...]],
) -> None:
    if args.dry_run:
        print(f"Validated {len(rows)} row(s) for {table}; no database changes made.")
        return

    try:
        import psycopg
    except ImportError as exc:
        raise SystemExit(
            "Missing PostgreSQL driver. Run: uv sync --project database/loaders"
        ) from exc

    connection_summary = f"{args.host}:{args.port}/{args.database}"
    print(f"Loading {len(rows)} row(s) into {table} at {connection_summary}...")

    try:
        with psycopg.connect(
            host=args.host,
            port=args.port,
            dbname=args.database,
            user=args.user,
            password=args.password,
        ) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT to_regclass(%s)", (table,))
                if cursor.fetchone()[0] is None:
                    raise RuntimeError(
                        f"Table {table} does not exist. Apply database/sqlScripts/ddl.sql first."
                    )

                processed = 0
                for batch in chunks(rows, args.batch_size):
                    cursor.executemany(statement, [to_values(row) for row in batch])
                    processed += len(batch)
                    print(f"  processed {processed}/{len(rows)}")
    except Exception as exc:
        raise SystemExit(f"Load failed; transaction rolled back: {exc}") from exc

    print(f"Successfully upserted {len(rows)} row(s) into {table}.")
