VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 1

審查範圍：`origin/main`（`75583672`）至目前 `codex/trade-report-late-fee` 工作目錄，含未提交及未追蹤檔案，共 20 檔。查閱 Backend、Repository、BFF、前端及測試變更；spec 三檔僅用於確認變更背景與稽核快照例外理由，不重做 spec 審查。規範依據為 `CLAUDE.md` 與 `spec/steering/structure.md`。

前輪 Major1 已由原始碼確認修正；本輪僅保留 schema 基準線待同步的 Minor，因此依 severity 規則產生上述 VERDICT。

## Findings

### [Minor] 新增費用事件表尚未同步至唯一 schema 基準線

位置：`backend/src/main/resources/db/changelog/db.changelog-master.yaml:546`、`backend/src/main/resources/db/changelog/changes/v1.170.0-trade-report-fee-event.sql:6`；待同步檔案為 `db/schema.sql:48`。

證據：實際執行以下查證：

```text
grep -ran 'v1.170.0' backend/src/main/resources/db/changelog

.../changes/v1.170.0-trade-report-fee-event.sql:2:
--changeset steven:v1.170.0-trade-report-fee-event splitStatements:false
.../db.changelog-master.yaml:546:
      file: db/changelog/changes/v1.170.0-trade-report-fee-event.sql
```

```text
grep -ran 'broker_trade_report_fee_event' db/schema.sql
→ 無命中，exit 1

git diff origin/main -- db/schema.sql
→ 無輸出
```

新增 migration 第 6 行宣告事件表，但 `db/schema.sql` 尚無對應內容，表數註記仍為 127。

違反：`CLAUDE.md`「`db/schema.sql` 是 DB schema 的唯一標準」第 339–340 行明訂：「任務若動到 `backend/src/main/resources/db/changelog/**`，收尾步驟就必須包含重產本檔。」第 347–348 行另明訂，實作後查不到新增表代表本檔過期、需要重產；`structure.md` §1 亦有相同要求。

修法：由 Claude 在獲授權的部署流程完成本版 migration 後，依 `db/schema.sql` 檔頭指令從運行中 DB 重產完整 schema、更新實際表數，納入本次變更並重新取得 B10 比對證據。不要手工依 changelog 拼出 schema。

本項列 Minor：目前可證實的是基準線產物未同步，未取得正式 DB 結構錯誤或金融資料受損的證據。

## 已查無發現

| 檢查項目 | 查證方式與結果 |
|---|---|
| 前輪 Repository 值域判斷 | `grep -ran -E 'signum|validateEnrichmentFees|updateNullFees'` 核對變更檔。負值規則位於 `TradeReportImportService.java:133`，第 54 行驗證後才於第 55 行呼叫更新；Repository 已無 `signum()`。 |
| 前輪補測 | `TradeReportImportServiceTest.java:70` 新增測試，明確涵蓋正數、零、null 與負費稅。僅確認測試內容，未重跑。 |
| 模組界線 | 對新增／修改的 Service 與 port 執行外部 URL、HTTP client、JDBC、SQL、HTTP response 型別搜尋，無命中。新增 SQL 集中於 Repository，讀寫本服務帳務表；沒有新增 external-materials 反向依賴、跨服務資料表讀取或券商交易呼叫。 |
| Controller／Service 分層 | `git diff origin/main` 確認未修改 Backend Controller；新增領域判斷及事務邊界位於 Service，沒有新增 Service 組裝 HTTP response。 |
| Repository 職責 | 完整讀取 `JdbcTradeReportFeeEventStore`。保留參數非空、查詢基數檢查及 SQL 條件，未再承擔費稅金融值域規則。 |
| Entity／DTO | 沒有新增或修改 Entity；Request、Response、Event、Sources 均為 `record`。未新增 DTO `@Data`、JPA 註解或 Entity 參照。 |
| BFF 一頁一支與 API 路徑 | 無新增頁面。`grep -ran` 確認 dialog 仍透過 `@/api` 的 `bffApi.transaction`，既有 API 方法指向 `/bff/transaction/report-import/preview`、`apply`；未新增跨頁 BFF、裸 `fetch()` 或 SSE。 |
| BFF 投影與前端計算 | 新增筆數及補費筆數由 `TradeReportImportBffController.java:60` 起計算，第 82–83 行投影。前端只讀取投影、檢查型別及顯示文字，未自行聚合列數或推導費稅。 |
| 多 panel 並行 | 本次為單一 dialog 的 preview／確認／apply 相依流程，沒有新增多 panel 獨立取資料需求；不適用 `Promise.allSettled` 檢查。 |
| 正規化與分類 | 事件 payload 的歷史稽核快照重複內容已有本次 spec 具名理由；未新增可管理投資業務分類。`MANUAL_EDIT`、`FEE_ENRICHMENT` 是事件協定碼，未據此誤報硬編業務 enum。 |
| 金額、日期與命名 | 新增傳輸／port 費稅仍為 `BigDecimal`，來源日期為 `LocalDate`；未新增浮點金額欄位。新增表名及 API 路徑未發現命名違規。 |
| Migration 靜態檢查 | `grep -ran 'v1.170.0'` 僅命中新 changeset 與 master include；索引／表使用 `IF NOT EXISTS`，函式及 trigger 使用 `CREATE OR REPLACE`。schema 同步問題另列 Finding。 |
| 行情資料來源 | 本次 diff 未新增即時價、收盤價、Redis 寫入或 tick fallback 路徑，不涉及行情來源規則變更。 |

## 無法查證

本輪未執行測試、build、spec-check 或容器操作，以遵守只讀限制。材料所列 Backend／BFF／Frontend 通過數與 affected34 PASS，屬作者提供結果，本輪未獨立重現。

未查詢運行中 DB，因此未確認本版 migration 的正式執行狀態及實際約束；沒有用 changelog 推測正式 DB 現況。也未執行成交匯入、補費、POST API、交易或寄信。

## 自我挑戰

1. **是否為本次新增：**以 `git diff origin/main` 及 `git status --short` 確認，新 migration、master include 與事件 Repository 均屬本次變更；schema 缺項由本次新增結構引入，未將既有技術債列入。
2. **前輪問題是否真正解決：**原始碼已確認值域判斷移至 Service，且發生於 SQL 更新之前。Repository 的 `Objects.requireNonNull` 與查詢基數檢查不足以構成金融業務規則，故前輪 Major1 不再保留。
3. **是否踩到誤報陷阱：**沒有因外層 class 判定 DTO 可變，也沒有把註解中的 `@Data` 或區域運算當作傳輸金額型別違規。
4. **是否有明文依據：**已用 `grep -ran` 重核 `CLAUDE.md` 的分層、BFF 與 schema 條文。唯一保留項直接對應 schema 重產要求；即使部署尚未進行，判準仍明文要求實作後列出此缺項，但目前證據不足以升為資料正確性重大違規。