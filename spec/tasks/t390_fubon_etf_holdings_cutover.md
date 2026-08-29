# [t390] 富邦 ETF 成分股持股明細——欄位解析、讀取端切換、清理舊爬蟲

**對應 Requirements:** Requirement 123（富邦「ETF 成分股持股明細查詢」排程串接，取代既有 MoneyDJ／Yahoo 爬蟲來源；本任務把 Task 389 存下的原始 JSON 解析成結構化資料、切換既有讀取端、清理已死的舊爬蟲程式碼）
**前置任務:** t389（`fubon_etf_holdings_snapshot` 表與排程必須已存在且能落地資料）
**Liquibase changeset:** 無

## 背景

Task 389 已把富邦 `ownership.etf_holdings` 的原始回應逐字存進 `fubon_etf_holdings_snapshot.raw_response_json`（`JSONB`），但**完全沒有解析**——因為這支 SDK 方法沒有 docstring、套件也無文件，本次規格撰寫當下的開發環境 `FUBON_ENABLED=false` 無法做真實呼叫核實欄位名稱。

**本任務的解析函式必須先在具備真實富邦憑證、`FUBON_ENABLED=true` 的環境對至少一支已知台股 ETF（建議 `0050`，市值大、必然有成分股資料）呼叫 Task 389 新增的 `POST /internal/market-data/etf-holdings`，取得真實 `rawResponseJson` 並記錄實際欄位名稱，才能定案。** 若本任務執行時仍無法取得這樣的環境（例如富邦官方連線測試尚未完成，見既有專案記憶「SDK_LOGIN_FAILED 多半是官方簽署＋連線測試沒做或沒過夜」），**不得**用猜測的欄位名稱硬寫解析邏輯讓測試「看起來」通過——這是驗收阻斷條件。此時的正確做法：解析函式明確回傳「尚未核實回傳欄位格式」的 fail-soft 結果，`getEtfHoldings()` 對應回傳「暫不可用」語意，同步在完成報告誠實記錄「本次未能完成真實環境核實，解析函式為 fail-soft 佔位實作，待日後有真實環境時補上正確欄位對應」，不視為任務失敗（因為 Task 389 的抓取與落地基礎設施本身已經是完整可用的交付），但也不得謊稱已完成解析。

既有 `MarketDataService.getEtfHoldings()`（`backend/src/main/java/com/steven/assets/service/MarketDataService.java:219`）目前實作：

```java
public record EtfHolding(String stockCode, String stockName, BigDecimal weight, BigDecimal shares) {}

public record EtfHoldingsResult(
        String stockCode, String market, boolean supported, String source,
        String asOfDate, String message, List<EtfHolding> holdings) {}

public EtfHoldingsResult getEtfHoldings(String stockCode, String market) {
    try {
        EtfHoldingsResult r = priceServiceClient.get()
                .uri(uriBuilder -> uriBuilder.path("/internal/etf-holdings")
                        .queryParam("code", stockCode).queryParam("market", market).build())
                .retrieve().bodyToMono(EtfHoldingsResult.class).block();
        return r;
    } catch (Exception e) { /* ... */ }
    return new EtfHoldingsResult(stockCode, market, false, null, null, "..." , List.of());
}
```

（實際程式碼細節以現有檔案為準，上方為結構示意；實作前請完整讀取該方法目前的真實內容。）目前不分市場，一律呼叫 `external-materials-service` 的 `/internal/etf-holdings`，該端點內部依市場分流呼叫 MoneyDJ（台股）或 Yahoo／FinMind（美股）爬蟲。**本任務只改台股分支**：`market=="台股"` 時改讀本地 `fubon_etf_holdings_snapshot`；`market=="美股"` 時完全不變（Fubon 無美股覆蓋，繼續用既有 Yahoo／FinMind 路徑）。

下游呼叫端（`StockAnalysisChartBffController` → `StockAnalysisDialog.vue`「ETF 成分股」圖表、`DashboardBffController` 資產總覽 look-through）都只認 `EtfHoldingsResult`／`EtfHolding` 這兩個 record 的欄位形狀，**這兩個 record 本身完全不改**，故本任務不需要動 BFF 或前端任何一行。前端既有 `frontend/src/components/StockAnalysisDialog.vue`（約第 635 行）對 `shares` 為 `null` 已有 `—` 的 fallback 顯示（`const sharesTxt = p.data?.shares == null ? '—' : ...`），所以即使富邦回應沒有股數欄位、`EtfHolding.shares` 恆為 `null`，畫面也不會壞。

`external-materials-service` 現有的舊爬蟲程式碼在 `MarketDataFetchService.java`（約第 447-670 行）：`getEtfHoldings()`（依市場分流）、`getMoneyDjEtfHoldings()`（台股，MoneyDJ 網頁解析）、`parseMoneyDjHoldings()`（解析 helper）、`getYahooEtfHoldings()`（美股，維持）。本任務移除台股專屬的 MoneyDJ 兩個方法，`getEtfHoldings()`／`fetchEtfHoldingsUncached()` 精簡為只處理美股分支；**`/internal/etf-holdings` endpoint 本身與美股 Yahoo／FinMind 路徑保留**，因為美股請求仍會呼叫它。

## 要做什麼

- [ ] **390.1 真實環境核實回應欄位（實作前置條件，非選配）。** 在具備真實富邦憑證的環境（`FUBON_ENABLED=true`、`FubonConfigState` 為 `READY`）呼叫 Task 389 新增的 `POST /internal/market-data/etf-holdings`（`{"codes":["0050"]}`），取得 `rawResponseJson` 並記錄：呼叫時間、使用的 ETF 代碼、原始回應內容（至少節錄關鍵欄位片段）。**這份核實紀錄要寫進 390.2 解析函式的程式碼註解**，讓日後審查者不需要重新打一次真實 API 就能確認欄位命名有依據。若本次任務執行時無法取得這樣的環境，比照「背景」段落所述走 fail-soft 路徑，並在完成報告明確記錄原因，不得跳過此步驟直接臆測欄位名稱。

- [ ] **390.2 新增 `FubonEtfHoldingsParser`。** 新增獨立、可單元測試的解析類別／方法（如 `FubonEtfHoldingsParser.parse(String rawResponseJson): List<MarketDataService.EtfHolding>`，放在 `com.steven.assets.integration.fubon` 或 `com.steven.assets.service` package，依既有專案慣例判斷放哪裡更合適）：
  - 已核實欄位（390.1 完成）：依實際核實結果把 JSON 陣列的每個元素轉成 `EtfHolding(stockCode, stockName, weight, shares)`；`stockCode`／`stockName`／`weight` 為必要欄位，缺任一者該筆略過（不整批失敗，比照既有系統對「單筆缺名稱時只跳過該筆」的既定寬容模式，例如 Task 385 對成交同步的處理）；`shares` 若原始回應無對應欄位，固定填 `null`。
  - 未核實（390.1 因環境限制無法完成）：函式直接回傳空清單，並讓呼叫端（390.3）判斷這是「尚未核實」狀態、給出對應訊息，而非「查無資料」。
  - `raw_response_json` 為 `NULL`（即該 ETF 在 `fubon_etf_holdings_snapshot` 裡是 `success=false` 的失敗紀錄）或 JSON 解析本身失敗（格式錯誤）時，一律回傳空清單，不得拋出未捕捉例外。
  新增對應單元測試：合法 JSON 正確解析；缺必要欄位的單筆正確跳過但不影響其他筆；`null`／格式錯誤輸入正確回傳空清單而非例外；若走 fail-soft 未核實路徑，驗證固定回傳空清單。

- [ ] **390.3 `MarketDataService.getEtfHoldings()` 依市場分流。** 完整讀取該方法目前的真實內容後再修改（不要憑上方背景段落的示意程式碼直接改寫，示意碼可能與實際檔案有出入）。改為：
  - `market.equals("台股")`：透過新增的 `FubonEtfHoldingsSnapshotRepository`（Task 389 已建立）查 `findById(stockCode)`；查無資料 → `EtfHoldingsResult(stockCode, market, false, null, null, "尚無同步資料", List.of())`；`success=false` → `EtfHoldingsResult(stockCode, market, false, null, null, "本次同步查詢失敗：" + reason, List.of())`；`success=true` → 呼叫 `FubonEtfHoldingsParser.parse(rawResponseJson)`，空清單且屬於「未核實」狀態時 → `EtfHoldingsResult(stockCode, market, false, null, null, "尚未核實回傳欄位格式", List.of())`，非空清單時 → `EtfHoldingsResult(stockCode, market, true, "Fubon", <fetched_at 格式化為既有 asOfDate 慣用格式>, null, holdings)`。**不得**在任一分支回退呼叫 `external-materials-service` 的 `/internal/etf-holdings`（該路徑本任務已不再對台股請求開放）。
  - `market.equals("美股")`：完全不動，維持原本呼叫 `priceServiceClient` → `/internal/etf-holdings` 的邏輯與所有既有錯誤處理。
  - 新增／調整單元測試驗證兩個分支互不影響、台股各種狀態（查無／失敗／未核實／成功）正確對應到 `EtfHoldingsResult` 的欄位。

- [ ] **390.4 清理 `external-materials-service` 已死的 MoneyDJ 程式碼。** 完整讀取 `MarketDataFetchService.java` 目前實際內容後再修改。移除 `getMoneyDjEtfHoldings()`、`parseMoneyDjHoldings()` 兩個方法與其專屬的 MoneyDJ HTTP 呼叫／HTML 解析程式碼；`fetchEtfHoldingsUncached()`（或其等價的分流方法）精簡為只保留美股（Yahoo／FinMind）分支，台股分支整段移除（不留 `if (台股) { ... 已刪除 ... }` 空殼，改成方法本身直接假設呼叫端只會為美股呼叫它，或在方法內對台股輸入明確拒絕並註明「台股已改由 backend 本地資料回答，不應呼叫到此」）。`getEtfHoldings()` 方法本體、`/internal/etf-holdings` endpoint、`etfHoldingsCache`（`ConcurrentHashMap`）／`ETF_HOLDINGS_TTL_MS` 快取機制、`EtfHolding`／`EtfHoldingsResult` 兩個 record **全部保留**（美股請求仍依賴它們），**不得**整支刪除 `MarketDataFetchService` 或該 endpoint。連動更新既有測試：移除／調整原本針對 MoneyDJ 分支的測試案例，保留並確認美股分支測試仍通過。

- [ ] **390.5 同步更新「富邦證 API」盤點頁（Requirement 121／Task 386）。** 完整讀取 `bff/src/main/java/com/steven/assets/bff/fubonapi/FubonApiInfoBffController.java` 目前的 `APIS` 常數後再修改：找到 `sdkReference` 等於 `"marketdata.rest_client.stock.ownership.etf_holdings"` 的那一筆（category=`行情查詢`，name=`ETF 成分股持股明細查詢`），改為：
  - `connected` → `true`
  - `httpEndpoint` → `"POST /internal/market-data/etf-holdings"`
  - `consumer` → 說明為「business-services（富邦 ETF 成分股持股同步排程，交易日 08:50／15:30，範圍為今日交易雷達台股 ETF，需另啟用設定才會執行）」
  - `requestSummary`／`responseSummary` → 依 Task 389／390 實際實作的 request/response 形狀改寫（不再是「未串接」時的猜測性文字）
  同步更新 `FubonApiInfoBffControllerTest`：`connected==true` 筆數由 8 改為 9（新增一組 `(sdkReference, httpEndpoint)` = `("marketdata.rest_client.stock.ownership.etf_holdings", "POST /internal/market-data/etf-holdings")`）；`connected==false` 由 44 改為 43；「行情查詢」分類的已串接筆數斷言（若測試裡有分類×已串接的細項斷言）由 2 改為 3（分類總筆數 20 不變，52 筆總數不變）。`spec/design.md` Requirement 121 設計段的分類表（`| 行情查詢 | 20 | 2 | ... |` 那一列）與架構圖註解「52 筆：8 已串接 + 44 未串接」同步改為「9 已串接 + 43 未串接」；`spec/requirements.md` Requirement 121 的 AC 文字（提到「已串接 8 筆、未串接 44 筆」處）同步更新。**也要同步更新 389.3 已經把 route 數從 6 改成 7 條的相關文字**（若 t386 裡還殘留「6 條 route」但這裡改成講「7 條」時前後矛盾，需通盤檢查一致）。

- [ ] **390.6 不在本次範圍。** 不修改美股 ETF 成分股的資料來源或快取邏輯；不新增使用者可調整的排程時間或觸發按鈕；不影響既有富邦庫存同步／成交同步／TW LIVE 報價／大盤指數串流的邏輯或 feature flag；不做任何下單、改單、圈存或轉帳類 API 呼叫；若真實環境核實後發現 `ownership.etf_holdings` 回應形狀與既有 `EtfHolding(stockCode, stockName, weight, shares)` 四欄位無法對應，允許在核實當下另行判斷是否需要調整 `EtfHolding` 形狀本身（若調整會影響既有下游 BFF／前端，須另外評估波及範圍並在完成報告說明），但**不得**為了「先讓測試過」而虛構欄位。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f external-materials-service/pom.xml test 2>/dev/null || true
docker compose -p asset-management build --no-cache business-services external-materials-service bff
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service bff
docker compose -p asset-management ps business-services external-materials-service bff
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/bff/fubon-api
```

（`external-materials-service` 的 Maven 指令若該模組建置方式不同，依實際專案結構調整，不強求與 backend/bff 完全一致的指令形式。）

以實際 Compose stack 驗證：登入後開啟任一台股 ETF（如 `0050`，若已有 Task 389 排程資料）的股票分析對話框「ETF 成分股」分頁，確認能看到成分股資料（已完成真實環境核實時）或明確的「尚未核實」/「尚無同步資料」訊息（未完成核實時，不得顯示假資料或報錯白屏）；確認美股 ETF 成分股分頁行為與改動前完全一致；「富邦證 API」頁該筆已顯示為「已串接」，8→9／44→43 的統計數字正確反映在畫面上。

本任務未新增 `db/changelog/**` 變更（Task 389 已建表），故不需重產 `db/schema.sql`。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因；**務必明確記錄 390.1 的真實環境核實是否完成，若未完成，記錄目前是 fail-soft 佔位狀態，待日後補上**。）
