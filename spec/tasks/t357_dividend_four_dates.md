# [t357] 配息事件的四個日期各自獨立落地與顯示（除息／除權／發放股息／發放股權）

**對應 Requirements:** Requirement 94（配息事件的除息日、除權日、發放股息日、發放股權日各自獨立儲存與顯示，格式一律 `yyyy-MM-dd`；停止把除息與除權壓成同一欄）
**前置任務:** t356（交易雷達三軌與週K，已完成並 merge）
**Liquibase changeset:** `v1.110.0-dividend-ex-rights-date.sql`

## 背景

使用者於 2026-08-22 指出交易雷達的「下一配息（已知時點）」區塊只給一個日期加一串 ISO timestamp（`2026-08-21T21:05:57.205074Z`），要求改為**四個日期**：除息、除權、發放股息、發放股權，且一律 `yyyy-MM-dd`。

查證後發現這不是顯示問題，而是**入庫當下的沉默資料遺失**：

- FinMind 的 `TaiwanStockDividend` 同時提供 `CashExDividendTradingDate`（除息日）與 `StockExDividendTradingDate`（除權日）。
- 本專案有**六處**壓合，分兩種機制：(1) FinMind `TaiwanStockDividend` 兩個原始欄位互相 fallback（第 1、2、3 處）；(2) 單一日期欄本來就沒有 fallback 對象，靠 `kind`／型別字串判準路由卻沒有把判讀結果帶進落地值（第 4、5、6 處，涵蓋官方日曆與 `TaiwanStockDividendResult` 兩個獨立資料集）。六處都要改，而且**角色各不相同**（只改其中幾處會漏掉真正落地的那條）：
  1. `DividendFetchClient.fetchTw`（約 519–520 行）——**這是歷史落地的主路徑**：`DividendPersister.syncOne()` → `client.fetchObservations()` → `fetchTw(...)` → `DividendSnapshotStore` → `stock_dividend_snapshot_event` → `DividendCurrentStateProjectionService` → `stock_dividend_history`。此處 `ex` 缺值時是 `continue` 只跳過該列。
  2. `DividendFetchClient.parseFinMindDividendRows`（約 379–380 行）——只服務 **upcoming 45 日 scope 的 provider fallback**（`fetchFinMindUpcomingScope`）。此處 `ex == null` 會 `return ParseEvents.invalid(...)`，**已累積的事件全數丟棄**、整個 observation 標為 `valid=false`。
  3. `MarketDataFetchService.getTwDividendHistory`（約 852–853 行）——**唯讀顯示投影，不寫任何表**，且其唯一出口 `MarketDataService.getDividendHistory` 目前**零呼叫端**。仍須一併修正以免日後復活；此處要改的是 `DividendRow` 的欄位，不是 DB 欄位。
  4. `TaiwanOfficialDividendCalendarClient.toEvent()`（約 184–188 行，`parseTwse`／`parseTpex` 呼叫）——**這是 upcoming 45 日 scope 的主要來源**（`MarketDividendUpcomingScopeClient` 對台股優先採用，FinMind 只在此不可用時 fallback），細節見 357.2e。
  5. `DividendFetchClient.parseFinMindResultRows`（約 397–414 行）——**upcoming 45 日 scope 的次要 fallback**（`TaiwanStockDividend` 查無時才用 `TaiwanStockDividendResult`），細節見 357.2f。
  6. `DividendFetchClient.fetchTwDividendResult`（約 647–674 行）——**歷史落地主路徑 `fetchTw` 的 fallback**（同一份 `TaiwanStockDividendResult`，服務個股資料集查無的 ETF），細節見 357.2f。
- 因此**只要一檔同時配息又配股，除權日就在入庫當下被丟棄**，沒有任何錯誤或警示。
- DB 兩張相關表都只有三個日期欄，沒有除權日（**以運行中的 `asset-postgres` 查證，非 `db/schema.sql`**——後者已落後，實測 `stock_dividend_history` 另有 `event_status`／`event_key` 兩欄是 dump 裡沒有的）：
  - `stock_dividend_snapshot_event`：`ex_dividend_date`／`cash_payment_date`／`stock_payment_date`
  - `stock_dividend_history`：`ex_dividend_date`／`cash_payment_date`／`stock_payment_date`

**實資料證據（運行中 DB，2026-08-22 查得）。** 同時配息又配股的事件確實存在且資料確實缺漏：

| 代號 | 年度 | 現金股利 | 股票股利 | `ex_dividend_date` | `cash_payment_date` | `stock_payment_date` |
|---|---|---:|---:|---|---|---|
| 7556 | 2026 | 4.0424 | 0.4930 | 2026-07-09 | 2026-08-14 | **（空）** |
| 9933 | 2025 | 1.0019 | 1.0019 | 2025-08-15 | 2025-09-17 | **（空）** |
| 7556 | 2025 | 3.2000 | 0.5000 | 2025-07-03 | 2025-08-08 | **（空）** |
| 7556 | 2024 | 2.5000 | 0.5000 | 2024-07-08 | 2024-08-09 | **（空）** |
| 7556 | 2023 | 3.0000 | 0.5000 | 2023-07-10 | 2023-08-15 | **（空）** |

這五筆每一筆都同時有現金與股票股利，卻**只有一個除息除權日、而且發放股權日全部是空的**——除權日被壓合丟棄、發放股權日則根本沒落地。7556 連續四年皆如此，代表這不是偶發資料問題。**這五筆是本任務回補後的第一組驗收對象。**

**同一個缺陷已經在使用者畫面上顯示錯誤資訊。** 「股票分析 → 股利歷史（10 年）」的**「除息日」欄，對純配股事件顯示的其實是除權日**。**經運行中 DB 查證，`2881` 是混合配息配股標的（2021–2025 各年皆有非零現金股利，非全年零現金），其現金股利與股票股利各自入列為獨立事件（同年多列）**；其中「純配股的那一列子事件」現金股利為 0，該列卻顯示 `2025-09-25` 等日期——那是除權日被 fallback 塞進除息欄。修正後這一列對這類事件應顯示 `—`，除權日則出現在新的除權日欄；`2881` 同年份裡有現金股利的那一列不受影響。**此為本任務可由使用者直接複驗的驗收點。**

使用者已明確選定兩件事：**「下一配息」顯示的是同一次事件的四個日期**（不是四個日期各自往後找最近的一個）；以及**歷史資料連同一併重抓回補**（不接受只從下次抓取起累積）。

## 要做什麼

### 357.0 實作前置：`exDividendDate`／`ex_dividend_date` 全樹窮盡性掃描（強制，先於 357.1 開工）

> 本任務規劃階段的 spec-review 歷經五輪，每一輪都額外發現至少一個先前完全沒被提到的
> `DividendEvent` 建構點或 `exDividendDate`／`ex_dividend_date` 消費點——且不只是 SQL
> 查詢，還包含記憶體內合併（`DividendFetchClient.mergeTaiwanEvents()`／`taiwanEventKey()`／
> `detailScore()`）與 identity 比對（`DividendCurrentStateProjectionService.relaxedIdentity()`／
> `valueIdentity()`／`addIdentities()`）這類容易被忽略的位置。這證明用 spec 逐一列舉清單
> 的方式無法保證窮盡；連續五輪在同一種模式上失手是統計顯著的訊號，不是審查不夠仔細，
> 而是這個欄位的呼叫端分散在三個服務、十幾個檔案，沒有任何工具幫忙算「涵蓋率」。因此
> 在動手改 357.1 之前，必須先產出一份掃描清單，作為本任務完成報告與 PR 描述的一部分接
> 受審查，取代「再開第六輪 spec-review 擠牙膏」。

- [x] **357.0a** 對 `backend/`、`bff/`、`external-materials-service/` 三個模組的 `src/main`（本任務的窮盡性風險在 production 落地路徑，不含 `src/test`）執行：
      ```bash
      grep -ran "new DividendEvent(\|new DividendFetchClient.DividendEvent(" . | grep -v /test/
      grep -ran "exDividendDate\|ex_dividend_date\|ExDividendDate" backend/src/main bff/src/main external-materials-service/src/main
      ```
      列出**全部**匹配位置（檔案:行號）。
- [x] **357.0b** 對每一個建構點，分類為「已知六處之一（357.2a–f）」或「新發現」；新發現者比照既有六處的規格（互不頂替、anchorDate 定義、測試要求）另立子項並實作。
- [x] **357.0c** 對每一個消費點（讀取 `exDividendDate()`／`ex_dividend_date` 做存在性判斷、區間比較、排序 key、dedupe／identity key、merge key 用途），逐一分類為：
      - 「本次一併改用 `anchorDate`／`COALESCE(ex_dividend_date, ex_rights_date)`」，或
      - 「明確不改並說明理由」（例如：US-only 路徑、`stockDividend` 恆為零）。
      不得留白，不得只寫「已掃描、無問題」。**特別檢查是否有函式把 `exDividendDate` 當作 merge／dedup／identity key 的一部分**（例如把 `null` 值退化為固定字串如 `"NO_DATE"`／`"NULL"` 再參與雜湊或 key 比對）——這類函式即使不在資料庫層，一樣會造成同一支股票不同年度、相同金額的純配股事件被靜默合併或誤判為同一事件（`2885` 於 2022 與 2025 兩筆股票股利皆為 `0.300000` 是已用運行中 DB 實測證實會踩中此陷阱的真實樣本，見 357.2g／357.3d-0c）。
- [x] **357.0d** 此清單本身連同分類理由，寫入完成報告最前面，作為驗收的第一項可稽核證據。

### 357.1 資料模型：新增除權日欄（Liquibase）

- [x] **357.1a** 新增 `backend/src/main/resources/db/changelog/changes/v1.110.0-dividend-ex-rights-date.sql`，並在 `db.changelog-master.yaml` 末尾以既有格式 `include`。**必須冪等、可重複執行**（`ADD COLUMN IF NOT EXISTS`），比照 `v1.107.0` 的既有寫法。
- [x] **357.1b** 兩張表各新增一欄 `ex_rights_date DATE`（nullable，無預設值）：`stock_dividend_snapshot_event`、`stock_dividend_history`。**不得**加 NOT NULL 或預設值——「這個事件沒有除權」與「還沒抓到」都必須能表示成 `null`，用預設值會讓兩者無法區分。
- [x] **357.1c** changeset 的 `--comment` 必須寫明：本欄修正的是「除息與除權被壓成同一欄」的既有缺陷，且既有 `ex_dividend_date` 在回補完成前可能存的是除權日。
- [x] **357.1d** 對應的 JPA entity（`StockDividendHistory`）與 snapshot event 的讀寫 SQL 同步新增欄位。**`db/schema.sql` 是離線 pg_dump 鏡像且已知落後**（實測缺 `stock_dividend_history.event_status`／`event_key`），本任務**不手改它**，也**不得**拿它當現況基準線——查現況一律用 `docker exec asset-postgres psql ... -c '\d <table>'`。

### 357.2 抓取端：停止壓合，兩個除息除權日各自落地

- [x] **357.2a** **上述三處**的 fallback 一律移除：`CashExDividendTradingDate` → 除息日、`StockExDividendTradingDate` → 除權日，**兩者互不頂替**。只配現金的事件除權日為 `null`；只配股的事件除息日為 `null`。落地欄位分別是 `ex_dividend_date` 與 `ex_rights_date`（`MarketDataFetchService` 那一處對應的是 `DividendRow` 的兩個欄位）。
- [x] **357.2b** **⚠ 拆開後有一個會讓資料消失的陷阱，兩條路徑的嚴重度不同，都要顯式處理。** 現在 `ex` 是 `firstNonBlank(cash, stock)`，只配股的事件靠 `StockExDividendTradingDate` 存活。若拆開後改成「除息日為 null 即無效」：
  - 在 `parseFinMindDividendRows` 會 `return ParseEvents.invalid(...)` → **已累積的事件全數丟棄、整個 observation 判為 invalid**；
  - 在 `fetchTw` 只是 `continue` 跳過該列 → 只配股的事件靜默不落地。
  兩處的正確判準都是：**兩個除息除權日至少要有一個可解析**，兩個都缺才是無效。
- [x] **357.2c** **事件的錨定日期（anchor）必須顯式定義**：`anchorDate = min(除息日, 除權日)`（取兩者中較早且非 null 者）。理由：對「只有一個日期」的既有事件，`anchorDate` 恆等於原本 `firstNonBlank` 取到的 `ex`，故**既有行為逐位不變**（`firstNonBlank` 與 `min` 在只有一個非空值時結果相同）；同時有兩個日期時取較早者，才不會讓事件落在區間外而漏抓。此不變性須以測試釘住。

  **`DividendFetchClient` 內壓合後的 `ex` 實際有四個用途，前三個改用 `anchorDate`、第四個維持原始日期不變（最容易被誤改）：**
  1. malformed／跳過判定（改為 357.2b 的「兩個至少一個可解析」）
  2. 日期區間過濾
  3. `year` 推導
  4. **`date.toString()` 直接成為 `DividendEvent.exDividendDate`** ← 這一個才是最終餵進 `canonicalEvent()`／`event_key`／`ex_dividend_date` 落地值的那一個。**它不能改用 `anchorDate`**：落地值必須是**真正的除息日**（沒有就 `null`），否則等於換一種方式繼續壓合。

  **`calcDividendBasis` 不在 `DividendFetchClient` 裡**（它定義於 `StockSourceQuery`、由 `MarketDataFetchService` 呼叫），該處沒有 `DividendEvent` 也沒有 anchorDate 概念，須獨立處理：它算的是**填息天數**，用除息日或 anchorDate 需顯式選擇並寫出理由。
- [x] **357.2d** `DividendFetchClient.DividendEvent` record 追加 `exRightsDate`（緊接 `exDividendDate` 之後）；所有建構點同步更新。該 record 本身**沒有**相容建構式，加欄位會讓所有建構點編譯失敗（可見、不會靜默）。
- [x] **357.2d-2** **真正會靜默吃掉新欄位的是下列五個帶相容形狀的型別，每一個都必須逐一檢查並顯式傳入新值，或在 Javadoc 標明不得用於 production 路徑：**
  - `TradingRadarDto.RadarEvidence`：相容建構式 ＋ `EMPTY` ＋ **三層 `withConfidence` overload 鏈**
  - `DividendEventEvidenceResolver.Event` 與 `Resolution`（各有 compat shape）
  - `MarketDataService.DividendRow`
  - `DividendCurrentStateRepository.Event` 與 `ProjectedEvent`

  本專案剛在 Task 356 因為相容建構式吃掉新欄位而讓整組新因子在 production 靜默失效（且 1334 個既有測試全綠），同一個陷阱不得再犯（見 `spec/tasks/t356_radar_three_horizon_weekly_k.md` 的完成報告）。

- [x] **357.2e（第四個壓合點，且是「下一配息」的主要來源，必修）** 背景段「本專案有三處壓合」**不完整**：`TaiwanOfficialDividendCalendarClient`（TWSE/TPEx 官方除權除息日曆）是**第四個**壓合點，機制與另三處不同——它本來就只有**單一**日期欄（`parseTwse`／`parseTpex` 各自的 `Date`／`ExRrightsExDividendDate`），沒有「現金除息日、股票除權日」兩個原始欄位可 fallback，而是判準 `kind.contains("息") || positive(stock)`（`TaiwanOfficialDividendCalendarClient.java:160、177`）已經明確容許「`kind` 不含『息』但 `stock>0`」的**純配股**列通過，`toEvent(date, cash, stock)`（約 184–188 行）卻不論 `kind` 為何，一律把該 `date` 塞進 `DividendEvent` 的 `exDividendDate` 位置。且此路徑不是次要：`MarketDividendUpcomingScopeClient.fetch()` 對台股優先呼叫官方日曆、`official.complete()` 即直接採用，FinMind 只在官方日曆不可用時才 fallback——也就是說，**這條路徑是「下一配息」（Requirement 94 的頭號驗收情境）在官方日曆可用時的主要資料來源**，不修此處，純配股標的的下一配息在官方日曆可用時會原樣重現使用者今天回報的 bug。

  修法：`parseTwse`／`parseTpex` 建構事件時保留 `kind` 判讀結果（cash-only／stock-only／combined 三種），`toEvent()` 依此分派：`kind` 含「息」且 `cash>0` → 該 `date` 進 `exDividendDate`；`kind` 不含「息」但 `stock>0`（純配股）→ 該 `date` 進 `exRightsDate`、`exDividendDate` 為 `null`；兩者皆為正（合併列）→ 兩欄同填該 `date`（台股官方日曆的合併配股配息事件除權與除息基準日本來就是同一天，這不是 357.4a「兩個日期不同時拆兩筆」要處理的情境，因為此處兩者相同、不衝突）。**`selectTarget()`（約 194–201 行）以 `LocalDate.parse(event.event().exDividendDate())` 組 dedupe key——`exDividendDate` 拆分後對純配股列會是 `null`，`LocalDate.parse(null)` 會直接拋 `NullPointerException` 並中斷整個 `fetch()`（不是「覆蓋或遺失」，是呼叫鏈上游 `MarketDividendUpcomingScopeClient.fetch()` 與 `DividendFetchClient.fetchObservations()` 都沒有 try/catch 接住的例外）**，此 key 邏輯必須改用 `anchorDate = COALESCE(exDividendDate, exRightsDate)` 或等效值解析，不得沿用現行寫法。驗收須有測試：(a) 構造一筆 `kind` 不含「息」、`stock>0`、`cash=0` 的 TWSE/TPEx 官方列，斷言 `toEvent()` 輸出 `exDividendDate` 為 `null`、`exRightsDate` 等於來源日期；(b) **經 `client.fetch(...)` 端到端**呼叫同一筆純配股官方事件，斷言不拋例外且回傳的 `UpcomingScope` 內該事件確實存在（現有 `TaiwanOfficialDividendCalendarClientTest` 既有案例都是端到端風格，此為同構延伸，非額外新工具）。

- [x] **357.2f（第五、六個壓合點，`TaiwanStockDividendResult` fallback，影響 ETF 配股事件、不在既有驗收樣本內，必修）** `DividendFetchClient.parseFinMindResultRows`（約 397–414 行，餵給 `fetchFinMindUpcomingScope` 的 fallback）與 `fetchTwDividendResult`（約 647–674 行，見 `spec/design.md` 「兩段資料源」圖）兩處同樣的壓合手法：兩者皆用 `stock_or_cache_dividend` 判斷 `stock`／`isStock`（含「權」且不含「息」→ 配股），卻不論判斷結果為何，一律把唯一日期欄 `date` 塞進 `DividendEvent` 的 `exDividendDate` 位置（`parseFinMindResultRows:409`／`fetchTwDividendResult:665`）。**此 fallback 主要服務個股資料集查無的 ETF**（`design.md` 已記載：債券 ETF／收益分配型 ETF 在 `TaiwanStockDividend` 查無時，`TaiwanStockDividendResult` 補上其除權息事件）——**但 `fetchTw()` 對 `fetchTwDividendResult` 是無條件呼叫、以事件鍵合併（`mergeTaiwanEvents`），不是「主表查無才 fallback」**（`fetchTw:544-547`；`design.md` 舊版「只有回空時才打結果表」的描述與程式碼不符，本輪已同步修正，見下方 design.md 段落），因此純現金個股與 ETF 都可能同時經過這條路徑。只要 `isStock=true` 的事件走此路徑，除權日一樣會被誤標成除息日——與 357.2a 開頭診斷的缺陷完全同構，只是資料集不同。**這條路徑不在本任務目前規劃的實資料驗收樣本（7556／9933／2881／2885／2891，皆為個股）覆蓋範圍內**，若不修，即使 357.1–357.8 其餘全數實作完成並通過所有指定驗收，ETF 配股事件仍會重現 Requirement 94 要修的原始 bug，且不會被任何既有測試攔到。

  修法：`parseFinMindResultRows` 與 `fetchTwDividendResult` 依現有 `stock`／`isStock` 判準分派：`true`（純配股）→ `exRightsDate`、`exDividendDate=null`；`false`（純現金，此資料集不含合併列語意，`stock_and_cache_dividend` 為單一金額不拆分）→ `exDividendDate`、`exRightsDate=null`。同步更新 `spec/design.md` 「兩段資料源」圖（約 1937–1948 行）反映新分派邏輯，不得讓文件與程式碼繼續脫鉤。驗收須有測試：以一筆 `stock_or_cache_dividend` 含「權」不含「息」的 `TaiwanStockDividendResult` 列，斷言 `fetchTw()` 與 `fetchFinMindUpcomingScope()` 的最終輸出中該事件 `exDividendDate` 為 `null`、`exRightsDate` 為來源日期。**實資料驗收樣本（`00751B`）已實測查無配股事件、全庫已追蹤 ETF 亦無此類事件**：若窮舉合理範圍（例如 FinMind `TaiwanStockDividendResult` 所有 ETF 清單）後仍找不到真實配股 ETF 案例，允許以「單元測試合成資料 ＋ 完成報告記錄查證過程與結論（含查過的候選清單）」取代 Docker 實資料驗收，不得因找不到真實樣本就略過此修正。

- [x] **357.2g（`DividendFetchClient` 記憶體內合併邏輯的 identity/key，實測已證實會靜默吞併真實事件，必修）** `mergeTaiwanEvents()` 家族——`taiwanEventKey()`（約 603–608 行）、`richer()`（約 610–620 行）、`detailScore()`（約 622–632 行）、`eventDateForSort()`（約 638–640 行）——供 `fetchTw()`（`:548`）與 `fetchFinMindUpcomingScope()`（`:237`）共用，供 357.2a–f 落地前的事件去重／合併。`taiwanEventKey()` 對 `exDividendDate()==null` 一律退化為固定字串 `"NO_DATE"` 再組 key；`detailScore()` 完全不認得 `exRightsDate`。357.2a／357.2f 實作後純配股事件的 `exDividendDate` 恆為 `null`，**同一標的、不同年度、金額相同的兩筆純配股事件會產生完全相同的 key，被 `merged.put(key, richer(...))` 互相覆蓋**。**已用運行中 DB 實測證實此碰撞真實存在，非理論案例**：`2885` 在 2022 與 2025 兩年的純配股事件，`stock_dividend` 皆為 `0.300000`——修正後兩者的 `taiwanEventKey()` 皆為 `"NO_DATE|0.3"`，其中一筆會在合併時消失，而這正是 357.3d-3 明訂為「必測樣本」的 24 列之一，若不修，回補後 2885 的年度資料反而會比修正前少一筆真實事件，與 Requirement 94 的目的完全相反。

  修法：`taiwanEventKey()` 改用 `anchorDate = COALESCE(exDividendDate, exRightsDate)` 組 key（不得再退化為固定字串）；`detailScore()` 加入 `exRightsDate != null` 計分；`eventDateForSort()` 改用 anchorDate 排序。驗收須以 `2885` 的真實資料（2022 與 2025 兩筆 `stock_dividend` 皆為 `0.3`）作回歸測試，斷言重新 `syncOne('2885','台股')` 後兩筆事件都存在、互不覆蓋。

### 357.3 歷史回補（使用者明確要求）

> **⚠ 回補之前必須先解決一個會讓整件事靜默失效的結構問題。** `stock_dividend_snapshot` 是 **immutable ＋ content-hash 去重**：`DividendSnapshotStore.canonicalEvent()` 目前只含 `year`／除息日／現金／股票／兩個發放日；`canonicalContentHash` 是 snapshot 的去重鍵、`canonicalEventHash` 是 `event_key`，寫入為 `ON CONFLICT (snapshot_id,event_key) DO NOTHING`，且 `appendEvents` 只在 `storedEventCount < expectedEventCount` 時才補跑。因此：
>
> - **不把除權日放進 `canonicalEvent`** → 重抓算出的 content hash 與舊的相同 → 重用既有 immutable snapshot → 事件列數相同 → 不會 append → **`ex_rights_date` 永遠寫不進去，回補靜默失效**。
> - **放進去** → 全部既有事件的 `event_key` 改變。而 `event_key` 是 current-state 的身分依據（`DividendCurrentStateProjectionService` 以 `"key:" + eventKey` 建立 identity），實測既有 1213 列中 906 列帶 `event_key`；身分全數對不上，可能整批被判為「新 snapshot 未含舊 event」而 CANCELLED ＋ 重插。
>
> **本任務選 (a)：把 `exRightsDate` 納入 `canonicalEvent()`**，理由是除權日是事件身分的一部分，長期把它排除在 canonical 之外會讓「同一事件」的判定永遠少一個維度。代價必須一併付清，見 357.3a-0。

- [x] **357.3a-0** `canonicalEvent()` 追加 `exRightsDate`（位置緊接除息日之後）。**必須同時處理 `event_key` 遷移**：提供一次性對映或重算，並以測試釘住「既有事件不得因 `event_key` 改變而被 `DividendCurrentStateProjectionService` 誤判為 CANCELLED」。此測試必須在沒有遷移的版本下紅燈（做突變驗證並在完成報告回報）。**遷移必須保證在任何 `syncOne`／`scheduledSyncAll`／`warmupOnStartup` 於新版 `canonicalEvent()` 程式碼下首次執行之前完成**——若新版程式碼先上線且排程／warmup 先跑一次 `syncOne`，會用新雜湊公式寫入新 `event_key`，屆時舊資料的遷移對照表若仍用「新舊各一半」的窗口期資料計算，會產生兩種雜湊公式混雜、對不上的既有列。實作時須明確選定機制保證此順序（例如遷移做成應用啟動時的一次性同步步驟並先於排程註冊，而非依賴人工手動先後部署），並在完成報告寫明採用的機制。**`DividendSnapshotStore.appendEvents()` 寫入 `stock_dividend_snapshot_event` 的 INSERT 語句（現行欄位清單：`snapshot_id,event_key,year,ex_dividend_date,cash_dividend,stock_dividend,cash_payment_date,stock_payment_date,source_available_at`）須同步加入 `ex_rights_date` 欄位與 `event.exRightsDate()` 參數**——這是 `canonicalEvent()`（雜湊輸入）之外、真正把值寫進資料庫的語句，兩者必須同步改，只改雜湊函式不改 INSERT 會讓雜湊算對但值仍寫不進去。

- [x] **357.3a-0b（`stock_dividend_history` 唯一寫入端，最高優先，Critical）** `stock_dividend_history` 全程式庫**唯一**的 INSERT/UPDATE 出處是 `JdbcDividendCurrentStateRepository.upsertEvent()`（約 168–211 行），其欄位清單、`loadSnapshot()` 的 SELECT（約 220–234 行）、以及 `DividendCurrentStateRepository` 的四個 record（`Event`／`ProjectedEvent`／`ActiveEventDetail`／`ActiveFutureEvent`，`DividendCurrentStateRepository.java:18–84`）**目前全部沒有 `exRightsDate`／`ex_rights_date` 的位置**。357.3d-0 修好 `withinScope()` 只解決「事件能不能通過投影閘門」，**不解決「通過閘門後寫進 DB 的那一列有沒有除權日值」**——record 加欄位觸發的編譯期安全網不會延伸到字串 SQL，即使四個 record 都補上 `exRightsDate` 並在所有建構點顯式傳值，只要 `upsertEvent()` 的 INSERT/UPDATE SQL 字面量不同步加欄位，程式照樣編譯成功、執行成功，`ex_rights_date` 卻永遠停在 `NULL`。本項必須同步完成：(a) `loadSnapshot()` 的 SELECT 加 `ex_rights_date`；(b) 四個 record 加 `exRightsDate` 欄位；(c) `DividendCurrentStateProjectionService.projectable()` 建構 `ProjectedEvent` 時傳入該值；(d) `upsertEvent()` 的 INSERT／UPDATE SQL 加 `ex_rights_date` 欄位與參數；(e) 判斷 `findByFullValue`／`findByDateAndAmount`／`findActiveTupleTwin`／`deleteCancelledTupleTwins`／`applyMergedEnrichment` 等以「除息日＋金額＋發放日」組成 identity tuple 的方法是否也要納入 `ex_rights_date`（若不納入須說明理由——目前 identity 完全靠 `ex_dividend_date`＋金額＋發放日，多個「金額相同但除權日不同」的純配股事件理論上會被誤判為同一事件）。**驗收須有一個端到端測試**：`syncOne()` 一檔混合配息配股標的 → 讀回 `stock_dividend_history` 該列 → 斷言 `ex_rights_date` 確實等於來源值（不能只測 `DividendSnapshotStore`／`Event` record 本身，那只證明資料流到了 snapshot 層，證不到 current-state 表）。

- [x] **357.3a** 回補分**兩段**，兩段都必須做，只做前半段 `stock_dividend_history` 不會更新：
  1. **ext 端重抓 snapshot**：入口為 `DividendPersister.syncOne(code, market)`，對外可經既有 `POST /internal/dividend/sync?code=&market=`（單檔）。既有的 `scheduledSyncAll`（每平日 17:00）與 `warmupOnStartup` 也走同一支。
  2. **backend 端投影到 current state**：`DividendPersister` 的 Javadoc 自己寫明「不維護 `stock_dividend_history` current-state」；該表由 `DividendCurrentStateProjectionService` 回填，而它**至少由兩處觸發：`DividendHistoryService.findFromDb`（單檔讀取時）與 `DividendEventEvidencePipeline.resolve()`（交易雷達每次取得該檔配息證據時）**，兩者都只投影當下查詢到的個股，不會覆蓋全庫。因此若不主動觸發投影，回補**碰不到** 357.3b 要修的那 24 列（除非該 24 檔剛好都被使用者瀏覽過或出現在交易雷達清單中）。

  **不新增排程、不新增對外端點、不新增 9090 路由、不新增第二條抓取路徑**；但**允許**新增一支內部一次性回補 runner（僅供本次執行，不對外曝露），用以串起上述兩段並提供 357.3c 要求的分批、續跑與逐檔失敗清單——既有機制沒有這些能力（失敗只有 `log.warn`），不新增就無法滿足 357.3c。
- [x] **357.3a-2** **發放股權日：FinMind 未提供，本任務一律揭露為缺值，不得推估、不得以發放股息日冒充。**

  > **已實測驗證（2026-08-22，經既有 `GET /internal/dividend-history` 實打 FinMind）：**
  > - `7556`（2021–2026 連續六年同時配息又配股）：每一年 `stockPaymentDate` 皆為 `null`，而同列的 `cashPaymentDate` 有值。
  > - `2881`（2021–2025 純配股）：`cashPay` 與 `stockPay` 皆為 `null`。
  > - DB 佐證：`stock_dividend_snapshot_event` 9968 筆中 343 筆有股票股利，`stock_payment_date` **0 筆**有值；`stock_dividend_history` 1213 筆同樣 0 筆。
  > - 另一個資料集 `TaiwanStockDividendResult` 只有 `date`（除息／除權日，見 357.2f）／`stock_and_cache_dividend`／`stock_or_cache_dividend`（填息天數用），**沒有任何發放日期欄**，補不上。
  >
  > 因此本任務實際交付的是**三個可得日期 ＋ 一個誠實標示的缺值**：發放股權日在畫面顯示 `—`、並於該欄的 `missingReason`／說明文字明寫「資料源未提供」。**驗收條件為「必須正確揭露為缺值」，不是「必須有值」。** 是否另尋來源（例如證交所除權除息預告表）為獨立任務，本次不做、也不得順手加一個新的外部抓取。

- [x] **357.3a-3** **除權日確認可得，必須真的落地。** 同一次實測中，`2881` 純配股各年的 `exDividendDate` 回 `2025-09-25`／`2024-09-09`／`2023-09-04`／`2022-09-22`／`2021-09-06`——現金股利為 0，代表這些值正是走 fallback 取到的 `StockExDividendTradingDate`。**除權日在來源端有值，只是被壓合掉了**，這是本任務的核心可交付項。
- [x] **357.3b** 回補會同時修正一類既有錯誤資料：當年該事件沒有現金除息日時，舊資料的 `ex_dividend_date` 存的其實是**除權日**。回補後該值會被移到 `ex_rights_date`、`ex_dividend_date` 變 `null`。**完成報告必須逐檔列出被這樣修正的標的與年度**，不得只說「已回補」。
- [x] **357.3c** FinMind 有額度限制，回補須分批並可中斷續跑；失敗的標的必須逐檔記錄且不得靜默跳過。回補完成前，畫面對未回補標的一律顯示「尚未回補」而非拿舊值冒充。

### 357.3d `ex_dividend_date` 變 `null` 的全域影響（最高風險項，必須先做）

**只配股的事件其除息日將變成 `null`，而全庫至少有九處查詢硬性排除或依賴非 null 的除息日——這份清單本身不保證窮盡，凡是「以 `ex_dividend_date` / `exDividendDate() != null` 做存在性或排除判斷」的邏輯，一律比照本節原則改掉，不得以「清單沒列到」為由略過。** 實測（運行中 DB）：

```sql
SELECT count(*) FROM stock_dividend_history
 WHERE (cash_dividend IS NULL OR cash_dividend = 0) AND stock_dividend > 0;   -- 24
```

這 24 列全屬 **2881／2885／2891**（2016–2026）。若不處理，回補後它們的股票股利稀釋**不再被還原**，而且會從交易雷達的「下一配息」整批消失——與 Requirement 94 的目的完全相反。

- [x] **357.3d-0（最高優先，回補機制自身依賴的寫入閘門）** `DividendCurrentStateProjectionService.withinScope()`（`backend/.../service/DividendCurrentStateProjectionService.java:264` 附近：`event.exDividendDate() != null`）同時被 `projectOne()`（決定 `authoritativePositiveDates`／`addIdentities`）與 `projectable()`（決定是否 `upsertActiveEvent`／`upsertHistoricalEvent`）呼叫。**357.3a 的整段回補章節明寫「backend 端投影到 current state」依賴這個服務**；只要 `withinScope()` 沒有一併改成 `anchorDate`／`COALESCE(ex_dividend_date, ex_rights_date)` 語意，純配股事件的 `exDividendDate()` 為 `null` 會讓 `projectable()` 回 `null`，`upsertActiveEvent(...)` 永遠不會被呼叫——即使 357.3d-1 的其餘各處全改好，這 24 列（以及未來所有純配股事件）**根本進不了 `stock_dividend_history` 的 current state**，回補會靜默失效，且不會被任何既有測試攔到。此項必須在 357.3a 的回補流程實際執行**之前**修好。
- [x] **357.3d-0c（`DividendCurrentStateProjectionService` 自身的 identity 比對函式，實測已證實會誤判不同事件為同一事件，必修）** `withinScope()`（357.3d-0）只解決「事件能不能通過投影閘門」；同檔內的 identity 比對函式——`relaxedIdentity()`、`valueIdentity()`、`addIdentities()`、`token()`（約 206–249 行）——以及 `projectOne()` 內的 `authoritativePositiveDates.add(event.exDividendDate())`（約 62 行）與 CANCEL 判定的 `existing.exDividendDate() != null` 二次過濾（約 85 行）**全部直接呼叫 `exDividendDate()` 且未涵蓋 anchorDate 語意**。`token(null)` 回傳固定字串 `"NULL"`，`relaxedIdentity(exDate, cash, stock)` 因此對「同金額、不同年度」的兩筆純配股事件會產生相同 identity。**已用運行中 DB 實測證實此碰撞真實存在**：`2885` 在 2022 與 2025 兩年的純配股事件（`stock_dividend` 皆為 `0.300000`）修正後皆會產生 `relaxedIdentity` 為 `"NULL|0|0.3"`——與 357.2g 描述的 `taiwanEventKey()` 碰撞是**同一組真實資料在兩個不同函式各自觸發的獨立缺陷**，兩處都要修，缺一不可。

  修法：`relaxedIdentity`／`valueIdentity`／`addIdentities`／`authoritativePositiveDates` 全部改用 `anchorDate = COALESCE(exDividendDate, exRightsDate)`；約 85 行的 `existing.exDividendDate() != null` 二次過濾一併改用 anchorDate（否則純配股的 ACTIVE 未來事件永遠不會被納入 CANCEL 判定）。驗收比照 357.2g，以 `2885` 的真實資料作回歸測試，斷言 current-state 表內兩筆事件的 identity 不再碰撞、皆維持 ACTIVE 且互不覆蓋。
- [x] **357.3d-1** 下列四處一律改用 `COALESCE(ex_dividend_date, ex_rights_date)`（即 `anchorDate` 的落地形式），不得保留「除息日為 null 就排除」的語意：
  - `JdbcDividendEventEvidenceRepository`（約 113 行）：`WHERE snapshot_id IN (...) AND ex_dividend_date IS NOT NULL` ← **交易雷達「下一配息」本身**
  - `StockDividendHistoryRepository.findAdjustmentEvents`（約 28 行）：`AND h.exDividendDate BETWEEN :fromDate AND :toDate`
  - `DistributionAdjustedPriceService.validEvent()`（約 263–266 行）：`event.getExDividendDate() != null`
  - `StockDividendHistoryRepository.findByStockSinceYear`（約 18 行）：`ORDER BY h.year DESC, h.exDividendDate DESC NULLS LAST`
- [x] **357.3d-1b（357.3d-0 之外另外四處已知位置，同樣須改）**
  - `JdbcDividendCurrentStateRepository.findActiveEventDetails()`（約 119 行）：`AND ex_dividend_date IS NOT NULL`，供自我修復去重 `collapseDuplicateActiveEvents()` 使用——不改，純配股事件永遠不會被納入去重掃描。
  - `JdbcDividendCurrentStateRepository.findActiveFutureEvents()`（約 98–102 行）：`ex_dividend_date>?` 系列比較，SQL 對 NULL 比較恆為 UNKNOWN、等同隱性排除，供 `DividendCurrentStateProjectionService` 的 CANCEL 判定使用。
  - `DividendEventEvidenceResolver.resolve()`（約 163 行）：`.filter(e -> e.exDividendDate() != null && ...)`——**這是直接產生「下一配息」證據（`TradingRadarDto.RadarEvidence`）的類別，是 Requirement 94 的頭號承諾**，不改則純配股的未來事件永遠不會成為「下一配息」。
  - `BacktestService.eventsWithin()`（`backend/.../service/BacktestService.java:4174` 附近，被 780/1195/2902 三處呼叫）與 `HistoricalBondYieldBetaEvidenceAdapter`（約 165–167、269 行）：這兩者在收到 `findAdjustmentEvents` 的結果**之後**，各自還有一層**獨立**的 `getExDividendDate() != null` 二次過濾；357.3d-1 只修 repository 的 SQL 對這兩處**沒有幫助**，須各自比照同一原則改掉。
- [x] **357.3d-2** `findAdjustmentEvents` 有 **5 個消費端**，全部須一併驗證不回歸：`BacktestService`（三處，含其自身的二次過濾，見 357.3d-1b）、`TradingRadarService`、`HistoricalBondYieldBetaEvidenceAdapter`（含其自身的二次過濾，見 357.3d-1b）。
- [x] **357.3d-3** **2881／2885／2891 的那 24 列是本任務的必測樣本**，驗收與完成報告都必須逐筆列出（不是只列背景段那五筆同時配息配股的）；且至少一筆純配股事件必須**實際跑過完整回補流程**並斷言其確實進入 `stock_dividend_history` 的 `ACTIVE` current state（不能只驗證還原後價格序列，還要驗證投影本身沒有把它濾掉）——見 357.4b。

### 357.3e 既有消費端影響評估（本任務必須逐條表態，不得留白）

四個日期的語意改變會外溢到交易雷達以外的頁面。下列每一項都必須明確標成「本次一併改」或「明確不改並說明為何安全」：

- [x] **357.3e-1（本次一併改）** `frontend/src/components/StockAnalysisDialog.vue`：已在顯示四個日期中的三個（除息日、發放股息日、發放股權日），且**以除息日作為錨點**做三件事——每筆事件的殖利率分母（除息日昨收價查找）、依除息日的年度分組、年度殖利率。**三處一律改用 `anchorDate = COALESCE(除息日, 除權日)` 對應日**（後端 `DividendRow` 需新增 `exRightsDate` 欄供前端計算 anchorDate，或由後端直接算好 anchorDate 一併回傳，兩者擇一並在完成報告寫明選了哪個）。理由：這一頁正是使用者今天發現本缺陷的畫面，且是本 Requirement 明訂的驗收點，沒有理由不同步改對。
- [x] **357.3e-2（本次一併改）** `PerformanceComparisonBffController`（含息報酬，經 `/api/market-data/dividends-readonly`，即 Requirement 65 的「個股含息＝股利再投入還原」計算）：其對接的 `MarketDataService.DividendRow` 依 357.2d-2 已新增 `exRightsDate` 欄；還原再投入邏輯（`exDividendDate` 為 `null` 的列現行邏輯是整列略過）改為**先取 `anchorDate = COALESCE(exDividendDate, exRightsDate)` 判斷是否略過**，套用日期則沿用 357.4a 定義的「現金用除息日、股票用除權日」規則。不改的話那 24 列的股票股利再投入會被整列跳過、含息報酬系統性少計，與本任務目的相反。
- [x] **357.3e-3** 除上述兩處外，須自行 `grep -ran "exDividendDate\|ex_dividend_date"` 全樹掃描其餘消費端，**判準**：凡邏輯把 `exDividendDate`／`ex_dividend_date` 當作「這個事件是否存在／是否落在區間內」的唯一依據者，一律比照 357.3d 的 `anchorDate`／`COALESCE` 原則改掉，不得留白；若判斷後認定某處「不改也安全」，完成報告須逐處列出具體理由（例如：該路徑本來就與股票股利無關、或只服務已知恆為現金事件的場景），不得只寫「已掃描、無問題」。**此掃描與 357.0a 的全樹掃描為同一份工作，不得重複做兩次獨立清單**——已知的一個候選是 `DividendHistoryService.java:88-90、128-130` 的次要排序鍵（`.thenComparing(r -> r.exDividendDate() == null ? "" : r.exDividendDate(), ...)`），純配股事件排序退化為空字串，屬顯示順序瑕疵（非資料遺失），一併改用 anchorDate 或明確排除並說明理由。

### 357.4 還原權息的行為不得因此改變（最高風險項）

- [x] **357.4a** `DistributionAdjustedPriceService` 目前以 `ex_dividend_date` 作為事件套用日，且一個事件的現金與股票因子合併計算成單一 `dividendFactor`、套用在單一 `FactorEvent.date`（`dividendFactor()`／`FactorEvent` 現行皆為「一事件一日期一因子」的資料結構）。新增 `ex_rights_date` 後**必須顯式定義每一種事件用哪一個日期**：**現金股利用 `ex_dividend_date`、股票股利用 `ex_rights_date`**；某一方缺值時退回 `anchorDate`（357.2c）。此定義須寫進 Javadoc。
  **兩個日期皆有值且不同時**（同一事件現金與股票除息除權日不同日，本任務存在的理由正是「除權日有時與除息日不同」，故此情形不可視為不會發生）：`FactorEvent` 須拆成**兩個**獨立事件——現金因子套在 `ex_dividend_date`、股票因子套在 `ex_rights_date`，不得只取其一或合併套在同一天。若查證台股 FinMind 資料實務上現金與股票除息除權日恆同日（須用抓取端實測結果佐證，不得憑印象），可改為「本任務不處理雙日期分拆，理由是……」並附上查證依據；兩者擇一，不得兩者皆未交代。

  > **分割（split）不在此列，也不得寫成「用除權日」。** 分割是由 `detectSplits()` 以相鄰收盤比例的序列啟發式偵測出來的，事件日期取自**價格列的 `trading_date`**，從來不持有任何 dividend 日期欄——該檔 Javadoc 自己就寫著「台股沒有合規且可程式化存取的分割事件來源，實測全庫兩筆真分割中的 `2327` 在 `stock_dividend_history` 完全沒有紀錄」。**分割維持現行機制，不受本任務影響。**

- [x] **357.4a-3（NPE 風險，必須與 357.3d-1 同步修，不得分開改）** `DistributionAdjustedPriceService.adjust()` 的 filter/sort/construct 鏈（約 101–113 行）在 `validEvent()` 之後**緊接著**四處直接呼叫 `event.getExDividendDate()`：區間過濾（`.isBefore(firstDate)`／`.isAfter(lastDate)`）、`Comparator.comparing(StockDividendHistory::getExDividendDate)` 排序、以及建構 `FactorEvent` 時取 `e.getExDividendDate()` 當日期與 `closeOn(rowsAsc, e.getExDividendDate())` 查收盤價。357.3d-1 要求放寬 `validEvent()` 讓純配股事件（`getExDividendDate()==null`）通過後，這四處會立即對這類事件拋 `NullPointerException`——**這不是各自獨立的兩個修改，`validEvent()` 放寬與這四處改用 anchorDate 必須是同一個 commit 內同步完成**，否則放寬 `validEvent()` 反而會讓原本因 `continue`／被過濾掉而「靜默不還原」的純配股事件，變成「直接讓整個還原流程炸掉」。四處全部改用 `anchorDate = COALESCE(getExDividendDate(), getExRightsDate())`；`FactorEvent` 的日期與因子則依 357.4a 剛定義的規則（現金套 `ex_dividend_date`、股票套 `ex_rights_date`，兩者皆有且不同時拆兩筆）決定，不得直接沿用區間過濾／排序用的 anchorDate 當作最終落地日期。驗收須以 2881／2885／2891 其中一檔（357.3d-3 的必測樣本）實際跑過 `adjust()`，斷言不拋例外且還原序列正確。
- [x] **357.4a-2** **`hasStockDividendOn()` 必須改判除權日。** 它目前以 `getExDividendDate()` 判斷「這個跳空已由股票股利解釋」，用來避免把配股跳空誤認為分割。拆欄後只配股事件的除息日會是 `null`，該保護在這類事件上會失效。**現有資料觸發不到**（實測 24 列只配股事件的跳空幅度約 1.1 倍，遠低於 `SPLIT_FORWARD_MIN = 2.0`，第一道門檻就 `continue`），但保護失效本身是缺陷，必須一併修並以測試釘住。
- [x] **357.4b** **必須有固定資料的回歸測試證明：在回補之前，既有標的的還原後價格序列、`volumeRatio`、以及交易雷達的 `score`／`action`／`reasons`／`risks` 逐位不變。** 回補**之後**確實會有標的的還原結果改變（那正是修正），這類標的必須**逐檔列出並說明改變的原因與方向**，不得混在「不變」的結論裡。
- [x] **357.4c** 價格比對一律用 `compareTo` / `isEqualByComparingTo`，不得用 `assertEquals` / `equals`（還原路徑的 BigDecimal scale 會由 4 變 8，這是 Task 356 已踩過的坑）。

### 357.5 「下一配息」改為同一次事件的四個日期

- [x] **357.5a** 解析「下一次尚未發生的配息事件」時，以 `anchorDate` 未來最近的一筆為準，取出**該筆事件**的四個日期一併輸出。**不得**四個日期各自往後找最近的一個——那會拼出一筆現實中不存在的事件。
- [x] **357.5b** 事件不含某一類時該日期為 `null`，畫面顯示 `—`；**不得顯示 0、不得空白到看不出是缺值**。
- [x] **357.5c** 尚未公布的日期一律為缺值並揭露「尚未公布」，**不得以往年同期推估或以任何方式填補**（Requirement 94 的「不得表述為預測」）。

### 357.6 DTO、匯出與 OpenAPI

- [x] **357.6a** 交易雷達的 dividend evidence（`TradingRadarDto.RadarEvidence`）曝露四個日期欄，命名與既有 `nextDistributionDate` 的風格一致且語意明確（建議 `nextExDividendDate`／`nextExRightsDate`／`nextCashPaymentDate`／`nextStockPaymentDate`）。既有 `nextDistributionDate` 若保留，**必須明確定義為 `anchorDate`** 並於 Javadoc 寫明，避免既有消費端語意漂移。
- [x] **357.6b** 四個日期一律 `yyyy-MM-dd` 字串。`nextDistributionKnownAt`（observation 取得時點，現行顯示為 `2026-08-21T21:05:57.205074Z`）**僅前端呈現**改為 `yyyy-MM-dd`；**API 與匯出一律維持 ISO-8601 date-time 不動**——既有 OpenAPI 契約即 `format: date-time`，且 `knownAt` 是 as-of 稽核時點，截成日期會破壞 Requirement 86 的證據可稽核性並讓 contract test 紅燈。精確時點以 tooltip 或展開揭露。
- [x] **357.6c** `docs/openapi/docker-external-api.yaml` 必須在**同一個 commit** 內補齊新欄位的 properties 與 `required`，並在 `TradingRadarOpenApiSchemaContractTest` 的綁定表與 nullable 表登錄。依 Requirement 86，文件不完整即視為功能未完成。
- [x] **357.6d** `TradingRadarExportService` 同步新增對應欄位。**`headers`／`formats`／每列 cell 是三份必須同步的平行清單**，只改表頭會在 runtime 才炸（Task 356 已踩過）；並補「表頭長度 ＝ formats 長度 ＝ 每列 cell 數」的斷言。

### 357.7 前端

- [x] **357.7a** `frontend/src/views/TradingRadarView.vue` 的「下一配息（已知時點）」區塊改為列出四個日期，每個都標明是哪一種（除息／除權／發放股息／發放股權），缺值顯示 `—`。**保留該區塊既有的 `v-if="row.evidence?.nextDistributionStatus"` 閘門**，四個日期的 `—` 只在區塊內生效；不得改成「四個日期任一有值才顯示」而讓 status 的揭露消失。
- [x] **357.7b** 版面沿用該區塊既有的樣式慣例，不新增第三方元件。四個日期不得擠成一行到看不清楚哪個是哪個。

### 357.8 不得做的事

- [x] **357.8a** 不新增排程、不新增對外端點、不新增 9090 路由；business-services 不得為此直連任何外部 API（四個日期只由既有 `external-materials-service` 的 FinMind 路徑落地）。
- [x] **357.8b** 不手改 `db/schema.sql`（那是 pg_dump 基準線）；不修改任何**已執行**的 Liquibase changeset 內容或註解（改註解也會讓 checksum 失效並造成 crash loop）。
- [x] **357.8c** 不得為了讓四個日期都有值而推估或填補；不得把 `stock_payment_date` 當成除權日、也不得把 `cash_payment_date` 當成除息日。

## 驗證

```bash
bash scripts/spec-check.sh
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f external-materials-service/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build
```

測試至少涵蓋：

- 同時有 `CashExDividendTradingDate` 與 `StockExDividendTradingDate` 的 FinMind 列，兩個日期各自落到 `ex_dividend_date` 與 `ex_rights_date`，**互不覆蓋**。
- 只有其中一種時，另一種為 `null` 而**不是**被頂替。
- **只配股（無現金除息日）的事件不得被判為 malformed 而整批拒收**（357.2b 的陷阱）。
- `anchorDate = min(兩個非 null 的除息除權日)`；只有一個日期時 `anchorDate` 恆等於改動前的 `ex`，區間過濾、`year` 推導與 `calcDividendBasis` 三處行為逐位不變。
- **TWSE/TPEx 官方日曆與 `TaiwanStockDividendResult` 兩個獨立資料集的純配股列，日期落 `exRightsDate` 而非 `exDividendDate`**（357.2e／357.2f），且不因此拋出例外（`selectTarget()` 的 NPE 陷阱）。
- **`ex_rights_date` 確實貫穿投影寫入路徑**：`syncOne()` 一檔混合配息配股標的 → 讀回 `stock_dividend_history` 該列，斷言 `ex_rights_date` 有值（357.3a-0b），而非只驗證到 `DividendSnapshotStore`／`Event` record 層級。
- **`DistributionAdjustedPriceService.adjust()` 對純配股事件不拋 NPE**（357.4a-3），以 2881／2885／2891 其中一檔實測。
- 「下一配息」取的是**同一次事件**的四個日期（構造一筆「除息在前、除權在後、發放更後」的事件，斷言四個值同屬該筆）。
- 四個日期輸出格式為 `yyyy-MM-dd`；缺值為 `null` 而非空字串或 0。
- 還原權息在回補**之前**的逐位回歸（357.4b）。
- Liquibase changeset 冪等：連續執行兩次不報錯、結果相同。
- OpenAPI 與 gateway 雙向對齊 contract test 綠燈。

實際 Docker stack 驗收：

```bash
cp /Users/steven/Project/asset-management/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service frontend
```

```bash
docker compose -p asset-management restart bff
```

實資料須確認：

- **三組驗收樣本，性質不同，都要列：**
  - **A 組（新增除權日）**：7556 於 2020–2026 連續七年同時配息又配股（背景表只列了其中四年）、9933 的 2025。回補後這些列必須新增 `ex_rights_date`，`stock_payment_date` 則應正確揭露為缺值（來源未提供）。
  - **B 組（`ex_dividend_date` 被移走變 null）**：2881／2885／2891 的那 24 列純配股事件——這一組才是會被改語意的，A 組不會。完成報告須把兩組分開統計。
  - **C 組（357.2f，`TaiwanStockDividendResult` fallback 的 ETF 配股事件）**：挑一檔已知走此 fallback 且曾配股的收益分配型 ETF（如 `00751B`；若該檔近 10 年查無配股事件則另尋一檔實測有配股者），確認其配股事件回補後 `ex_rights_date` 有值、`ex_dividend_date` 為 `null`，不得遺漏——A／B 兩組皆為個股，未涵蓋此路徑。
- 挑一檔只配現金的標的，其除權日與發放股權日為 `—` 而非 0 或空白。
- 「下一配息」區塊的四個日期屬於同一次事件；observation 取得時點顯示為 `yyyy-MM-dd`。
- 回補後 `SELECT count(*) FROM stock_dividend_history WHERE ex_rights_date IS NOT NULL` 大於 0，且抽查數筆與官方一致。

## 完成報告

（實作者完成後回填：實際修改檔案清單、四個驗證指令的完整輸出、回補的標的與年度統計、**因回補而被修正「舊 `ex_dividend_date` 其實是除權日」的標的逐檔清單**、還原權息在回補前的逐位回歸結果與回補後改變的標的清單、實資料抽查（含官方公告比對）、以及與本計畫的偏差及原因。）

### backend 模組範圍完成報告（本輪，範圍：357.3a-0b／357.3d-0／357.3d-0c／357.3d-1／357.3d-1b／357.3d-2／357.3d-3／357.4a／357.4a-2／357.4a-3／357.4b（部分）／357.4c／357.5a-c／357.6a-d／357.3a-c 回補 runner／357.0 殘留掃描；db 遷移 357.1 與 external-materials-service 357.2／357.3a-0 由前一輪完成，本輪未改動）

**357.0 殘留掃描**：對 `backend/src/main` 全樹 grep `exDividendDate|ex_dividend_date|ExDividendDate` 逐點檢視，除既有清單外新發現：`StockDividendHistoryRepository.findFirstByStockCodeAndMarketAndYearAndExDividendDate(IsNull)` 兩個 derived-query 方法在全庫（含測試）零呼叫端，判定為死碼，**明確不改**（無風險、也無驗收樣本可測）。其餘全部消費點已比照 anchorDate 原則改掉（見下方逐檔清單）。

**identity tuple 決策（357.3a-0b(e)）**：`findByFullValue`／`findByDateAndAmount`／`findActiveTupleTwin`／`deleteCancelledTupleTwins` 四個方法**皆已納入 `ex_rights_date`**（不是留白不改）：前兩者在既有參數尾端追加 `ex_rights_date` 的 null-safe 比對（`IS NOT DISTINCT FROM`），且把原本裸 `ex_dividend_date=?` 一併換成 null-safe 寫法；後兩者沿用既有 `COALESCE(col, DATE '1970-01-01')=COALESCE(?, DATE '1970-01-01')` 風格在尾端追加 `ex_rights_date` 欄。`ex_rights_date` 定位為與 `ex_dividend_date` 同等級的**身分／不可變欄位**（只在 INSERT 落地一次，UPDATE 分支不覆寫），故 tuple-twin 系列一律取 `current.exRightsDate()`（既有列的值），INSERT 前比對系列取 `event.exRightsDate()`（來源事件的值）。`uk_dividend_event` 這條 DB 唯一索引**未新增 `ex_rights_date`**——因為索引已含 `year`，同年同事件在正常資料下不會出現「金額與其餘欄位皆同、僅除權日不同」的情形；且 `event_key`（已納入 `ex_rights_date` 雜湊）已足以在該極端情形下避免撞鍵，經評估後決定不新增 Liquibase changeset 擴充索引。

**event_key 遷移機制**：external-materials-service 端的 `DividendEventKeyMigration`（`@PostConstruct`，早於 `@Scheduled` 註冊與 `ApplicationReadyEvent`）已於前一輪完成，只處理 `stock_dividend_snapshot_event`。backend 端 `stock_dividend_history.event_key` **不需要對應遷移**：`event_key` 只在 `findByEventKey` 這一個 tier 用來加速比對，找不到時會自然 fallback 到 `findByFullValue`／`findByDateAndAmount`（date+amount 寬鬆比對），下一次 `projectOne()` 執行時 `upsertActiveEvent`／`upsertHistoricalEvent` 會用新雜湊覆寫 `event_key` 欄位（`SET ... event_key=?`），故不會有「舊 key 永久卡住」的問題，一次投影週期內自然收斂。

**357.3d-0c 2885 回歸測試結果（真實資料）**：以運行中 DB 實測值（`2885` 2022-08-12 與 2025-08-12 兩筆純配股事件，`stock_dividend` 皆為 `0.300000`）建立 `DividendCurrentStateProjectionServiceTest.twoRealPureStockEventsWithSameAmountDifferentYearsAreNotCollapsedTogether`。**已做突變驗證**：暫時把 `collapseDuplicateActiveEvents()` 的 `relaxedIdentity(row.anchorDate(), ...)` 改回 `relaxedIdentity(row.exDividendDate(), ...)`（即回退到未修正前的行為），重新執行該測試 → **紅燈**（`Tests run: 1, Failures: 1`，斷言 `cancelActiveEvent(202L)` 未被呼叫失敗，證實兩筆事件在未修正前確實會被誤判合併）；還原修正後重跑 → 綠燈。`JdbcDividendCurrentStateRepositoryTest.insertPathCarriesExRightsDateIntoTheInsertStatement`（357.3a-0b 端到端斷言）也做了同樣的突變驗證：暫時把 INSERT 語句的 `ex_rights_date` 欄位與參數移除 → 紅燈；還原後 → 綠燈。

**357.3a／357.3b／357.3c 回補 runner**：新增 `DividendHistoricalBackfillRunner`（`backend/src/main/java/com/steven/assets/service/`），不對外曝露、不新增排程或端點。串起既有 `POST /internal/dividend/sync` 與 backend 端 `DividendCurrentStateProjectionService.projectOne()`；逐檔 try/catch、失敗清單、批次間 800ms 延遲（FinMind 額度保護）。已用本機 `HttpServer` 頂替 ext 服務完成單元測試（`DividendHistoricalBackfillRunnerTest`，4 個案例：成功清單、單檔失敗不中斷整批、`projectOne` 回 `false` 視為失敗並揭露原因、空清單直接回空結果）。**尚未對 7556／9933／2881／2885／2891 五檔實際執行**——原因見下方「風險與待辦」。執行前查得的 baseline（`stock_dividend_history`，2026-08-22，`docker exec asset-postgres psql`）：74 列全部 `ex_rights_date` 為空、7556／9933／2881/2885/2891 現況與 spec 背景表描述一致（詳見完成報告附錄／StructuredOutput risksOrOpenQuestions）。

**357.4a／357.4a-2／357.4a-3（`DistributionAdjustedPriceService`）**：新增 `addFactorEvents()` 依 357.4a 規則分派套用日期與因子（現金→`ex_dividend_date`、股票→`ex_rights_date`，兩者皆有且不同日才拆兩筆；同日或單邊 fallback 到 anchorDate 時維持既有合併因子，逐位不變）；`validEvent()`／區間過濾／排序／`FactorEvent` 構造／`closeOn` 查價全數改用 anchorDate，`validEvent()` 放寬與這四處改動在同一次修改內完成（未分兩步）。`hasStockDividendOn()` 改判 `exRightsDate`（缺值時退回 anchorDate）。新增 4 個測試：純配股（`getExDividendDate()` 為 null）不拋例外並正確還原（以 2881 真實除權日 2022-09-22、`stock_dividend=2.690500` 為樣本）、現金與股票除息除權日不同日時確實拆成兩筆、同日時維持單筆合併（回歸不變）、`hasStockDividendOn` 改判除權日後跳空不被誤判為分割。

**357.4b（部分完成）**：`DistributionAdjustedPriceServiceTest` 既有 17 個測試（皆為合成樣本，不含真實回補資料）全數維持綠燈，證明**合成場景**下的還原邏輯逐位不變；**未完成**對 2881／2885／2891 真實資料在「回補前」狀態下跑一次交易雷達全鏈路（`score`／`action`／`reasons`／`risks`）並與「回補後」逐位比對——這需要先執行 357.3a-c 的實際回補（見上）才能取得「回補後」狀態做對照，故與回補的實際執行綁在一起，尚未完成。

**357.4c**：本輪新增的全部 BigDecimal 斷言一律用 `compareTo`/`isEqualByComparingTo`（`assertEquals(0, x.compareTo(y))` 或 AssertJ `isEqualByComparingTo`），未使用 `assertEquals`/`equals` 直接比對。

**357.5a-c（`DividendEventEvidenceResolver`）**：`resolve()` 的區間過濾／排序／session 計數全數改用 `Event.anchorDate()`；`Event` record 新增 `exRightsDate`（附加在既有欄位之後＋新增一層相容建構式，保留原有呼叫端不動）。新增測試證明：(a) 純配股事件（`exDividendDate` 為 null）仍能成為 `nextEvent`；(b) 「除息在前、除權在後、發放更後」的合成事件，四個日期同屬 `result.nextEvent()` 這一筆（357.5a 的「同一次事件」要求）。357.5b／357.5c 為既有機制天然滿足（`Event` 各日期欄位獨立為 `LocalDate`，缺值即 `null`；`resolve()` 未公布金額的事件本就標記 `PARTIAL` 且不推估）。

**357.6a-d**：`TradingRadarDto.RadarEvidence` 新增 `nextExDividendDate`／`nextExRightsDate`／`nextCashPaymentDate`／`nextStockPaymentDate`（附加在既有欄位最尾端＋更新全部 3 處相容建構式／`EMPTY`／`withConfidence` 顯式賦值，不依賴預設 null）；`nextDistributionDate` 之 Javadoc 明確改為 anchorDate 定義。`docs/openapi/docker-external-api.yaml` 的 `RadarEvidence` schema 同步補 4 個 `required`＋`properties`（`format: date`）；`TradingRadarOpenApiSchemaContractTest` 的 `NULLABLE`／`STRING_FORMATS` 表同步登錄。`TradingRadarExportService` 新增 `DIVIDEND_FOUR_DATES_HEADERS`／`_FORMATS`（附加在整張表真正最末，沿用 Task 320／356.12a 的「尾端附加」慣例），並在 `TradingRadarExportServiceTest`／`TradingRadarDualFormatTest` 新增／更新斷言（後者含表頭總數 190→194 的全面更新：`STOCK_HEADERS_V11`、`TAIL_COLS`、多處 subList 錨點與 `hasSize` 斷言）。新增 `TradingRadarDtoRadarEvidenceTest` 直接驗證 `withConfidence()` 對純配股與雙日期事件的欄位攤平正確。

**附帶完成（非本輪指定範圍，但被 357.6a 依賴、且是已知會系統性少計的既有 bug，故一併修正）**：
- `bff/PerformanceComparisonBffController.reinvestDividends()`：改用 anchorDate 判斷事件是否落在再投入區間內，套用日期依 357.4a 規則分派（不同日拆兩筆）。**未新增專屬單元測試**（該 controller 目前無既有測試檔，且 bff 不在本任務四個驗證指令範圍內）；已跑過 `mvn -f bff/pom.xml test`（166 個既有測試全綠）確認未引入回歸。
- `StockAnalysisDialog.vue`（357.3e-1）與 `TradingRadarView.vue`（357.7a/b）**未在本輪處理**——依 CLAUDE.md「新增／修改／刪除前端程式（Vue）固定用 sonnet 5／high 的獨立 subagent」規範，frontend Vue 變更超出本次 backend 實作 subagent 的範圍，需另派 frontend 專責 subagent。

**驗證指令輸出**：
```
$ mvn -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
[INFO] Tests run: 1361, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
（`external-materials-service` 與 `frontend` 建置本輪未變動，沿用前一輪／後續輪次結果；`bff` 額外執行 `mvn -f bff/pom.xml test` → `Tests run: 166, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。）

**與本計畫的偏差**：
1. 357.3a-c 回補 runner 已寫好並單元測試覆蓋，但**未對 5 檔實際執行**——需要先把本輪＋前一輪（ext）的程式碼一起 rebuild 進 Docker image 才能反映新邏輯，而本專案有「多 worktree 共用同一份 image tag、非 main worktree 重建有被洗掉風險」的既有教訓（見 `feedback_shared_stack_tag_clobber.md`），此步驟留待整合到 main 後由 `/run-stack` 執行。
2. 357.4b 的「回補前後」逐位回歸只完成「前」（合成樣本＋既有測試綠燈），「後」需等實際回補執行完才能比對。
3. 357.3e-1／357.7 前端頁面未動（見上，屬另一 subagent 範圍）。

---

### 收尾輪完成報告（範圍：實際執行歷史回補 ＋ 357.4b「回補後」比對 ＋ 錨定日 `LEAST` 正確性修正 ＋ 唯一索引補齊）

上一輪的 backend 模組報告明列兩項未完成——**357.3a-c 的回補未實際執行**、**357.4b 的「回補後」逐位比對因此無法進行**。本輪補完，並修正一處錨定日語意缺陷。

#### A. 歷史回補已實際執行（357.3a／357.3b／357.3c）

```
SUMMARY  attempted=60  succeeded=60  failed=0  corrections=23
```

回補對象為 `stock_dividend_history` ∪ `stock` 主檔聯集（台股 44 ＋ 美股 16），**零失敗**。分 6 批、批次間停 5 秒，單檔沿用 ext 既有節流（台股 300ms／美股 500ms）。續跑帳與稽核帳落於 `/home/steven/dividend-backfill-t357/`。

| 項目 | 回補前 | 回補後 |
|---|---:|---:|
| `ex_rights_date` 有值 | 0 | **35** |
| 有股票股利的事件 | 32 | 36 |
| A 組（同時配息配股） | 8 | 8 |
| B 組（純配股） | 24 | 28 |
| `stock_payment_date` 有值 | 0 | **0** |

另補回 4 筆原本遺漏的配股事件：2882 的 2019／2022、3037 的 2025、7556 的 2025。

**357.3b 逐檔修正清單（舊 `ex_dividend_date` 其實是除權日，23 筆）**

| 代號 | 年度 | 被移動的日期 | 筆數 |
|---|---|---|---:|
| 2881 | 2021／2022／2023／2024／2025 | 2021-09-06／2022-09-22／2023-09-04／2024-09-09／2025-09-25 | 各 2，計 10 |
| 2885 | 2020／2022／2023／2024／2025／2026 | 2020-08-17／2022-08-12／2023-08-11／2024-08-12／2025-08-12／2026-08-18 | 11 |
| 2891 | 2016 | 2016-10-12 | 2 |

**已知缺口（1 列未修正）**：`2885` 2026 年的 `stock=0.4` 那列，`ex_dividend_date` 仍為 `2026-08-18`。查證：FinMind 主表經唯讀路徑（`MarketDataFetchService`）查得該筆帶 `exRightsDate`，但落地路徑（`fetchTw`）重抓兩次都只取得 fallback 表的 `2.6269`，主表該筆未進入 snapshot。**根因是 `fetchTw` 與 `MarketDataFetchService` 兩條路徑對 FinMind 的既有查詢差異，非拆欄邏輯所致**——同型事件在 2020–2025 各年度兩筆皆正確拆欄，僅 2026（除權日為 4 天前的當年度事件）缺主表那筆。建議另開任務處理該路徑分歧。

`event_status` 出現 5 筆 CANCELLED，經 `NOT EXISTS` 查詢驗證**每一筆都有內容等值的 ACTIVE 列存活**（0 rows），且全為純現金 ETF 事件，與拆欄無關——屬重複列收斂，**零事件遺失**。

#### B. 357.4b「回補後」逐位比對（上一輪未完成項）

以免 OAuth 端點對 35 檔實資料取回補前後 snapshot，逐位比對三軌 `score`／`action`／`reasons`／`risks`：

```
回補前 35 檔／回補後 35 檔　消失 0／新增 0　決策有變動：0 檔
```

**規格預期「回補後會有標的的還原結果改變」，實際為 0 檔——這不是比對失效，而是資料事實。** 實測 `WHERE ex_rights_date < ex_dividend_date` 為 **0**：台股現有資料的除息日與除權日完全同日，故 `anchorDate` 在回補前後逐位相同，還原權息的事件套用日不變。資料語意被修正，計算行為零變動。

實資料抽查 `2881`（規格背景段點名、使用者可直接複驗的缺陷）：純配股年度的「除息日」欄已由**錯誤顯示除權日**改為 `—`，除權日出現在新欄；純現金事件反之；發放股權日全部 `—`。

#### C. ⚠ 錨定日語意修正：`COALESCE` → `LEAST`（正確性缺陷）

上一輪把錨定日落地為 `COALESCE(ex_dividend_date, ex_rights_date)`。**這與 357.2c 的定義不符。** 357.2c 明定 `anchorDate = min(除息日, 除權日)`，理由是「取較早者才不會讓事件落在區間外而漏抓」；357.3d-1 那句 `COALESCE(...)`「即 anchorDate 的落地形式」是**規格自身的內部矛盾**。

真實 PostgreSQL 實測：

```
LEAST('2026-09-05','2026-08-28')    = 2026-08-28   ← 較早者
COALESCE('2026-09-05','2026-08-28') = 2026-09-05   ← 第一個非 null
視窗 2026-08-20 ~ 08-31：  LEAST → t（命中）   COALESCE → f（漏抓）
```

已改（SQL 9 處／JPQL 3 處）：`JdbcDividendEventEvidenceRepository` 入口過濾、`JdbcDividendCurrentStateRepository` 的 `findActiveFutureEvents` 三處區間比較與 `findActiveEventDetails`、`StockDividendHistoryRepository` 兩支 JPQL 的 `ORDER BY`×2 與 `BETWEEN`。Java 端 6 個 `anchorDate()`（原為 coalesce 三元式）一併收斂委派 `model/DividendDates`，避免 SQL 與 Java 兩側語意分歧。

**刻意不改**：`findActiveTupleTwin`／`deleteCancelledTupleTwins` 的 `COALESCE(col, DATE '1970-01-01')` 是唯一索引的 null-sentinel，兩個日期欄各自獨立比對，改 `LEAST` 會把兩欄壓成一個值；已加測試釘住它必須維持 `COALESCE`。bff 的 `exDividendDate ?? exRightsDate` 是 357.4a 的「套用日期」（現金套除息、股票套除權），不是錨定日。

已做突變驗證：還原成 `COALESCE` 後 backend 8 個測試紅燈、ext 2 個紅燈，還原後全綠。`least` 為 Hibernate 6.6 PostgreSQLDialect 註冊過的函式，並以 `SqmFunctionRegistry.findFunctionDescriptor("least")` 斷言釘住（Hibernate 對未註冊函式名一律放行，解析通過不代表函式存在）。

#### D. `uk_dividend_event` 唯一索引（與上一輪決策的差異）

上一輪評估後決定索引**不**納入 `ex_rights_date`。但運行中的 DB **已經**有含 `ex_rights_date` 的 `uk_dividend_event` 與兩個欄位 COMMENT。新增 `v1.111.0-dividend-event-uniqueness.sql`（不含 `ADD COLUMN`、冪等、**未修改已執行的 `v1.110.0` 一個字**）把該狀態補回 codebase，使乾淨環境可重現 production 現況，避免「DB 有、codebase 沒有」的漂移。

#### E. 回補 runner 的重複（待收斂）

本輪補入一組帶完整觸發機制的 runner（`DividendBackfillService`／`Starter`／`Ledger`／`TargetRepository` ＋ 兩支實作，`@ConditionalOnProperty` 預設關閉、無排程、無 controller、無 9090 路由），**上述 A 的回補即由它執行**。

main 既有的 `DividendHistoricalBackfillRunner` 功能重疊且**無任何觸發路徑**（無 starter／排程／端點），本輪**未刪除**——因本報告上一段已指名該類別與其測試，刪除會讓 `scripts/spec-check.sh` 因「spec 提到不存在的測試類」BLOCK。建議另開任務收斂為單一實作。

#### F. 驗證

| 指令 | main 基準 | 本輪 |
|---|---|---|
| `mvn -f backend/pom.xml test` | 1371 / 0 / 0 | **1397 / 0 / 0** SUCCESS |
| `mvn -f bff/pom.xml test` | 177 / 0 / 0 | **177 / 0 / 0** SUCCESS |
| `mvn -f external-materials-service/pom.xml test` | 559 / 0 / 0 | **562 / 0 / 0** SUCCESS |

零回歸。另 `scripts/tests/docker-external-api-openapi-test.rb` → `PASS: 9090 gateway/OpenAPI 九路 parity…` exit 0。

Liquibase 冪等已於獨立臨時 database 實測：連續執行兩次皆 exit 0，第二次正確 skip 已存在項，結果一致；臨時 database 已 DROP，production 資料全程未觸及。

#### G. 其他偏差

1. **ext 端 `anchorDate` 有三份重複實作**（`client.DividendFetchClient`／`client.TaiwanOfficialDividendCalendarClient`／`service.MarketDataFetchService`），同屬一個 Maven artifact、同一個 Spring context，不適用「跨 artifact 無法共用」的免罪理由（`structure.md` §3.2 具名例外之二對此形狀有明文）。已收斂為 `externalmaterials/model/DividendDates`，並在 §3.2 登記**具名例外之五**。
2. **`scripts/spec-check.sh` 目前 BLOCK 一條為誤報**：「改動了 @Scheduled/cron 但未同步 `SchedulePublicBffController.JOBS`」。本輪未新增或修改任何排程，命中的是 runner Javadoc 裡「**沒有** `@Scheduled`」這句話（純字串比對）。未為繞過檢查而改寫該註解。
3. **發放股權日**：FinMind 未提供（10549 筆 snapshot event 中 `stock_payment_date` 0 筆有值），依 357.3a-2 一律揭露為缺值。另註：`TaiwanOfficialDividendCalendarClient` 已存在且已在用（TWSE `TWT48U_ALL`＋TPEX `tpex_exright_prepost`），另尋來源不需新建抓取管線，但該表為除權息**預告**、只涵蓋近期與未來，補不了歷史。
4. **前端衍生值計算未搬進 BFF**：`arch-auditor` 指出前端做了錨定日計算與排序挑選，違反 §3.2 鐵則 5「前端只 render」。該路徑的 BFF 是純 gateway route、無 controller，修法需新增 controller 屬結構性變更；且同段的年度分組與 `yieldPct` 在本任務之前就已在前端、屬既有債。經使用者裁決**延後為獨立任務**。
