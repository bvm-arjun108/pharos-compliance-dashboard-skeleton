#!/usr/bin/env python3
"""Load record_transformation_journey.json into pharos.record_transformation_journey."""

from pathlib import Path

from loader_common import build_parser, jsonb, read_json_rows, run_loader


TABLE = "pharos.record_transformation_journey"
DEFAULT_FILE = Path(__file__).resolve().parents[1] / "mockData" / "record_transformation_journey.json"
REQUIRED_COLUMNS = ("rpt_grp_id", "batch_id", "identifier")

UPSERT = """
INSERT INTO pharos.record_transformation_journey (
    rpt_grp_id, batch_id, identifier, mtcn, stage, status, comments,
    created_timestamp, modified_timestamp, reporting_timestamp_latest,
    processing_complete, txn_metadata, skip_reason
) VALUES (
    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
)
ON CONFLICT (rpt_grp_id, batch_id, identifier) DO UPDATE SET
    mtcn = EXCLUDED.mtcn,
    stage = EXCLUDED.stage,
    status = EXCLUDED.status,
    comments = EXCLUDED.comments,
    created_timestamp = EXCLUDED.created_timestamp,
    modified_timestamp = EXCLUDED.modified_timestamp,
    reporting_timestamp_latest = EXCLUDED.reporting_timestamp_latest,
    processing_complete = EXCLUDED.processing_complete,
    txn_metadata = EXCLUDED.txn_metadata,
    skip_reason = EXCLUDED.skip_reason
"""


def to_values(row: dict) -> tuple:
    return (
        row["rpt_grp_id"],
        row["batch_id"],
        row["identifier"],
        row.get("mtcn"),
        row.get("stage"),
        row.get("status"),
        row.get("comments"),
        row.get("created_timestamp"),
        row.get("modified_timestamp"),
        row.get("reporting_timestamp_latest"),
        row.get("processing_complete"),
        jsonb(row.get("txn_metadata")),
        row.get("skip_reason"),
    )


def main() -> None:
    parser = build_parser("Load record transformation journey mock data", DEFAULT_FILE)
    args = parser.parse_args()
    rows = read_json_rows(args.file, REQUIRED_COLUMNS)
    run_loader(args=args, rows=rows, table=TABLE, statement=UPSERT, to_values=to_values)


if __name__ == "__main__":
    main()

