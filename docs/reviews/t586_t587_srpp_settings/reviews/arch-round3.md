VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 2

審查範圍：`asset-complete-round3.diff` 的 73 檔、`srpp-complete-round3.diff` 的 13 檔，包含 untracked 與 generated artifacts。基準為 asset HEAD `5d3bb0f1`、SRPP HEAD `8f88672f` 至工作樹；逐檔比對確認材料與工作樹一致。

排除 diff 包裝格式後，SRPP 差異內容與第 2 輪相同；asset 僅五個程式／測試檔變動，另新增證據與審查紀錄。未變部分沿用前兩輪查證，本輪重新核對修正及兩項 Minor。

## Findings

### [Minor] 短線 DTO 價格欄位仍使用 String

位置：

- `backend/src/main/java/com/steven/assets/dto/SrppShortTermDto.java:35`
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/SrppShortTermDto.java:35`

證據：兩檔執行 `grep -ran -E 'String price|record PriceReference'` 均命中：

```text
35: ...String observationType,String status,String price,String quoteStatus,
40: public record PriceReference(String buyLower,String buyUpper,String sellLower,String sellUpper,...
```

另查得：

```text
SrppShortTermService.java:160:
private static String decimal(BigDecimal value){return value==null?null:value.toPlainString();}
```

違反：`structure.md` §7，`:621`：「金額單位 | `BigDecimal`（不用 `double` / `float`）」。

修法：兩層價格欄位改為 `BigDecimal`，使用字串序列化維持 decimal-string wire，並核對 null、原始 scale 與相容測試。

本輪未修正此項；目前沒有浮點轉換或精度損壞證據，因此維持 Minor。

### [Minor] 新增日期與時間戳欄位仍使用 String

位置：

- `backend/src/main/java/com/steven/assets/dto/SrppSettingsDto.java:111`
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/SrppSettingsDto.java:111`
- backend／BFF 的 `SrppShortTermDto.java:12`

證據：以 `grep -ran` 核對，兩層 settings DTO 均命中：

```text
111: ...String birthDate,
122: public record LaborPension(String mode,String receiptDate,
125: public record LaborInsurance(String mode,String startDate,
153: public record PayrollEntry(String payDate,...
196: public record MonthlyStrategyAudit(String date,...
```

短線 DTO 另命中：

```text
12: ...String generatedAt,List<Market> markets,
17: public record Market(...String asOfDate,String quoteStatus) {}
36: String priceUpdatedAt,String dailyCandleAsOfDate,...
```

違反：`structure.md` §7，`:622`：「時間欄位 | `LocalDate`（日期）/ `Instant`（時間戳）；wall-time 帶市場時區由 service 層處理」。

修法：完整日期改為 `LocalDate`、時間戳改為 `Instant`；同步處理來源 offset、序列化及 canonical hash 相容性。`eventMonthDay` 不屬於完整日期。

`SettingsResultValidator.java:116` 已用 `LocalDate.parse()` 驗證日期，但不消除 Java 型別慣例差異，維持 Minor。

## 已查無發現

| 維度 | 查證方式與結果 |
|---|---|
| 前輪 Major | `grep -ran -E 'rowsFor|observationBuckets'` 確認 Vue `:113` 迭代 `listData.observationBuckets`、`:115` 使用 `bucket.rows`，已無 `rowsFor()`。BFF controller `:21` 委派 `SrppShortTermPresentation::list`，分組責任已移回 BFF。 |
| 分組完整性 | 讀取 `SrppShortTermPresentation.java:14–32`：保留 source、直接加入原 Row、維持各組來源順序；null／未知分類進 WAIT，未修改原分類或價格。集合採 `List.copyOf()`。 |
| 修正測試 | `grep -ran` 確認 `SrppShortTermBffTest.java:40`、`:60` 涵蓋原物件保留、順序、null／未知分類、不可變集合及 HTTP envelope 一致性；本輪未執行測試。 |
| 模組界線 | 檢索完整 diff 新增 production 行，未見外部行情 URL、反向 service 呼叫、券商交易呼叫或跨服務資料表存取；external-materials 本輪無變更。 |
| 分層職責 | 新 backend Controller 僅委派 service；Repository 注入位於 Service。新增 service 行未見 `ResponseEntity`／`HttpServletResponse`。無 Repository／Entity 變更。DTO 為巢狀 records，未見真實 `@Data` 或 JPA 標註。 |
| BFF 路由 | `api/index.js:434`、`:438` 經 axios `/api` baseURL 呼叫本頁 `/bff/portfolio-advice/...`；元件掛在既有 `AssetAllocationAdviceView.vue:5`。未新增跨頁 BFF、裸 `fetch()` 或 EventSource。 |
| 前端計算 | `SrppSettingsCenter.vue:1300–1303` 僅格式化 weights 顯示，沒有回寫 state；合計與 validation display 在使用者授權範圍。未新增退休、股數、限價或雷達訊號計算。 |
| 非同步載入 | root 後讀 run detail 依賴取得的 run ID；展開後讀 stock detail 依賴選取列。未發現本次新增可獨立 panel 請求被序列化的問題。 |
| 資料模型與命名 | 無新增持久化欄位、FK／名稱冗餘、業務分類或 Liquibase changeset；新 endpoint 使用 kebab-case。group／status 為來源投影協定碼。 |
| 雷達資料來源 | `SrppShortTermService.java:43`、`:88` 使用既有 `getList()`／`getStockEvaluation()`；追讀確認沿用 compact／cached-only 路徑，未改用會 refresh 的 detail 路徑，未新增價格預設值或寫回。 |
| Generated artifacts | 唯讀核對 runtime manifest 的 19 檔 hash 全數相符，且與 sibling SRPP 位元組一致；backend catalog／payroll／policy mirrors 一致。兩層 wire fixture 相同，其 canonical SHA-256 與 `.sha256` 相符。 |

## 無法查證

本輪未執行測試、瀏覽器操作、API 呼叫、DB 查詢或容器操作，未修改檔案。

UI 收據明示 synthetic radar；worker 收據明示隔離 preflight。已讀取這些證據，但不視為本輪獨立重現或正式部署驗收。正式 registry、部署與 runtime adoption 仍未執行。

## 自我挑戰

- 兩項 Minor 均已對照完整 diff，確認是本次新增欄位，沒有混入既有 String 型別技術債。
- 第 2 輪 Major 已確認修復，撤下；BFF 分組沒有重新推導交易方向。
- weights 格式化與合計具有明確授權，未列為違規；欄位排版與編輯狀態操作未誤列為金融資料聚合。
- 已排除外層 DTO class、註解中的 `@Data`、比例及 service 區域浮點運算三類誤報。
- 價格精度損壞疑慮不成立；日期已有格式驗證。兩項只保留明文型別慣例差異，未升級 severity。
- 已重新以 `grep -ran` 核對 `structure.md:621–622` 原文。沒有 Critical／Major，依指定規則產生 PASS。