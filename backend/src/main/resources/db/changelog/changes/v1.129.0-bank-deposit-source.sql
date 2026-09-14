--liquibase formatted sql
--changeset steven:v1.129.0-bank-deposit-source
ALTER TABLE bank_deposit
    ADD COLUMN source varchar(20) NOT NULL DEFAULT 'MANUAL',
    ADD CONSTRAINT ck_bank_deposit_source CHECK (source IN ('MANUAL', 'FUBON_SYNC'));
