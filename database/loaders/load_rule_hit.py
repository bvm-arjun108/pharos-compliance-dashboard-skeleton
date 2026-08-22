#!/usr/bin/env python3
"""Load rule_hit.json into pharos.rule_hit."""

from pathlib import Path

from loader_common import build_parser, read_json_rows, run_loader


TABLE = "pharos.rule_hit"
DEFAULT_FILE = Path(__file__).resolve().parents[1] / "mockData" / "rule_hit.json"
REQUIRED_COLUMNS = ("rpt_grp_id", "bucket_id", "rule_id", "attempt_id")

COLUMNS = (
    "rpt_grp_id",
    "bucket_id",
    "rule_id",
    "attempt_id",
    "activity_type",
    "batch_id",
    "created_timestamp",
    "efile_batch_id",
    "exclusion_reason_id",
    "external_txn_key",
    "galactic_id",
    "is_reported",
    "modified_timestamp",
    "mtcn",
    "objective_aggregation_key",
    "reporting_timestamp",
    "rpt_grp_name",
    "rule_currency_amount",
    "rule_iso_currency_code",
    "send_date",
    "source",
    "transaction_date",
    "transaction_side",
    "reported_batch_id",
)

UPSERT = """
INSERT INTO pharos.rule_hit (
    rpt_grp_id, bucket_id, rule_id, attempt_id, activity_type, batch_id,
    created_timestamp, efile_batch_id, exclusion_reason_id, external_txn_key,
    galactic_id, is_reported, modified_timestamp, mtcn, objective_aggregation_key,
    reporting_timestamp, rpt_grp_name, rule_currency_amount, rule_iso_currency_code,
    send_date, source, transaction_date, transaction_side, reported_batch_id
) VALUES (
    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
)
ON CONFLICT (rpt_grp_id, bucket_id, rule_id, attempt_id) DO UPDATE SET
    activity_type = EXCLUDED.activity_type,
    batch_id = EXCLUDED.batch_id,
    created_timestamp = EXCLUDED.created_timestamp,
    efile_batch_id = EXCLUDED.efile_batch_id,
    exclusion_reason_id = EXCLUDED.exclusion_reason_id,
    external_txn_key = EXCLUDED.external_txn_key,
    galactic_id = EXCLUDED.galactic_id,
    is_reported = EXCLUDED.is_reported,
    modified_timestamp = EXCLUDED.modified_timestamp,
    mtcn = EXCLUDED.mtcn,
    objective_aggregation_key = EXCLUDED.objective_aggregation_key,
    reporting_timestamp = EXCLUDED.reporting_timestamp,
    rpt_grp_name = EXCLUDED.rpt_grp_name,
    rule_currency_amount = EXCLUDED.rule_currency_amount,
    rule_iso_currency_code = EXCLUDED.rule_iso_currency_code,
    send_date = EXCLUDED.send_date,
    source = EXCLUDED.source,
    transaction_date = EXCLUDED.transaction_date,
    transaction_side = EXCLUDED.transaction_side,
    reported_batch_id = EXCLUDED.reported_batch_id
"""


def to_values(row: dict) -> tuple:
    return tuple(row.get(column) for column in COLUMNS)


def main() -> None:
    parser = build_parser("Load rule_hit mock data", DEFAULT_FILE)
    args = parser.parse_args()
    rows = read_json_rows(args.file, REQUIRED_COLUMNS)
    run_loader(args=args, rows=rows, table=TABLE, statement=UPSERT, to_values=to_values)


if __name__ == "__main__":
    main()
