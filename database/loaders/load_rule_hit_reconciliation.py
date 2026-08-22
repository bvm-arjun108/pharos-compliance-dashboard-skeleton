#!/usr/bin/env python3
"""Load rule_hit_reconciliation.json into pharos.rule_hit_reconciliation."""

from pathlib import Path

from loader_common import build_parser, read_json_rows, run_loader


TABLE = "pharos.rule_hit_reconciliation"
DEFAULT_FILE = Path(__file__).resolve().parents[1] / "mockData" / "rule_hit_reconciliation.json"
REQUIRED_COLUMNS = ("rpt_grp_id", "run_date", "seq_no")

COLUMNS = (
    "rpt_grp_id",
    "run_date",
    "seq_no",
    "created_timestamp",
    "data_selection_end_date",
    "data_selection_start_date",
    "distinct_rule_hits_count_iwra",
    "distinct_rule_hits_count_pharos",
    "missed_rule_hits_count_pharos",
    "modified_timestamp",
    "rpt_grp_name",
    "rule_hit_publish_count_iwra",
)

UPSERT = """
INSERT INTO pharos.rule_hit_reconciliation (
    rpt_grp_id, run_date, seq_no, created_timestamp, data_selection_end_date,
    data_selection_start_date, distinct_rule_hits_count_iwra,
    distinct_rule_hits_count_pharos, missed_rule_hits_count_pharos,
    modified_timestamp, rpt_grp_name, rule_hit_publish_count_iwra
) VALUES (
    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
)
ON CONFLICT (rpt_grp_id, run_date, seq_no) DO UPDATE SET
    created_timestamp = EXCLUDED.created_timestamp,
    data_selection_end_date = EXCLUDED.data_selection_end_date,
    data_selection_start_date = EXCLUDED.data_selection_start_date,
    distinct_rule_hits_count_iwra = EXCLUDED.distinct_rule_hits_count_iwra,
    distinct_rule_hits_count_pharos = EXCLUDED.distinct_rule_hits_count_pharos,
    missed_rule_hits_count_pharos = EXCLUDED.missed_rule_hits_count_pharos,
    modified_timestamp = EXCLUDED.modified_timestamp,
    rpt_grp_name = EXCLUDED.rpt_grp_name,
    rule_hit_publish_count_iwra = EXCLUDED.rule_hit_publish_count_iwra
"""


def to_values(row: dict) -> tuple:
    return tuple(row.get(column) for column in COLUMNS)


def main() -> None:
    parser = build_parser("Load rule_hit_reconciliation mock data", DEFAULT_FILE)
    args = parser.parse_args()
    rows = read_json_rows(args.file, REQUIRED_COLUMNS)
    run_loader(args=args, rows=rows, table=TABLE, statement=UPSERT, to_values=to_values)


if __name__ == "__main__":
    main()
