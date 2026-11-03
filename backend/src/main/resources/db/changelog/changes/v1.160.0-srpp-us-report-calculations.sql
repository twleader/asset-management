--liquibase formatted sql
--changeset steven:v1.160.0-srpp-us-report-calculations splitStatements:false
--comment: Task542 explicit immutable report/audit snapshot denormalization; current observation is consumer/market/symbol isolated. No trading API.
CREATE TABLE IF NOT EXISTS srpp_us_watch_import (
 owner_user_id bigint NOT NULL REFERENCES app_user(id) CHECK(owner_user_id BETWEEN 1 AND 9007199254740991),
 consumer varchar(16) NOT NULL CHECK(consumer IN ('Codex','Claude')),
 market varchar(2) NOT NULL CHECK(market='US'),
 ledger_sha256 char(64) NOT NULL CHECK(ledger_sha256 ~ '^[0-9a-f]{64}$'),
 watch_policy_sha256 char(64) NOT NULL CHECK(watch_policy_sha256 ~ '^[0-9a-f]{64}$'),
 evidence_jcs text NOT NULL CHECK(length(evidence_jcs)>2), imported_at timestamptz NOT NULL,
 PRIMARY KEY(owner_user_id,consumer,market)
);
CREATE TABLE IF NOT EXISTS srpp_us_watch_state (
 owner_user_id bigint NOT NULL, consumer varchar(16) NOT NULL, market varchar(2) NOT NULL CHECK(market='US'), symbol varchar(20) NOT NULL CHECK(length(symbol)>0),
 version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),
 state_jcs text NOT NULL CHECK(length(state_jcs)>2), state_sha256 char(64) NOT NULL CHECK(state_sha256 ~ '^[0-9a-f]{64}$'), updated_at timestamptz NOT NULL,
 PRIMARY KEY(owner_user_id,consumer,market,symbol),
 FOREIGN KEY(owner_user_id,consumer,market) REFERENCES srpp_us_watch_import(owner_user_id,consumer,market)
);
CREATE TABLE IF NOT EXISTS srpp_us_report_package (
 id uuid PRIMARY KEY, owner_user_id bigint NOT NULL REFERENCES app_user(id) CHECK(owner_user_id BETWEEN 1 AND 9007199254740991),
 consumer varchar(16) NOT NULL CHECK(consumer IN ('Codex','Claude')), market varchar(2) NOT NULL CHECK(market='US'), session_date date NOT NULL,
 report_slot varchar(24) NOT NULL CHECK(report_slot='US_OPEN_0945'),
 policy_bundle_sha256 char(64) NOT NULL REFERENCES srpp_policy_registry(policy_bundle_sha256) CHECK(policy_bundle_sha256 ~ '^[0-9a-f]{64}$'),
 swagger_sha256 char(64) NOT NULL CHECK(swagger_sha256 ~ '^[0-9a-f]{64}$'), calculator_version varchar(96) NOT NULL,
 source_fingerprint_sha256 char(64) NOT NULL CHECK(source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'), input_snapshot_sha256 char(64) NOT NULL CHECK(input_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 content_sha256 char(64) NOT NULL CHECK(content_sha256 ~ '^[0-9a-f]{64}$'), content_jcs text NOT NULL CHECK(length(content_jcs)>2), published_at timestamptz NOT NULL,
 CONSTRAINT srpp_us_report_source_uq UNIQUE(owner_user_id,consumer,market,session_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,source_fingerprint_sha256),
 CONSTRAINT srpp_us_report_tenant_uq UNIQUE(id,owner_user_id,consumer,market)
);
CREATE INDEX IF NOT EXISTS idx_srpp_us_report_latest ON srpp_us_report_package(owner_user_id,consumer,market,session_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,published_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS srpp_us_watch_transition (
 id uuid PRIMARY KEY,package_id uuid NOT NULL,owner_user_id bigint NOT NULL,consumer varchar(16) NOT NULL,market varchar(2) NOT NULL CHECK(market='US'),symbol varchar(20) NOT NULL,
 version_before bigint NOT NULL CHECK(version_before>=0),version_after bigint NOT NULL CHECK(version_after>=version_before AND version_after<=version_before+1),
 state_before_jcs text NOT NULL,state_after_jcs text NOT NULL,state_before_sha256 char(64) NOT NULL CHECK(state_before_sha256 ~ '^[0-9a-f]{64}$'),state_after_sha256 char(64) NOT NULL CHECK(state_after_sha256 ~ '^[0-9a-f]{64}$'),
 source_fingerprint_sha256 char(64) NOT NULL CHECK(source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),calculator_version varchar(96) NOT NULL,created_at timestamptz NOT NULL,
 FOREIGN KEY(package_id,owner_user_id,consumer,market) REFERENCES srpp_us_report_package(id,owner_user_id,consumer,market),
 CONSTRAINT srpp_us_watch_transition_source_uq UNIQUE(owner_user_id,consumer,market,symbol,source_fingerprint_sha256,calculator_version)
);
DROP TRIGGER IF EXISTS trg_srpp_us_watch_import_immutable ON srpp_us_watch_import;
CREATE TRIGGER trg_srpp_us_watch_import_immutable BEFORE UPDATE OR DELETE ON srpp_us_watch_import FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();
DROP TRIGGER IF EXISTS trg_srpp_us_report_immutable ON srpp_us_report_package;
CREATE TRIGGER trg_srpp_us_report_immutable BEFORE UPDATE OR DELETE ON srpp_us_report_package FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();
DROP TRIGGER IF EXISTS trg_srpp_us_transition_immutable ON srpp_us_watch_transition;
CREATE TRIGGER trg_srpp_us_transition_immutable BEFORE UPDATE OR DELETE ON srpp_us_watch_transition FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();
CREATE OR REPLACE FUNCTION guard_srpp_us_watch_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR NEW.version<>OLD.version+1 OR NEW.owner_user_id<>OLD.owner_user_id OR NEW.consumer<>OLD.consumer OR NEW.market<>OLD.market OR NEW.symbol<>OLD.symbol THEN
  RAISE EXCEPTION 'US_WATCH_VERSION_OR_IDENTITY_CONFLICT';
 END IF;
 RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_srpp_us_watch_version ON srpp_us_watch_state;
CREATE TRIGGER trg_srpp_us_watch_version BEFORE UPDATE OR DELETE ON srpp_us_watch_state FOR EACH ROW EXECUTE FUNCTION guard_srpp_us_watch_version();
INSERT INTO api_error_log_operation(source,operation_key,operation_label,display_order) VALUES('OPEN_API','OPEN_SRPP_US_REPORT_CALCULATIONS','SRPP 美股不可變報告計算包',242) ON CONFLICT(source,operation_key) DO NOTHING;
