--liquibase formatted sql

--changeset steven:v1.71.0-remove-unreferenced-legacy-admin splitStatements:false
-- Requirement 28 / Task 233：舊版 migration 曾建立固定管理者 seed。
-- 不修改歷史 changeset（避免既有 DB checksum 失效）；只在該列沒有任何 owner_user_id 資料或其他外鍵引用時移除。
-- 某些較新的 owner 資料表基於歷史原因沒有 FK，因此先動態掃描所有 owner_user_id 欄位，再以 FK 例外兜底。
DO $$
DECLARE
    legacy_user_id BIGINT;
    owner_table RECORD;
    has_owner_rows BOOLEAN;
BEGIN
    SELECT id INTO legacy_user_id
    FROM app_user
    WHERE email = 'tw.leader@gmail.com';

    IF legacy_user_id IS NULL THEN
        RETURN;
    END IF;

    FOR owner_table IN
        SELECT table_schema, table_name
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND column_name = 'owner_user_id'
    LOOP
        EXECUTE format(
            'SELECT EXISTS (SELECT 1 FROM %I.%I WHERE owner_user_id = $1)',
            owner_table.table_schema,
            owner_table.table_name
        ) INTO has_owner_rows USING legacy_user_id;

        IF has_owner_rows THEN
            RAISE NOTICE '保留仍擁有資料的舊版管理者帳號；參照資料表=%', owner_table.table_name;
            RETURN;
        END IF;
    END LOOP;

    BEGIN
        DELETE FROM app_user
        WHERE id = legacy_user_id;
    EXCEPTION
        WHEN foreign_key_violation THEN
            RAISE NOTICE '保留仍被資料引用的舊版管理者帳號；ADMIN_EMAIL 不會自動轉移資料所有權';
    END;
END
$$;
