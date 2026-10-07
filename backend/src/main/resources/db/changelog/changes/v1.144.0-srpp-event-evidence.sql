--liquibase formatted sql
--changeset steven:v1.144.0-srpp-event-evidence
CREATE TABLE IF NOT EXISTS srpp_event_evidence (
  id uuid PRIMARY KEY, owner_email varchar(320) NOT NULL, trading_date date NOT NULL,
  slot varchar(5) NOT NULL, analysis_profile varchar(24) NOT NULL,
  policy_bundle_sha256 char(64) NOT NULL, swagger_sha256 char(64) NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('CAPTURING','FINAL')),
  input_snapshot_sha256 char(64) NOT NULL, content_sha256 char(64), content_jcs text,
  created_at timestamptz NOT NULL, finalized_at timestamptz,
  CONSTRAINT srpp_event_evidence_identity_uq UNIQUE(owner_email, trading_date, slot, analysis_profile)
);
