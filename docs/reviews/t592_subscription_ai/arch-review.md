VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 1

審查範圍：`origin/main`（`cf697f8dc098d7f02692b07de4b00d6c46120a5f`）至目前工作樹，含 21 個已追蹤變更與 13 個未追蹤新檔，共 34 檔。查核 backend AI port／adapter／service／DTO／Entity、BFF DTO／presentation、前端元件與工具函式、host bridge／安裝器、Liquibase、`db/schema.sql`、Compose，以及相關測試原始碼。`spec/` 僅用於理解變更範圍，未重做規格審查。

前輪 Major1 的前端可用工具聚合已移除；Major2 的 schema 檔案缺漏已補齊，但本輪無法重新確認與實機 DB 的逐位元一致性。前輪 Minor1 仍存在。未發現新的 Critical／Major；依 severity 規則判為 PASS，不代表完整 runtime 驗收完成。

## Findings

### [Minor] 新增 AI 執行收據的時間戳仍使用 String，未符合 Instant 型別慣例

位置：

- `backend/src/main/java/com/steven/assets/dto/SrppSettingsDto.java:77`
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/SrppSettingsDto.java:77`

證據：執行 `grep -ranE 'AiExecution\(|String modelEvidence|Instant.parse'` 查核兩份 DTO 與 adapter，命中：

```text
backend/.../SrppSettingsDto.java:77:
    String modelEvidence,String billingMode,String adapterVersion,String startedAt,String completedAt,

bff/.../SrppSettingsDto.java:77:
    String modelEvidence,String billingMode,String adapterVersion,String startedAt,String completedAt,

backend/.../SrppSettingsLocalAi.java:134:
    try {if(Instant.parse(receipt.completedAt()).isBefore(Instant.parse(receipt.startedAt())))throw failure("AI_EXECUTION_FAILED");}
```

`git diff --unified=1 origin/main -- <兩份 DTO>` 確認，這兩個時間欄位屬於本次新增的 `AiExecution` record。

違反：`spec/steering/structure.md` §7，行 622：

> 時間欄位：`LocalDate`（日期）/ `Instant`（時間戳）；wall-time 帶市場時區由 service 層處理。

修法：將兩層 `AiExecution.startedAt/completedAt` 改為 `Instant`；同步讓 `SettingsCanonicalJson` 與 BFF 使用的 mapper 支援 Java Time，維持 ISO 字串 wire 格式與舊資料讀取相容性，並調整收據驗證與相關測試。

目前 `Instant.parse` 已檢查格式及時間順序，因此保留為 Minor。沿用既有字串 transport 的理由可解釋相容性考量，但未構成 §7 的明文例外，故本項尚未解決。

## 已查無發現

- **模組界線：未發現新增越界。** 對本次 Java／前端 diff 新增行，以及新增 adapter／bridge 檔案，查核外部 URL、Repository、HTTP response、DB／Redis 與券商交易相關識別字。新增 business HTTP 目的地為 `SrppSettingsLocalAi.java:34` 的固定 `http://host.docker.internal:8766`；bridge 的外連為 AI 費用證據或管理者設定的本機模型端點，未新增 business 直連行情／NAV／配息 API、external 反向呼叫 business、跨服務讀表或券商交易路徑。

- **分層職責：未發現新增違規。** Controller／Repository 沒有本次變更；既有 `SrppSettingsController.java:12–18` 仍委派 service。新增 AI adapter 透過 `SrppSettingsAiPort` 隔離，未組裝 `ResponseEntity`／`HttpServletResponse`。Entity 僅新增兩個欄位映射，沒有新增領域判斷或 enum 欄位。

- **DTO 不可變性：符合。** `grep -ranE '^\s*@Data\b|public record Ai'` 確認新增 AI transport 型別皆為巢狀 record，沒有真正的 `@Data` 標註；清單欄位沿用不可變複製。外層 `public final class SrppSettingsDto` 是 record 容器，未列為違規。

- **BFF 歸屬與 API 呼叫：符合。** `SrppSettingsCenter` 位於既有 `AssetAllocationAdviceView.vue`。元件呼叫仍經 `bffApi.portfolioAdvice.srpp`；`frontend/src/api/index.js:429–443` 對應既有 `/bff/portfolio-advice/srpp/...`，BFF controller 前綴位於 `SrppSettingsBffController.java:14`。沒有新增頁面、跨頁 BFF 呼叫、裸 `fetch()` 或 `EventSource`。本次新增載入沿既有 root request，未新增可並行卻序列執行的獨立 panel 請求。

- **前輪 Major1：已解決。** `grep -ranE 'availableSrppAiTools|aiModeAvailable|aiCatalogStatusSummary|aiAvailable'` 確認舊聚合識別字已不存在；`SrppSettingsCenter.vue:1004、1015` 直接使用後端 `aiAvailable`。後端摘要由 `SrppSettingsService` 計算，BFF presentation 原樣攜帶。剩餘工具／模型查找與 effort 檢查用於選單連動及輸入驗證，沒有重算全體工具可用摘要或金融衍生值。

- **資料模型與歷史證據：未發現新增正規化違規。** 兩個 JSON 欄位保存當次選擇與執行收據，Entity 註解及新增 migration 已說明凍結歷史證據用途。AI metadata 未加入 worker request 或 deterministic result 的 hash 計算路徑。adapter／billing／狀態碼屬基礎設施協定，未新增須由設定頁管理的投資業務分類；本次亦未新增金額欄位。

- **Liquibase 與 schema 檔案：靜態查核符合。** `grep -ranE 'v1\.167\.0' backend/src/main/resources/db/changelog` 僅命中新 changeset 與 master include；新增欄位使用 `ADD COLUMN IF NOT EXISTS`，函式使用 `CREATE OR REPLACE`。以 `db/schema.sql` 查核現況，行 2732–2733 已有兩個 nullable `text` 欄位，行 266–270 已有 selection／execution 不可變規則，行 6462 有對應 trigger；表數為 123，與檔頭一致。因此前輪「schema 檔案尚未同步」的缺漏不再保留。

- **行情來源：本次未變更。** 新增流程只解讀已凍結模擬結果，未新增即時價／收盤價讀寫、Redis TTL 或缺價填補路徑。

## 無法查證

本輪嘗試將唯讀 `pg_dump --schema-only --no-owner --no-privileges` 輸出留在記憶體中，與去除專案檔頭的 `db/schema.sql` 比較；Docker 回覆：

```text
permission denied while trying to connect to the docker API
at unix:///Users/steven/.docker/run/docker.sock
```

因此無法獨立確認本輪實機 schema 與檔案逐位元相等，也無法確認 migration 執行紀錄。提供材料中的 drift PASS 是作者提供的證據，本輪未重新驗得；此限制不構成已證實漂移。

依唯讀範圍，未執行測試、build、POST、模型推論、容器重建或登入頁面驗收。材料中的測試數、模型成功與部署健康狀態未由本輪重新驗證。

## 自我挑戰

1. **新增範圍已確認。** 保留的 Minor 對應兩份 DTO 的新增 diff 行；既有 DTO 時間字串、既有前端排序／篩選、Controller 技術債及 SSE 基準線均未擴列為 finding。
2. **已排除三種誤報。** DTO 外層 class 是巢狀 record 容器；沒有新增真正的 `@Data`；既有機率／比率 `double` 未當成新增金額型別違規。
3. **規範已重查。** 時間型別 finding 直接對應 `structure.md:622`；分層、BFF 與模組邊界分別依 §2.2、§3.2、§8，以及 `CLAUDE.md` 的明文查核。
4. **前輪問題分別處理。** Major1 已有程式碼證據可結案；Major2 的檔案缺漏已補齊，實機同步另列無法查證；Minor1 的相容性理由成立於實作考量，但未消除型別慣例差異，因此維持 Minor。
5. **未以可疑處擴張規範。** 選單連動、輸入防禦與協定碼未被當成業務聚合或硬編投資分類；部署健康與合成測試 fixture 也未被當成完整 runtime 成功證據。