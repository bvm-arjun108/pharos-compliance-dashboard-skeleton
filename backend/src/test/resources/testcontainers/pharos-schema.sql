--
-- PostgreSQL database dump
--


-- Dumped from database version 16.8 (Debian 16.8-1.pgdg120+1)
-- Dumped by pg_dump version 16.15

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pharos; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA pharos;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: record_transformation_journey; Type: TABLE; Schema: pharos; Owner: -
--

CREATE TABLE pharos.record_transformation_journey (
    rpt_grp_id integer NOT NULL,
    batch_id text NOT NULL,
    identifier text NOT NULL,
    mtcn text,
    stage text,
    status text,
    comments text,
    created_timestamp timestamp with time zone,
    modified_timestamp timestamp with time zone,
    reporting_timestamp_latest timestamp without time zone,
    processing_complete boolean,
    txn_metadata jsonb,
    skip_reason text
);


--
-- Name: reg_reportable_activity; Type: TABLE; Schema: pharos; Owner: -
--

CREATE TABLE pharos.reg_reportable_activity (
    txn_sur_key bigint NOT NULL,
    associated_mtcn text,
    associated_txn_sur_key text,
    bl_customer_verification_source text,
    group_mtcn text,
    group_send_date text,
    latest_attempt_time timestamp without time zone,
    mtcn text,
    mtcn10 text,
    pa_intended_pay_out text,
    pa_pay_brand text,
    pa_pay_channel text,
    pa_pay_in text,
    pa_pay_out text,
    pa_pay_product_code text,
    pa_product text,
    pa_recording_channel text,
    pa_send_brand text,
    pa_send_product_code text,
    pa_send_speed_of_delivery text,
    r3_party_address_line_2 text,
    r3_party_city text,
    r3_party_country text,
    r3_party_date_of_birth text,
    r3_party_id_number text,
    r3_party_indorgflag text,
    r3_party_name text,
    r3_party_name_1 text,
    r3_party_name_2 text,
    r3_party_name_3 text,
    r3_party_nationality text,
    r3_party_occupation text,
    r3_party_phone_number text,
    r3_party_place_of_birth text,
    r3_party_state text,
    r3_party_zip_code text,
    r_3rd_party_address_line_1 text,
    r_act_on_others_behalf text,
    r_act_on_own_behalf text,
    r_agent_country text,
    r_agent_id text,
    r_agent_network_id text,
    r_agent_operator_name_1 text,
    r_agent_operator_name_2 text,
    r_agent_operator_name_3 text,
    r_attempt_id bigint,
    r_bank_city text,
    r_bank_code text,
    r_bank_name text,
    r_city text,
    r_cpc_currency_code text,
    r_currency text,
    r_date text,
    r_electronic_validation text,
    r_fx_feed_date text,
    r_fx_feed_id text,
    r_ip_address text,
    r_local_principal double precision,
    r_party_address_line_1 text,
    r_party_address_line_2 text,
    r_party_bank_account_number text,
    r_party_base_mobile_phone text,
    r_party_base_mobile_phone_prefix text,
    r_party_base_phone_number text,
    r_party_base_phone_number_prefix text,
    r_party_city text,
    r_party_city_of_birth text,
    r_party_country_of_birth text,
    r_party_country_of_residence text,
    r_party_date_of_birth text,
    r_party_email_address text,
    r_party_employer text,
    r_party_galactic_id text,
    r_party_gender text,
    r_party_id2_exp_date text,
    r_party_id2_issue_date text,
    r_party_id2_issuing_country text,
    r_party_id2_number text,
    r_party_id2type text,
    r_party_id3_number text,
    r_party_id3type text,
    r_party_id_expiration_date text,
    r_party_id_issue_date text,
    r_party_id_issuer_country text,
    r_party_id_issuing_agency text,
    r_party_id_issuing_authority_1 text,
    r_party_id_number text,
    r_party_id_type text,
    r_party_issuing_authority text,
    r_party_mobile_phone text,
    r_party_name text,
    r_party_name_1 text,
    r_party_name_2 text,
    r_party_name_3 text,
    r_party_name_4 text,
    r_party_nationality text,
    r_party_nature_of_work text,
    r_party_occupation text,
    r_party_pcp_number text,
    r_party_phone_number text,
    r_party_place_of_birth text,
    r_party_relationship text,
    r_party_state_province text,
    r_party_status_of_employment text,
    r_party_zipcode_postalcode text,
    r_province text,
    r_purpose_of_transaction text,
    r_routing_number text,
    r_source_of_funds text,
    r_template text,
    r_time text,
    r_us_principal double precision,
    refund_flag text,
    s3_party_address_line_2 text,
    s3_party_city text,
    s3_party_country text,
    s3_party_date_of_birth text,
    s3_party_id_number text,
    s3_party_indorgflag text,
    s3_party_name text,
    s3_party_name_1 text,
    s3_party_name_2 text,
    s3_party_name_3 text,
    s3_party_nationality text,
    s3_party_occupation text,
    s3_party_phone_number text,
    s3_party_place_of_birth text,
    s3_party_state text,
    s3_party_zip_code text,
    s_3rd_party_address_line_1 text,
    s_act_on_others_behalf text,
    s_act_on_own_behalf text,
    s_agent_country text,
    s_agent_id text,
    s_agent_network_id text,
    s_agent_operator_name_1 text,
    s_agent_operator_name_2 text,
    s_agent_operator_name_3 text,
    s_attempt_id bigint,
    s_bank_city text,
    s_bank_code text,
    s_bank_name text,
    s_city text,
    s_counter_party_address_line_1 text,
    s_counter_party_address_line_2 text,
    s_counter_party_city text,
    s_counter_party_country text,
    s_counter_party_state text,
    s_counter_party_zipcode_postalcode text,
    s_cpc_currency_code text,
    s_currency text,
    s_date text,
    s_electronic_validation text,
    s_fx_feed_date text,
    s_fx_feed_id text,
    s_intended_bank_city text,
    s_intended_bank_code text,
    s_intended_bank_name text,
    s_intended_city text,
    s_intended_country text,
    s_intended_party_address_line_1 text,
    s_intended_party_address_line_2 text,
    s_intended_party_bank_acct_num text,
    s_intended_party_city text,
    s_intended_party_country_of_res text,
    s_intended_party_email_address text,
    s_intended_party_mobile_phone text,
    s_intended_party_name text,
    s_intended_party_name_1 text,
    s_intended_party_name_2 text,
    s_intended_party_name_3 text,
    s_intended_party_name_4 text,
    s_intended_party_state_province text,
    s_intended_party_zipcode_postalcode text,
    s_intended_routing_number text,
    s_ip_address text,
    s_local_charges double precision,
    s_local_principal double precision,
    s_party_address_line_1 text,
    s_party_address_line_2 text,
    s_party_bank_account_number text,
    s_party_base_mobile_phone text,
    s_party_base_mobile_phone_prefix text,
    s_party_base_phone_number text,
    s_party_base_phone_number_prefix text,
    s_party_cc_number text,
    s_party_city text,
    s_party_city_of_birth text,
    s_party_country_of_birth text,
    s_party_country_of_residence text,
    s_party_date_of_birth text,
    s_party_email_address text,
    s_party_employer text,
    s_party_galactic_id text,
    s_party_gender text,
    s_party_id2_exp_date text,
    s_party_id2_issue_date text,
    s_party_id2_issuing_country text,
    s_party_id2_number text,
    s_party_id2type text,
    s_party_id3_number text,
    s_party_id3type text,
    s_party_id_expiration_date text,
    s_party_id_issue_date text,
    s_party_id_issuer_country text,
    s_party_id_issuing_agency text,
    s_party_id_issuing_authority_1 text,
    s_party_id_number text,
    s_party_id_type text,
    s_party_issuing_authority text,
    s_party_mobile_phone text,
    s_party_name text,
    s_party_name_1 text,
    s_party_name_2 text,
    s_party_name_3 text,
    s_party_name_4 text,
    s_party_nationality text,
    s_party_nature_of_work text,
    s_party_occupation text,
    s_party_pcp_number text,
    s_party_phone_number text,
    s_party_place_of_birth text,
    s_party_relationship text,
    s_party_state_province text,
    s_party_status_of_employment text,
    s_party_zipcode_postalcode text,
    s_province text,
    s_purpose_of_transaction text,
    s_routing_number text,
    s_source_of_funds text,
    s_template text,
    s_time text,
    s_us_charges double precision,
    s_us_principal double precision,
    sub_status text,
    txn_status text,
    urb_force_pay_flag text,
    urb_indicator text,
    created_timestamp timestamp with time zone,
    modified_timestamp timestamp with time zone,
    s_party_addr_verif_doc_issuing_authority text,
    r_party_addr_verif_doc_issuing_authority text,
    s_party_addr_verif_doc_issue_date text,
    r_party_addr_verif_doc_issue_date text,
    s_party_addr_verif_doc_number text,
    r_party_addr_verif_doc_number text,
    s_party_addr_verif_doc_type text,
    r_party_addr_verif_doc_type text,
    s_party_id2_issuer_authority text,
    r_party_id2_issuer_authority text,
    s_party_id3_issuing_country text,
    r_party_id3_issuing_country text,
    s_party_id3_issue_date text,
    r_party_id3_issue_date text,
    s_party_id3_exp_date text,
    r_party_id3_exp_date text,
    s_party_id3_issuer_authority text,
    r_party_id3_issuer_authority text,
    s_intended_party_base_phone_number_prefix text,
    s_intended_party_base_phone_number text,
    s_intended_party_base_mobile_phone_prefix text,
    s_intended_party_base_mobile_phone text,
    s_intended_party_phone_number text,
    s_intended_agent_country text,
    s_intended_party_occupation text,
    s_intended_party_nationality text,
    s_intended_party_galactic_id text,
    s_intended_party_date_of_birth text,
    r_party_bank_account_type text,
    s_account_holder_name text,
    r_account_holder_name text,
    s_intended_account_holder_name text,
    s_merchant_id text,
    r_merchant_id text,
    s_merchant_cat text,
    r_merchant_cat text,
    s_merchant_name text,
    r_merchant_name text,
    s_merchant_address text,
    r_merchant_address text,
    s_merchant_city text,
    r_merchant_city text,
    s_merchant_state text,
    r_merchant_state text,
    s_merchant_zip text,
    r_merchant_zip text,
    s_merchant_country text,
    r_merchant_country text,
    s_txn_desc_1 text,
    r_txn_desc_1 text,
    s_txn_desc_2 text,
    r_txn_desc_2 text,
    transaction_code text,
    transaction_number text,
    s_intended_party_bank_name text,
    s_intended_party_bank_city text,
    s_intended_party_routing_number text,
    s_wueco_flag text,
    r_wueco_flag text,
    s_state_of_birth text,
    r_state_of_birth text,
    s_bank_account_number_2 text,
    r_bank_account_number_2 text
);


--
-- Name: report_batch_info; Type: TABLE; Schema: pharos; Owner: -
--

CREATE TABLE pharos.report_batch_info (
    rpt_grp_id integer NOT NULL,
    batch_id character varying(255) NOT NULL,
    seq_no integer NOT NULL,
    batch_status character varying(255),
    compiler_status character varying(255),
    created_timestamp timestamp with time zone,
    created_user_id integer,
    modified_timestamp timestamp with time zone,
    process_timestamp timestamp without time zone,
    report_header jsonb,
    report_status character varying(255),
    rpt_grp_name character varying(255),
    transformer_mapping_version character varying(255),
    txn_end_timestamp timestamp without time zone,
    txn_start_timestamp timestamp without time zone,
    txn_lookback_start_timestamp timestamp without time zone,
    selection_version integer,
    created_user_email text,
    dms_ref_info jsonb
);


--
-- Name: report_group_config; Type: TABLE; Schema: pharos; Owner: -
--

CREATE TABLE pharos.report_group_config (
    rpt_grp_id integer NOT NULL,
    rpt_selection_version_id integer NOT NULL,
    transformer_version_id text NOT NULL,
    ack_prf_docsubtype text,
    additional_data text,
    bizgrp_name text,
    country_code text,
    country_name text,
    db_lookup_enabled boolean,
    inbound_rule_id text,
    is_blank_report boolean,
    is_non_transactional_report boolean,
    is_partial_report boolean,
    mapping_project_key text,
    mapping_service_name text,
    created_timestamp timestamp with time zone,
    modified_timestamp timestamp with time zone,
    outbound_rule_id text,
    output_file_docsubtype text,
    reg_reportable_activity_columns text,
    reg_rpt_type text,
    region_code text,
    region_name text,
    report_currency text,
    rpt_config_active_flag boolean,
    rpt_grp_name text,
    rpt_period integer,
    rpt_selection text,
    rule_hit_columns text,
    submission_prf_docsubtype text,
    transformer_config jsonb,
    exclusion_strategy text,
    exclusion_reason text,
    column_to_compare text,
    three_letter_country_code text,
    manipulation_strategy_metadata jsonb,
    reconciliation_strategy_metadata jsonb
);


--
-- Name: report_transformation_reconciliation; Type: TABLE; Schema: pharos; Owner: -
--

CREATE TABLE pharos.report_transformation_reconciliation (
    batch_id text NOT NULL,
    seq_no integer NOT NULL,
    rpt_grp_id integer NOT NULL,
    rpt_grp_name text,
    rpt_look_back_date text,
    rpt_from_date text,
    rpt_to_date text,
    txn_selected integer,
    txn_simulated integer,
    excluded_txn integer,
    txn_missing_attempt_count integer,
    already_reported_count integer,
    expected_reportable_txn integer,
    actual_reportable_txn integer,
    lookback_txn integer,
    lookback_future_reporting_txn integer,
    lookback_actual_txn integer,
    reporting_period_txn integer,
    reporting_period_future_reporting_txn integer,
    reporting_period_actual_txn integer,
    activity_selected integer,
    activity_missing integer,
    activity_simulated integer,
    expected_activity_eligible_for_transformation integer,
    actual_activity_eligible_for_transformation integer,
    activity_transformed integer,
    activity_transformation_failed integer,
    duplicate_transformation integer,
    created_timestamp timestamp without time zone,
    modified_timestamp timestamp without time zone,
    soft_dedup_dropped_txn_count integer
);


--
-- Name: rule_hit; Type: TABLE; Schema: pharos; Owner: -
--

CREATE TABLE pharos.rule_hit (
    rpt_grp_id integer NOT NULL,
    bucket_id integer NOT NULL,
    rule_id text NOT NULL,
    attempt_id bigint NOT NULL,
    activity_type text,
    batch_id integer,
    created_timestamp timestamp with time zone,
    efile_batch_id text,
    exclusion_reason_id text,
    external_txn_key bigint,
    galactic_id text,
    is_reported boolean,
    modified_timestamp timestamp with time zone,
    mtcn text,
    objective_aggregation_key text,
    reporting_timestamp timestamp without time zone,
    rpt_grp_name text,
    rule_currency_amount numeric,
    rule_iso_currency_code text,
    send_date date,
    source text,
    transaction_date timestamp without time zone,
    transaction_side text,
    reported_batch_id text
);


--
-- Name: rule_hit_exclusion_audit; Type: TABLE; Schema: pharos; Owner: -
--

CREATE TABLE pharos.rule_hit_exclusion_audit (
    attempt_id bigint NOT NULL,
    rpt_grp_id integer NOT NULL,
    rule_id text NOT NULL,
    bucket_id integer NOT NULL,
    rpt_grp_name text,
    external_txn_key bigint,
    processing_batch_id text,
    exclusion_reason_id text,
    exclusion_strategy text,
    reported_batch_id text,
    mtcn text,
    created_timestamp timestamp without time zone,
    modified_timestamp timestamp without time zone,
    reporting_timestamp timestamp without time zone
);


--
-- Name: rule_hit_reconciliation; Type: TABLE; Schema: pharos; Owner: -
--

CREATE TABLE pharos.rule_hit_reconciliation (
    rpt_grp_id integer NOT NULL,
    run_date integer NOT NULL,
    seq_no integer NOT NULL,
    created_timestamp timestamp with time zone,
    data_selection_end_date timestamp without time zone,
    data_selection_start_date timestamp without time zone,
    distinct_rule_hits_count_iwra integer,
    distinct_rule_hits_count_pharos integer,
    missed_rule_hits_count_pharos integer,
    modified_timestamp timestamp with time zone,
    rpt_grp_name text,
    rule_hit_publish_count_iwra integer
);


--
-- Name: record_transformation_journey record_transformation_journey_pkey; Type: CONSTRAINT; Schema: pharos; Owner: -
--

ALTER TABLE ONLY pharos.record_transformation_journey
    ADD CONSTRAINT record_transformation_journey_pkey PRIMARY KEY (rpt_grp_id, batch_id, identifier);


--
-- Name: reg_reportable_activity reg_reportable_activity_pkey; Type: CONSTRAINT; Schema: pharos; Owner: -
--

ALTER TABLE ONLY pharos.reg_reportable_activity
    ADD CONSTRAINT reg_reportable_activity_pkey PRIMARY KEY (txn_sur_key);


--
-- Name: report_batch_info report_batch_info_pkey; Type: CONSTRAINT; Schema: pharos; Owner: -
--

ALTER TABLE ONLY pharos.report_batch_info
    ADD CONSTRAINT report_batch_info_pkey PRIMARY KEY (rpt_grp_id, batch_id, seq_no);


--
-- Name: report_group_config report_group_config_pkey; Type: CONSTRAINT; Schema: pharos; Owner: -
--

ALTER TABLE ONLY pharos.report_group_config
    ADD CONSTRAINT report_group_config_pkey PRIMARY KEY (rpt_grp_id, rpt_selection_version_id, transformer_version_id);


--
-- Name: report_transformation_reconciliation report_transformation_reconciliation_pkey; Type: CONSTRAINT; Schema: pharos; Owner: -
--

ALTER TABLE ONLY pharos.report_transformation_reconciliation
    ADD CONSTRAINT report_transformation_reconciliation_pkey PRIMARY KEY (rpt_grp_id, batch_id, seq_no);


--
-- Name: rule_hit_exclusion_audit rule_hit_exclusion_audit_pkey; Type: CONSTRAINT; Schema: pharos; Owner: -
--

ALTER TABLE ONLY pharos.rule_hit_exclusion_audit
    ADD CONSTRAINT rule_hit_exclusion_audit_pkey PRIMARY KEY (bucket_id, rpt_grp_id, rule_id, attempt_id);


--
-- Name: rule_hit rule_hit_pkey; Type: CONSTRAINT; Schema: pharos; Owner: -
--

ALTER TABLE ONLY pharos.rule_hit
    ADD CONSTRAINT rule_hit_pkey PRIMARY KEY (rpt_grp_id, bucket_id, rule_id, attempt_id);


--
-- Name: rule_hit_reconciliation rule_hit_reconciliation_pkey; Type: CONSTRAINT; Schema: pharos; Owner: -
--

ALTER TABLE ONLY pharos.rule_hit_reconciliation
    ADD CONSTRAINT rule_hit_reconciliation_pkey PRIMARY KEY (rpt_grp_id, run_date, seq_no);


--
-- Name: atttimestamp_reg_reportable_activity_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX atttimestamp_reg_reportable_activity_idx ON pharos.reg_reportable_activity USING btree (latest_attempt_time);


--
-- Name: created_timestamp_reg_reportable_activity_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX created_timestamp_reg_reportable_activity_idx ON pharos.reg_reportable_activity USING btree (created_timestamp);


--
-- Name: created_timestamp_rule_hit_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX created_timestamp_rule_hit_idx ON pharos.rule_hit USING btree (created_timestamp);


--
-- Name: efilebatchid_rule_hit_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX efilebatchid_rule_hit_idx ON pharos.rule_hit USING btree (efile_batch_id);


--
-- Name: idx_journey_transformation_failure; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX idx_journey_transformation_failure ON pharos.record_transformation_journey USING btree (rpt_grp_id, batch_id, identifier) WHERE ((upper(stage) = 'TRANSFORMATION'::text) AND (upper(status) = ANY (ARRAY['ERROR'::text, 'FAILED'::text, 'FAILURE'::text])));


--
-- Name: idx_reg_reportable_activity_mtcn; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX idx_reg_reportable_activity_mtcn ON pharos.reg_reportable_activity USING btree (mtcn);


--
-- Name: idx_reg_reportable_activity_r_party; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX idx_reg_reportable_activity_r_party ON pharos.reg_reportable_activity USING btree (r_party_galactic_id);


--
-- Name: idx_reg_reportable_activity_r_party_sdate; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX idx_reg_reportable_activity_r_party_sdate ON pharos.reg_reportable_activity USING btree (r_party_galactic_id, s_date);


--
-- Name: idx_reg_reportable_activity_s_party; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX idx_reg_reportable_activity_s_party ON pharos.reg_reportable_activity USING btree (s_party_galactic_id);


--
-- Name: idx_reg_reportable_activity_s_party_sdate; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX idx_reg_reportable_activity_s_party_sdate ON pharos.reg_reportable_activity USING btree (s_party_galactic_id, s_date);


--
-- Name: idx_rule_hit_galactic_id; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX idx_rule_hit_galactic_id ON pharos.rule_hit USING btree (galactic_id);


--
-- Name: modified_timestamp_reg_reportable_activity_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX modified_timestamp_reg_reportable_activity_idx ON pharos.reg_reportable_activity USING btree (modified_timestamp);


--
-- Name: modified_timestamp_rule_hit_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX modified_timestamp_rule_hit_idx ON pharos.rule_hit USING btree (modified_timestamp);


--
-- Name: record_transformation_journey_identifier_bigint_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX record_transformation_journey_identifier_bigint_idx ON pharos.record_transformation_journey USING btree (rpt_grp_id, batch_id, ((identifier)::bigint)) WHERE (identifier ~ '^[0-9]+$'::text);


--
-- Name: record_transformation_journey_mtcn_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX record_transformation_journey_mtcn_idx ON pharos.record_transformation_journey USING btree (rpt_grp_id, batch_id, mtcn);


--
-- Name: reporting_timestamp_rule_hit_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX reporting_timestamp_rule_hit_idx ON pharos.rule_hit USING btree (reporting_timestamp);


--
-- Name: rule_hit_exclusion_audit_batch_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX rule_hit_exclusion_audit_batch_idx ON pharos.rule_hit_exclusion_audit USING btree (rpt_grp_id, processing_batch_id);


--
-- Name: rule_hit_rptgrp_efilebatchid_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX rule_hit_rptgrp_efilebatchid_idx ON pharos.rule_hit USING btree (rpt_grp_id, efile_batch_id);


--
-- Name: txn_status_reg_reportable_activity_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX txn_status_reg_reportable_activity_idx ON pharos.reg_reportable_activity USING btree (txn_status);


--
-- Name: txn_sur_key_rule_hit_idx; Type: INDEX; Schema: pharos; Owner: -
--

CREATE INDEX txn_sur_key_rule_hit_idx ON pharos.rule_hit USING btree (external_txn_key);


--
-- PostgreSQL database dump complete
--


