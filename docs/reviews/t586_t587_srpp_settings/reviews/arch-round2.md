VERDICT: CHANGES_REQUESTED

## 判定
critical: 0   major: 1   minor: 2

審查範圍：asset HEAD `5d3bb0f1383de98af0f3dd642f2bdb7e04bb61bc` 至目前工作樹，包含未追蹤檔；以 `asset-complete-round2.diff`、`srpp-complete-round2.diff` 為材料。

唯讀比對確認 asset 全部 48 個檔案差異均與材料一致；非前端完整 diff、SRPP 完整 diff 均與第 1 輪逐字相同。因此沿用前輪非前端查證，補審五個前端檔案及整合邊界，並重新確認兩項既有 Minor。

## Findings

### [Major] 短線觀察清單仍由 Vue 過濾分組，未由 BFF 提供各區資料

位置：`frontend/src/components/SrppShortTermOpportunities.vue:285`；使用位置為同檔 `:115`。

證據：執行 `grep -ran -E 'rowsFor|observationBuckets'` 實際命中：

```text
113:      <section v-for="bucket in observationBuckets" :key="bucket.key" class="observation-section">
115:        <el-table :data="rowsFor(bucket.key)" :row-key="rowKey" size="small" stripe
240:const observationBuckets = [
285:function rowsFor(key) { return (listData.value?.rows || []).filter(row => row.observationType === key) }
```

追讀 `SrppShortTermBffController.java:18`、`:29`，BFF 目前直接反序列化並轉送 `ListEnvelope`；`SrppShortTermDto.java:13` 仍只提供平面 `List<Row> rows`。三個表格的資料過濾實際在瀏覽器執行。

違反：

- `CLAUDE.md`〈BFF 與資料來源規範〉第 1 點，`:368`：「BFF 負責跨服務 aggregation、預先計算 / 排序 / 過濾，前端只負責 render」。
- `structure.md` §3.2 第 5 點，`:350`：「前端只 render，BFF 預先聚合 / 排序 / 過濾」。

修法：在本頁 BFF presentation 層，依來源既有 `observationType` 產出各觀察區的 rows；Vue 直接迭代 BFF 回傳的分組資料，移除 `rowsFor().filter()`。維持來源方向、排序與價格不變，不重新計算訊號。

使用者本輪授權的是 weights 暫時加總與 validation display，未包含雷達列過濾；兩份規範的其他具名例外也不涵蓋此元件。

### [Minor] 短線 DTO 價格欄位仍使用 String，前輪型別慣例差異尚未修正

位置：

- `backend/src/main/java/com/steven/assets/dto/SrppShortTermDto.java:35`
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/SrppShortTermDto.java:35`

證據：兩檔執行 `grep -ran` 均命中：

```text
35:            String shortActionLabel,Integer shortScore,String observationType,String status,String price,String quoteStatus,
40:    public record PriceReference(String buyLower,String buyUpper,String sellLower,String sellUpper,String asOfDate,String applicableSide) {}
```

service 的實際轉換為：

```text
SrppShortTermService.java:160:    private static String decimal(BigDecimal value){return value==null?null:value.toPlainString();}
```

違反：`structure.md` §7，`:621`：「金額單位 | `BigDecimal`（不用 `double` / `float`）」。

修法：後續相容重構時，兩層 DTO 改用 `BigDecimal`，透過字串序列化維持既有 decimal-string wire；核對 null、原始 scale 與 wire 測試。

Claude 的處理理由足以支持維持 Minor：目前沒有浮點轉換或精度損壞證據。但 wire 已核准使用字串，並不代表 Java 欄位已符合 BigDecimal 慣例；此項仍是保留事項。

### [Minor] 新增日期與時間戳仍使用 String，前輪型別慣例差異尚未修正

位置：

- `backend/src/main/java/com/steven/assets/dto/SrppSettingsDto.java:111`
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/SrppSettingsDto.java:111`
- backend／BFF 的 `SrppShortTermDto.java:12`

證據：`grep -ran` 在兩層 settings DTO 均查得：

```text
111:            @JsonInclude(JsonInclude.Include.NON_NULL) String birthDate,
122:    public record LaborPension(String mode,String receiptDate,
125:    public record LaborInsurance(String mode,String startDate,
153:    public record PayrollEntry(String payDate,...
196:    public record MonthlyStrategyAudit(String date,...
```

短線 DTO 另查得：

```text
12:            String modelInputsSha256,String ruleVersion,String actionPolicyVersion,String generatedAt,List<Market> markets,
17:    public record Market(String market,boolean stale,boolean dataComplete,String asOfDate,String quoteStatus) {}
36:            String priceUpdatedAt,String dailyCandleAsOfDate,boolean horizonConflict) {}
```

違反：`structure.md` §7，`:622`：「時間欄位 | `LocalDate`（日期）/ `Instant`（時間戳）；wall-time 帶市場時區由 service 層處理」。

修法：於協定相容重構中將完整日期改為 `LocalDate`、時間戳改為 `Instant`；同步處理來源 offset、序列化與 canonical hash 相容性。`eventMonthDay` 不屬於完整日期，不包含在本 finding。

已確認 `SettingsResultValidator.java:116` 使用 `LocalDate.parse()` 驗證日期。Claude 提出的驗證與協定相容理由支持維持 Minor，並未消除型別慣例差異。

## 已查無發現

| 維度 | 查證方式與結果 |
|---|---|
| 模組界線 | 對完整 diff 的新增 production 行檢索外部行情 URL、service HTTP response、券商交易呼叫及前端 `fetch`／`EventSource`，均無命中；external-materials 本輪無變更。 |
| 分層職責 | 讀取 Controller、Service 與 DTO，並以 `grep -ran` 核對；Controller 委派 service，未新增 Repository／Entity 變更。DTO 為外層容器加巢狀 records，未把容器 class 當成違規。 |
| BFF 路由 | `api/index.js:434`、`:438` 經 axios `/api` baseURL 呼叫本頁 `/bff/portfolio-advice/...`，對應 `bff/portfolioadvice/`。元件掛在既有 `AssetAllocationAdviceView.vue:5`，沒有新增跨頁 BFF 呼叫。過濾問題另列於上。 |
| 結果整合 | backend／BFF 兩組 DTO 除 package 外內容一致。root 使用 `runSummaryTree()`，前端選取後經 `getSimulation()` 讀完整結果；新增薪資、給付、準備金與稽核數字直接取 typed response。 |
| 前端計算 | weights 合計限於已載入欄位，符合本輪明確授權；未見新增退休金額、股數、限價或雷達訊號計算。金額格式化與表單格式驗證未誤列金融試算。 |
| 非同步載入 | 核對 owner／sequence／detail epoch 防護。root 後讀 run detail 依賴 root 提供的 run ID，展開後讀 stock detail 依賴選取列；未將這些必要相依誤判成可並行請求。 |
| 模型與來源 | catalog 實讀為 76 欄；沒有新增 Entity、業務分類或 Liquibase changeset。前輪已查證的雷達來源、正規化與即時／收盤資料邊界，其完整 diff 未變。 |

## 無法查證

本輪未執行測試、瀏覽器操作、API 呼叫或容器操作，也未修改任何檔案。

已讀取 UI 與 JAR preflight 收據：UI 明示 synthetic radar，JAR 明示 `OFFLINE_IMAGE_AND_JAR_PREFLIGHT_NOT_FORMAL_SERVE`。這些收據不構成本輪獨立重現或正式部署驗收。

## 自我挑戰

- 新 Major 位於本輪新增的完整元件，已核對材料與工作樹一致；不是既有技術債。
- 雷達 `observationType` 已由後端決定，因此撤下「前端重算方向」疑慮；保留的是明文禁止的前端資料過濾。
- weights 加總具有使用者明確授權，未列 finding；表單編輯狀態清理與欄位排版也未一概當成業務資料過濾。
- 兩項 Minor 已逐項重新查證，屬前輪保留事項，沒有宣稱 Claude 已修正，也沒有升級為資料損壞。
- 已排除外層 DTO class、註解中的 `@Data`、比例與 service 區域浮點運算三類誤報。
- 三項 finding 均重新核對規範原文。因存在一項 Major，依指定機械規則產生 `CHANGES_REQUESTED`。