--liquibase formatted sql
--changeset steven:v1.133.0-tw-stock-master-user-input-authority
UPDATE stock SET name = '元大臺灣ESG永續'
WHERE code = '00850' AND market = '台股' AND name IS DISTINCT FROM '元大臺灣ESG永續';
