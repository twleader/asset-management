--liquibase formatted sql
--changeset steven:v1.166.0-srpp-settings-simulation splitStatements:false
--comment: Requirement208 owner-scoped drafts, frozen simulations and insert-only release requests.
CREATE TABLE IF NOT EXISTS srpp_settings_draft (
 id UUID PRIMARY KEY, owner_user_id BIGINT NOT NULL REFERENCES app_user(id), name VARCHAR(100) NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>0), base_bundle_sha256 VARCHAR(64) NOT NULL CHECK(base_bundle_sha256 ~ '^[a-f0-9]{64}$'),
 model_inputs_sha256 VARCHAR(64) NOT NULL CHECK(model_inputs_sha256 ~ '^[a-f0-9]{64}$'),
 catalog_version VARCHAR(80) NOT NULL, catalog_sha256 VARCHAR(64) NOT NULL CHECK(catalog_sha256 ~ '^[a-f0-9]{64}$'),
 config_sha256 VARCHAR(64) NOT NULL CHECK(config_sha256 ~ '^[a-f0-9]{64}$'), values_json TEXT NOT NULL, changes_json TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, UNIQUE(id,owner_user_id)
);
CREATE TABLE IF NOT EXISTS srpp_settings_run (
 id UUID PRIMARY KEY, owner_user_id BIGINT NOT NULL REFERENCES app_user(id), draft_id UUID NOT NULL,
 draft_revision BIGINT NOT NULL CHECK(draft_revision>0), mode VARCHAR(10) NOT NULL CHECK(mode IN ('LOCAL','AI')),
 status VARCHAR(20) NOT NULL CHECK(status IN ('PROCESSING','SUCCEEDED','FAILED')),
 config_sha256 VARCHAR(64) NOT NULL CHECK(config_sha256 ~ '^[a-f0-9]{64}$'), input_sha256 VARCHAR(64) NOT NULL CHECK(input_sha256 ~ '^[a-f0-9]{64}$'),
 result_sha256 VARCHAR(64) CHECK(result_sha256 ~ '^[a-f0-9]{64}$'), request_json TEXT NOT NULL, result_json TEXT, changes_json TEXT NOT NULL,
 ai_status VARCHAR(20) NOT NULL CHECK(ai_status IN ('NOT_REQUESTED','PENDING','SUCCEEDED','FAILED','NOT_CONFIGURED')),
 ai_interpretation TEXT, error_code VARCHAR(80), created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ,
 UNIQUE(id,owner_user_id), FOREIGN KEY(draft_id,owner_user_id) REFERENCES srpp_settings_draft(id,owner_user_id)
);
CREATE TABLE IF NOT EXISTS srpp_settings_release (
 id UUID PRIMARY KEY, owner_user_id BIGINT NOT NULL REFERENCES app_user(id), draft_id UUID NOT NULL, run_id UUID NOT NULL,
 request_sha256 VARCHAR(64) NOT NULL UNIQUE CHECK(request_sha256 ~ '^[a-f0-9]{64}$'), payload_json TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(draft_id,owner_user_id) REFERENCES srpp_settings_draft(id,owner_user_id),
 FOREIGN KEY(run_id,owner_user_id) REFERENCES srpp_settings_run(id,owner_user_id)
);
CREATE INDEX IF NOT EXISTS srpp_settings_draft_owner_updated ON srpp_settings_draft(owner_user_id,updated_at DESC);
CREATE INDEX IF NOT EXISTS srpp_settings_run_owner_created ON srpp_settings_run(owner_user_id,created_at DESC);
CREATE INDEX IF NOT EXISTS srpp_settings_run_matching ON srpp_settings_run(owner_user_id,draft_id,draft_revision,config_sha256,status,created_at DESC);
CREATE INDEX IF NOT EXISTS srpp_settings_release_owner_created ON srpp_settings_release(owner_user_id,created_at DESC);
CREATE OR REPLACE FUNCTION guard_srpp_settings_run_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.owner_user_id,NEW.draft_id,NEW.draft_revision,NEW.mode,NEW.config_sha256,NEW.input_sha256,NEW.request_json,NEW.changes_json,NEW.created_at)
 IS DISTINCT FROM ROW(OLD.id,OLD.owner_user_id,OLD.draft_id,OLD.draft_revision,OLD.mode,OLD.config_sha256,OLD.input_sha256,OLD.request_json,OLD.changes_json,OLD.created_at)
 OR OLD.status <> 'PROCESSING'
 OR (OLD.result_json IS NOT NULL AND ROW(NEW.result_json,NEW.result_sha256) IS DISTINCT FROM ROW(OLD.result_json,OLD.result_sha256)) THEN
 RAISE EXCEPTION 'SRPP settings run evidence is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS srpp_settings_run_guard ON srpp_settings_run;
CREATE TRIGGER srpp_settings_run_guard BEFORE UPDATE ON srpp_settings_run FOR EACH ROW EXECUTE FUNCTION guard_srpp_settings_run_update();
DROP TRIGGER IF EXISTS srpp_settings_release_immutable ON srpp_settings_release;
CREATE TRIGGER srpp_settings_release_immutable BEFORE UPDATE OR DELETE ON srpp_settings_release FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();
