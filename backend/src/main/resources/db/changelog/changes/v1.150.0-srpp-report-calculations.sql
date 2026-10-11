--liquibase formatted sql
--changeset steven:v1.150.0-srpp-report-calculations splitStatements:false
--comment: Task 489 intentional immutable audit denormalization of indexed identity and canonical envelope. Published bases/packages are insert-only and atomically committed.
CREATE TABLE IF NOT EXISTS srpp_report_calculation_base (
 id uuid PRIMARY KEY,
 owner_user_id bigint NOT NULL REFERENCES app_user(id) CHECK(owner_user_id BETWEEN 1 AND 9007199254740991),
 trading_date date NOT NULL, report_slot varchar(5) NOT NULL CHECK(report_slot IN ('09:05','12:00')),
 policy_bundle_sha256 char(64) NOT NULL REFERENCES srpp_policy_registry(policy_bundle_sha256) CHECK(policy_bundle_sha256 ~ '^[0-9a-f]{64}$'),
 swagger_sha256 char(64) NOT NULL CHECK(swagger_sha256 ~ '^[0-9a-f]{64}$'),
 calculator_version varchar(96) NOT NULL CHECK(length(calculator_version) BETWEEN 1 AND 96),
 source_fingerprint_sha256 char(64) NOT NULL CHECK(source_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
 captured_at timestamptz NOT NULL, asset_generated_at timestamptz NOT NULL CHECK(asset_generated_at <= captured_at),
 asset_snapshot_id bigint NOT NULL CHECK(asset_snapshot_id BETWEEN 1 AND 9007199254740991),
 valuation_capture_id uuid NOT NULL,
 input_jcs text NOT NULL CHECK(length(input_jcs)>2), source_vector_jcs text NOT NULL CHECK(length(source_vector_jcs)>2),
 shared_modules_jcs text NOT NULL CHECK(length(shared_modules_jcs)>2),
 shared_content_sha256 char(64) NOT NULL CHECK(shared_content_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL CHECK(created_at >= captured_at),
 CONSTRAINT srpp_report_base_source_uq UNIQUE(owner_user_id,trading_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,source_fingerprint_sha256),
 CONSTRAINT srpp_report_base_tenant_identity_uq UNIQUE(id,owner_user_id,trading_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version)
);
CREATE TABLE IF NOT EXISTS srpp_report_calculation_package (
 id uuid PRIMARY KEY,
 shared_calculation_id uuid NOT NULL,
 owner_user_id bigint NOT NULL REFERENCES app_user(id) CHECK(owner_user_id BETWEEN 1 AND 9007199254740991),
 consumer varchar(16) NOT NULL CHECK(consumer IN ('Codex','Claude')),
 trading_date date NOT NULL, report_slot varchar(5) NOT NULL CHECK(report_slot IN ('09:05','12:00')),
 policy_bundle_sha256 char(64) NOT NULL REFERENCES srpp_policy_registry(policy_bundle_sha256) CHECK(policy_bundle_sha256 ~ '^[0-9a-f]{64}$'),
 swagger_sha256 char(64) NOT NULL CHECK(swagger_sha256 ~ '^[0-9a-f]{64}$'),
 calculator_version varchar(96) NOT NULL CHECK(length(calculator_version) BETWEEN 1 AND 96),
 consumer_state_sha256 char(64) NOT NULL CHECK(consumer_state_sha256 ~ '^[0-9a-f]{64}$'),
 status varchar(16) NOT NULL CHECK(status='FINAL'),
 input_snapshot_sha256 char(64) NOT NULL CHECK(input_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 content_jcs text NOT NULL CHECK(length(content_jcs)>2), content_sha256 char(64) NOT NULL CHECK(content_sha256 ~ '^[0-9a-f]{64}$'),
 published_at timestamptz NOT NULL,
 CONSTRAINT srpp_report_package_base_identity_fk FOREIGN KEY(shared_calculation_id,owner_user_id,trading_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version) REFERENCES srpp_report_calculation_base(id,owner_user_id,trading_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version),
 CONSTRAINT srpp_report_package_state_uq UNIQUE(shared_calculation_id,consumer,consumer_state_sha256),
 CONSTRAINT srpp_report_package_input_uq UNIQUE(owner_user_id,consumer,trading_date,report_slot,policy_bundle_sha256,swagger_sha256,input_snapshot_sha256,calculator_version)
);
CREATE INDEX IF NOT EXISTS idx_srpp_report_package_latest ON srpp_report_calculation_package(owner_user_id,consumer,trading_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,published_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_srpp_report_base_owner_date ON srpp_report_calculation_base(owner_user_id,trading_date,report_slot,created_at DESC);
DROP TRIGGER IF EXISTS trg_srpp_report_base_no_update ON srpp_report_calculation_base;
CREATE TRIGGER trg_srpp_report_base_no_update BEFORE UPDATE OR DELETE ON srpp_report_calculation_base FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();
DROP TRIGGER IF EXISTS trg_srpp_report_package_no_update ON srpp_report_calculation_package;
CREATE TRIGGER trg_srpp_report_package_no_update BEFORE UPDATE OR DELETE ON srpp_report_calculation_package FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();
INSERT INTO api_error_log_operation(source,operation_key,operation_label,display_order)
VALUES('OPEN_API','OPEN_SRPP_REPORT_CALCULATIONS','SRPP 不可變報告計算包',240)
ON CONFLICT(source,operation_key) DO NOTHING;
