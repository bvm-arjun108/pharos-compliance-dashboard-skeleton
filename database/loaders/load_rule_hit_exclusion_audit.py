#!/usr/bin/env python3
"""Load rule_hit_exclusion_audit.json into pharos.rule_hit_exclusion_audit."""

from pathlib import Path

from loader_common import build_parser, read_json_rows, run_loader


TABLE = "pharos.rule_hit_exclusion_audit"
DEFAULT_FILE = Path(__file__).resolve().parents[1] / "mockData" / "rule_hit_exclusion_audit.json"
REQUIRED_COLUMNS = ("bucket_id", "rpt_grp_id", "rule_id", "attempt_id")

COLUMNS = (
    "attempt_id",
    "rpt_grp_id",
    "rule_id",
    "bucket_id",
    "rpt_grp_name",
    "external_txn_key",
    "processing_batch_id",
    "exclusion_reason_id",
    "exclusion_strategy",
    "reported_batch_id",
    "mtcn",
    "created_timestamp",
    "modified_timestamp",
    "reporting_timestamp",
)

UPSERT = """
INSERT INTO pharos.rule_hit_exclusion_audit (
    attempt_id, rpt_grp_id, rule_id, bucket_id, rpt_grp_name,
    external_txn_key, processing_batch_id, exclusion_reason_id,
    exclusion_strategy, reported_batch_id, mtcn, created_timestamp,
    modified_timestamp, reporting_timestamp
) VALUES (
    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
)
ON CONFLICT (bucket_id, rpt_grp_id, rule_id, attempt_id) DO UPDATE SET
    rpt_grp_name = EXCLUDED.rpt_grp_name,
    external_txn_key = EXCLUDED.external_txn_key,
    processing_batch_id = EXCLUDED.processing_batch_id,
    exclusion_reason_id = EXCLUDED.exclusion_reason_id,
    exclusion_strategy = EXCLUDED.exclusion_strategy,
    reported_batch_id = EXCLUDED.reported_batch_id,
    mtcn = EXCLUDED.mtcn,
    created_timestamp = EXCLUDED.created_timestamp,
    modified_timestamp = EXCLUDED.modified_timestamp,
    reporting_timestamp = EXCLUDED.reporting_timestamp
"""


def to_values(row: dict) -> tuple:
    return tuple(row.get(column) for column in COLUMNS)


def main() -> None:
    parser = build_parser("Load rule-hit exclusion audit mock data", DEFAULT_FILE)
    args = parser.parse_args()
    rows = read_json_rows(args.file, REQUIRED_COLUMNS)
    run_loader(args=args, rows=rows, table=TABLE, statement=UPSERT, to_values=to_values)


if __name__ == "__main__":
    main()

