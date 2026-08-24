-- "pharosRBT".pharos.record_transformation_journey definition

-- Drop table

-- DROP TABLE "pharosRBT".pharos.record_transformation_journey;

CREATE TABLE "pharosRBT".pharos.record_transformation_journey (
                                                                  rpt_grp_id int4 NOT NULL,
                                                                  batch_id text NOT NULL,
                                                                  identifier text NOT NULL,
                                                                  mtcn text,
                                                                  stage text,
                                                                  status text,
                                                                  comments text,
                                                                  created_timestamp timestamptz,
                                                                  modified_timestamp timestamptz,
                                                                  reporting_timestamp_latest timestamp,
                                                                  processing_complete bool,
                                                                  txn_metadata jsonb,
                                                                  skip_reason text,
                                                                  CONSTRAINT record_transformation_journey_pkey
                                                                      PRIMARY KEY (rpt_grp_id, batch_id, identifier)
);

-- Backs the identifier-first transaction bridge (journey.identifier vs rule_hit.external_txn_key)
-- without weakening its validated semantics: this indexes exactly the rows the bridge's own
-- "identifier ~ '^[0-9]+$'" guard already accepts, so the planner can use it instead of scanning
-- every journey row in the batch per rule_hit row.
CREATE INDEX record_transformation_journey_identifier_bigint_idx
    ON pharos.record_transformation_journey (rpt_grp_id, batch_id, ((identifier)::bigint))
    WHERE identifier ~ '^[0-9]+$';

-- Backs the mtcn fallback path of the same bridge (used only when the identifier match misses).
CREATE INDEX record_transformation_journey_mtcn_idx
    ON pharos.record_transformation_journey (rpt_grp_id, batch_id, mtcn);


-- "pharosRBT".pharos.report_transformation_reconciliation definition

-- Drop table

-- DROP TABLE "pharosRBT".pharos.report_transformation_reconciliation;

CREATE TABLE "pharosRBT".pharos.report_transformation_reconciliation (
                                                                         batch_id text NOT NULL,
                                                                         seq_no int4 NOT NULL,
                                                                         rpt_grp_id int4 NOT NULL,
                                                                         rpt_grp_name text,
                                                                         rpt_look_back_date text,
                                                                         rpt_from_date text,
                                                                         rpt_to_date text,
                                                                         txn_selected int4,
                                                                         txn_simulated int4,
                                                                         excluded_txn int4,
                                                                         txn_missing_attempt_count int4,
                                                                         already_reported_count int4,
                                                                         expected_reportable_txn int4,
                                                                         actual_reportable_txn int4,
                                                                         lookback_txn int4,
                                                                         lookback_future_reporting_txn int4,
                                                                         lookback_actual_txn int4,
                                                                         reporting_period_txn int4,
                                                                         reporting_period_future_reporting_txn int4,
                                                                         reporting_period_actual_txn int4,
                                                                         activity_selected int4,
                                                                         activity_missing int4,
                                                                         activity_simulated int4,
                                                                         expected_activity_eligible_for_transformation int4,
                                                                         actual_activity_eligible_for_transformation int4,
                                                                         activity_transformed int4,
                                                                         activity_transformation_failed int4,
                                                                         duplicate_transformation int4,
                                                                         created_timestamp timestamp,
                                                                         modified_timestamp timestamp,
                                                                         soft_dedup_dropped_txn_count int4,
                                                                         CONSTRAINT report_transformation_reconciliation_pkey
                                                                             PRIMARY KEY (rpt_grp_id, batch_id, seq_no)
);


-- "pharosRBT".pharos.rule_hit_exclusion_audit definition

-- Drop table

-- DROP TABLE "pharosRBT".pharos.rule_hit_exclusion_audit;

CREATE TABLE "pharosRBT".pharos.rule_hit_exclusion_audit (
                                                             attempt_id int8 NOT NULL,
                                                             rpt_grp_id int4 NOT NULL,
                                                             rule_id text NOT NULL,
                                                             bucket_id int4 NOT NULL,
                                                             rpt_grp_name text,
                                                             external_txn_key int8,
                                                             processing_batch_id text,
                                                             exclusion_reason_id text,
                                                             exclusion_strategy text,
                                                             reported_batch_id text,
                                                             mtcn text,
                                                             created_timestamp timestamp,
                                                             modified_timestamp timestamp,
                                                             reporting_timestamp timestamp,
                                                             CONSTRAINT rule_hit_exclusion_audit_pkey
                                                                 PRIMARY KEY (bucket_id, rpt_grp_id, rule_id, attempt_id)
);

-- The transaction evidence report filters this table by (rpt_grp_id, processing_batch_id), which
-- shares no leading column with the PK (bucket_id first) — without this index every lookup is a
-- full table scan regardless of how small the matching batch is.
CREATE INDEX rule_hit_exclusion_audit_batch_idx
    ON pharos.rule_hit_exclusion_audit (rpt_grp_id, processing_batch_id);


-- DROP TABLE pharos.report_group_config;

CREATE TABLE pharos.report_group_config (
                                            rpt_grp_id int4 NOT NULL,
                                            rpt_selection_version_id int4 NOT NULL,
                                            transformer_version_id text NOT NULL,
                                            ack_prf_docsubtype text NULL,
                                            additional_data text NULL,
                                            bizgrp_name text NULL,
                                            country_code text NULL,
                                            country_name text NULL,
                                            db_lookup_enabled bool NULL,
                                            inbound_rule_id text NULL,
                                            is_blank_report bool NULL,
                                            is_non_transactional_report bool NULL,
                                            is_partial_report bool NULL,
                                            mapping_project_key text NULL,
                                            mapping_service_name text NULL,
                                            created_timestamp timestamptz NULL,
                                            modified_timestamp timestamptz NULL,
                                            outbound_rule_id text NULL,
                                            output_file_docsubtype text NULL,
                                            reg_reportable_activity_columns text NULL,
                                            reg_rpt_type text NULL,
                                            region_code text NULL,
                                            region_name text NULL,
                                            report_currency text NULL,
                                            rpt_config_active_flag bool NULL,
                                            rpt_grp_name text NULL,
                                            rpt_period int4 NULL,
                                            rpt_selection text NULL,
                                            rule_hit_columns text NULL,
                                            submission_prf_docsubtype text NULL,
                                            transformer_config jsonb NULL,
                                            exclusion_strategy text NULL,
                                            exclusion_reason text NULL,
                                            column_to_compare text NULL,
                                            three_letter_country_code text NULL,
                                            manipulation_strategy_metadata jsonb NULL,
                                            reconciliation_strategy_metadata jsonb NULL,
                                            CONSTRAINT report_group_config_pkey
                                                PRIMARY KEY (
                                                             rpt_grp_id,
                                                             rpt_selection_version_id,
                                                             transformer_version_id
                                                    )
);


-- Drop table

-- DROP TABLE pharos.rule_hit;

CREATE TABLE pharos.rule_hit (
                                 rpt_grp_id int4 NOT NULL,
                                 bucket_id int4 NOT NULL,
                                 rule_id text NOT NULL,
                                 attempt_id int8 NOT NULL,
                                 activity_type text NULL,
                                 batch_id int4 NULL,
                                 created_timestamp timestamptz NULL,
                                 efile_batch_id text NULL,
                                 exclusion_reason_id text NULL,
                                 external_txn_key int8 NULL,
                                 galactic_id text NULL,
                                 is_reported bool NULL,
                                 modified_timestamp timestamptz NULL,
                                 mtcn text NULL,
                                 objective_aggregation_key text NULL,
                                 reporting_timestamp timestamp NULL,
                                 rpt_grp_name text NULL,
                                 rule_currency_amount numeric NULL,
                                 rule_iso_currency_code text NULL,
                                 send_date date NULL,
                                 "source" text NULL,
                                 transaction_date timestamp NULL,
                                 transaction_side text NULL,
                                 reported_batch_id text NULL,
                                 CONSTRAINT rule_hit_pkey PRIMARY KEY (
                                                                       rpt_grp_id,
                                                                       bucket_id,
                                                                       rule_id,
                                                                       attempt_id
                                     )
);

CREATE INDEX created_timestamp_rule_hit_idx
    ON pharos.rule_hit USING btree (created_timestamp);

CREATE INDEX efilebatchid_rule_hit_idx
    ON pharos.rule_hit USING btree (efile_batch_id);

-- Composite covering index for the evidence report's WHERE (rpt_grp_id, efile_batch_id) lookup;
-- the single-column index above still works via efile_batch_id's own selectivity, but this avoids
-- the extra Filter step at scale.
CREATE INDEX rule_hit_rptgrp_efilebatchid_idx
    ON pharos.rule_hit (rpt_grp_id, efile_batch_id);

CREATE INDEX idx_rule_hit_galactic_id
    ON pharos.rule_hit USING btree (galactic_id);

CREATE INDEX modified_timestamp_rule_hit_idx
    ON pharos.rule_hit USING btree (modified_timestamp);

CREATE INDEX reporting_timestamp_rule_hit_idx
    ON pharos.rule_hit USING btree (reporting_timestamp);

CREATE INDEX txn_sur_key_rule_hit_idx
    ON pharos.rule_hit USING btree (external_txn_key);

-- Drop table

-- DROP TABLE pharos.rule_hit_reconciliation;

CREATE TABLE pharos.rule_hit_reconciliation (
                                                rpt_grp_id int4 NOT NULL,
                                                run_date int4 NOT NULL,
                                                seq_no int4 NOT NULL,
                                                created_timestamp timestamptz NULL,
                                                data_selection_end_date timestamp NULL,
                                                data_selection_start_date timestamp NULL,
                                                distinct_rule_hits_count_iwra int4 NULL,
                                                distinct_rule_hits_count_pharos int4 NULL,
                                                missed_rule_hits_count_pharos int4 NULL,
                                                modified_timestamp timestamptz NULL,
                                                rpt_grp_name text NULL,
                                                rule_hit_publish_count_iwra int4 NULL,
                                                CONSTRAINT rule_hit_reconciliation_pkey PRIMARY KEY (
                                                                                                     rpt_grp_id,
                                                                                                     run_date,
                                                                                                     seq_no
                                                    )
);


