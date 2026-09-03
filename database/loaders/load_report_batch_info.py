#!/usr/bin/env python3
"""Load report_batch_info.json into pharos.report_batch_info."""

from pathlib import Path

from loader_common import build_parser, jsonb, read_json_rows, run_loader


TABLE = "pharos.report_batch_info"
DEFAULT_FILE = Path(__file__).resolve().parents[1] / "mockData" / "report_batch_info.json"
PRIMARY_KEY_COLUMNS = (
    "rpt_grp_id",
    "batch_id",
    "seq_no",
)
REQUIRED_COLUMNS = PRIMARY_KEY_COLUMNS

COLUMNS = (
    "rpt_grp_id",
    "batch_id",
    "seq_no",
    "batch_status",
    "compiler_status",
    "created_timestamp",
    "created_user_id",
    "modified_timestamp",
    "process_timestamp",
    "report_header",
    "report_status",
    "rpt_grp_name",
    "transformer_mapping_version",
    "txn_end_timestamp",
    "txn_start_timestamp",
    "txn_lookback_start_timestamp",
    "selection_version",
    "created_user_email",
    "dms_ref_info",
)

JSONB_COLUMNS = frozenset(
    {
        "report_header",
        "dms_ref_info",
    }
)

UPDATE_COLUMNS = tuple(column for column in COLUMNS if column not in PRIMARY_KEY_COLUMNS)
UPSERT = f"""
INSERT INTO {TABLE} (
    {", ".join(COLUMNS)}
) VALUES (
    {", ".join("%s" for _ in COLUMNS)}
)
ON CONFLICT ({", ".join(PRIMARY_KEY_COLUMNS)}) DO UPDATE SET
    {", ".join(f"{column} = EXCLUDED.{column}" for column in UPDATE_COLUMNS)}
"""


def to_values(row: dict) -> tuple:
    return tuple(
        jsonb(row.get(column)) if column in JSONB_COLUMNS else row.get(column)
        for column in COLUMNS
    )


def main() -> None:
    parser = build_parser("Load report batch info mock data", DEFAULT_FILE)
    args = parser.parse_args()
    rows = read_json_rows(args.file, REQUIRED_COLUMNS)
    run_loader(args=args, rows=rows, table=TABLE, statement=UPSERT, to_values=to_values)


if __name__ == "__main__":
    main()
