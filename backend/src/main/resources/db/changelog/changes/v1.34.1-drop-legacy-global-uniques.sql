--liquibase formatted sql

-- Requirement 28 bug fix：移除殘留的「單欄全域唯一」約束（snapshot_date / email）。
-- 既有 DB 的舊約束可能由 Hibernate 自動命名（如 uk543wceg5u97okprjcn4wq41x0），v1.34.0 以標準名
-- DROP CONSTRAINT IF EXISTS asset_snapshot_snapshot_date_key 並未命中 → 舊全域唯一仍在 →
-- 第二個使用者無法建立「管理者已用過日期」的快照（23505）。
-- 改用動態查詢依「欄位組合」刪除，跨環境皆可靠；保留 v1.34.0 建立的複合唯一。

--changeset steven:v1.34.1-drop-legacy-snapshot-date-unique splitStatements:false
DO $$
DECLARE c text;
BEGIN
  FOR c IN
    SELECT con.conname
    FROM pg_constraint con
    WHERE con.conrelid = 'asset_snapshot'::regclass
      AND con.contype = 'u'
      AND con.conname <> 'uq_snapshot_owner_date'
      AND (
        SELECT array_agg(att.attname::text ORDER BY att.attnum)
        FROM unnest(con.conkey) AS k(attnum)
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
      ) = ARRAY['snapshot_date']
  LOOP
    EXECUTE format('ALTER TABLE asset_snapshot DROP CONSTRAINT %I', c);
  END LOOP;
END $$;

--changeset steven:v1.34.1-drop-legacy-recipient-email-unique splitStatements:false
DO $$
DECLARE c text;
BEGIN
  FOR c IN
    SELECT con.conname
    FROM pg_constraint con
    WHERE con.conrelid = 'notification_recipient'::regclass
      AND con.contype = 'u'
      AND con.conname <> 'uq_recipient_owner_email'
      AND (
        SELECT array_agg(att.attname::text ORDER BY att.attnum)
        FROM unnest(con.conkey) AS k(attnum)
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
      ) = ARRAY['email']
  LOOP
    EXECUTE format('ALTER TABLE notification_recipient DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
