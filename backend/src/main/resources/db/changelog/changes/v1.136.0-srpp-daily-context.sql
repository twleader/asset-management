--liquibase formatted sql
--changeset steven:v1.136.0-srpp-daily-context splitStatements:false
--comment: Requirement 163 / Task 452: SRPP shared-calculation registry, owner key, immutable package and evidence tables.
--
-- 正規化說明（Requirement 163／Task 452.1）：
--   * calculationPolicySha256、formulaSetSha256、contextContentSha256、bodySha256 都可由
--     policy_document／formula_manifest／context_jcs／body 以 RFC 8785 JCS + SHA-256 計算得出，
--     故刻意不設欄位（禁止儲存衍生值）。
--   * srpp_context_package 的 owner_user_id／trading_date／slot／policy_bundle_sha256／generated_at
--     與 context_jcs 內同義值重複，是「不可變證據＋查詢鍵」的刻意 denormalization
--     （與 asset_snapshot.total_* 同類）：package 是可重建的派生快取，發布後不可 UPDATE，
--     查詢鍵由 producer 同一程式路徑寫入。
--   * 這四張表不寫持倉、交易、存款或任何權威資料；只保存背景 producer 發布的計算結果與凍結來源。
--   * srpp_policy_registry 上線時為空表，不得 seed 任何列；登錄只能由離線維運以
--     scripts/srpp-policy-register.rb 產生的 INSERT 進行。全零 hash 保留給 Tailscale preflight。

CREATE TABLE IF NOT EXISTS srpp_policy_registry (
    policy_bundle_sha256 char(64) PRIMARY KEY
        CHECK (policy_bundle_sha256 ~ '^[0-9a-f]{64}$' AND policy_bundle_sha256 <> repeat('0', 64)),
    formula_version varchar(64) NOT NULL,
    policy_document text NOT NULL,
    formula_manifest text NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS srpp_owner_key (
    owner_user_id bigint PRIMARY KEY REFERENCES app_user(id),
    owner_key uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS srpp_context_package (
    package_id uuid PRIMARY KEY,
    owner_user_id bigint NOT NULL REFERENCES app_user(id),
    trading_date date NOT NULL,
    slot varchar(5) NOT NULL CHECK (slot IN ('09:05', '11:40')),
    policy_bundle_sha256 char(64) NOT NULL
        REFERENCES srpp_policy_registry(policy_bundle_sha256) ON DELETE RESTRICT,
    generated_at timestamptz NOT NULL,
    context_jcs text NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_srpp_context_package_latest
    ON srpp_context_package (owner_user_id, trading_date, slot, policy_bundle_sha256,
                             generated_at DESC, package_id DESC);
CREATE INDEX IF NOT EXISTS idx_srpp_context_package_trading_date
    ON srpp_context_package (trading_date);

CREATE TABLE IF NOT EXISTS srpp_context_evidence (
    package_id uuid NOT NULL REFERENCES srpp_context_package(package_id) ON DELETE CASCADE,
    source_id varchar(64) NOT NULL,
    body text NOT NULL,
    PRIMARY KEY (package_id, source_id)
);

CREATE OR REPLACE FUNCTION reject_srpp_immutable_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is immutable; UPDATE is not allowed', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_srpp_policy_registry_no_update ON srpp_policy_registry;
CREATE TRIGGER trg_srpp_policy_registry_no_update
    BEFORE UPDATE ON srpp_policy_registry
    FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();

DROP TRIGGER IF EXISTS trg_srpp_owner_key_no_update ON srpp_owner_key;
CREATE TRIGGER trg_srpp_owner_key_no_update
    BEFORE UPDATE ON srpp_owner_key
    FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();

DROP TRIGGER IF EXISTS trg_srpp_context_package_no_update ON srpp_context_package;
CREATE TRIGGER trg_srpp_context_package_no_update
    BEFORE UPDATE ON srpp_context_package
    FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();

DROP TRIGGER IF EXISTS trg_srpp_context_evidence_no_update ON srpp_context_evidence;
CREATE TRIGGER trg_srpp_context_evidence_no_update
    BEFORE UPDATE ON srpp_context_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();

-- t454 的 9090 公開 API 錯誤日誌 operation（與 backend ApiErrorLogOperationCatalog 同步）。
INSERT INTO api_error_log_operation (source, operation_key, operation_label, display_order)
VALUES ('OPEN_API', 'OPEN_SRPP_DAILY_CONTEXT', 'SRPP 共用計算結果', 140)
ON CONFLICT (source, operation_key) DO NOTHING;
