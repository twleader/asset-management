# [t363] `TaiwanStockDividendResult`「權」列不得再當配股率入庫——修正金額灌水並清理既有髒資料

**對應 Requirements:** Requirement 99（`TaiwanStockDividendResult` 的「權」型列一律不得解析為配股事件；既有因此產生的髒資料須經應用層清理）
**前置任務:** t361（配息抓取視窗改用 anchorDate 過濾，已完成並 merge 進 main，commit `c1f22070`）
**Liquibase changeset:** 無（不改資料庫 schema，只改抓取解析邏輯與既有資料的 `event_status`）

> **編號說明**：Requirement 98／Task 362 已由另一支 worktree（`trading-radar-disclosure-gap-bdc041`，處理交易雷達 evidence 揭露、尚未 merge）佔用，依專案慣例讓號，本任務編為 363／Requirement 99。

---

## 背景

### 現在的錯誤行為

`DividendFetchClient` 對台股股利抓取兩張 FinMind 表：主表 `TaiwanStockDividend`（公司盈餘分配政策）與 fallback 表 `TaiwanStockDividendResult`（除權息結果表）。兩者的解析方法 `fetchTw` 內部呼叫的 `fetchTwDividendResult`（歷史落地路徑）與 `parseFinMindResultRows`（upcoming scope 路徑）目前都把 fallback 表中 `stock_or_cache_dividend` 欄含「權」不含「息」的列，直接把 `stock_and_cache_dividend` 欄的值當成配股率寫入 `DividendEvent.stockDividend()`：

```java
// fetchTwDividendResult() 現況（external-materials-service DividendFetchClient.java 706-741 行）
double amount = item.path("stock_and_cache_dividend").asDouble(0);
...
boolean isStock = type.contains("權") && !type.contains("息");
BigDecimal amt = BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP);
out.add(new DividendEvent(year, isStock ? BigDecimal.ZERO : amt, isStock ? amt : BigDecimal.ZERO, ...));
```

**這個值是錯的。** 實測 `stock_and_cache_dividend` 對「權」型列存的其實是**除權參考價落差**（`before_price − after_price`），不是配股率：`2885` 2026-08-18 那列 `before_price=68.30, after_price=65.67`，`stock_and_cache_dividend=2.626924`（＝ 68.30 − 65.67），而同一次除權息在 FinMind 主表 `TaiwanStockDividend` 給的真實配股率是 `0.4`——量級差超過六倍。

`fetchTw`（歷史落地）對兩張表一律無條件查詢（不是「主表查無才查 fallback」），並以 `mergeTaiwanEvents()` 用「`anchorDate` ＋ 合併金額」當事件鍵去重。因為兩表對同一次除權息算出的金額不同，鍵就不同，於是同一事件在 `stock_dividend_history` 裡變成**兩列並存**：一列是主表真實配股率、另一列是 fallback 表算錯的價差冒充配股率。

### 正確行為

`fetchTwDividendResult`／`parseFinMindResultRows` 對 fallback 表 `TaiwanStockDividendResult` 只解析「息」型列（現金配息，`stock_or_cache_dividend` 不含「權」或同時含「息」）；「權」型列一律**不產生 `DividendEvent`**，只留 `log.warn` 稽核軌跡。台股個股的配股事件改由主表 `TaiwanStockDividend`（`StockEarningsDistribution` + `StockStatutorySurplus`）單一來源覆蓋。

### 為什麼不是「主表優先、fallback 表只在主表查無時補位」

這是動工前最初設想的修法方向，**經廣泛取樣後被推翻**。查證分兩種情境：

**情境一：同一 anchorDate 主表與 fallback 表都有資料。** 對運行中 DB `stock_dividend_history` 查詢「現金股利 = 0 且股票股利 > 0 且 `event_status='ACTIVE'`」的列，除已知的 `2885`（6 個年度）外，還查到 `2881`（5 個年度）與 `2891`（1 個年度）皆有同一 `ex_rights_date` 兩列並存，其中一列明顯是價差量級：

```sql
-- 已查證的重複列（stock_code, year, ex_rights_date, cash_dividend, stock_dividend, source, id）
2881 2021 2021-09-06 0 1.000000 ... id=395   ← 保留（推定為主表值）
2881 2021 2021-09-06 0 8.464400 ... id=1008  ← 疑為 fallback 價差
2881 2022 2022-09-22 0 0.500000 ... id=393
2881 2022 2022-09-22 0 2.690500 ... id=1009
2881 2023 2023-09-04 0 0.500000 ... id=391
2881 2023 2023-09-04 0 3.085700 ... id=1010
2881 2024 2024-09-09 0 0.500000 ... id=389
2881 2024 2024-09-09 0 4.404800 ... id=1011
2881 2025 2025-09-25 0 0.250000 ... id=387
2881 2025 2025-09-25 0 2.170700 ... id=1012
2885 2020 2020-08-17 0 0.400000 ... id=422
2885 2020 2020-08-17 0 0.734600 ... id=1014
2885 2022 2022-08-12 0 0.300000 ... id=419
2885 2022 2022-08-12 0 0.617500 ... id=1015
2885 2023 2023-08-11 0 0.150000 ... id=417
2885 2023 2023-08-11 0 0.373900 ... id=1016
2885 2024 2024-08-12 0 0.200000 ... id=415
2885 2024 2024-08-12 0 0.639200 ... id=1017
2885 2025 2025-08-12 0 0.300000 ... id=413
2885 2025 2025-08-12 0 0.967000 ... id=1018
2885 2026 2026-08-18 0 0.400000 ... id=1292  ← 保留
2885 2026 2026-08-18 0 2.626900 ... id=1212  ← 已知價差（Requirement 97 記載）
2891 2016 2016-10-12 0 0.800000 ... id=411
2891 2016 2016-10-12 0 1.344400 ... id=1013
```

共 **12 個年度事件、24 列**受影響（`2881`×5、`2885`×6、`2891`×1）。**上述 `id` 為 2026-08-23 查證時的實際值，實作時須以 SQL 重新查詢當下的實際 `id`，不得假設 id 不變**（重新抓取或投影可能改變既有列的 id 分佈或新增列）。

**情境二：只有 fallback 表有資料、主表查無同 anchorDate 事件（原先假設是 ETF 覆蓋，經查證推翻）。**

- **台股 ETF 完全不觸發本缺陷。** 對本系統實際納管的全部 7 檔主流 ETF（`0050`／`0056`／`006208`／`00878`／`00919`／`00929`／`00713`）直接查詢 FinMind `TaiwanStockDividendResult`，**零筆**「權」型列；運行中 DB 全部 57 檔納管標的中，「現金股利=0 且股票股利>0」的列**僅出現在個股**（`2881`／`2882`／`2885`／`2891`／`3037`／`7556`），無一檔 ETF。台灣 ETF 的收益分配一律走「息」（現金），不會有「權」（配股）事件——「fallback 表補上 ETF 覆蓋」的顧慮只適用於「息」型列，本任務不改動「息」型列的既有邏輯。
- **個股的單列 fallback 事件經 Goodinfo 除權息日程表核對，4 個樣本 4 個都對不上任何真實除權息紀錄**，不只是金額錯，連日期本身都無法追溯：
  - `2882`（國泰金）DB 中兩個孤立事件：`2019-10-14` 股票股利 `0.2288`（id=1225）、`2022-12-05` 股票股利 `0.8332`（id=1226）。`2882` 過去 20 年僅兩次真實配股：`2019-07-01`（現金 1.2＋股票 0.3）與 `2023-07-13`（純股票 0.9）——兩個 DB 事件的日期與金額皆與任何一次真實事件對不上。
  - `3037`（欣興）DB 中 `2025-11-14` 股票股利 `1.8247`（id=1227）。`3037` 自 2009 年起**每一個年度股票股利皆為 0**（純現金股利），近 17 年無任何一次配股，`2025-11-14` 附近沒有除權事件。此列完全查無對應真實事件。
  - `7556`（意德士）DB 中 `2025-08-18` 股票股利 `1.142`（id=1235）。`7556` 2024 年度（2025 發放）真實配股為 `0.5`、除權交易日為 `2025-07-03`——日期差 46 天、金額差逾一倍，同樣對不上任何真實事件。

  4 個樣本 0 個能與 Goodinfo 核對一致，顯示 fallback 表「權」列的資料品質問題不只是「金額算錯」，在無主表可交叉核對時，連事件真實性都無法確認。

**結論**：正確修法是 fallback 表的「權」型列一律不解析為配股事件，不論主表是否有對照——因為（a）ETF 從未觸發此列；（b）個股配股已由主表完整覆蓋；（c）fallback 表「權」列本身系統性不可信。

---

## 要做什麼

改動涵蓋兩處：

1. `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/DividendFetchClient.java`（＋其測試）——停止解析「權」型列（363.1、363.2）。
2. `backend/`——既有髒資料清理的一次性應用層路徑（363.3）。

**不改資料庫 schema、不新增 Liquibase changeset、不改動 Requirement 97／Task 361 已修正的抓取視窗邏輯、不改動「息」型列（現金配息）的既有解析與落地邏輯。**

### 363.1 `DividendFetchClient` 停止解析「權」型 fallback 列

- [ ] **363.1a** `fetchTwDividendResult(String stockCode, int years, LocalDate today)`（707-741 行）：迴圈內判定 `isStock = type.contains("權") && !type.contains("息")` 為 `true` 時，**不得**組出 `DividendEvent` 並加入 `out`；改為 `log.warn` 記錄「fallback 表配股列已知不可信、已排除」（含 `stockCode`、`exDate`、原始 `amount` 值），然後 `continue`。「息」型列（`isStock == false`）的既有解析邏輯**不變**。
- [ ] **363.1b** `parseFinMindResultRows(JsonNode rows, LocalDate from, LocalDate to)`（426-446 行，供 `fetchFinMindUpcomingScope` 的 upcoming scope 使用）：同樣的判定與處理——`type.contains("權") && !type.contains("息")` 為 `true` 時不組出事件、只記 `log.warn` 並跳過該列。**malformed 判定順序不變**：`amount`／`ex` 缺失或不可解析時仍先 `return ParseEvents.invalid(...)`（即 363.1a／363.1b 的排除判定必須在既有 malformed 檢查**之後**，不得因為要排除「權」列而略過原有的資料完整性檢查）。
- [ ] **363.1c** `mergeTaiwanEvents`／`taiwanEventKey`（627-665 行）**不需修改**——363.1a／363.1b 生效後 fallback 表不會再產生「權」型 `DividendEvent`，合併時自然不會再出現「主表真實配股率」與「fallback 價差」並存的情況。**不要為了這次修正額外調整合併鍵邏輯**，範圍會超出本任務。

### 363.2 測試（與實作同一支任務，不得延後）

測試放 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/`，沿用該目錄既有的測試風格與 stub 手法（`fetchTw`／`fetchTwDividendResult`／`fetchFinMindBounded` 都是 private，經公開入口 `fetchObservations(...)`／`fetchProviderUpcomingScope(...)` 進入，用 `mock(HttpClient)` 頂替回應，比照 `DividendFetchCoverageTest`／`MarketDividendUpcomingScopeClientTest` 既有寫法）。

- [ ] **363.2a** 構造一份 `TaiwanStockDividendResult` 回應，含一列 `stock_or_cache_dividend="權"`（如 `date=2026-08-18, stock_and_cache_dividend=2.6269`），`TaiwanStockDividend` 回應為空陣列。經 `fetchObservations(...)` 取得歷史觀測，斷言結果**不含**任何 `stockDividend > 0` 的事件（即該列被完全排除，不是被合併掉）。
- [ ] **363.2b** 同一份 fallback 回應另加一列 `stock_or_cache_dividend="息"`（現金配息，如 `date=2026-07-21, stock_and_cache_dividend=1.8`），斷言該列**仍正常解析**為 `cashDividend=1.8, exDividendDate="2026-07-21"` 的事件（證明 363.1a 只排除「權」型列，未動到「息」型列）。
- [ ] **363.2c** 主表與 fallback 表同時有資料的合併案例：`TaiwanStockDividend` 回一列配股 `StockEarningsDistribution=0.4, StockExDividendTradingDate=2026-08-18`，`TaiwanStockDividendResult` 回一列「權」型 `date=2026-08-18, stock_and_cache_dividend=2.6269`。斷言合併後**只有一個**事件、`stockDividend=0.4`（即主表值），不是兩個事件並存。
- [ ] **363.2d** upcoming scope 路徑（`fetchProviderUpcomingScope`）比照 363.2a／363.2b 各寫一案：`parseFinMindResultRows` 對「權」型列不產生事件、對「息」型列正常解析。
- [ ] **363.2e** 既有測試若斷言「fallback 表『權』型列會被解析為股票股利事件」，一併修正為斷言「不會被解析」；執行 `grep -rn "stock_or_cache_dividend\|isStock\|權" external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/` 找出所有相關既有測試逐一檢查，不得留下互相矛盾的新舊斷言並存。
- [ ] **363.2f** 既有測試全綠：`DividendFetchCoverageTest`、`DividendFetchResultTest`、`MarketDividendUpcomingScopeClientTest`、`DividendSnapshotAppendOnlyTest`、`DividendPersisterAppendOnlyTest`、`DividendEventKeyMigrationTest`、`DividendDatesTest`、`DividendFetchWindowAnchorTest`（t361 新增）皆不得因本次改動而紅燈。

### 363.3 既有髒資料清理

> **為什麼不能只靠重新抓取自動修好。** `DividendCurrentStateProjectionService.projectOne` 的 cancel pass（`findActiveFutureEvents`）**只處理未來事件**（`decisionDate` 之後）；`collapseDuplicateActiveEvents` 只收斂 `relaxedIdentity`（anchorDate＋現金＋股票金額）**完全相同**的重複列。本缺陷的兩列金額本來就不同（一個是真配股率、一個是價差），所以既有的自癒機制**兩者都碰不到**——重新 sync＋投影不會讓錯誤列自動消失，必須顯式清理。

- [ ] **363.3a** **不得讓新 Controller 直接注入 `DividendCurrentStateRepository`**（違反 Clean Architecture「Controller 不得直接注入 Repository」鐵則；該介面自己的 Javadoc 也明文寫「ACTIVE/CANCELLED decisions belong to `DividendCurrentStateProjectionService`」）。正確做法分兩層：
      1. 在 `backend/src/main/java/com/steven/assets/service/DividendCurrentStateProjectionService.java` 新增一個 public 方法 `boolean cancelEventById(String code, String market, long id)`：呼叫既有 `repository.findActiveEventDetails(code, market)`（`collapseDuplicateActiveEvents` 已在用同一方法）取得該股票的 ACTIVE 列清單，若清單中存在 `id` 相符者，呼叫既有 `repository.cancelActiveEvent(id)` 並回傳 `true`；找不到（非 ACTIVE 或不存在）回傳 `false`，不呼叫 `cancelActiveEvent`。
      2. 在 `backend/src/main/java/com/steven/assets/controller/`（找既有 `/internal/**` 端點所在的 controller，比照其風格與命名，例如注入 Service 而非 Repository 的既有慣例）新增一個**一次性維護用**端點 `POST /internal/dividend/cancel-event?code=&market=&id={id}`：注入 `DividendCurrentStateProjectionService`、呼叫上述 `cancelEventById(...)`，回傳 `true` 時 200、回傳 `false` 時 404。**路徑刻意命名為 `/internal/dividend/cancel-event`（呼應 external-materials-service 既有 `/internal/dividend/sync` 的『資源/動作』式 `/internal/{domain}/{action}` 命名風格；backend 自身要比照的既有 controller 慣例為 `InternalBacktestController`／`TreasuryYieldController`／`UserAdminController`），不得命名為 `/internal/dividend-history/...`——`/internal/dividend-history` 是 external-materials-service 既有的另一支端點（`InternalPriceController`，查主表用），字面撞名容易讓實作者誤放進錯的 service／錯的 controller。此端點不得註冊進 BFF 或 9090 gateway**（只供 backend 容器內 `curl localhost:8080` 呼叫），比照 `/internal/dividend/sync` 的「內部維護端點」定位。
- [ ] **363.3b** 執行清理前，先以下列 SQL 重新查詢**當下**（實作時，而非本任務檔撰寫時）的實際受影響列，取得真實 `id`（背景段落列出的 id 僅供參照，**不得直接照抄使用**）：
      ```sql
      SELECT id, stock_code, year, ex_rights_date, cash_dividend, stock_dividend, event_status
      FROM stock_dividend_history
      WHERE stock_code IN ('2881','2882','2885','2891','3037','7556')
        AND COALESCE(cash_dividend,0) = 0 AND COALESCE(stock_dividend,0) > 0
        AND event_status = 'ACTIVE'
      ORDER BY stock_code, ex_rights_date, id;
      ```
- [ ] **363.3c** 對情境一（同 `ex_rights_date` 兩列以上並存，`2881`／`2885`／`2891`）：兩列中**保留較小值、取消較大值**（依背景段落已驗證的模式：fallback 價差通常顯著大於真實配股率）。
      **判斷「顯著」的門檻是兩值比例 ≥ 2 倍**；若同一 `ex_rights_date` 下的兩個候選值比例 < 2 倍，不得套用量級啟發式，須另外核對 FinMind `TaiwanStockDividend`（主表）決定。已知背景段落列出的 12 組樣本逐一核算比例如下（皆為「較大值 ÷ 較小值」）：
      - `2881`：2021 年 8.46 倍、2022 年 5.38 倍、2023 年 6.17 倍、2024 年 8.81 倍、2025 年 8.68 倍——五組皆 ≥ 2 倍，可套用量級啟發式。
      - `2885`：2020 年 **1.84 倍**、2022 年 2.06 倍、2023 年 2.49 倍、2024 年 3.20 倍、2025 年 3.22 倍、2026 年 6.57 倍——**只有 2020 年這組未達 2 倍門檻**，其餘五組可套用量級啟發式。
      - `2891`：2016 年 **1.68 倍**——未達 2 倍門檻。
      **`2885` 2020 年（`0.4` vs `0.7346`）與 `2891` 2016 年（`0.8` vs `1.3444`）這兩組執行時務必走下方 FinMind 核對路徑，不得套用「保留較小值」的量級啟發式**；其餘 10 組（`2881`×5、`2885` 2022／2023／2024／2025／2026 共 5 組）比例皆 ≥ 2 倍，可直接套用量級啟發式。**但實作時仍須以第 363.3b 步查到的當下實際值重新算一次比例，不得直接沿用本段列出的數字**（背景段落數字僅供參照，資料可能已隨重新抓取而變動，屆時凡比例 < 2 倍者一律改走 FinMind 核對路徑，不限於本段點名的這兩組）。
      **FinMind 主表核對的具體指令**（不得由 backend 容器對 FinMind 發送任何請求，一律經 `external-materials-service` 這支既有唯讀端點）：
      ```bash
      docker exec asset-external-materials-service curl -fsS 'http://localhost:8080/internal/dividend-history?code={code}&market=%E5%8F%B0%E8%82%A1&years=15'
      ```
      此端點呼叫 `MarketDataFetchService.getTwDividendHistory`，只查主表 `TaiwanStockDividend`（不含 fallback 表），回傳結果中找該 anchorDate（`exRightsDate`）對應的 `stockDividend` 值，與兩個候選值比對，數值相符者為主表來源、保留，另一者取消。
- [ ] **363.3d** 對情境二（單列孤立事件，`2882`×2、`3037`×1、`7556`×1，已於背景段落逐一查證均查無對應真實事件）：全部標記 `CANCELLED`。若清理當下發現除這 4 個已知樣本外還有其他孤立「權」型列，須逐一以 Goodinfo（`https://goodinfo.tw/tw/StockDividendSchedule.asp?STOCK_ID={code}`）或公開資訊觀測站核對後才可清理，不得未查證逕行批次刪除或批次保留。
- [ ] **363.3e** 逐一呼叫 363.3a 的端點取消每一列，執行時記錄每一次呼叫的 `id`、取消前的欄位值、判定依據（何種規則或查證來源），供完成報告使用。
- [ ] **363.3f** 清理完成後，363.3a 新增的端點**予以保留**（不刪除程式碼）；它是通用的「取消單一 current-state 列」維護工具，未來若再發生類似資料品質事件仍可重用，比照 `/internal/dividend/sync` 的既有維護端點定位，不算是「用完即丟」的暫時程式碼。

---

## 驗證

```bash
# 0. 一律先進 worktree
cd /Users/steven/Project/asset-management/.claude/worktrees/dividend-fetch-stock-rate-fix-8aed0b
cp /Users/steven/Project/asset-management/.env .env
```

```bash
# 1. 單元測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 2. 重建並重啟兩支 JVM service（一律 --no-cache）
docker compose -p asset-management build --no-cache external-materials-service business-services
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services
docker compose -p asset-management restart bff
```

```bash
# 3. 確認 image 真的含本次變更
docker exec asset-external-materials-service sh -lc 'ls -la /app/*.jar'
docker exec asset-business-services sh -lc 'ls -la /app/*.jar'
```

```bash
# 4. 抓取層驗收：重新同步一檔已知受影響標的（以 2885 為例），確認新抓取不再產生價差列
docker exec asset-external-materials-service curl -fsS -X POST 'http://localhost:8080/internal/dividend/sync?code=2885&market=%E5%8F%B0%E8%82%A1'
docker exec asset-external-materials-service curl -fsS 'http://localhost:8080/internal/dividend-history?code=2885&market=%E5%8F%B0%E8%82%A1&years=12'
```
> 第 4 步的唯讀路徑輸出中，2026 年應只有 `stockDividend=0.4` 一筆股票股利事件，不應再看到 `2.6269`。

```bash
# 5. 髒資料清理前查詢當下實際受影響列（取代背景段落的示例 id）
docker exec asset-postgres sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -c \"SELECT id, stock_code, year, ex_rights_date, cash_dividend, stock_dividend, event_status FROM stock_dividend_history WHERE stock_code IN ('2881','2882','2885','2891','3037','7556') AND COALESCE(cash_dividend,0)=0 AND COALESCE(stock_dividend,0)>0 AND event_status='ACTIVE' ORDER BY stock_code, ex_rights_date, id;\""
```

```bash
# 6. 逐列呼叫 363.3a 端點取消判定為錯誤的列（{code}/{market}/{id} 替換為第 5 步查到的實際值，逐一執行；
#    market 需 URL encode，台股＝%E5%8F%B0%E8%82%A1）
docker exec asset-business-services curl -fsS -X POST 'http://localhost:8080/internal/dividend/cancel-event?code={code}&market=%E5%8F%B0%E8%82%A1&id={id}'
```

```bash
# 7. 清理後複查：情境一各標的每個年度應只剩一列 ACTIVE，情境二四個樣本應全數 CANCELLED
docker exec asset-postgres sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -c \"SELECT stock_code, year, ex_rights_date, cash_dividend, stock_dividend, event_status FROM stock_dividend_history WHERE stock_code IN ('2881','2882','2885','2891','3037','7556') AND COALESCE(cash_dividend,0)=0 AND COALESCE(stock_dividend,0)>0 ORDER BY stock_code, ex_rights_date, event_status;\""
```

```bash
# 8. 還原權息下游影響抽樣覆核（findAdjustmentEvents 消費者之一：交易雷達還原權息序列）
docker exec asset-business-services curl -fsS 'http://localhost:8080/api/market-data/dividends?code=2885&market=%E5%8F%B0%E8%82%A1&years=12' | head -c 2000
```

**通過判準**：
- 第 4 步：`2885` 2026 年只剩 `stockDividend=0.4`，不再出現 `2.6269`。
- 第 7 步：`2881`／`2885`／`2891` 每個受影響年度只剩**一列 ACTIVE**（金額為較小、貼近真實配股率的值）；`2882`／`3037`／`7556` 的 4 個孤立樣本全數 `CANCELLED`、無殘留 ACTIVE 純配股列。
- 全部單元測試綠燈，`docker compose` 兩支服務健康檢查通過。

## 完成報告

**實際改動檔案**：
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/DividendFetchClient.java`（`fetchTwDividendResult`／`parseFinMindResultRows` 排除「權」型 fallback 列，只留 `log.warn` 稽核軌跡）
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/DividendFallbackStockExclusionTest.java`（新增，6 案）
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/DividendFetchCoverageTest.java`（既有矛盾斷言改向）
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/DividendFetchWindowAnchorTest.java`（既有矛盾斷言改向）
- `backend/src/main/java/com/steven/assets/service/DividendCurrentStateProjectionService.java`（新增 `cancelEventById(String code, String market, long id)`）
- `backend/src/main/java/com/steven/assets/controller/InternalDividendMaintenanceController.java`（新檔，`POST /internal/dividend/cancel-event`）

**單元測試**：`external-materials-service`（576 tests，含 Docker-testcontainers 的 `PriceCacheWriterFubonRedisIntegrationTest`）與 `backend`（1403 tests，含 `FubonSnapshotLockPostgresTest`）皆全綠，無 Failures/Errors。

**架構稽核**：`arch-auditor` 對本次 diff 查證零 findings（Controller 只注入 Service、未接 BFF／9090、無外部行情直連違規）。

**抓取層驗收（AC1/AC8）**：`--no-cache` 重建 `external-materials-service`／`business-services`，`restart bff`，三支容器皆 healthy。重跑 `POST /internal/dividend/sync?code=2885&market=台股` → `{"written":16}`；`GET /internal/dividend-history?code=2885&years=12` 的 2026 年只剩 `stockDividend=0.4`，全部輸出不再出現 `2.6269`。獨立複驗（非執行清理的 subagent，由主 agent 另行 curl）結果一致。

**既有髒資料清理（AC5/AC6，363.3b–363.3f）— 16 列取消，12 列保留**：清理前重新查詢，實際受影響列與任務檔背景段落列出的 id 完全一致（未變動）。

情境一，比例 ≥ 2 倍，套用量級啟發式（保留較小值）：

| code | 取消 id | anchorDate | 取消前值 | 保留值(id) | 比例 |
|---|---|---|---|---|---|
| 2881 | 1008 | 2021-09-06 | 8.4644 | 1.0(395) | 8.46× |
| 2881 | 1009 | 2022-09-22 | 2.6905 | 0.5(393) | 5.38× |
| 2881 | 1010 | 2023-09-04 | 3.0857 | 0.5(391) | 6.17× |
| 2881 | 1011 | 2024-09-09 | 4.4048 | 0.5(389) | 8.81× |
| 2881 | 1012 | 2025-09-25 | 2.1707 | 0.25(387) | 8.68× |
| 2885 | 1015 | 2022-08-12 | 0.6175 | 0.3(419) | 2.06× |
| 2885 | 1016 | 2023-08-11 | 0.3739 | 0.15(417) | 2.49× |
| 2885 | 1017 | 2024-08-12 | 0.6392 | 0.2(415) | 3.20× |
| 2885 | 1018 | 2025-08-12 | 0.967 | 0.3(413) | 3.22× |
| 2885 | 1212 | 2026-08-18 | 2.6269 | 0.4(1292) | 6.57× |

情境一，比例 < 2 倍，改走 FinMind 主表核對（`GET /internal/dividend-history?code=...` 只查主表）：

| code | 取消 id | anchorDate | 取消前值 | 保留值(id) | 比例 | 核對結果 |
|---|---|---|---|---|---|---|
| 2885 | 1014 | 2020-08-17 | 0.7346 | 0.4(422) | 1.84× | 主表該 anchorDate 給 `stockDividend=0.4`，與保留列相符 |
| 2891 | 1013 | 2016-10-12 | 1.3444 | 0.8(411) | 1.68× | 主表該 anchorDate 給 `stockDividend=0.8`，與保留列相符 |

情境二，孤立事件（背景段落已用 Goodinfo 逐一查證查無對應真實事件），全數取消：

| code | id | anchorDate | 值 |
|---|---|---|---|
| 2882 | 1225 | 2019-10-14 | 0.2288 |
| 2882 | 1226 | 2022-12-05 | 0.8332 |
| 3037 | 1227 | 2025-11-14 | 1.8247 |
| 7556 | 1235 | 2025-08-18 | 1.142 |

全部 16 次 `POST /internal/dividend/cancel-event` 呼叫皆回 200。清理後複查（主 agent 獨立重跑第 7 步 SQL，非沿用執行清理 subagent 的宣稱）：`2881`／`2885`／`2891` 每個受影響年度只剩一列 ACTIVE（較小值）；`2882`／`3037`／`7556` 四個孤立樣本全數 CANCELLED，無殘留 ACTIVE 純配股列，與宣稱一致。

**下游抽樣覆核（第 8 步）**：`/api/market-data/dividends?code=2885&years=12` 反映清理後狀態，2020 年 `stockDividend=0.4`（非 0.7346）、2026 年 `stockDividend=0.4`（非 2.6269）。

**端點行為複驗**：主 agent 另以不存在的 `id` 呼叫 `/internal/dividend/cancel-event` 確認回 404（未寫入），與 `cancelEventById` 的存在性檢查設計相符。

**與原計畫的偏差**：無實質偏離。清理過程中額外發現一筆非本任務範圍的既有列（`2885` id=1004，`source=TWSE_TWT48U_ALL+TPEX_EXRIGHT_PREPOST`，屬 Task 361 官方日曆管線、已是 CANCELLED 狀態），與本次 FinMind fallback 缺陷無關，未觸碰，如實記錄。
