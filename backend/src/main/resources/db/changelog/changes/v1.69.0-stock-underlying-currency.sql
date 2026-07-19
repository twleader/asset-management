--liquibase formatted sql

--changeset steven:v1.69.0-stock-underlying-currency
--comment Requirement 47（Task 227）：交易雷達納入匯率曝險。台幣計價但持有外幣資產的 ETF（如美債 ETF），其台幣報價 ≈ 底層外幣價 × 匯率——實測 00719B 與 USD/TWD 近一年相關係數 0.9737、日報酬相關 0.6763，剝除匯率後底層一年僅動 2.05%（台幣價動 10.34%）；相關性隨存續期單調遞減（00719B 0.97 > 00697B 0.84 > 00679B 0.59），符合「短債幾乎無利率風險故只剩匯率」的結構。原本評分完全沒有匯率輸入，等於在美元逼近高點時把「美元多頭排列」翻譯成「債券 ETF 買進候選」。但 stock 表原本沒有 currency 欄位，系統無法區分「以台幣交易且持有台幣資產」與「以台幣交易但持有美元資產」，故新增 underlying_currency。刻意不建設定表與 /api/settings 端點：此欄位是客觀的證券屬性而非使用者可自訂的業務分類，值域由市場既成事實決定，僅作為規則推斷（美股→USD、英股→GBP、台股→TWD）誤判時的 override；若日後需頻繁人工修正，應優先修推斷規則而非補設定頁。版號說明：v1.67.0 已由通知去抖任務預定、v1.68.0 已由基本面資料任務預定（兩者檔案皆尚未建立），故本 changeset 用 v1.69.0 避讓。全部語句寫成冪等，避免日後版號避讓改名時 Liquibase 視為新 migration 重跑而失敗。
ALTER TABLE stock ADD COLUMN IF NOT EXISTS underlying_currency VARCHAR(10);

COMMENT ON COLUMN stock.underlying_currency IS
  '底層資產幣別（TWD/USD/GBP…）。null 時依 market 推斷：美股→USD、英股→GBP、台股→TWD。台幣計價但持有外幣資產的 ETF 必須顯式標記，否則匯率因子會誤判為無曝險。誤判修正路徑為 DBA 手動 UPDATE（刻意不建設定頁）。';

UPDATE stock SET underlying_currency = 'USD'
 WHERE market = '台股'
   AND code IN ('00679B', '00697B', '00719B')
   AND (underlying_currency IS NULL OR underlying_currency <> 'USD');
