--liquibase formatted sql
--changeset steven:v1.130.0-tw-stock-master-name-authority
UPDATE stock
SET name = '富邦台50'
WHERE code = '006208'
  AND market = '台股'
  AND name IS DISTINCT FROM '富邦台50';
