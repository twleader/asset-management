--liquibase formatted sql
--changeset steven:v1.143.0-srpp-daily-report-mail
CREATE TABLE IF NOT EXISTS srpp_daily_report_mail (
  idempotency_key varchar(200) PRIMARY KEY,
  request_sha256 char(64) NOT NULL,
  state varchar(24) NOT NULL CHECK (state IN ('PROCESSING','SUBMITTING','SENT','FAILED','OUTCOME_UNKNOWN')),
  lease_id uuid,
  lease_expires_at timestamptz,
  message_id varchar(998), sent_at timestamptz,
  from_address varchar(320), to_address varchar(320), subject varchar(512),
  html_sha256 char(64), text_sha256 char(64),
  created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS srpp_daily_report_mail_state_sent_at_idx ON srpp_daily_report_mail(state, sent_at);
