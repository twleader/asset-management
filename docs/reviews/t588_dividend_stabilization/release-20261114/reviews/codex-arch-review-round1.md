VERDICT: CHANGES_REQUESTED

## 判定

critical: 0   major: 1   minor: 0

審查範圍：以 `HEAD b0fdac0fbff823e5a28df0360ed316083e598186` 對照目前工作目錄的 D218 修改及新增檔案；涵蓋七支正式 Java 類別、相關測試、公式產生器、註冊與查核腳本、OpenAPI。另閱讀提供的 SRPP diff，並查證三份 Swagger。規範依據限於完整讀取的 `CLAUDE.md` 與 `spec/steering/structure.md`。

## Findings

### [Major] D218 已修改 OpenAPI 契約，但三份 Swagger 尚未同步重產

位置：`docs/openapi/docker-external-api.yaml:5906`；相關產物為 `docs/openapi/9090-api-swagger.md:10`、`backend/src/main/resources/srpp/9090-api-swagger.md:10`、`../SRPP/docs/9090 Port API Swagger.md:10`。

證據：`git diff -- docs/openapi/docker-external-api.yaml` 確認本輪將版本由 `1.28.1` 改為 `1.28.2`，並新增六個 D218 fact 名稱。實際執行：

```text
grep -ran -E '1\.28\.[12]|d218ObservedNoNewLowSessions|d218BudgetCeiling' <三份 Swagger>

docs/openapi/9090-api-swagger.md:10:| 契約版本 | `1.28.1` |
backend/src/main/resources/srpp/9090-api-swagger.md:10:| 契約版本 | `1.28.1` |
../SRPP/docs/9090 Port API Swagger.md:10:| 契約版本 | `1.28.1` |
```

三份均未命中新 fact 名稱；兩份 asset-management mirror 的 `git diff --name-only` 亦為空。正式計算器已新增這些輸出，但發布文件仍描述舊契約。

違反：`CLAUDE.md`「BFF 與資料來源規範」第 3 點，行 407 明訂：「每次 9090 API 變更必須由它重產本專案 `docs/openapi/9090-api-swagger.md` 與 business-services classpath resource……的位元組一致 Markdown。」`structure.md` §3.2 同樣要求 YAML 每次變更重產 Swagger；跨專案操作應依 `CLAUDE.md` Task504 修訂。

修法：由 Claude 依 Task504 準備符合條件的受管理 SRPP feature worktree，完成 renderer → identity sync → renderer check → sync check，將三份 Swagger 與必要的 consumer／authority 身分產物同步納入變更，確認包含六個新 fact 名稱且三份位元組一致。

## 已查無發現

- **模組界線：**以 `git diff --name-only` 限定範圍，再對變更的正式 Java 檔執行 `grep -ran -E 'https?://|WebClient|RestClient|JdbcTemplate|EntityManager|place_order|modify_order|cancel_order'`，無命中。未新增外部行情直連、跨服務讀表或券商交易呼叫。
- **分層職責：**本輪沒有 Controller、Repository、Entity 或 DTO 檔案變更；正式計算器未命中 `ResponseEntity`／`HttpServletResponse`。新增邏輯位於 service，純計算器未引入 I/O。
- **BFF／前端：**`git diff --numstat -- frontend bff external-materials-service` 無輸出；沒有新增頁面、跨頁 BFF 呼叫、裸 `fetch`／SSE、前端聚合或序列載入問題。
- **資料模型：**沒有 Entity、資料表或 Liquibase 變更，未新增冗餘持久化欄位。新增 `Price`／`Record` 使用 `record`；價格、預算與支出運算使用 `BigDecimal`。FIRST／RETEST／FOLLOW 是計算協定階段，未認定為須建立設定頁的業務分類。
- **資料來源：**追查 `SrppReportExtraCaptureAdapter`，既有市場 body 沿 `PriceQueryService`、`StockPriceHistoryRepository` 與 canonical 配息來源取得；新增 adapter 讀取凍結 body，未新增價格回寫或預設價補值。
- **預算接線：**`SrppReportTopupSizing.java:14,18` 確認新增上限為 `BigDecimal`，並在逐張 sizing 時比較 `amount.add(fee)`。未以展示文字取代計算限制。
- **歷史版本：**diff 未改動 V1／V2 manifest 常數內容與既有 V2 fixture；註冊 SQL 維持 `INSERT … ON CONFLICT … DO NOTHING`。

## 無法查證

本輪未執行測試、產生器、資料庫查詢、API 或部署，因此未驗證測試結果、運行中 schema、registry、JAR 或自然排程採用。

既有 canonical wave／帳戶／pending feeds 的完整度未實測。新增程式在缺 state 時保留 `STABILIZATION_STATE_UNAVAILABLE`；離線 synthetic fixtures 不構成目前市場或生產正數採用證據。

## 自我挑戰

1. **新增性：**finding 的版本與 enum 修改均由本輪 `git diff` 確認；三份產物仍停留舊版本，並非回報既有 Controller／SSE 技術債。
2. **誤報陷阱：**未以外層 class 判斷 DTO 可變、未把註解中的 `@Data` 當標註，也未把區域運算誤判為持久化金額型別違規。
3. **規範核對：**已用 `grep -ran` 再確認 `CLAUDE.md:407` 與 `structure.md:199` 的同步要求。保留 finding 的理由是明文要求的契約產物尚未跟上本輪 YAML；未將既有缺失 state、尚待發布的模型接線或任務核取方塊另列 finding。