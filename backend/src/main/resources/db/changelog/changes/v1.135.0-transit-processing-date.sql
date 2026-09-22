--liquibase formatted sql
--changeset steven:v1.135.0-transit-processing-date splitStatements:false
--comment: Explicit transit processing date; never infer a payment date for legacy rows.
ALTER TABLE bank_deposit ADD COLUMN IF NOT EXISTS processing_date date;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_bank_deposit_processing_date_transit'
          AND conrelid = 'bank_deposit'::regclass
    ) THEN
        ALTER TABLE bank_deposit ADD CONSTRAINT ck_bank_deposit_processing_date_transit
            CHECK (processing_date IS NULL OR
                (currency IS NOT NULL AND currency IN ('TRANSIT_TWD', 'TRANSIT_USD')));
    END IF;
END $$;
