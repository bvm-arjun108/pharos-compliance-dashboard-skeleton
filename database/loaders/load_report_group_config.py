#!/usr/bin/env python3
"""Load report_group_config.json into pharos.report_group_config."""

from pathlib import Path

from loader_common import build_parser, jsonb, read_json_rows, run_loader


TABLE = "pharos.report_group_config"
DEFAULT_FILE = Path(__file__).resolve().parents[1] / "mockData" / "report_group_config.json"
PRIMARY_KEY_COLUMNS = (
    "rpt_grp_id",
    "rpt_selection_version_id",
    "transformer_version_id",
)
REQUIRED_COLUMNS = PRIMARY_KEY_COLUMNS

COLUMNS = (
    "rpt_grp_id",
    "rpt_selection_version_id",
    "transformer_version_id",
    "ack_prf_docsubtype",
    "additional_data",
    "bizgrp_name",
    "country_code",
    "country_name",
    "db_lookup_enabled",
    "inbound_rule_id",
    "is_blank_report",
    "is_non_transactional_report",
    "is_partial_report",
    "mapping_project_key",
    "mapping_service_name",
    "created_timestamp",
    "modified_timestamp",
    "outbound_rule_id",
    "output_file_docsubtype",
    "reg_reportable_activity_columns",
    "reg_rpt_type",
    "region_code",
    "region_name",
    "report_currency",
    "rpt_config_active_flag",
    "rpt_grp_name",
    "rpt_period",
    "rpt_selection",
    "rule_hit_columns",
    "submission_prf_docsubtype",
    "transformer_config",
    "exclusion_strategy",
    "exclusion_reason",
    "column_to_compare",
    "three_letter_country_code",
    "manipulation_strategy_metadata",
    "reconciliation_strategy_metadata",
)

JSONB_COLUMNS = frozenset(
    {
        "transformer_config",
        "manipulation_strategy_metadata",
        "reconciliation_strategy_metadata",
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
    parser = build_parser("Load report group configuration mock data", DEFAULT_FILE)
    args = parser.parse_args()
    rows = read_json_rows(args.file, REQUIRED_COLUMNS)
    run_loader(args=args, rows=rows, table=TABLE, statement=UPSERT, to_values=to_values)


if __name__ == "__main__":
    main()
