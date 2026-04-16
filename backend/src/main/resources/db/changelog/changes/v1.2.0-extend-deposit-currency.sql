--liquibase formatted sql
--changeset steven:v1.2.0-extend-deposit-currency
ALTER TABLE bank_deposit ALTER COLUMN currency TYPE VARCHAR(20);
