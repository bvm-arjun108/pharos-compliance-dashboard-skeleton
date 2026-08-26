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


-- DROP TABLE pharos.reg_reportable_activity;

CREATE TABLE pharos.reg_reportable_activity (
                                                txn_sur_key int8 NOT NULL,
                                                associated_mtcn text NULL,
                                                associated_txn_sur_key text NULL,
                                                bl_customer_verification_source text NULL,
                                                group_mtcn text NULL,
                                                group_send_date text NULL,
                                                latest_attempt_time timestamp NULL,
                                                mtcn text NULL,
                                                mtcn10 text NULL,
                                                pa_intended_pay_out text NULL,
                                                pa_pay_brand text NULL,
                                                pa_pay_channel text NULL,
                                                pa_pay_in text NULL,
                                                pa_pay_out text NULL,
                                                pa_pay_product_code text NULL,
                                                pa_product text NULL,
                                                pa_recording_channel text NULL,
                                                pa_send_brand text NULL,
                                                pa_send_product_code text NULL,
                                                pa_send_speed_of_delivery text NULL,
                                                r3_party_address_line_2 text NULL,
                                                r3_party_city text NULL,
                                                r3_party_country text NULL,
                                                r3_party_date_of_birth text NULL,
                                                r3_party_id_number text NULL,
                                                r3_party_indorgflag text NULL,
                                                r3_party_name text NULL,
                                                r3_party_name_1 text NULL,
                                                r3_party_name_2 text NULL,
                                                r3_party_name_3 text NULL,
                                                r3_party_nationality text NULL,
                                                r3_party_occupation text NULL,
                                                r3_party_phone_number text NULL,
                                                r3_party_place_of_birth text NULL,
                                                r3_party_state text NULL,
                                                r3_party_zip_code text NULL,
                                                r_3rd_party_address_line_1 text NULL,
                                                r_act_on_others_behalf text NULL,
                                                r_act_on_own_behalf text NULL,
                                                r_agent_country text NULL,
                                                r_agent_id text NULL,
                                                r_agent_network_id text NULL,
                                                r_agent_operator_name_1 text NULL,
                                                r_agent_operator_name_2 text NULL,
                                                r_agent_operator_name_3 text NULL,
                                                r_attempt_id int8 NULL,
                                                r_bank_city text NULL,
                                                r_bank_code text NULL,
                                                r_bank_name text NULL,
                                                r_city text NULL,
                                                r_cpc_currency_code text NULL,
                                                r_currency text NULL,
                                                r_date text NULL,
                                                r_electronic_validation text NULL,
                                                r_fx_feed_date text NULL,
                                                r_fx_feed_id text NULL,
                                                r_ip_address text NULL,
                                                r_local_principal float8 NULL,
                                                r_party_address_line_1 text NULL,
                                                r_party_address_line_2 text NULL,
                                                r_party_bank_account_number text NULL,
                                                r_party_base_mobile_phone text NULL,
                                                r_party_base_mobile_phone_prefix text NULL,
                                                r_party_base_phone_number text NULL,
                                                r_party_base_phone_number_prefix text NULL,
                                                r_party_city text NULL,
                                                r_party_city_of_birth text NULL,
                                                r_party_country_of_birth text NULL,
                                                r_party_country_of_residence text NULL,
                                                r_party_date_of_birth text NULL,
                                                r_party_email_address text NULL,
                                                r_party_employer text NULL,
                                                r_party_galactic_id text NULL,
                                                r_party_gender text NULL,
                                                r_party_id2_exp_date text NULL,
                                                r_party_id2_issue_date text NULL,
                                                r_party_id2_issuing_country text NULL,
                                                r_party_id2_number text NULL,
                                                r_party_id2type text NULL,
                                                r_party_id3_number text NULL,
                                                r_party_id3type text NULL,
                                                r_party_id_expiration_date text NULL,
                                                r_party_id_issue_date text NULL,
                                                r_party_id_issuer_country text NULL,
                                                r_party_id_issuing_agency text NULL,
                                                r_party_id_issuing_authority_1 text NULL,
                                                r_party_id_number text NULL,
                                                r_party_id_type text NULL,
                                                r_party_issuing_authority text NULL,
                                                r_party_mobile_phone text NULL,
                                                r_party_name text NULL,
                                                r_party_name_1 text NULL,
                                                r_party_name_2 text NULL,
                                                r_party_name_3 text NULL,
                                                r_party_name_4 text NULL,
                                                r_party_nationality text NULL,
                                                r_party_nature_of_work text NULL,
                                                r_party_occupation text NULL,
                                                r_party_pcp_number text NULL,
                                                r_party_phone_number text NULL,
                                                r_party_place_of_birth text NULL,
                                                r_party_relationship text NULL,
                                                r_party_state_province text NULL,
                                                r_party_status_of_employment text NULL,
                                                r_party_zipcode_postalcode text NULL,
                                                r_province text NULL,
                                                r_purpose_of_transaction text NULL,
                                                r_routing_number text NULL,
                                                r_source_of_funds text NULL,
                                                r_template text NULL,
                                                r_time text NULL,
                                                r_us_principal float8 NULL,
                                                refund_flag text NULL,
                                                s3_party_address_line_2 text NULL,
                                                s3_party_city text NULL,
                                                s3_party_country text NULL,
                                                s3_party_date_of_birth text NULL,
                                                s3_party_id_number text NULL,
                                                s3_party_indorgflag text NULL,
                                                s3_party_name text NULL,
                                                s3_party_name_1 text NULL,
                                                s3_party_name_2 text NULL,
                                                s3_party_name_3 text NULL,
                                                s3_party_nationality text NULL,
                                                s3_party_occupation text NULL,
                                                s3_party_phone_number text NULL,
                                                s3_party_place_of_birth text NULL,
                                                s3_party_state text NULL,
                                                s3_party_zip_code text NULL,
                                                s_3rd_party_address_line_1 text NULL,
                                                s_act_on_others_behalf text NULL,
                                                s_act_on_own_behalf text NULL,
                                                s_agent_country text NULL,
                                                s_agent_id text NULL,
                                                s_agent_network_id text NULL,
                                                s_agent_operator_name_1 text NULL,
                                                s_agent_operator_name_2 text NULL,
                                                s_agent_operator_name_3 text NULL,
                                                s_attempt_id int8 NULL,
                                                s_bank_city text NULL,
                                                s_bank_code text NULL,
                                                s_bank_name text NULL,
                                                s_city text NULL,
                                                s_counter_party_address_line_1 text NULL,
                                                s_counter_party_address_line_2 text NULL,
                                                s_counter_party_city text NULL,
                                                s_counter_party_country text NULL,
                                                s_counter_party_state text NULL,
                                                s_counter_party_zipcode_postalcode text NULL,
                                                s_cpc_currency_code text NULL,
                                                s_currency text NULL,
                                                s_date text NULL,
                                                s_electronic_validation text NULL,
                                                s_fx_feed_date text NULL,
                                                s_fx_feed_id text NULL,
                                                s_intended_bank_city text NULL,
                                                s_intended_bank_code text NULL,
                                                s_intended_bank_name text NULL,
                                                s_intended_city text NULL,
                                                s_intended_country text NULL,
                                                s_intended_party_address_line_1 text NULL,
                                                s_intended_party_address_line_2 text NULL,
                                                s_intended_party_bank_acct_num text NULL,
                                                s_intended_party_city text NULL,
                                                s_intended_party_country_of_res text NULL,
                                                s_intended_party_email_address text NULL,
                                                s_intended_party_mobile_phone text NULL,
                                                s_intended_party_name text NULL,
                                                s_intended_party_name_1 text NULL,
                                                s_intended_party_name_2 text NULL,
                                                s_intended_party_name_3 text NULL,
                                                s_intended_party_name_4 text NULL,
                                                s_intended_party_state_province text NULL,
                                                s_intended_party_zipcode_postalcode text NULL,
                                                s_intended_routing_number text NULL,
                                                s_ip_address text NULL,
                                                s_local_charges float8 NULL,
                                                s_local_principal float8 NULL,
                                                s_party_address_line_1 text NULL,
                                                s_party_address_line_2 text NULL,
                                                s_party_bank_account_number text NULL,
                                                s_party_base_mobile_phone text NULL,
                                                s_party_base_mobile_phone_prefix text NULL,
                                                s_party_base_phone_number text NULL,
                                                s_party_base_phone_number_prefix text NULL,
                                                s_party_cc_number text NULL,
                                                s_party_city text NULL,
                                                s_party_city_of_birth text NULL,
                                                s_party_country_of_birth text NULL,
                                                s_party_country_of_residence text NULL,
                                                s_party_date_of_birth text NULL,
                                                s_party_email_address text NULL,
                                                s_party_employer text NULL,
                                                s_party_galactic_id text NULL,
                                                s_party_gender text NULL,
                                                s_party_id2_exp_date text NULL,
                                                s_party_id2_issue_date text NULL,
                                                s_party_id2_issuing_country text NULL,
                                                s_party_id2_number text NULL,
                                                s_party_id2type text NULL,
                                                s_party_id3_number text NULL,
                                                s_party_id3type text NULL,
                                                s_party_id_expiration_date text NULL,
                                                s_party_id_issue_date text NULL,
                                                s_party_id_issuer_country text NULL,
                                                s_party_id_issuing_agency text NULL,
                                                s_party_id_issuing_authority_1 text NULL,
                                                s_party_id_number text NULL,
                                                s_party_id_type text NULL,
                                                s_party_issuing_authority text NULL,
                                                s_party_mobile_phone text NULL,
                                                s_party_name text NULL,
                                                s_party_name_1 text NULL,
                                                s_party_name_2 text NULL,
                                                s_party_name_3 text NULL,
                                                s_party_name_4 text NULL,
                                                s_party_nationality text NULL,
                                                s_party_nature_of_work text NULL,
                                                s_party_occupation text NULL,
                                                s_party_pcp_number text NULL,
                                                s_party_phone_number text NULL,
                                                s_party_place_of_birth text NULL,
                                                s_party_relationship text NULL,
                                                s_party_state_province text NULL,
                                                s_party_status_of_employment text NULL,
                                                s_party_zipcode_postalcode text NULL,
                                                s_province text NULL,
                                                s_purpose_of_transaction text NULL,
                                                s_routing_number text NULL,
                                                s_source_of_funds text NULL,
                                                s_template text NULL,
                                                s_time text NULL,
                                                s_us_charges float8 NULL,
                                                s_us_principal float8 NULL,
                                                sub_status text NULL,
                                                txn_status text NULL,
                                                urb_force_pay_flag text NULL,
                                                urb_indicator text NULL,
                                                created_timestamp timestamptz NULL,
                                                modified_timestamp timestamptz NULL,
                                                s_party_addr_verif_doc_issuing_authority text NULL,
                                                r_party_addr_verif_doc_issuing_authority text NULL,
                                                s_party_addr_verif_doc_issue_date text NULL,
                                                r_party_addr_verif_doc_issue_date text NULL,
                                                s_party_addr_verif_doc_number text NULL,
                                                r_party_addr_verif_doc_number text NULL,
                                                s_party_addr_verif_doc_type text NULL,
                                                r_party_addr_verif_doc_type text NULL,
                                                s_party_id2_issuer_authority text NULL,
                                                r_party_id2_issuer_authority text NULL,
                                                s_party_id3_issuing_country text NULL,
                                                r_party_id3_issuing_country text NULL,
                                                s_party_id3_issue_date text NULL,
                                                r_party_id3_issue_date text NULL,
                                                s_party_id3_exp_date text NULL,
                                                r_party_id3_exp_date text NULL,
                                                s_party_id3_issuer_authority text NULL,
                                                r_party_id3_issuer_authority text NULL,
                                                s_intended_party_base_phone_number_prefix text NULL,
                                                s_intended_party_base_phone_number text NULL,
                                                s_intended_party_base_mobile_phone_prefix text NULL,
                                                s_intended_party_base_mobile_phone text NULL,
                                                s_intended_party_phone_number text NULL,
                                                s_intended_agent_country text NULL,
                                                s_intended_party_occupation text NULL,
                                                s_intended_party_nationality text NULL,
                                                s_intended_party_galactic_id text NULL,
                                                s_intended_party_date_of_birth text NULL,
                                                r_party_bank_account_type text NULL,
                                                s_account_holder_name text NULL,
                                                r_account_holder_name text NULL,
                                                s_intended_account_holder_name text NULL,
                                                s_merchant_id text NULL,
                                                r_merchant_id text NULL,
                                                s_merchant_cat text NULL,
                                                r_merchant_cat text NULL,
                                                s_merchant_name text NULL,
                                                r_merchant_name text NULL,
                                                s_merchant_address text NULL,
                                                r_merchant_address text NULL,
                                                s_merchant_city text NULL,
                                                r_merchant_city text NULL,
                                                s_merchant_state text NULL,
                                                r_merchant_state text NULL,
                                                s_merchant_zip text NULL,
                                                r_merchant_zip text NULL,
                                                s_merchant_country text NULL,
                                                r_merchant_country text NULL,
                                                s_txn_desc_1 text NULL,
                                                r_txn_desc_1 text NULL,
                                                s_txn_desc_2 text NULL,
                                                r_txn_desc_2 text NULL,
                                                transaction_code text NULL,
                                                transaction_number text NULL,
                                                s_intended_party_bank_name text NULL,
                                                s_intended_party_bank_city text NULL,
                                                s_intended_party_routing_number text NULL,
                                                s_wueco_flag text NULL,
                                                r_wueco_flag text NULL,
                                                s_state_of_birth text NULL,
                                                r_state_of_birth text NULL,
                                                s_bank_account_number_2 text NULL,
                                                r_bank_account_number_2 text NULL,
                                                CONSTRAINT reg_reportable_activity_pkey PRIMARY KEY (txn_sur_key)
);
CREATE INDEX atttimestamp_reg_reportable_activity_idx ON pharos.reg_reportable_activity USING btree (latest_attempt_time);
CREATE INDEX created_timestamp_reg_reportable_activity_idx ON pharos.reg_reportable_activity USING btree (created_timestamp);
CREATE INDEX idx_reg_reportable_activity_mtcn ON pharos.reg_reportable_activity USING btree (mtcn);
CREATE INDEX idx_reg_reportable_activity_r_party ON pharos.reg_reportable_activity USING btree (r_party_galactic_id);
CREATE INDEX idx_reg_reportable_activity_r_party_sdate ON pharos.reg_reportable_activity USING btree (r_party_galactic_id, s_date);
CREATE INDEX idx_reg_reportable_activity_s_party ON pharos.reg_reportable_activity USING btree (s_party_galactic_id);
CREATE INDEX idx_reg_reportable_activity_s_party_sdate ON pharos.reg_reportable_activity USING btree (s_party_galactic_id, s_date);
CREATE INDEX modified_timestamp_reg_reportable_activity_idx ON pharos.reg_reportable_activity USING btree (modified_timestamp);
CREATE INDEX txn_status_reg_reportable_activity_idx ON pharos.reg_reportable_activity USING btree (txn_status);
