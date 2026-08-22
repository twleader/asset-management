# [t357] 配息事件的四個日期各自獨立落地與顯示（除息／除權／發放股息／發放股權）

**對應 Requirements:** Requirement 94（配息事件的除息日、除權日、發放股息日、發放股權日各自獨立儲存與顯示，格式一律 `yyyy-MM-dd`；停止把除息與除權壓成同一欄）
**前置任務:** t356（交易雷達三軌與週K，已完成並 merge）
**Liquibase changeset:** `v1.108.0-dividend-ex-rights-date.sql`

## 背景

使用者於 2026-08-22 指出交易雷達的「下一配息（已知時點）」區塊只給一個日期加一串 ISO timestamp（`2026-08-21T21:05:57.205074Z`），要求改為**四個日期**：除息、除權、發放股息、發放股權，且一律 `yyyy-MM-dd`。

查證後發現這不是顯示問題，而是**入庫當下的沉默資料遺失**：

- FinMind 的 `TaiwanStockDividend` 同時提供 `CashExDividendTradingDate`（除息日）與 `StockExDividendTradingDate`（除權日）。
- 本專案有**三處**壓合，三處都要改，而且**角色各不相同**（只改其中一兩處會漏掉真正落地的那條）：
  1. `DividendFetchClient.fetchTw`（約 519–520 行）——**這是歷史落地的主路徑**：`DividendPersister.syncOne()` → `client.fetchObservations()` → `fetchTw(...)` → `DividendSnapshotStore` → `stock_dividend_snapshot_event` → `DividendCurrentStateProjectionService` → `stock_dividend_history`。此處 `ex` 缺值時是 `continue` 只跳過該列。
  2. `DividendFetchClient.parseFinMindDividendRows`（約 379–380 行）——只服務 **upcoming 45 日 scope 的 provider fallback**（`fetchFinMindUpcomingScope`）。此處 `ex == null` 會 `return ParseEvents.invalid(...)`，**已累積的事件全數丟棄**、整個 observation 標為 `valid=false`。
  3. `MarketDataFetchService.getTwDividendHistory`（約 852–853 行）——**唯讀顯示投影，不寫任何表**，且其唯一出口 `MarketDataService.getDividendHistory` 目前**零呼叫端**。仍須一併修正以免日後復活；此處要改的是 `DividendRow` 的欄位，不是 DB 欄位。
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

**同一個缺陷已經在使用者畫面上顯示錯誤資訊。** 「股票分析 → 股利歷史（10 年）」的**「除息日」欄，對純配股標的顯示的其實是除權日**：`2881` 於 2021–2025 每年現金股利皆為 0、只配股票股利，該欄卻顯示 `2025-09-25` 等日期——那是除權日被 fallback 塞進除息欄。修正後這一欄對這類標的應顯示 `—`，除權日則出現在新的除權日欄。**此為本任務可由使用者直接複驗的驗收點。**

使用者已明確選定兩件事：**「下一配息」顯示的是同一次事件的四個日期**（不是四個日期各自往後找最近的一個）；以及**歷史資料連同一併重抓回補**（不接受只從下次抓取起累積）。

## 要做什麼

### 357.1 資料模型：新增除權日欄（Liquibase）

- [ ] **357.1a** 新增 `backend/src/main/resources/db/changelog/changes/v1.108.0-dividend-ex-rights-date.sql`，並在 `db.changelog-master.yaml` 末尾以既有格式 `include`。**必須冪等、可重複執行**（`ADD COLUMN IF NOT EXISTS`），比照 `v1.107.0` 的既有寫法。
- [ ] **357.1b** 兩張表各新增一欄 `ex_rights_date DATE`（nullable，無預設值）：`stock_dividend_snapshot_event`、`stock_dividend_history`。**不得**加 NOT NULL 或預設值——「這個事件沒有除權」與「還沒抓到」都必須能表示成 `null`，用預設值會讓兩者無法區分。
- [ ] **357.1c** changeset 的 `--comment` 必須寫明：本欄修正的是「除息與除權被壓成同一欄」的既有缺陷，且既有 `ex_dividend_date` 在回補完成前可能存的是除權日。
- [ ] **357.1d** 對應的 JPA entity（`StockDividendHistory`）與 snapshot event 的讀寫 SQL 同步新增欄位。**`db/schema.sql` 是離線 pg_dump 鏡像且已知落後**（實測缺 `stock_dividend_history.event_status`／`event_key`），本任務**不手改它**，也**不得**拿它當現況基準線——查現況一律用 `docker exec asset-postgres psql ... -c '\d <table>'`。

### 357.2 抓取端：停止壓合，兩個除息除權日各自落地

- [ ] **357.2a** **上述三處**的 fallback 一律移除：`CashExDividendTradingDate` → 除息日、`StockExDividendTradingDate` → 除權日，**兩者互不頂替**。只配現金的事件除權日為 `null`；只配股的事件除息日為 `null`。落地欄位分別是 `ex_dividend_date` 與 `ex_rights_date`（`MarketDataFetchService` 那一處對應的是 `DividendRow` 的兩個欄位）。
- [ ] **357.2b** **⚠ 拆開後有一個會讓資料消失的陷阱，兩條路徑的嚴重度不同，都要顯式處理。** 現在 `ex` 是 `firstNonBlank(cash, stock)`，只配股的事件靠 `StockExDividendTradingDate` 存活。若拆開後改成「除息日為 null 即無效」：
  - 在 `parseFinMindDividendRows` 會 `return ParseEvents.invalid(...)` → **已累積的事件全數丟棄、整個 observation 判為 invalid**；
  - 在 `fetchTw` 只是 `continue` 跳過該列 → 只配股的事件靜默不落地。
  兩處的正確判準都是：**兩個除息除權日至少要有一個可解析**，兩個都缺才是無效。
- [ ] **357.2c** **事件的錨定日期（anchor）必須顯式定義**：`anchorDate = min(除息日, 除權日)`（取兩者中較早且非 null 者）。理由：對「只有一個日期」的既有事件，`anchorDate` 恆等於原本 `firstNonBlank` 取到的 `ex`，故**既有行為逐位不變**（`firstNonBlank` 與 `min` 在只有一個非空值時結果相同）；同時有兩個日期時取較早者，才不會讓事件落在區間外而漏抓。此不變性須以測試釘住。

  **`DividendFetchClient` 內壓合後的 `ex` 實際有四個用途，全部改用 `anchorDate`，其中第四個最容易漏：**
  1. malformed／跳過判定（改為 357.2b 的「兩個至少一個可解析」）
  2. 日期區間過濾
  3. `year` 推導
  4. **`date.toString()` 直接成為 `DividendEvent.exDividendDate`** ← 這一個才是最終餵進 `canonicalEvent()`／`event_key`／`ex_dividend_date` 落地值的那一個。**它不能改用 `anchorDate`**：落地值必須是**真正的除息日**（沒有就 `null`），否則等於換一種方式繼續壓合。

  **`calcDividendBasis` 不在 `DividendFetchClient` 裡**（它定義於 `StockSourceQuery`、由 `MarketDataFetchService` 呼叫），該處沒有 `DividendEvent` 也沒有 anchorDate 概念，須獨立處理：它算的是**填息天數**，用除息日或 anchorDate 需顯式選擇並寫出理由。
- [ ] **357.2d** `DividendFetchClient.DividendEvent` record 追加 `exRightsDate`（緊接 `exDividendDate` 之後）；所有建構點同步更新。該 record 本身**沒有**相容建構式，加欄位會讓所有建構點編譯失敗（可見、不會靜默）。
- [ ] **357.2d-2** **真正會靜默吃掉新欄位的是下列五個帶相容形狀的型別，每一個都必須逐一檢查並顯式傳入新值，或在 Javadoc 標明不得用於 production 路徑：**
  - `TradingRadarDto.RadarEvidence`：相容建構式 ＋ `EMPTY` ＋ **三層 `withConfidence` overload 鏈**
  - `DividendEventEvidenceResolver.Event` 與 `Resolution`（各有 compat shape）
  - `MarketDataService.DividendRow`
  - `DividendCurrentStateRepository.Event` 與 `ProjectedEvent`

  本專案剛在 Task 356 因為相容建構式吃掉新欄位而讓整組新因子在 production 靜默失效（且 1334 個既有測試全綠），同一個陷阱不得再犯（見 `spec/tasks/t356_radar_three_horizon_weekly_k.md` 的完成報告）。

### 357.3 歷史回補（使用者明確要求）

> **⚠ 回補之前必須先解決一個會讓整件事靜默失效的結構問題。** `stock_dividend_snapshot` 是 **immutable ＋ content-hash 去重**：`DividendSnapshotStore.canonicalEvent()` 目前只含 `year`／除息日／現金／股票／兩個發放日；`canonicalContentHash` 是 snapshot 的去重鍵、`canonicalEventHash` 是 `event_key`，寫入為 `ON CONFLICT (snapshot_id,event_key) DO NOTHING`，且 `appendEvents` 只在 `storedEventCount < expectedEventCount` 時才補跑。因此：
>
> - **不把除權日放進 `canonicalEvent`** → 重抓算出的 content hash 與舊的相同 → 重用既有 immutable snapshot → 事件列數相同 → 不會 append → **`ex_rights_date` 永遠寫不進去，回補靜默失效**。
> - **放進去** → 全部既有事件的 `event_key` 改變。而 `event_key` 是 current-state 的身分依據（`DividendCurrentStateProjectionService` 以 `"key:" + eventKey` 建立 identity），實測既有 1213 列中 906 列帶 `event_key`；身分全數對不上，可能整批被判為「新 snapshot 未含舊 event」而 CANCELLED ＋ 重插。
>
> **本任務選 (a)：把 `exRightsDate` 納入 `canonicalEvent()`**，理由是除權日是事件身分的一部分，長期把它排除在 canonical 之外會讓「同一事件」的判定永遠少一個維度。代價必須一併付清，見 357.3a-0。

- [ ] **357.3a-0** `canonicalEvent()` 追加 `exRightsDate`（位置緊接除息日之後）。**必須同時處理 `event_key` 遷移**：提供一次性對映或重算，並以測試釘住「既有事件不得因 `event_key` 改變而被 `DividendCurrentStateProjectionService` 誤判為 CANCELLED」。此測試必須在沒有遷移的版本下紅燈（做突變驗證並在完成報告回報）。

- [ ] **357.3a** 回補分**兩段**，兩段都必須做，只做前半段 `stock_dividend_history` 不會更新：
  1. **ext 端重抓 snapshot**：入口為 `DividendPersister.syncOne(code, market)`，對外可經既有 `POST /internal/dividend/sync?code=&market=`（單檔）。既有的 `scheduledSyncAll`（每平日 17:00）與 `warmupOnStartup` 也走同一支。
  2. **backend 端投影到 current state**：`DividendPersister` 的 Javadoc 自己寫明「不維護 `stock_dividend_history` current-state」；該表由 `DividendCurrentStateProjectionService` 回填，而它**只在 `DividendHistoryService.findFromDb` 被讀到時才觸發**。因此若不主動觸發投影，回補**碰不到** 357.3b 要修的那 24 列。

  **不新增排程、不新增對外端點、不新增 9090 路由、不新增第二條抓取路徑**；但**允許**新增一支內部一次性回補 runner（僅供本次執行，不對外曝露），用以串起上述兩段並提供 357.3c 要求的分批、續跑與逐檔失敗清單——既有機制沒有這些能力（失敗只有 `log.warn`），不新增就無法滿足 357.3c。
- [ ] **357.3a-2** **發放股權日：FinMind 未提供，本任務一律揭露為缺值，不得推估、不得以發放股息日冒充。**

  > **已實測驗證（2026-08-22，經既有 `GET /internal/dividend-history` 實打 FinMind）：**
  > - `7556`（2021–2026 連續六年同時配息又配股）：每一年 `stockPaymentDate` 皆為 `null`，而同列的 `cashPaymentDate` 有值。
  > - `2881`（2021–2025 純配股）：`cashPay` 與 `stockPay` 皆為 `null`。
  > - DB 佐證：`stock_dividend_snapshot_event` 9968 筆中 343 筆有股票股利，`stock_payment_date` **0 筆**有值；`stock_dividend_history` 1213 筆同樣 0 筆。
  > - 另一個資料集 `TaiwanStockDividendResult` 只有 `date`／`stock_and_cache_dividend`／`stock_or_cache_dividend`（填息天數用），**沒有任何日期欄**，補不上。
  >
  > 因此本任務實際交付的是**三個可得日期 ＋ 一個誠實標示的缺值**：發放股權日在畫面顯示 `—`、並於該欄的 `missingReason`／說明文字明寫「資料源未提供」。**驗收條件為「必須正確揭露為缺值」，不是「必須有值」。** 是否另尋來源（例如證交所除權除息預告表）為獨立任務，本次不做、也不得順手加一個新的外部抓取。

- [ ] **357.3a-3** **除權日確認可得，必須真的落地。** 同一次實測中，`2881` 純配股各年的 `exDividendDate` 回 `2025-09-25`／`2024-09-09`／`2023-09-04`／`2022-09-22`／`2021-09-06`——現金股利為 0，代表這些值正是走 fallback 取到的 `StockExDividendTradingDate`。**除權日在來源端有值，只是被壓合掉了**，這是本任務的核心可交付項。
- [ ] **357.3b** 回補會同時修正一類既有錯誤資料：當年該事件沒有現金除息日時，舊資料的 `ex_dividend_date` 存的其實是**除權日**。回補後該值會被移到 `ex_rights_date`、`ex_dividend_date` 變 `null`。**完成報告必須逐檔列出被這樣修正的標的與年度**，不得只說「已回補」。
- [ ] **357.3c** FinMind 有額度限制，回補須分批並可中斷續跑；失敗的標的必須逐檔記錄且不得靜默跳過。回補完成前，畫面對未回補標的一律顯示「尚未回補」而非拿舊值冒充。

### 357.3d `ex_dividend_date` 變 `null` 的全域影響（最高風險項，必須先做）

**只配股的事件其除息日將變成 `null`，而全庫有四處查詢硬性排除或依賴非 null 的除息日。** 實測（運行中 DB）：

```sql
SELECT count(*) FROM stock_dividend_history
 WHERE (cash_dividend IS NULL OR cash_dividend = 0) AND stock_dividend > 0;   -- 24
```

這 24 列全屬 **2881／2885／2891**（2016–2026）。若不處理，回補後它們的股票股利稀釋**不再被還原**，而且會從交易雷達的「下一配息」整批消失——與 Requirement 94 的目的完全相反。

- [ ] **357.3d-1** 下列四處一律改用 `COALESCE(ex_dividend_date, ex_rights_date)`（即 `anchorDate` 的落地形式），不得保留「除息日為 null 就排除」的語意：
  - `JdbcDividendEventEvidenceRepository`（約 113 行）：`WHERE snapshot_id IN (...) AND ex_dividend_date IS NOT NULL` ← **交易雷達「下一配息」本身**
  - `StockDividendHistoryRepository.findAdjustmentEvents`（約 28 行）：`AND h.exDividendDate BETWEEN :fromDate AND :toDate`
  - `DistributionAdjustedPriceService.validEvent()`（約 263–266 行）：`event.getExDividendDate() != null`
  - `StockDividendHistoryRepository.findByStockSinceYear`（約 18 行）：`ORDER BY h.year DESC, h.exDividendDate DESC NULLS LAST`
- [ ] **357.3d-2** `findAdjustmentEvents` 有 **5 個消費端**，全部須一併驗證不回歸：`BacktestService`（三處）、`TradingRadarService`、`HistoricalBondYieldBetaEvidenceAdapter`。
- [ ] **357.3d-3** **2881／2885／2891 的那 24 列是本任務的必測樣本**，驗收與完成報告都必須逐筆列出（不是只列背景段那五筆同時配息配股的）。

### 357.3e 既有消費端影響評估（本任務必須逐條表態，不得留白）

四個日期的語意改變會外溢到交易雷達以外的頁面。下列每一項都必須明確標成「本次一併改」或「明確不改並說明為何安全」：

- [ ] **357.3e-1** `frontend/src/components/StockAnalysisDialog.vue`：已在顯示四個日期中的三個（除息日、發放股息日、發放股權日），且**以除息日作為錨點**做三件事——每筆事件的殖利率分母（除息日昨收價）、依除息日的年度分組、年度殖利率。對那 24 列，除息日變 `null` 後這三處都會失去錨點。依 BFF 規範第 2 條（同義欄位走同一支 business API），四個日期的語意應同時套用到這一頁。
- [ ] **357.3e-2** `PerformanceComparisonBffController`（含息報酬，經 `/api/market-data/dividends-readonly`）：其註解明寫「每筆含 exDividendDate/cashDividend/stockDividend」；那 24 列的股票股利會少計。
- [ ] **357.3e-3** 預估年配息與殖利率的其他消費端：須自行 `grep -ran "exDividendDate\|ex_dividend_date"` 全樹確認有無遺漏，並在完成報告列出掃描結果。

### 357.4 還原權息的行為不得因此改變（最高風險項）

- [ ] **357.4a** `DistributionAdjustedPriceService` 目前以 `ex_dividend_date` 作為事件套用日。新增 `ex_rights_date` 後**必須顯式定義每一種事件用哪一個日期**：**現金股利用 `ex_dividend_date`、股票股利用 `ex_rights_date`**；某一方缺值時退回 `anchorDate`（357.2c）。此定義須寫進 Javadoc。

  > **分割（split）不在此列，也不得寫成「用除權日」。** 分割是由 `detectSplits()` 以相鄰收盤比例的序列啟發式偵測出來的，事件日期取自**價格列的 `trading_date`**，從來不持有任何 dividend 日期欄——該檔 Javadoc 自己就寫著「台股沒有合規且可程式化存取的分割事件來源，實測全庫兩筆真分割中的 `2327` 在 `stock_dividend_history` 完全沒有紀錄」。**分割維持現行機制，不受本任務影響。**

- [ ] **357.4a-2** **`hasStockDividendOn()` 必須改判除權日。** 它目前以 `getExDividendDate()` 判斷「這個跳空已由股票股利解釋」，用來避免把配股跳空誤認為分割。拆欄後只配股事件的除息日會是 `null`，該保護在這類事件上會失效。**現有資料觸發不到**（實測 24 列只配股事件的跳空幅度約 1.1 倍，遠低於 `SPLIT_FORWARD_MIN = 2.0`，第一道門檻就 `continue`），但保護失效本身是缺陷，必須一併修並以測試釘住。
- [ ] **357.4b** **必須有固定資料的回歸測試證明：在回補之前，既有標的的還原後價格序列、`volumeRatio`、以及交易雷達的 `score`／`action`／`reasons`／`risks` 逐位不變。** 回補**之後**確實會有標的的還原結果改變（那正是修正），這類標的必須**逐檔列出並說明改變的原因與方向**，不得混在「不變」的結論裡。
- [ ] **357.4c** 價格比對一律用 `compareTo` / `isEqualByComparingTo`，不得用 `assertEquals` / `equals`（還原路徑的 BigDecimal scale 會由 4 變 8，這是 Task 356 已踩過的坑）。

### 357.5 「下一配息」改為同一次事件的四個日期

- [ ] **357.5a** 解析「下一次尚未發生的配息事件」時，以 `anchorDate` 未來最近的一筆為準，取出**該筆事件**的四個日期一併輸出。**不得**四個日期各自往後找最近的一個——那會拼出一筆現實中不存在的事件。
- [ ] **357.5b** 事件不含某一類時該日期為 `null`，畫面顯示 `—`；**不得顯示 0、不得空白到看不出是缺值**。
- [ ] **357.5c** 尚未公布的日期一律為缺值並揭露「尚未公布」，**不得以往年同期推估或以任何方式填補**（Requirement 94 的「不得表述為預測」）。

### 357.6 DTO、匯出與 OpenAPI

- [ ] **357.6a** 交易雷達的 dividend evidence（`TradingRadarDto.RadarEvidence`）曝露四個日期欄，命名與既有 `nextDistributionDate` 的風格一致且語意明確（建議 `nextExDividendDate`／`nextExRightsDate`／`nextCashPaymentDate`／`nextStockPaymentDate`）。既有 `nextDistributionDate` 若保留，**必須明確定義為 `anchorDate`** 並於 Javadoc 寫明，避免既有消費端語意漂移。
- [ ] **357.6b** 四個日期一律 `yyyy-MM-dd` 字串。`nextDistributionKnownAt`（observation 取得時點，現行顯示為 `2026-08-21T21:05:57.205074Z`）**僅前端呈現**改為 `yyyy-MM-dd`；**API 與匯出一律維持 ISO-8601 date-time 不動**——既有 OpenAPI 契約即 `format: date-time`，且 `knownAt` 是 as-of 稽核時點，截成日期會破壞 Requirement 86 的證據可稽核性並讓 contract test 紅燈。精確時點以 tooltip 或展開揭露。
- [ ] **357.6c** `docs/openapi/docker-external-api.yaml` 必須在**同一個 commit** 內補齊新欄位的 properties 與 `required`，並在 `TradingRadarOpenApiSchemaContractTest` 的綁定表與 nullable 表登錄。依 Requirement 86，文件不完整即視為功能未完成。
- [ ] **357.6d** `TradingRadarExportService` 同步新增對應欄位。**`headers`／`formats`／每列 cell 是三份必須同步的平行清單**，只改表頭會在 runtime 才炸（Task 356 已踩過）；並補「表頭長度 ＝ formats 長度 ＝ 每列 cell 數」的斷言。

### 357.7 前端

- [ ] **357.7a** `frontend/src/views/TradingRadarView.vue` 的「下一配息（已知時點）」區塊改為列出四個日期，每個都標明是哪一種（除息／除權／發放股息／發放股權），缺值顯示 `—`。**保留該區塊既有的 `v-if="row.evidence?.nextDistributionStatus"` 閘門**，四個日期的 `—` 只在區塊內生效；不得改成「四個日期任一有值才顯示」而讓 status 的揭露消失。
- [ ] **357.7b** 版面沿用該區塊既有的樣式慣例，不新增第三方元件。四個日期不得擠成一行到看不清楚哪個是哪個。

### 357.8 不得做的事

- [ ] **357.8a** 不新增排程、不新增對外端點、不新增 9090 路由；business-services 不得為此直連任何外部 API（四個日期只由既有 `external-materials-service` 的 FinMind 路徑落地）。
- [ ] **357.8b** 不手改 `db/schema.sql`（那是 pg_dump 基準線）；不修改任何**已執行**的 Liquibase changeset 內容或註解（改註解也會讓 checksum 失效並造成 crash loop）。
- [ ] **357.8c** 不得為了讓四個日期都有值而推估或填補；不得把 `stock_payment_date` 當成除權日、也不得把 `cash_payment_date` 當成除息日。

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

- **兩組驗收樣本，性質不同，都要列：**
  - **A 組（新增除權日）**：7556 於 2020–2026 連續七年同時配息又配股（背景表只列了其中四年）、9933 的 2025。回補後這些列必須新增 `ex_rights_date`，`stock_payment_date` 則應正確揭露為缺值（來源未提供）。
  - **B 組（`ex_dividend_date` 被移走變 null）**：2881／2885／2891 的那 24 列純配股事件——這一組才是會被改語意的，A 組不會。完成報告須把兩組分開統計。
- 挑一檔只配現金的標的，其除權日與發放股權日為 `—` 而非 0 或空白。
- 「下一配息」區塊的四個日期屬於同一次事件；observation 取得時點顯示為 `yyyy-MM-dd`。
- 回補後 `SELECT count(*) FROM stock_dividend_history WHERE ex_rights_date IS NOT NULL` 大於 0，且抽查數筆與官方一致。

## 完成報告

（實作者完成後回填：實際修改檔案清單、四個驗證指令的完整輸出、回補的標的與年度統計、**因回補而被修正「舊 `ex_dividend_date` 其實是除權日」的標的逐檔清單**、還原權息在回補前的逐位回歸結果與回補後改變的標的清單、實資料抽查（含官方公告比對）、以及與本計畫的偏差及原因。）
