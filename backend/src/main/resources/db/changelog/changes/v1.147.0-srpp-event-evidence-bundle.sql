--liquibase formatted sql
--changeset steven:v1.147.0-srpp-event-evidence-bundle
--comment: Requirement 181 / Task 481: immutable SRPP event-evidence bundle; drops the t483 placeholder table.
--
-- 正規化說明（Requirement 181／Task 481.9）：
--   * 不存 content_sha256、rubric_sha256：前者可由 content_jcs 以 RFC 8785 JCS + SHA-256 計算，後者是
--     SrppRiskRubricV1 常數類的計算值，讀取時才算（禁止儲存衍生值）。
--   * request_sha256 是刻意保留的 denormalization：request 本身不保存、無法重算，冪等衝突偵測需要它。
--   * content_jcs 內含 score／assessed／riskMode 等評分結果，是評分當下的不可變收據；評分輸入（request 與
--     news_headline 列）不保存或之後會被覆寫／清除，結果無法重算，故不屬「可計算的衍生值」。
--   * 識別欄位同時出現在欄位與 content_jcs.identity，是與 srpp_context_package 相同的「不可變證據＋查詢鍵」
--     刻意 denormalization。
--   * 本表不寫持倉、交易、存款或任何權威資料；只保存引用指紋、來源種類、provenance、期間、分數與雜湊。
--   * 佔位表 srpp_event_evidence 只由 Task 483 之前的佔位服務寫入（內容是空的 SOURCE_UNAVAILABLE 結果，
--     沒有任何使用端依賴），由本表取代並一併刪除。

CREATE TABLE IF NOT EXISTS srpp_event_evidence_bundle (
    id uuid PRIMARY KEY,
    owner_user_id bigint NOT NULL REFERENCES app_user(id),
    trading_date date NOT NULL,
    slot varchar(5) NOT NULL CHECK (slot IN ('09:05', '11:40')),
    analysis_profile varchar(24) NOT NULL CHECK (analysis_profile IN ('TW_DAILY')),
    consumer varchar(16) NOT NULL CHECK (consumer IN ('Claude', 'Codex')),
    decision_id varchar(100) NOT NULL,
    policy_bundle_sha256 char(64) NOT NULL
        REFERENCES srpp_policy_registry(policy_bundle_sha256) ON DELETE RESTRICT,
    swagger_sha256 char(64) NOT NULL,
    request_sha256 char(64) NOT NULL,
    content_jcs text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT srpp_event_evidence_bundle_identity_uq
        UNIQUE (owner_user_id, trading_date, slot, analysis_profile, consumer, decision_id)
);

DROP TRIGGER IF EXISTS trg_srpp_event_evidence_bundle_no_update ON srpp_event_evidence_bundle;
CREATE TRIGGER trg_srpp_event_evidence_bundle_no_update
    BEFORE UPDATE ON srpp_event_evidence_bundle
    FOR EACH ROW EXECUTE FUNCTION reject_srpp_immutable_update();

DROP TABLE IF EXISTS srpp_event_evidence;
