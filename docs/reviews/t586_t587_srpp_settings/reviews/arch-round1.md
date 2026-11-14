VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 2

審查範圍：提供的 `asset-complete-round1.diff`、`srpp-complete-round1.diff` 中全部非前端 production 變更及相關測試、產物。以 asset HEAD `5d3bb0f1`、sibling SRPP HEAD `8f88672f` 對照本輪工作樹，包含新增未追蹤檔。未審前端，未重做 spec 審查。

## Findings

### [Minor] 新短線 DTO 的價格欄位未沿用 BigDecimal 慣例

位置：`backend/src/main/java/com/steven/assets/dto/SrppShortTermDto.java:35`、`:40`、`:46`；BFF 同名檔相同行號。

證據：對上述兩檔執行 `grep -ran`，均命中：

```text
35: ...String price,String quoteStatus,
40: public record PriceReference(String buyLower,String buyUpper,String sellLower,String sellUpper,...
46: ...String price,String quoteStatus,String priceUpdatedAt,...
```

`SrppShortTermService.java:160` 另確認價格由 `BigDecimal.toPlainString()` 轉成字串。

違反：`structure.md` §7，`:621`：「金額單位 | `BigDecimal`（不用 `double` / `float`）」。

修法：兩層 DTO 的價格改為 `BigDecimal`，以 `@JsonFormat(shape=STRING)` 保持現有 decimal-string wire 契約及 null；service 直接傳原始 BigDecimal。

現行轉換未經浮點數，未查得精度損壞，因此僅列型別慣例差異。

### [Minor] 新增日期與時間戳欄位使用 String

位置：backend／BFF 的 `SrppSettingsDto.java:111`、`:122`、`:125`、`:153`、`:196`；兩層 `SrppShortTermDto.java:12`、`:17`、`:36`。

證據：`grep -ran` 實際命中：

```text
111: ...String birthDate,
122: public record LaborPension(String mode,String receiptDate,
125: public record LaborInsurance(String mode,String startDate,
153: public record PayrollEntry(String payDate,...
196: public record MonthlyStrategyAudit(String date,...
```

短線 DTO 另有 `String generatedAt`、`String asOfDate`、`String priceUpdatedAt`。以上均為本輪新增欄位。

違反：`structure.md` §7，`:622`：「時間欄位 | `LocalDate`（日期）/ `Instant`（時間戳）；wall-time 帶市場時區由 service 層處理」。

修法：完整日期改為 `LocalDate`、時間戳改為 `Instant`，在 service 處理來源時區；同步配置 canonical／BFF mapper 的時間序列化，維持既有 ISO wire 與結果 hash。`eventMonthDay` 是月日規約，不包含在本 finding。

## 已查無發現

| 維度 | 查證結果與證據 |
|---|---|
| 模組界線 | 對兩份完整 diff 的新增 production 行查詢外部行情 URL、反向 service URL、SQL 存取及券商交易呼叫，未見新增越界；external-materials 本輪無變更。 |
| Controller／Service | `grep -ran` 確認新 Controller 僅委派 service；Repository 注入位於 `SrppShortTermService:28`。未見新增 service 組 HTTP response；命中的 ResponseEntity 位於 Controller／advice。 |
| Repository／Entity／DTO | 本輪沒有新增或修改 Repository、Entity。DTO 均為外層容器加巢狀 records；未見真實 `@Data` 或 JPA 標註，清單有防禦性複製。 |
| BFF | 新 controller 位於 `bff/portfolioadvice/`，使用 `/api/bff/portfolio-advice/srpp/short-opportunities`，只轉送本頁 business GET；未見直查 DB／外部行情或跨頁 BFF 呼叫。 |
| 雷達來源 | `SrppShortTermService:43` 呼叫 `getList()`，`:88` 呼叫 `getStockEvaluation()`；追讀既有實作確認使用 compact／cached-only 路徑，沒有改呼叫 `getStockDetail()`。SHORT 理由與方向沿來源投影。 |
| 正規化／分類／migration | 無新增持久化欄位、FK／名稱冗餘或 changeset；新增 group／status 為投影協定碼，未新增可管理業務分類。registry SQL 保持 insert-only、`ON CONFLICT DO NOTHING`。 |
| 金額與來源產物 | 新薪資／給付／準備金結果金額使用 BigDecimal；比例、波動率與 Python 內部運算未誤列金額違規。唯讀逐位元檢查確認 runtime manifest 的 19 個來源檔全部符合 hash 且與 sibling SRPP 相同；catalog、payroll、policy manifest mirrors 一致，backend／BFF wire fixtures 亦相同。 |
| API 命名 | 新路徑使用 kebab-case；本輪新增介面只有 GET，無軟停用或新 9090 route。 |

## 無法查證

未執行測試、POST、容器操作或正式環境 API 驗收。提供的 worker preflight 收據為 `PASS`、`formalDeployment:false`；本輪僅讀取收據及靜態產物，沒有獨立重現 runtime 結果。

前端跨頁呼叫、聚合及 `Promise.allSettled` 留待包含前端的後續完整審查。

## 自我挑戰

- 已用提供 diff 與 `git diff --unified=0` 確認 findings 都涉及新增欄位；未回報既有日期 String、舊 Controller／SSE 技術債。
- 已排除「外層 DTO class」「註解中的 @Data」「service 區域浮點運算」三類誤報。
- 撤下金額精度損壞疑慮：價格使用 `toPlainString()`，沒有浮點轉換；只保留明文 BigDecimal 慣例差異，列 Minor。
- 日期已有部分上游格式驗證，未據此推稱資料已損壞；保留的也是 §7 型別慣例差異。
- 兩項規範均已重新查詢 `structure.md:621–622`；沒有新增 Critical／Major，因此依指定規則產生 PASS。

