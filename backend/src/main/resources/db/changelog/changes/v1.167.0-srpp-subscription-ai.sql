--liquibase formatted sql
--changeset steven:v1.167.0-srpp-subscription-ai splitStatements:false
--comment Task592: independent immutable AI selection and execution evidence; historical runs remain readable.
ALTER TABLE srpp_settings_run ADD COLUMN IF NOT EXISTS ai_selection_json TEXT;
ALTER TABLE srpp_settings_run ADD COLUMN IF NOT EXISTS ai_execution_json TEXT;
CREATE OR REPLACE FUNCTION guard_srpp_settings_run_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.owner_user_id,NEW.draft_id,NEW.draft_revision,NEW.mode,NEW.config_sha256,NEW.input_sha256,NEW.request_json,NEW.changes_json,NEW.created_at,NEW.ai_selection_json)
 IS DISTINCT FROM ROW(OLD.id,OLD.owner_user_id,OLD.draft_id,OLD.draft_revision,OLD.mode,OLD.config_sha256,OLD.input_sha256,OLD.request_json,OLD.changes_json,OLD.created_at,OLD.ai_selection_json)
 OR OLD.status <> 'PROCESSING'
 OR (OLD.result_json IS NOT NULL AND ROW(NEW.result_json,NEW.result_sha256) IS DISTINCT FROM ROW(OLD.result_json,OLD.result_sha256))
 OR (OLD.ai_execution_json IS NOT NULL AND NEW.ai_execution_json IS DISTINCT FROM OLD.ai_execution_json) THEN
 RAISE EXCEPTION 'SRPP settings run evidence is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
