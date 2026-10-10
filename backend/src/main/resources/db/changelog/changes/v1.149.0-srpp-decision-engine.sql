--liquibase formatted sql
--changeset steven:v1.149.0-srpp-decision-engine splitStatements:false
--comment: Task 488 replaces unused placeholders; FINAL is insert-only. JSON/hashes duplicate indexed identity intentionally for immutable audit evidence.
DO $$ BEGIN
 IF to_regclass('srpp_decision_run') IS NOT NULL THEN
  IF EXISTS (SELECT 1 FROM srpp_decision_run LIMIT 1) THEN
   RAISE EXCEPTION 'Task488 refuses to drop nonempty placeholder srpp_decision_run';
  END IF;
 END IF;
END $$;
DROP TABLE IF EXISTS srpp_decision_run;
CREATE TABLE srpp_decision_run (
 id uuid PRIMARY KEY, owner_user_id bigint NOT NULL REFERENCES app_user(id),
 trading_date date NOT NULL, slot varchar(5) NOT NULL CHECK(slot IN ('09:05','11:40')),
 policy_bundle_sha256 char(64) NOT NULL REFERENCES srpp_policy_registry(policy_bundle_sha256),
 swagger_sha256 char(64) NOT NULL CHECK(swagger_sha256 ~ '^[0-9a-f]{64}$'),
 status varchar(16) NOT NULL CHECK(status='FINAL'),
 input_jcs text NOT NULL, input_snapshot_sha256 char(64) NOT NULL CHECK(input_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 content_jcs text NOT NULL, decision_content_sha256 char(64) NOT NULL CHECK(decision_content_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL, finalized_at timestamptz NOT NULL,
 CONSTRAINT srpp_decision_run_identity_uq UNIQUE(owner_user_id,trading_date,slot)
);
CREATE INDEX idx_srpp_decision_run_owner_week ON srpp_decision_run(owner_user_id,trading_date,slot);
CREATE TRIGGER trg_srpp_decision_run_no_update BEFORE UPDATE ON srpp_decision_run FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();
CREATE TABLE srpp_decision_capture_claim (
 id uuid PRIMARY KEY, owner_user_id bigint NOT NULL REFERENCES app_user(id),trading_date date NOT NULL,
 slot varchar(5) NOT NULL CHECK(slot IN ('09:05','11:40')),
 policy_bundle_sha256 char(64) NOT NULL REFERENCES srpp_policy_registry(policy_bundle_sha256),
 swagger_sha256 char(64) NOT NULL CHECK(swagger_sha256 ~ '^[0-9a-f]{64}$'),
 generation uuid NOT NULL, claimed_at timestamptz NOT NULL,lease_until timestamptz NOT NULL CHECK(lease_until>claimed_at),
 CONSTRAINT srpp_decision_claim_identity_uq UNIQUE(owner_user_id,trading_date,slot)
);
