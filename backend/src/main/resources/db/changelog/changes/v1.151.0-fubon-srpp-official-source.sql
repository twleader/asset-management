--liquibase formatted sql
--changeset steven:1.151.0-fubon-srpp-official-source splitStatements:false
-- Immutable official raw read observations; JSON is an intentional audit snapshot,
-- never a second mutable broker ledger or synthesized order/fill record.
CREATE TABLE IF NOT EXISTS fubon_srpp_source_snapshot (
    sequence_id BIGSERIAL PRIMARY KEY,
    id UUID NOT NULL UNIQUE,
    owner_user_id BIGINT NOT NULL REFERENCES app_user(id) CHECK (owner_user_id BETWEEN 1 AND 9007199254740991),
    trading_date DATE NOT NULL,
    account_fingerprint VARCHAR(24),
    captured_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('VERIFIED','UNAVAILABLE')),
    revision CHAR(64) NOT NULL CHECK (revision ~ '^[a-f0-9]{64}$'),
    payload_jcs TEXT NOT NULL,
    CHECK (status <> 'VERIFIED' OR (account_fingerprint IS NOT NULL AND account_fingerprint ~ '^[a-f0-9]{24}$'))
);
CREATE INDEX IF NOT EXISTS idx_fubon_srpp_snapshot_owner_date ON fubon_srpp_source_snapshot(owner_user_id,trading_date,sequence_id DESC);
CREATE OR REPLACE FUNCTION reject_fubon_srpp_snapshot_mutation() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'fubon_srpp_source_snapshot is immutable'; END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS fubon_srpp_snapshot_immutable ON fubon_srpp_source_snapshot;
CREATE TRIGGER fubon_srpp_snapshot_immutable BEFORE UPDATE OR DELETE ON fubon_srpp_source_snapshot FOR EACH ROW EXECUTE FUNCTION reject_fubon_srpp_snapshot_mutation();
