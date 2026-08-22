#!/usr/bin/env python3
"""Load report_batch_reconciliation.json into report_transformation_reconciliation."""

from pathlib import Path

from loader_common import build_parser, read_json_rows, run_loader


TABLE = "pharos.report_transformation_reconciliation"
DEFAULT_FILE = Path(__file__).resolve().parents[1] / "mockData" / "report_batch_reconciliation.json"
REQUIRED_COLUMNS = ("rpt_grp_id", "batch_id", "seq_no")

COLUMNS = (
    "batch_id",
    "seq_no",
    "rpt_grp_id",
    "rpt_grp_name",
    "rpt_look_back_date",
    "rpt_from_date",
    "rpt_to_date",
    "txn_selected",
    "txn_simulated",
    "excluded_txn",
    "txn_missing_attempt_count",
    "already_reported_count",
    "expected_reportable_txn",
    "actual_reportable_txn",
    "lookback_txn",
    "lookback_future_reporting_txn",
    "lookback_actual_txn",
    "reporting_period_txn",
    "reporting_period_future_reporting_txn",
    "reporting_period_actual_txn",
    "activity_selected",
    "activity_missing",
    "activity_simulated",
    "expected_activity_eligible_for_transformation",
    "actual_activity_eligible_for_transformation",
    "activity_transformed",
    "activity_transformation_failed",
    "duplicate_transformation",
    "created_timestamp",
    "modified_timestamp",
    "soft_dedup_dropped_txn_count",
)

UPSERT = """
INSERT INTO pharos.report_transformation_reconciliation (
    batch_id, seq_no, rpt_grp_id, rpt_grp_name, rpt_look_back_date,
    rpt_from_date, rpt_to_date, txn_selected, txn_simulated, excluded_txn,
    txn_missing_attempt_count, already_reported_count, expected_reportable_txn,
    actual_reportable_txn, lookback_txn, lookback_future_reporting_txn,
    lookback_actual_txn, reporting_period_txn,
    reporting_period_future_reporting_txn, reporting_period_actual_txn,
    activity_selected, activity_missing, activity_simulated,
    expected_activity_eligible_for_transformation,
    actual_activity_eligible_for_transformation, activity_transformed,
    activity_transformation_failed, duplicate_transformation,
    created_timestamp, modified_timestamp, soft_dedup_dropped_txn_count
) VALUES (
    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
)
ON CONFLICT (rpt_grp_id, batch_id, seq_no) DO UPDATE SET
    rpt_grp_name = EXCLUDED.rpt_grp_name,
    rpt_look_back_date = EXCLUDED.rpt_look_back_date,
    rpt_from_date = EXCLUDED.rpt_from_date,
    rpt_to_date = EXCLUDED.rpt_to_date,
    txn_selected = EXCLUDED.txn_selected,
    txn_simulated = EXCLUDED.txn_simulated,
    excluded_txn = EXCLUDED.excluded_txn,
    txn_missing_attempt_count = EXCLUDED.txn_missing_attempt_count,
    already_reported_count = EXCLUDED.already_reported_count,
    expected_reportable_txn = EXCLUDED.expected_reportable_txn,
    actual_reportable_txn = EXCLUDED.actual_reportable_txn,
    lookback_txn = EXCLUDED.lookback_txn,
    lookback_future_reporting_txn = EXCLUDED.lookback_future_reporting_txn,
    lookback_actual_txn = EXCLUDED.lookback_actual_txn,
    reporting_period_txn = EXCLUDED.reporting_period_txn,
    reporting_period_future_reporting_txn = EXCLUDED.reporting_period_future_reporting_txn,
    reporting_period_actual_txn = EXCLUDED.reporting_period_actual_txn,
    activity_selected = EXCLUDED.activity_selected,
    activity_missing = EXCLUDED.activity_missing,
    activity_simulated = EXCLUDED.activity_simulated,
    expected_activity_eligible_for_transformation = EXCLUDED.expected_activity_eligible_for_transformation,
    actual_activity_eligible_for_transformation = EXCLUDED.actual_activity_eligible_for_transformation,
    activity_transformed = EXCLUDED.activity_transformed,
    activity_transformation_failed = EXCLUDED.activity_transformation_failed,
    duplicate_transformation = EXCLUDED.duplicate_transformation,
    created_timestamp = EXCLUDED.created_timestamp,
    modified_timestamp = EXCLUDED.modified_timestamp,
    soft_dedup_dropped_txn_count = EXCLUDED.soft_dedup_dropped_txn_count
"""


def to_values(row: dict) -> tuple:
    # The source-only `rn` field is intentionally ignored because it is not in the DDL.
    return tuple(row.get(column) for column in COLUMNS)


def main() -> None:
    parser = build_parser("Load report transformation reconciliation mock data", DEFAULT_FILE)
    args = parser.parse_args()
    rows = read_json_rows(args.file, REQUIRED_COLUMNS)
    run_loader(args=args, rows=rows, table=TABLE, statement=UPSERT, to_values=to_values)


if __name__ == "__main__":
    main()

