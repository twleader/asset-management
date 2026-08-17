# [t345] 股利歷史重複事件去重——同除息日同金額不得因發放日 metadata 分裂成兩列

**對應 Requirements:** Requirement 13（股票分析對話框的股利歷史頁籤：每年事件列＋年度小計列，年度小計不得灌水）、Requirement 65（append-only dividend snapshot 與 ACTIVE/CANCELLED current-state 投影語意）
**前置任務:** t322（append-only 股利 evidence 的 JDBC 時間邊界）
**Liquibase changeset:** 無（不改 schema，只改投影與對帳邏輯；既有髒資料由投影自癒，不寫 data migration）

## 背景

### 錯誤行為（2026-08-17 實測運行中 DB，00881 國泰台灣科技龍頭）

00881 每年固定配息兩次，但股利歷史頁籤 2026 與 2025 年**各出現三個事件列（應為兩個）**，年度小計被灌水成 2026 合計 $9.90、殖利率 27.60%，2025 合計 $2.05。`stock_dividend_history` 實際內容：

| id | year | 除息日 | 現金股利 | 發放日 | yield_pct | event_key | event_status | source |
|---|---|---|---|---|---|---|---|---|
| 1121 | 2026 | 2026-08-18 | 4.60 | 2026-09-11 | — | d448df… | **CANCELLED** | FinMind[TaiwanStockDividend+TaiwanStockDividendResult] |
| 1122 | 2026 | 2026-08-18 | 4.60 | **null** | — | a31f09… | ACTIVE | TWSE_TWT48U_ALL+TPEX_EXRIGHT_PREPOST |
| 151 | 2026 | 2026-01-20 | 2.65 | 2026-02-12 | 7.3878 | （空） | ACTIVE | FinMind（舊制列，投影制之前寫入） |
| 1024 | 2026 | 2026-01-20 | 2.65 | **null** | — | 2b6394… | **ACTIVE（重複）** | FinMind[TaiwanStockDividend+TaiwanStockDividendResult] |
| 152 | 2025 | 2025-08-18 | 0.55 | 2025-09-11 | 1.9935 | 165ef8… | ACTIVE | （正常：新事件值全等舊制列，對帳成功） |
| 153 | 2025 | 2025-01-17 | 0.75 | 2025-02-18 | 3.0475 | （空） | ACTIVE | FinMind（舊制列） |
| 1023 | 2025 | 2025-01-17 | 0.75 | **null** | — | 1ab2dc… | **ACTIVE（重複）** | FinMind[TaiwanStockDividend+TaiwanStockDividendResult] |

前端 `StockAnalysisDialog.vue` 依除息日年份分組加總產生年度小計 → 2026＝4.60+2.65+2.65＝**9.90**（真實 7.25）、2025＝0.55+0.75+0.75＝**2.05**（真實 1.30）。年度殖利率以合計現金股利/該年最近一次除息昨收價（35.87）計算 → **27.60%**（灌水）。

### 根因（三個環節疊加，缺一不會壞）

1. **事件身分含發放日。** `DividendSnapshotStore.canonicalEvent`（external-materials-service）的 `event_key = sha256(year|除息日|現金|股票|現金發放日|股票發放日)`；backend `DividendCurrentStateProjectionService.valueIdentity` 同樣把兩個發放日算進身分。發放日是**公告 metadata**（常晚於除息事件本身公告，且 FinMind `TaiwanStockDividendResult` 與 TWSE TWT48U 兩來源根本不提供），null→有值或來源間缺值都會讓同一顆真實事件變成「不同身分」。
2. **upsert 對帳要求全欄位相等。** `JdbcDividendCurrentStateRepository.findMatchingIds` 先比 `event_key`、再比「year＋除息日＋金額＋**兩個發放日** 全部 `IS NOT DISTINCT FROM`」。舊制列（id 151/153：`event_key` 空、發放日已 enrich）對上新 snapshot 事件（有 key、發放日 null）兩關都不中 → **INSERT 第二列**。id 152 之所以沒重複，是因為該事件新舊兩邊發放日恰好都有值且相等。
3. **取消對帳只涵蓋未來事件。** `projectOne` 的 cancel pass 只掃 `findActiveFutureEvents`（`ex_dividend_date > decisionDate`），已發生事件的重複列**永遠沒有機制清掉**。未來事件那半雖會取消（id 1121），卻因身分過嚴把「含發放日的較完整列」取消、留下較空的列——發放日資訊被丟棄（頁面 2026-08-18 顯示不出發放日 2026-09-11 就是這樣來的）。

### 影響範圍不只顯示

`stock_dividend_history` 的重複 ACTIVE 列同時餵給：
- `StockDividendHistoryRepository.findAdjustmentEvents` → `BacktestService`（三處呼叫）、`TradingRadarService`、`HistoricalBondYieldBetaEvidenceAdapter` 的**還原權息**——重複列會把同一筆配息扣兩次，還原序列直接錯。
- `DividendHistoryService.findFromDbReadOnly` → 績效比較頁（Requirement 33）。

### 修法定調

事件同一性改以「**除息日＋現金股利＋股票股利**」為準（下稱 relaxed identity；金額比對 **null 一律視 0**，與 `uk_dividend_event` 索引的 `COALESCE(…,0)` 口徑一致）；發放日、`event_key`、來源都是 metadata，只能更新既有列、不得分裂新列。**同日不同金額仍視為不同事件**（可能是真實的兩筆或來源分歧，不自動合併——未來事件由既有 cancel pass 收斂，已發生事件保留並存，屬已知限制）。既有髒資料不寫 migration，由投影入口自癒：`projectOne` 每次讀取都會被 `DividendHistoryService.projectFailSoft` 呼叫，收斂步驟放在那裡即可讓標的在下次開啟股利對話框時修復（radar／backtest 的 `DividendEventEvidencePipeline.resolve` 亦觸發 `projectOne`，擴大覆蓋面）。注意 `findFromDbReadOnly`（績效比較頁）依設計零寫入副作用、不觸發收斂；僅被該頁讀取的標的，須待同標的經股利對話框或 evidence pipeline 觸發 `projectOne` 後才修復。

## 要做什麼

### backend：`JdbcDividendCurrentStateRepository`（upsert 對帳放寬＋不得倒退 enrich）

- [x] 345.1 `findMatchingIds` 改為**六段依序**匹配，命中即回傳（全部 `ORDER BY id` 取第一筆；relaxed 段改為 `ORDER BY (cash_payment_date IS NULL), id`，優先命中已有發放日的列）：
  1. `event_key` 相等 **且 `event_status='ACTIVE'`**
  2. 全欄位值相等（現行第二段條件不變）**且 `event_status='ACTIVE'`**
  3. **relaxed**：`ex_dividend_date` 相等且 `COALESCE(cash_dividend,0)=COALESCE(?,0)`、`COALESCE(stock_dividend,0)=COALESCE(?,0)`（**null 金額一律視 0**，與 `uk_dividend_event` 索引口徑一致；運行中 DB 實測 TWSE 投影列 `stock_dividend=null` 而 FinMind 列為 `0.000000`，用 `IS NOT DISTINCT FROM` 會 miss），**不比發放日、不比 year**，且 `event_status='ACTIVE'`
  4. `event_key` 相等（不限狀態；保留現行 revive-cancelled 語意）
  5. 全欄位值相等（不限狀態）
  6. relaxed（不限狀態，金額同樣 null 視 0）
  **ACTIVE 三段整塊在前、不限狀態三段整塊在後**（1→6 依序，不得交錯成 key-ACTIVE→key-任意→…）：同 key 的 CANCELLED 重複列存在時，絕不能搶在 ACTIVE 列前被選中（`upsertHistoricalEvent` 的 UPDATE 帶 `AND event_status='ACTIVE'` guard，選中 CANCELLED 列會變成靜默 no-op——舊病復發）。
- [x] 345.2 `upsertEvent` 的 UPDATE 分支改為「先讀後寫」：先 SELECT 目標列現值，於 Java 端算出寫入後各欄最終值——`cash_payment_date`／`stock_payment_date` 取 incoming 非 null 者、否則保留現值（**來源給 null 不得清掉既有發放日**）；金額、`source`、`event_key` 以 incoming 為準——再依 345.3 的 tombstone 防撞後執行 UPDATE。INSERT 分支不變（走到 INSERT 代表連 relaxed 都沒中，即無任何既有列同除息日同金額，`uk_dividend_event` 必不衝突）。
- [x] 345.3 **既有唯一索引 `uk_dividend_event` 是硬約束（v1.99.0-radar-dividend-event-identity.sql）**：欄位為 `(stock_code, market, year, COALESCE(ex_dividend_date,…), COALESCE(cash_dividend,0), COALESCE(stock_dividend,0), COALESCE(cash_payment_date,…), COALESCE(stock_payment_date,…), COALESCE(event_key,''))`，**不含 `event_status`**——CANCELLED 列照樣佔用 tuple。任何 UPDATE（345.2 的 upsert UPDATE 與 345.4 的 `applyMergedEnrichment`）寫入前，必須先以 Java 端算好的「寫入後 tuple」刪除與之在索引口徑下逐欄全等的 **CANCELLED** 列（`DELETE … WHERE event_status='CANCELLED' AND id<>目標 AND 各欄以 COALESCE 口徑相等`；UPDATE 不改 `year`，故 tuple 的 year 取目標列現值）。否則實測資料立刻炸：除息日過後 FinMind PARTIAL 快照重投影 2026-08-18 事件（發放日 2026-09-11），relaxed 命中 ACTIVE 列 1122 並補上發放日與 key 後，tuple 與 CANCELLED 列 1121 逐欄全等 → duplicate key violation → `projectOne`（`REQUIRES_NEW`）整體 rollback，該檔自癒與 enrich **永久失敗**。「全等」一律以 **`uk_dividend_event` 的 9 欄 COALESCE 口徑**為準：`yield_pct`／`previous_close`／`fill_days`／`source` 不在索引內、**不參與比對**，索引口徑全等即刪（enrichment 差異不阻擋刪除，evidence 真相在 snapshot 三表）；**索引口徑不全等的 CANCELLED 列一律保留**（finalized 還原語意，Requirement 65）。（註：`db/schema.sql` 的 dump 早於 v1.99.0，其 3703 行仍是 4 欄舊版且整份 dump 尚無 `event_key`／`event_status` 欄；本索引以 changelog v1.99.0 為準，已向運行 DB `pg_indexes` 查證為 9 欄。）與 ACTIVE 列全等的情形設計上不可能發生（collapse 先行＋seg2 先於 seg3），實作防禦性檢查發現時以該 ACTIVE 列為對帳目標、不得強行 UPDATE。SQL 一律多句簡單語法（SELECT→DELETE→UPDATE），不得用 `UPDATE … FROM`。

### backend：`DividendCurrentStateRepository` port 新增原子操作

- [x] 345.4 port 介面新增（決策邏輯留在 projection service，port 只做原子讀寫，遵守既有 javadoc 分層宣告）：
  ```java
  record ActiveEventDetail(long id, String eventKey, Integer year, LocalDate exDividendDate,
          BigDecimal cashDividend, BigDecimal stockDividend, LocalDate cashPaymentDate,
          LocalDate stockPaymentDate, BigDecimal yieldPct, BigDecimal previousClose,
          Integer fillDays) {}

  /** 某檔全部 ACTIVE 且除息日非 null 的事件列（含 enrichment 欄位），id 升冪。 */
  List<ActiveEventDetail> findActiveEventDetails(String code, String market);

  /** 把合併後的 metadata/enrichment 寫回 keeper 列（不改金額、除息日、狀態）。 */
  void applyMergedEnrichment(long id, String eventKey, LocalDate cashPaymentDate,
          LocalDate stockPaymentDate, BigDecimal yieldPct, BigDecimal previousClose,
          Integer fillDays);
  ```
  `JdbcDividendCurrentStateRepository` 實作兩者；`applyMergedEnrichment` 直接以參數覆寫上述六欄＋`updated_at=NOW()`（合併值已在 service 端算好，SQL 不再 COALESCE），**執行前須先做 345.3 的 tombstone DELETE 防撞**（keeper 補上 dup 的 `event_key` 後，tuple 可能與「兩列發放日皆 null、僅 key 不同」情境下同組被取消的列全等）。

### backend：`DividendCurrentStateProjectionService` 收斂重複＋取消對帳保護

- [x] 345.5 新增私有步驟 `collapseDuplicateActiveEvents(code, market)`，於 `projectOne` 通過 null 檢查後**最先執行**（在 `projectHistorical` 之前；不依賴任何 snapshot 存在，讓髒資料在查無 snapshot 時也能自癒）：
  1. `findActiveEventDetails` 取全部 ACTIVE 列，依 relaxed identity（除息日＋`stripTrailingZeros` 後的現金股利＋股票股利；null 金額以 `0` 視之）分組。
  2. 每組 size > 1 時選 keeper：`cash_payment_date` 非空優先 → `yield_pct` 非空優先 → id 最小。
  3. 逐欄合併 metadata：keeper 自身非 null 值優先，缺值依上述偏好順序從其餘列補（`event_key`、兩個發放日、`yield_pct`、`previous_close`、`fill_days`）；先 `cancelActiveEvent` 其餘列，再呼叫 `applyMergedEnrichment(keeper.id, …)`（內含 345.3 tombstone DELETE：合併後 keeper tuple 若與剛取消的 dup 在索引 9 欄口徑下全等，該 dup 會被刪除而非留存——刪除判準仍是 345.3 的索引口徑，此時 dup 的 metadata 已併入 keeper，刪除不損失資訊）。
  4. 有收斂發生時記一行 INFO（含 code/market/收斂列數）。
- [x] 345.6 cancel pass 的身分集合加入 relaxed token：`addIdentities` 對每個權威正金額事件額外加入 `"date-amount:" + 除息日 + "|" + 現金 + "|" + 股票`（金額 `stripTrailingZeros`、**null 一律以 `0` 產 token**，與 345.1 seg3／345.5.1／`uk_dividend_event` 同一口徑；否則 TWSE 權威事件 `stock=null` 對 FinMind 既有列 `stock=0.000000` 產出 `…|NULL` vs `…|0` 不匹配，保護失效），`matchesAnyIdentity` 以既有列的同型 token 檢查。**同除息日同金額的未來 ACTIVE 列，不得因 `event_key` 或發放日差異被取消**（修掉 id 1121 那類「取消較完整列、發放日遺失」的行為；上游 345.1 relaxed upsert 命中後 key 會被覆寫成本次來源的 key，此保護是第二道防線）。
- [x] 345.7 不動 external-materials-service：`event_key` 的 canonical 定義維持現狀（它是 snapshot event 的內容雜湊，改了會讓既有 snapshot 全部視為新內容）；本任務只在 backend 投影層把 key 降級為 metadata。前端 `StockAnalysisDialog.vue`、BFF、`DividendHistoryService` 讀取路徑均不修改。

### 測試（與實作同交付）

- [x] 345.8 `JdbcDividendCurrentStateRepositoryTest` 新增（沿用既有 Mockito mock `JdbcTemplate` 風格）：
  - 發放日不同（既有列有、來源 null）時 upsert 走 UPDATE 而非 INSERT（relaxed 段命中），且既有發放日保留（不被 null 倒退）。
  - relaxed 段對「既有列 `stock_dividend=0` vs incoming `null`」命中（null 視 0）。
  - 同 key 同時存在 CANCELLED 與 ACTIVE 列時，選中 ACTIVE 列。
  - tombstone DELETE（`event_status='CANCELLED'` 且全欄 COALESCE 相等）在 UPDATE **之前**發出（Mockito `InOrder`），且 DELETE 條件含 `id<>` 目標。
  - `applyMergedEnrichment` 同樣先 DELETE 後 UPDATE。
- [x] 345.9 `DividendCurrentStateProjectionServiceTest` 新增（沿用既有 mock repository 風格）：
  - 00881 fixture：同除息日同金額兩列（一列含發放日＋yield、一列全空）→ keeper 為含發放日列、`applyMergedEnrichment` 收到由重複列補進的 `event_key`、重複列被 `cancelActiveEvent`、keeper 不被取消。
  - 三列同組（一 enrich＋兩空）→ 取消兩列。
  - `findLatestComplete` 與 `findLatestHistorical` 皆空時，收斂仍執行。
  - 未來 ACTIVE 列與權威事件同除息日同金額、僅發放日/key 不同 → cancel pass 不取消。
  - 未來 ACTIVE 列金額不同 → 仍取消（現行改期/改額語意不變）。

## 驗證

```bash
# 單元測試（Java 25 環境跑 Mockito 需 extraArgLine，勿用 -DargLine）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 重建部署（JVM service 一律 --no-cache；recreate business 後 restart bff）
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management restart bff

# 觸發自癒（在 business 容器內帶租戶 header 打股利端點）並查證 DB
docker exec asset-business-services curl -s -H "X-User-Id: 1" -H "X-User-Role: ADMIN" \
  -H "X-User-Status: ACTIVE" "http://localhost:8080/api/market-data/dividends?code=00881&market=台股&years=10"
docker exec asset-postgres psql -U assets -d assets -c "
  SELECT ex_dividend_date, COUNT(*) FROM stock_dividend_history
   WHERE stock_code='00881' AND event_status='ACTIVE' AND ex_dividend_date IS NOT NULL
   GROUP BY ex_dividend_date HAVING COUNT(*)>1;"   # 期望 0 列
docker exec asset-postgres psql -U assets -d assets -c "
  SELECT id, ex_dividend_date, cash_dividend, cash_payment_date, yield_pct, event_status
    FROM stock_dividend_history WHERE stock_code='00881' AND year>=2025 ORDER BY id;"
# 期望：151（2026-01-20，發放日 2026-02-12、yield 保留）與 153（2025-01-17）仍 ACTIVE 且補上 event_key；
#       1023/1024 CANCELLED；1122（2026-08-18）ACTIVE。頁面 2026 年度小計＝7.25、2025＝1.30。
```

## 完成報告

**完成日期：** 2026-08-17

**變更檔案（backend 5 檔，無 Liquibase、無前端/BFF/external-materials 變更）：**
- `backend/src/main/java/com/steven/assets/service/DividendCurrentStateRepository.java` — 新增 `ActiveEventDetail` record、`findActiveEventDetails`、`applyMergedEnrichment`
- `backend/src/main/java/com/steven/assets/repository/JdbcDividendCurrentStateRepository.java` — `findMatchingIds` 六段依序（ACTIVE 三段整塊優先）；`upsertEvent` 先讀後寫（發放日不倒退）；`deleteCancelledTupleTwins` tombstone DELETE（哨兵值與 v1.99.0 一致：`DATE '1970-01-01'`／`0`／`''`）；`findActiveTupleTwin` 防禦性檢查；實作兩個新 port 方法
- `backend/src/main/java/com/steven/assets/service/DividendCurrentStateProjectionService.java` — `projectOne` 進場先 `collapseDuplicateActiveEvents`（分組→keeper→合併→取消＋INFO log）；`addIdentities`／`matchesAnyIdentity` 加 `date-amount:` relaxed token（null 金額以 0 產 token）
- 測試：`JdbcDividendCurrentStateRepositoryTest`（+5，含 `InOrder` 驗證 DELETE 先於 UPDATE）、`DividendCurrentStateProjectionServiceTest`（+5，含 00881 fixture、三列同組、雙 snapshot 皆空仍收斂、未來列同日同額不取消／金額不同仍取消）

**測試結果：** `mvn test -DextraArgLine=-Dnet.bytebuddy.experimental=true` → `Tests run: 1152, Failures: 0, Errors: 0, Skipped: 0`（主 agent 獨立重跑驗證，surefire 133 檔彙總一致）。spec 對抗式審查三輪收斂（R1：2 major 修畢；R2：1 major 修畢；R3：0 major）；arch-auditor 0 findings。

**部署與 DB 查證（2026-08-17 23:48 rebuild --no-cache → recreate business-services → restart bff）：**
- 部署前全 DB relaxed 口徑重複組 4 組（0056、00850、00881×2）；對三檔各打一次 `/api/market-data/dividends` 觸發自癒後，重複組查詢回 **0 列**。
- business log：`收斂重複 ACTIVE 股利事件：台股 00881 取消 2 列`／`0056 取消 1 列`／`00850 取消 1 列`，無 ERROR、無投影失敗 WARN。
- 00881 終態與本檔驗證段期望完全一致：151（2026-01-20，保留發放日 2026-02-12、yield 7.3878、fill 16，補上 event_key `2b6394…`）與 153（2025-01-17，補上 `1ab2dc…`）仍 ACTIVE；1023/1024 轉 CANCELLED（tuple 與 keeper 不全等，未觸發 tombstone 刪除，符合規則）；1122（2026-08-18）ACTIVE。
- API 年度合計：2026＝**7.25**、2025＝**1.30**（修正前 9.90／2.05），每年恰兩個除息事件列，頁面年度小計隨之正確。
