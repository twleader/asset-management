--liquibase formatted sql

--changeset steven:v1.1.0-drop-legacy-constraints
-- Remove legacy NOT NULL constraint on bank_name (now optional, replaced by bank_id FK)
ALTER TABLE bank_deposit ALTER COLUMN bank_name DROP NOT NULL;
