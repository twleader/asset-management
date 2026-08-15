# [t335] 交易雷達大盤風險卡片改為「台股」「美股」兩個分頁

**對應 Requirements:** Requirement 76（交易雷達大盤風險卡片改為「台股」「美股」兩個分頁——把後端已算好卻被丟棄的美股 regime 顯示出來）
**前置任務:** 無（t294 的 `buildUsMarket()`、t295 的個股表格分頁、t323 的 IXIC 量能接線皆已 landed 於 `main`，本任務直接站在其上）
**Liquibase changeset:** 無（不入庫；分數與建議皆為可重算的衍生值，沿用交易雷達既有的「不持久化」設計）

> **⚠ 編號撞號警告（落地前必讀）。** 本任務的 Task 編號 335 與 Requirement 編號 76 同時被其他**尚未合併**的 worktree 佔用：
> - `t335_local_market_analysis_engine.md`（worktree `local-analysis-replace-api-e5fa7c`）
> - `t335_export_schedule_writeback_race.md`（worktree `export-schedule-transactional-fix-fc806b`，同時佔 Requirement 76）
> - `t335_commodity_intraday_live_quote.md`（worktree `oil-gold-price-updates-d021fa`，同時佔 Requirement 76）
>
> `scripts/spec-check.sh` **抓不到這類撞號**——它只比對 `origin/main`，四份都未 landed 時一律回報 `BLOCK: 0`。避讓規則見 CLAUDE.md：**已 landed 的保留原號、改自己這邊**。故 merge 前務必重新確認 `origin/main` 上 Task 335／Requirement 76 是否已被別人佔走；若已被佔，本任務須改號（任務檔檔名、檔內全部 `335.x` 編號、`spec/tasks.md` 索引列、`spec/requirements.md` 的 Requirement 標題與內文交叉引用、`spec/design.md` 的 Task 335 標註，以及 `CLAUDE.md`／`spec/tasks/README.md`／`spec/steering/structure.md` 的索引範圍字串）。程式碼本身不依賴編號，改號不影響實作。

---

## 背景

### 現在的行為

`TradingRadarService.assemble()`（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`）**已經**無條件算出美股那一組大盤。現行第 404-406 行逐字如下：

```java
MarketState twMarket = buildMarket(context.market(), decisionInstant);
// 不論本輪有沒有美股標的都計算（比照台股組現行行為），維持「大盤資料與個股清單解耦」的既有設計。
MarketState usMarket = buildUsMarket(decisionInstant);
```

`MarketState` 是同檔的 record，含 `summary()`（`TradingRadarDto.MarketSummary`）／`regime()`／`stale()` 三者。但同一方法組 `Response` 時（現行第 430-437 行，已核對命中）只帶了台股那一份：

```java
return new TradingRadarDto.Response(
        TradingRadarRuleEngine.RULE_VERSION,
        TradingRadarEvidenceGate.ACTION_POLICY_VERSION,
        decisionInstant.atZone(TAIPEI).toOffsetDateTime().toString(),
        twMarket.summary(),        // ← 只有台股
        decisions,
        skippedNonTw.size(),
        context.publicInformation());
```

`usMarket.summary()` 除了經 `marketSummaryFor(market, twMarket, usMarket)` 餵給美股個股評分之外，**整份被丟棄、從未回傳前端**。

前端 `frontend/src/views/TradingRadarView.vue` 頂部因此只有一張寫死台股的卡片（第 23-91 行，`class="market-card"`，標題「台股大盤風險」）。Task 295 在**個股**卡片加了台股／美股分頁，並在美股分頁放了一則 `el-alert`（第 144-152 行）告訴使用者「美股大盤情境採 NASDAQ 綜合指數（IXIC）自身技術面」——但頁面上查不到 IXIC 究竟是什麼 regime、幾分、站不站季線／年線、KD 位置為何。**知道有這個依據，卻無從檢視它。**

### 正確行為

頁面頂部大盤卡片可切換「台股」「美股」兩個分頁；美股分頁顯示 IXIC 的 regime／分數／點位／各條均線／KD／量能比與支持、風險訊號，其值即 `buildUsMarket()` 餵給美股個股評分的**同一份** summary。

### 這個決定推翻了什麼

推翻 **Task 295.9** 一條明文的範圍排除。該排除只寫在任務檔 `spec/tasks/t295_trading_radar_market_tabs.md` 的 295.9 項，**未**寫進 Requirement 64 本身的 (a)–(f) 排除清單，引用時不得標成 Requirement 64 的排除項。原文逐字為：

> **本次刻意不新增獨立的「美股大盤」卡片**（避免 `TradingRadarDto`／`MarketSummary` 需要為此新增巢狀欄位、擴大本次前端改動範圍），改為在美股分頁的 `el-tabs` 下方（或表格上方，比照現有版面）新增一行固定提示文字……若日後要做獨立的美股大盤卡片，須另立任務並在 `TradingRadarDto.MarketSummary` 新增對應欄位（本次不做）。

本任務即該處指定的「另立任務」。**推翻的關鍵在於當時的成本估計本身有誤**：`MarketSummary` 現有 **30 個 component**（`spec/design.md` 舊記的「Task 281 後 21 個」已漂移，該數字未計入其後追加的量能與跨市場欄）。其中**前 25 個**（`regime` 至 `marketVolumeAsOfDate`）無任何市場專屬語意，`buildUsMarket()` 現行已直接複用它組出美股那一份；**最後 5 個**（`nasdaqChangePercent`／`soxChangePercent`／`usTechCompositePercent`／`usTechAsOfDate`／`usTechAvailable`）語意上確實是給台股列的跨市場領先訊號，但美股組本就把它們固定填為 `null`／`false`（見 335.9），**不需要也不應該**為美股另建對應欄位。實際只需在 `Response` 增加一個**同型別**的 `usMarket` component，`MarketSummary` 一個欄位都不必動。

### 一個目前不可見、上畫面就會顯形的既有缺陷

`buildUsMarket()` 現行第 706 行對 `MarketSummary.quoteStatus` 一律傳字面值 `"CLOSE_PENDING"`：

```java
TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
        result.regime().name(),
        regimeLabel(result.regime()),
        result.score(),
        result.regime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE,
        stale,
        latestEodDate == null ? null : latestEodDate.toString(),
        price,
        changePercent,
        "CLOSE_PENDING",          // ← 第 706 行
        ...
```

但同方法的 `price` 取自 `us_index_daily_history` 的最新一列收盤價（`rows.get(0).getClosePoint()`，`closes.get(0)`），在該列**確為最近一個已完成美股交易日**時，語意上是**已驗證收盤**、不是「等待官方收盤價回補」。此缺陷目前不可見（欄位從未回傳前端），一旦本任務把 `usMarket` 送上畫面就會立即顯形——前端 `frontend/src/utils/displayQuote.js` 的判準逐字為：

```js
export function isClosePending(quote) {
  return quote?.quoteStatus === 'CLOSE_PENDING'
}
```

而卡片第 59-60 行：

```html
<strong v-if="isClosePending(market)" style="color:#d97706;font-size:13px">收盤價待補</strong>
<strong v-else>{{ fmtNumber(market.price, 2) }}</strong>
```

→ 美股分頁的「最新點位」會**永遠**顯示橘字「收盤價待補」而看不到 IXIC 點位。本任務一併修正。

---

## 要做什麼

### 後端

- [x] **335.1 `TradingRadarDto.Response` 新增 `usMarket` component。** 檔案 `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java`。現行 canonical 建構式為 7 個 component：

  ```java
  public record Response(
          String ruleVersion,
          String actionPolicyVersion,
          String generatedAt,
          MarketSummary market,
          List<StockDecision> stocks,
          int skippedNonTwStocks,
          List<PublicInformationItem> publicInformation
  ) {
  ```

  在 `market` **之後**插入 `MarketSummary usMarket`（共 8 個 component），並加 javadoc 註明：`market` 為台股（TAIEX）組、`usMarket` 為美股（IXIC）組，兩者同型別；`usMarket` 為 `buildUsMarket()` 餵給美股個股評分的同一份 summary，非另算。

  **`market` 一律不得改名為 `twMarket`**——該欄位名已被 Redis 快照 JSON、`TradingRadarExportService` 與前端多處讀取，改名屬無償的破壞性變更。

- [x] **335.2 相容建構式：現行兩個各多填一個 `null`，另新增一個承接舊 canonical 形狀，落地後共三個。** 同檔現行**兩個**相容建構式（Java record 的 alternative constructor，非 compact constructor——後者無參數列，本 record 沒有）逐字如下（連同 javadoc 一併保留，不得刪）：

  ```java
  /** t316 前的 response 形狀；舊快照沒有 action policy，不得由 ruleVersion 推導。 */
  public Response(String ruleVersion, String generatedAt, MarketSummary market,
                  List<StockDecision> stocks, int skippedNonTwStocks,
                  List<PublicInformationItem> publicInformation) {
      this(ruleVersion, null, generatedAt, market, stocks, skippedNonTwStocks,
              publicInformation);
  }

  /** 舊快照／舊測試相容建構式；Task 291 前沒有公開資訊清單。 */
  public Response(String ruleVersion, String generatedAt, MarketSummary market,
                  List<StockDecision> stocks, int skippedNonTwStocks) {
      this(ruleVersion, null, generatedAt, market, stocks, skippedNonTwStocks, List.of());
  }
  ```

  兩者各多傳一個 `null` 給新的 `usMarket` 位置。**另須新增一個 7 參數相容建構式**（`ruleVersion`／`actionPolicyVersion`／`generatedAt`／`market`／`stocks`／`skippedNonTwStocks`／`publicInformation`，即 t335 前的 canonical 形狀），同樣填 `usMarket = null`。

  **這個新建構式有明確的既有呼叫端，不是預留：** `TradingRadarSnapshotStoreTest.java:90` 正是以該形狀呼叫，加了它就**不必**改那一行（見 335.5）。落地後 `Response` 共三個相容建構式（7／6／5 參數）＋ 一個 8 參數 canonical；arity 皆不同，無多載歧義。

  > **相容性為什麼是硬性要求：** `TradingRadarSnapshotStore` 會把 `Response` 序列化進 Redis，供 Requirement 48 的區間匯出讀回。新增 component 會改變 content hash，使部署後寫入第一筆新 schema 快照——這是**既有預期行為**，`spec/design.md` 已載明先例（「舊 Redis radar snapshot 是 JsonNode 讀取、不反序列化 record，缺新欄仍可匯出；部署後 content hash 改變而寫第一筆新 schema 快照是預期行為」）。`usMarket` 為 `null` 時，所有既有讀取端（匯出、通知）行為必須逐位不變。

- [x] **335.3 `assemble()` 帶入已算出的 `usMarket.summary()`，不得重算。** 檔案 `TradingRadarService.java`，`assemble(Long ownerId, Instant decisionInstant)` 方法末尾的 `return new TradingRadarDto.Response(...)` 多傳 `usMarket.summary()`（位置緊接 `twMarket.summary()` 之後）。

  **嚴禁**為此新增任何 repository 查詢、`TechnicalIndicatorService` 呼叫或 `ruleEngine.evaluateMarket()` 呼叫，也**不得**在此再呼叫一次 `buildUsMarket()`。理由：兩次呼叫之間 Redis／DB 狀態可能改變，會讓「個股評分依據的美股 regime」與「畫面顯示的美股 regime」對不上，正是本專案 BFF 規範「**同義欄位、同一 business service API**」要防的情境。方法內既有的 `MarketState usMarket` 區域變數直接取用即可。

- [x] **335.4 修正 `buildUsMarket()` 寫死的 `quoteStatus`——判準必須含完成日邊界，不得只看 `price` 是否 null。** 同檔 `buildUsMarket(Instant)` 第 706 行的字面值 `"CLOSE_PENDING"` 改為三段判斷：

  ```java
  price == null ? "CLOSE_PENDING"
          : latestEodDate.equals(mostRecentCompleted) ? "VERIFIED_CLOSE"
          : "PREVIOUS_CLOSE",
  ```

  `latestEodDate`（`:693`）與 `mostRecentCompleted`（`:694`）**在同方法內已經算出來**（現行 `:695` 的 `stale` 就是用這兩個值），零額外查詢成本，直接沿用即可。加註解說明理由。

  **為什麼不能寫成「`price != null` 就 `VERIFIED_CLOSE`」——這是兩個都會實際發生的錯誤狀態：**
  - **(i) 落後一盤時把舊收盤標成已驗證。** `price` 取 `rows.get(0)`，**不保證**等於 `mostRecentCompleted`；兩者不等時 `stale=true` 而 `price` 仍非 null。這不是假想情境：`spec/tasks/t324_radar_evidence_coverage_backlog.md` 記錄的實測是「IXIC `max(trading_date)` 仍為 2026-08-11、缺 8/12」，也就是 `stale=true && price!=null` 曾是**實際穩態**（Requirement 75／Task 332 正是為此收緊回補判準）。
  - **(ii) 未收盤的當日列被當成官方收盤。** `findTopNByIndexCodeOrderByTradingDateDesc(IXIC_CODE, 241)`（`:658-659`）**沒有任何完成日過濾**；Yahoo `range=10y&interval=1d` 在盤中抓會回傳當日的部分 bar，此時 `latestEodDate` 會晚於 `mostRecentCompleted`。用 `equals` 而非 `!isBefore` 正是為了同時擋掉這一側。

  **`PREVIOUS_CLOSE` 是本專案既有語彙、不是新造的狀態**：`TaiexDisplayPriceService.java:62-63` 與 `PriceQueryService.java:140-141` 皆為 `session.phase() == AFTER_CLOSE ? "VERIFIED_CLOSE" : "PREVIOUS_CLOSE"`。`spec/requirements.md` 的 Requirement 7（Task 290）並明文要求「`WatchStockService.toIndexResponse` 與 Trading Radar `MarketSummary` 的可見點位共用相同階段語意」且「不得拿更舊昨收冒充」——本項即是讓美股組對齊台股組早已遵守的那套語意。

  **前端對 `PREVIOUS_CLOSE` 不需任何新處理**：`displayQuote.js` 的 `isClosePending()` 只認 `CLOSE_PENDING`，故 `PREVIOUS_CLOSE` 會正常顯示點位數值（正是我們要的），而該情境同時 `stale=true`，既有的 stale 警示橫幅與 `asOfDate` 實際日期已足以讓使用者辨識。

  `incompleteMarket(String message)` 內的 `"CLOSE_PENDING"` 佔位**維持不變**（該情境本就無 price）。

  > **一個本次不修、但落地後會變成可見現象的既有不一致（須知情、不要當成 bug 去改）：** `asOfDate`（`:703`）用的是**未過濾**的 `latestEodDate`，而 `marketVolumeAsOfDate`（`:731-732`）用的是 `resolveUsV13()` **已過濾**的 `usContext.marketAsOfDate()`（邊界為紐約 16:00，見 `TradingRadarMarketContextService.java:302`／`:529`）。兩者在盤中可能差一日，卡片上會同時出現「完成日 K＝今日」與「量比小字＝前一日」。**本次不動這兩處的取值**——改 `asOfDate` 會連動個股評分讀的 `marketSummary.asOfDate()`（`:931`），超出本任務射程。若要統一須另立任務。

  **此改動對個股評分與匯出皆零影響，已查證：**
  - **個股評分**：`buildStock()` 只讀 `marketSummary` 的四個 accessor——`marketVolumeAsOfDate()`／`marketVolumeRatio()`／`marketTurnoverRatio()`（第 894-895 行組 `TradingRadarEvidenceConfidenceResolver.MarketContext`）與 `asOfDate()`（第 931 行）。`MarketSummary.quoteStatus()` 這支 **Java accessor 全樹零呼叫端**。
  - **匯出**：大盤 `quoteStatus` 有一個 JSON path 消費端——`TradingRadarExportService.java:161` 大盤總覽工作表的「行情狀態」欄（`txt(m, "quoteStatus")`，其中 `m = s.path("market")`）。它讀的是 **`market`（台股）而非 `usMarket`**，本次只改 `buildUsMarket()`，故匯出內容與 golden 檔不受影響。**不得**順手把該欄改成讀 `usMarket` 或為美股新增欄（見 335.17）。

  實作前請用 `grep -ran "marketSummary\." backend/src/main/java`、`grep -ran "\.quoteStatus()" backend/src bff/src external-materials-service/src` 與 `grep -ran "quoteStatus" backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java` 各自再確認一次（本專案 `grep -r` 會靜默跳過被 `file(1)` 判為 data 的 `.java` 檔，**務必帶 `-a`**）。

- [x] **335.5 更新 `Response` canonical 建構式的既有呼叫端。** 全樹共 4 處（`grep -ran "new TradingRadarDto.Response(" backend/src bff/src` 查證）：
  - `backend/src/main/java/com/steven/assets/service/TradingRadarService.java`（335.3 已涵蓋）
  - `backend/src/test/java/com/steven/assets/service/TradingRadarSnapshotStoreTest.java`
  - `backend/src/test/java/com/steven/assets/service/TradingRadarRefreshServiceTest.java`

  逐一處置（**已查證各自用哪個形狀，實作時不必重新判斷**）：

  | 呼叫端 | 現行形狀 | 處置 |
  |---|---|---|
  | `TradingRadarService.java:430` | 7 參數 canonical | 改為 8 參數，多傳 `usMarket.summary()`（335.3 已涵蓋） |
  | `TradingRadarSnapshotStoreTest.java:85` | 5 參數相容 | **不改**（既有相容建構式吸收） |
  | `TradingRadarSnapshotStoreTest.java:90` | 7 參數 canonical | **不改**（由 335.2 新增的 7 參數相容建構式吸收） |
  | `TradingRadarRefreshServiceTest.java:50` | 5 參數相容 | **不改**（既有相容建構式吸收） |

  也就是說：**production 只有一處要改，三處測試呼叫端一律不動**。若實作時有測試因此編譯失敗，代表 335.2 的相容建構式沒補齊——回頭補建構式，不要改測試。

### 前端

檔案一律為 `frontend/src/views/TradingRadarView.vue`。

- [x] **335.6 新增大盤分頁狀態與資料來源 computed。** `<script setup>` 現行第 979 行（`market`）與第 990 行（`marketClass`）已有：

  ```js
  const market = computed(() => radar.value.market || {})
  ...
  const marketClass = computed(() => `regime-${String(market.value.regime || 'DATA_INCOMPLETE').toLowerCase().replace('_', '-')}`)
  ```

  新增（緊接 `market` computed 之後，與 Task 295 個股分頁的 `marketTab` 群組分開放並加註解區分兩者）：

  ```js
  // 大盤卡片的台股／美股分頁（Requirement 76 / Task 335）。與下方個股表格的 marketTab 是
  // 兩個獨立狀態：使用者可以在看台股大盤的同時看美股個股清單，不得合併成同一個 ref。
  const marketCardTab = ref('台股')
  const usMarket = computed(() => radar.value.usMarket || {})
  const currentMarket = computed(() => marketCardTab.value === '美股' ? usMarket.value : market.value)
  ```

  **`market` computed 保持不變**——它是 `currentMarket` 台股分支的上游，也是 335.10 分頁 stale 標記的資料來源。

  同時新增 335.10 需要的兩個 computed（**這兩個必須各自讀自己那一組、不能讀 `currentMarket`**，否則兩個分頁標籤永遠同步，335.10 要防的可見性退化原封不動）：

  ```js
  // 335.10：分頁標籤上的 stale 標記必須各自反映該組狀態，故刻意不走 currentMarket。
  const twStale = computed(() => !!market.value.stale)
  const usStale = computed(() => !!usMarket.value.stale)
  ```

  **殘留自檢（連同上面兩個 computed 一起判讀，不要誤判成漏改）：** 改完 335.7／335.8／335.10 之後跑
  `grep -n "[^a-zA-Z.]market\." frontend/src/views/TradingRadarView.vue`，**預期命中恰為兩處**——`currentMarket` 定義那一行，以及上面 `twStale` 那一行（`usMarket.` 因大寫 M 不會被此 pattern 捕捉）。現行是 22 個命中（行號 31、32、38、48、50、53、60、61、63-71、78、79、85、86、990），其餘全部應已改綁 `currentMarket`；若還有第三處以上的命中，才代表有 markup 漏改。`radar` ref 的初始值（現行第 955 行 `ref({ market: {}, stocks: [], publicInformation: [], skippedNonTwStocks: 0, ruleVersion: 'TW_RULES_V12' })`）加上 `usMarket: {}`。

  `marketClass` 改為依 `currentMarket` 取 regime，使卡片外框配色跟著當前分頁走。

  **兩個分頁狀態不得合併。** 頂部大盤分頁與個股表格分頁是各自獨立的檢視選擇。

- [x] **335.7 卡片標頭改為分頁。** 現行第 23-35 行：

  ```html
  <el-card shadow="never" class="market-card" :class="marketClass">
    <template #header>
      <div class="card-head">
        <div>
          <span class="section-title">台股大盤風險</span>
          <el-tag size="small" effect="plain" type="info" class="rule-tag">{{ radar.ruleVersion || 'TW_RULES_V12' }}</el-tag>
        </div>
        <div class="as-of-group">
          <span class="as-of">完成日 K：{{ market.asOfDate || '資料不足' }}</span>
          <span v-if="market.intraday" class="as-of live-as-of">即時更新：{{ fmtTime(market.liveUpdatedAt) }}</span>
        </div>
      </div>
    </template>
  ```

  改為：標題文字依分頁顯示「台股大盤風險」／「美股大盤風險」；`as-of-group` 內兩處 `market.` 改讀 `currentMarket.`；在 `</template>`（`#header` 結束）之後、`stale` 警示之前插入 `el-tabs`，比照同檔 Task 295 既有寫法（第 125-142 行）：

  ```html
  <el-tabs v-model="marketCardTab" style="margin-bottom:12px">
    <el-tab-pane name="台股">
      <template #label>
        <span style="display:inline-flex;align-items:center;gap:6px">
          <TaiwanMap :size="18" />
          台股
        </span>
      </template>
    </el-tab-pane>
    <el-tab-pane name="美股">
      <template #label>
        <span style="display:inline-flex;align-items:center;gap:6px">
          <UsFlag :size="22" />
          美股
        </span>
      </template>
    </el-tab-pane>
  </el-tabs>
  ```

  `TaiwanMap`／`UsFlag` **已在本檔匯入**（第 910-911 行 `import TaiwanMap from '@/components/TaiwanMap.vue'`／`import UsFlag from '@/components/UsFlag.vue'`），不需重複匯入、不需新建元件。與個股分頁不同，大盤分頁標籤**不顯示檔數 `el-tag`**（大盤沒有「檔數」概念）。

  `rule-tag` 顯示的 `radar.ruleVersion || 'TW_RULES_V12'` **兩個分頁維持相同**：`TradingRadarRuleEngine.RULE_VERSION` 是兩個市場共用的同一支規則引擎版本、非台股專屬。**不得**為美股分頁另編 `US_RULES_V12` 這種不存在的版本字串。

- [x] **335.8 卡片本體 markup 只維護一份，資料來源改綁 `currentMarket`。** 現行第 46-90 行的 `.market-layout`（含 `.regime-panel`／`.market-metrics`）與 `.reason-row` 整塊，所有 `market.xxx` 改讀 `currentMarket.xxx`，`isClosePending(market)` 改為 `isClosePending(currentMarket)`。

  **嚴禁為美股複製第二份卡片 markup。** regime 面板、metric 格、支持／風險清單全部只能有一份，靠 `currentMarket` 切換資料來源——與 Task 295 對個股表格採用的原則相同（避免日後改一處而兩邊漂移）。

- [x] **335.9 美股組恆為 null 的三個 metric 不渲染，而非渲染成 `—`。** 現行第 69-71 行三個 metric 格（已核對命中）在美股分頁須**整格不出現**（`v-if="marketCardTab === '台股'"` 或等效寫法）：

  ```html
  <div class="metric"><span class="metric-label">成交金額比</span>...</div>       <!-- 第 69 行 -->
  <div class="metric"><span class="metric-label">NASDAQ 前一日</span>...</div>    <!-- 第 70 行 -->
  <div class="metric"><span class="metric-label">SOX 前一日</span>...</div>       <!-- 第 71 行 -->
  ```

  理由（`buildUsMarket()` 已在程式碼內逐條註明，此處複寫）：
  - `marketTurnoverRatio` **恆 null**——`us_index_daily_history` 沒有成交值／週轉率欄位，且明文「不得以成交量除以任何數字偽造週轉率」。
  - `nasdaqChangePercent`／`soxChangePercent`／`usTechCompositePercent`／`usTechAsOfDate` **恆 null**，`usTechAvailable` **恆 `false`**（`:736-740` 為 `null, null, null, null, false` 五個固定值）——「美股科技共同完成日」因子的設計語意是**台股列的領先訊號**（台股 14:00 決策時看前一個已完成的美股 session），填進美股列自己使用的 `MarketSummary` 是自我指涉。其中 `usTechCompositePercent` 與 `usTechAvailable` 前端無渲染點，故畫面上受影響的是三格。

  渲染成空欄或 `—` 會讓使用者誤以為是資料抓取失敗，故必須整格隱藏。**台股分頁這三格維持現狀不變。**

  第 68 行的「大盤完成日量比」（`marketVolumeRatio`）**兩個分頁都要顯示**——美股組**有值**，Task 323 已把 IXIC 成交量接上（`buildUsMarket()` 內 `usContext.marketVolumeRatio()`）。

- [x] **335.10 stale 警示與「即時更新」依當前分頁自身狀態。** 現行第 37-44 行的 stale 警示：

  ```html
  <el-alert
    v-if="market.stale"
    class="stale-alert"
    type="warning"
    show-icon
    :closable="false"
    title="大盤資料非最新，今日買進訊號暫停"
    description="本次未能取得即時大盤點位，已退回前一交易日資料；為避免以昨日的環境替今日背書，此期間不採計大盤加分，也不產生買進／加碼候選。偏空環境的扣分與限制仍照常生效。" />
  ```

  `v-if` 改為 `currentMarket.stale`。美股組的 `stale` 是其自身判定（`latestEodDate < mostRecentCompletedUsTradingDay(decisionInstant)`），驅動的是**美股**買進閘門。

  **標題與描述都要依分頁取用，描述不得原樣沿用。** 標題改為內插市場名（如 `` `${marketCardTab}大盤資料非最新，今日買進訊號暫停` ``）。描述現行逐字為「本次未能取得**即時**大盤點位，已退回前一交易日資料……」——這是 Task 228 為台股寫的，前提是 TAIEX 有 Redis 盤中即時值可取。**該因果對美股恆為假**：`buildUsMarket()` 從不查任何 live 來源（`:717-718` 把 `intraday`／`liveUpdatedAt` 寫死 `false`／`null`，`stale` 純由 `:694-695` 的完成日比較決定，且全樹無 IXIC 的 Redis poller）。美股 stale 的真正原因是**日線回補落後**，沿用台股文案會把使用者導向錯誤的排除方向。美股分頁的描述改為指向「最近一個已完成美股交易日的日線尚未回補」（該回補路徑見 Requirement 75／Task 332），台股分頁描述維持現行文字不變。

  **同時必須處理一個由分頁化引入的可見性退化（硬性要求，不得略過）：** 現行該警示是**無條件**掛在唯一那張卡上（`v-if="market.stale"`），只要台股大盤過時就一定看得到。改綁 `currentMarket.stale` 之後，「台股 stale 但使用者正停在美股分頁」時警示不渲染，使用者會錯過「台股買進訊號已暫停」這個事實。故**兩個分頁標籤各自須在該組 `stale=true` 時帶視覺標記**（小紅點、`el-badge` 或等效做法），讓非當前分頁的 stale 狀態仍看得見。標記只需表示「該分頁有狀況」，細節仍由使用者切過去看警示本體。

  **標記的資料來源是 335.6 定義的 `twStale`／`usStale` 兩個 computed，不是 `currentMarket.stale`**——綁 `currentMarket` 會讓兩個標籤永遠同步，等於沒解決問題。同 repo 有可直接照抄的既有寫法：`frontend/src/views/SnapshotFormView.vue:467-473` 的 `el-tab-pane` 自訂 `#label` 內掛 `el-badge`（本專案 element-plus `^2.8.0` 支援）。

  「即時更新」時間（第 32 行 `v-if="market.intraday"`）改讀 `currentMarket.intraday`。美股組 `intraday` 恆 `false`、`liveUpdatedAt` 恆 `null`（本專案未為 IXIC 建立 Redis 即時報價來源，Requirement 64 排除項 (e)），故該行在美股分頁自然不顯示，**不需另加條件**。

- [x] **335.11 `usMarket` 為 null 或 `DATA_INCOMPLETE` 時須優雅降級，不得崩潰。** 兩種情境：
  - **`null`**：舊 Redis 快照缺該欄，或後端尚未部署新版。335.6 的 `computed(() => radar.value.usMarket || {})` 已提供 null 防護（比照同檔既有 `market` computed 的既有寫法），所有 metric 顯示既有的空值 fallback（`fmtNumber`／`fmtPct`／`fmtRatio` 對 null 的既有行為），`regimeLabel` 顯示「載入中」、`score` 顯示 `—`。
  - **`DATA_INCOMPLETE`**：`buildUsMarket()` 走進 catch 時回 `incompleteMarket("讀取美股大盤資料失敗，美股個股暫停產生交易訊號。")`，此時 `regime='DATA_INCOMPLETE'`、`score=null`、各 MA 為 null、`risks` 含該則訊息、`stale=true`。畫面靠既有 markup 自然呈現（風險清單會顯示那則訊息，stale 警示會出現），**不需**另寫空狀態元件。

  兩種情境都**不得**出現 `TypeError` 或整頁白畫面。

- [x] **335.12 調整個股分頁的 IXIC 提示 `el-alert`（現行第 144-152 行）。** 本任務落地後，該提示所指的大盤資訊已可在頁面頂部直接檢視，故其措辭須改為引導使用者去看，例如描述改為指向「頁面頂部『美股大盤風險』分頁」。

  **不得原封不動保留**「只告知有依據、不告知在哪看」的舊措辭；**亦不得整則刪除**——「美股個股不套用台股 regime」這個反直覺事實仍須留在個股分頁就近揭露。標題可維持不變。

- [x] **335.13 不升 `RULE_VERSION`。** `TW_RULES_V12` 維持不變，`TradingRadarRuleEngine.java` 常數、本檔第 955 行 `radar` ref 初始值與第 28 行顯示 fallback 全部**不動**，通知基準不重建。

  **援引的是 Task 281 先例，不是 Task 249 先例——兩條互斥，援引錯了會得到相反結論：**
  - Task 249 的成立條件是「同一份輸入前後產生完全相同的輸出」。**本次不符**：response 結構多一個 component、Redis 快照 content hash 會變。
  - Task 281 的成立條件是「新增欄位**純揭露**——不進 `StockInput`／`MarketInput`，`action`／`score`／`regime`／`reasons`／`risks` 逐位不變；其輸出**結構**確有變化但規則集本身未變」。**本次逐條相符**：`usMarket` 只是把既有 `buildUsMarket()` 的輸出多回傳一份，335.4 的 `quoteStatus` 修正經查證亦無任何評分讀取端。

### 測試

- [x] **335.14 後端測試加在既有的 `backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java`**（t294 建立，已有 `stubBaseline()`／`stubDivergentRegimes()` 兩支 helper 與 `newService().assembleAt(fixedAfterUsClose)` 的既有組裝方式，`ruleEngine` 為包住真實引擎的 `Mockito.spy`）。新增至少四項：
  1. **`usMarket` 非 null，且與餵給美股個股評分的是同一份值**（防的是日後被改成各算一次）。**`regime` 與 `score` 必須用兩種不同手法驗**——`TradingRadarRuleEngine.StockInput` 共 25 個 component，與大盤相關的**只有 `marketRegime` 與 `marketStale` 兩個，沒有任何大盤分數欄位**，所以 `score` 攔 `evaluateStock()` 是攔不到的：
     - `regime`：以 `ArgumentCaptor<TradingRadarRuleEngine.StockInput>` 攔 `evaluateStock()`，斷言美股個股那一筆的 **`marketRegime().name()`** 等於 `response.usMarket().regime()`。**型別不同、不得直接比對**——`StockInput.marketRegime` 是 `MarketRegime` enum，`MarketSummary.regime` 是 `String`。
     - `score`：改攔 spy 的 `evaluateMarket()`，取其回傳 `MarketResult.score()` 與 `response.usMarket().score()` 比對（`ruleEngine` 已是包住真實引擎的 `Mockito.spy`，可用 `doAnswer` 保留回傳值後檢查）。**`evaluateMarket()` 每次 `assemble()` 會被呼叫兩次**——`:578` 台股（先）、`:673` 美股（後），必須以 `MarketInput` 內容或回傳的 regime 明確辨識出美股那一次，**不得**取「最後一次」了事：那只是目前呼叫順序的巧合，日後 `assemble()` 換順序就會靜默失效。
  2. **`usMarket()` 與 `market()` 各自獨立**——用既有 `stubDivergentRegimes()` 造出台股 `RISK_OFF`、美股非 `RISK_OFF` 的情境，斷言兩個 component 的 `regime` 確實不同。
  3. **`quoteStatus` 的四個分支各自獨立驗**（**不得**只驗其一就宣稱涵蓋）：
     - **`latestEodDate == mostRecentCompleted`** → `response.usMarket().quoteStatus()` 為 `"VERIFIED_CLOSE"`。
     - **`latestEodDate` 早於 `mostRecentCompleted`（落後一盤）** → `"PREVIOUS_CLOSE"`，且同一份 summary 的 `stale()` 為 `true`、`price()` **非 null**。這條是 335.4 的核心，漏驗等於沒修。
     - **`price` 為 null 的正常路徑** → `"CLOSE_PENDING"`。造法：IXIC repo stub 成空 list，**但 `indicatorService.computeAllForNasdaq()` 一定要另外 stub**。注意 `rows` 為空**不會**擲例外、不會走進 catch（`:658-668` 只會讓 `price=null`／`changePercent=null`）。真正會炸的是 `computeAllForNasdaq()` 沒 stub 時 mock 回 `null`：求值順序上 `:677` 的 `indicators(ind)` 會先解參考（實際擲出點在 `RadarInputAssembler.java:258`，且該測試注入的是**真的** `RadarInputAssembler` 非 mock），NPE 被 `:742` 的 catch 吞成 `incompleteMarket()`——那就驗到別的分支去了。（`:707` 的 `ind.weeklyMa()` 走不到，別誤標成那裡。）
     - **走 `incompleteMarket()`** → `"CLOSE_PENDING"`。造法：明確讓 repo `thenThrow`，不要靠 NPE 意外觸發。

     > **上面第三、第四個分支的期望值相同（都是 `CLOSE_PENDING`），所以必須另加一條互斥斷言，否則測試會綠燈但驗錯路徑。** 這不是假想風險：既有 `stubBaseline()`（`TradingRadarUsStockEngineTest.java:224-228`）**本來就**把 IXIC repo stub 成空 list 且**沒有** stub `computeAllForNasdaq()`（那個 stub 只在 `:238 stubDivergentRegimes()`），也就是**預設狀態就會落進 catch**。`regime` 也無法區辨（rows 空時正常路徑算出的 regime 同樣是 `DATA_INCOMPLETE`）。唯一可區辨的觀測點是 `risks`——`incompleteMarket()` 的訊息為 `"讀取美股大盤資料失敗，美股個股暫停產生交易訊號。"`（`:744` 傳入、`:1482` 放進 `risks`）。故：**第三個分支加負向斷言**（`risks()` 不含該訊息）、**第四個分支加正向斷言**（含該訊息）。
  4. **相容建構式**——缺 `usMarket` 的三個相容建構式呼叫後 `usMarket()` 為 `null` 且不拋例外。此項可視情況放在 `TradingRadarSnapshotStoreTest.java`（該檔已直接 `new TradingRadarDto.Response(...)`）。

- [x] **335.15 既有測試全綠。** 特別確認以下兩支不因新增 component 而失敗：
  - **`backend/src/test/java/com/steven/assets/service/export/TradingRadarDualFormatTest.java`** ——四支 golden（`radar`／`radar_empty`／`radar_pre_t281`／`radar_empty_pre_t281`，經 `GoldenWorkbooks.golden(...)`）的**唯一**比對端就在這裡。**注意不是 `TradingRadarExportServiceTest`**（該檔沒有任何 golden 比對，只覆蓋多快照四分頁／時間欄為文字／缺漏彙總／空區間／from>to）。結構上本次碰不到它：該檔的快照是手工 `ObjectNode`（非經 `TradingRadarDto.Response` 序列化），新增 record component 進不了那份 JSON。
  - **`TradingRadarSnapshotStoreTest`** ——該檔直接 `new TradingRadarDto.Response(...)`，335.5 已判定其兩處呼叫皆由相容建構式吸收、不需改。

  另須**順手訂正一處會被本次改動變成假敘述的既有 javadoc**（測試本身仍會綠，屬純註解漂移）：`backend/src/test/java/com/steven/assets/service/TradingRadarUsMarketVolumeWiringTest.java:52-53` 逐字寫著「⚠ 觀測入口**只能**用 `buildMarketSnapshot(US_MARKET).summary()`：`buildUsMarket` 是 private，而 `assembleAt(Instant)` 的 `Response.market` 只帶台股 summary。」——335.3 落地後 `assembleAt(...).usMarket()` 就是第二個觀測入口，該「只能」不再成立。改註解即可，不動測試邏輯。

  若 golden 檔仍因故需要更新，**須在完成報告說明變更了什麼、為何是預期的**，不得靜默重新產生 golden 檔。

- [x] **335.16 前端驗證：`npm test` ＋ `vite build`。** 本專案 `frontend/` **有** node 內建測試（`package.json` 的 `"test": "node --test src/utils/displayQuote.test.js src/utils/valuationEvidence.test.js"`），只是不涵蓋 `.vue` 元件層。

  **`displayQuote.test.js` 與本次高度相關**：它正在測 `CLOSE_PENDING`／`VERIFIED_CLOSE` 的合併語意，與 335.4 改的是同一組狀態常數。本次不改 `src/utils/`，但**必須跑一次 `npm test` 確認未回歸**；若該測試因本次改動而失敗，代表 335.4 的狀態語意判斷有誤，須回頭檢討而非改測試。元件層（`.vue`）無測試框架，以 `vite build` 驗編譯 ＋ 部署後瀏覽器實測涵蓋。

### 明確不在本次範圍

- [x] **335.17 以下項目一律不做，做了即為超出範圍：**
  - **Excel／JSON 匯出不新增美股大盤工作表或欄位**——`TradingRadarExportService` 的大盤總覽工作表維持只寫台股那一組（比照 Task 295.10 的既有取向——該處原文防的是「不得因為加了分頁而誤改為只匯出當前分頁」、方向是不得**縮小**；本次延伸為同樣不因加分頁而**擴大**，屬本任務自訂決定，非 295.10 原文）。匯出範圍變更須另立任務。
  - **IXIC 即時盤中報價**——美股大盤維持只用完成日資料（Requirement 64 排除項 (e)）。
  - **SOX 作為第二組美股大盤**——美股大盤仍以 IXIC 為唯一代理（Requirement 64 排除項 (f)）。
  - **美股大盤的 Email 通知**——`trading_radar_notification_*` 相關邏輯完全不動，通知仍只針對個股狀態轉換。
  - **`market` component 改名為 `twMarket`**（見 335.1）。
  - **英股大盤**（無資料源，沿用既有排除）。
  - **個股表格分頁（`marketTab`）的任何行為變更**——Task 295 的既有實作不動。

---

## 驗證

```bash
# 後端測試（本專案 Mockito 需 byte-buddy 實驗旗標；務必用 -DextraArgLine，不得用 -DargLine——
# 後者會覆蓋掉 surefire 的時區設定，導致大量測試 error 且錯誤訊息偽裝成 byte-buddy 問題）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true test

# 前端既有單元測試（涵蓋 src/utils，含與本次同組狀態常數的 displayQuote.test.js）
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm test

# 前端編譯
/Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite build
```

部署（本專案沒有 dev server，「改好」＝ image rebuild + container recreate）：

```bash
# ⚠ 上一個 block 的 `cd frontend` 會外溢到這裡，且 compose 檔只存在於 worktree 根，
#   不先 cd 回來的話 .env 會複製到錯的地方、docker compose 也會在沒有 compose 檔的目錄執行。
cd /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-tabs-tw-us-e8f0d3

# 從 worktree 跑 compose 前先把主 repo 的 .env 複製進來（env_file 相對 compose 檔解析，--env-file 救不了）
cp /Users/steven/Project/asset-management/.env .
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
# business-services 重建會換 IP，BFF 握舊 IP 會回 500 且 Docker DNS TTL 600s 內不自癒
docker compose -p asset-management restart bff
```

> **JVM service 一律 `--no-cache`**：cached build 可能產出不含本次變更的 stale jar（前端卻有），症狀是 gateway 404 或欄位消失。可用 `unzip -p` 檢查 jar 內 `.class` 是否含本次變更確認。

API 層驗收：

```bash
# usMarket 已回傳且非 null，quoteStatus 不是 CLOSE_PENDING
docker compose -p asset-management exec -T business-services \
  curl -s -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' \
  http://localhost:8080/api/trading-radar | \
  python3 -c "import sys,json; r=json.load(sys.stdin); u=r.get('usMarket'); print('usMarket:', json.dumps(u, ensure_ascii=False)[:400])"
```

**另須實查一個 spec 未能離線確認的值**：`usMarket.marketVolumeRatio` 是否真的非 null。335.9 要求「大盤完成日量比」兩個分頁都顯示，依據是 Task 323 已把 IXIC 成交量接上（`db/schema.sql` 的 `us_index_daily_history` 確有 `volume` 欄、程式碼確有接線）。但若運行中 DB 該值實際為 null，美股分頁會顯示一個空的量比格。落地前用上面那條 `curl` 確認：

```bash
docker compose -p asset-management exec -T business-services \
  curl -s -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' \
  http://localhost:8080/api/trading-radar | \
  python3 -c "import sys,json; u=json.load(sys.stdin).get('usMarket') or {}; print('marketVolumeRatio:', u.get('marketVolumeRatio'), '| asOf:', u.get('marketVolumeAsOfDate'))"
```

若為 null，於完成報告記錄實測值並說明是資料面缺口（非本任務的實作錯誤），量比格的處置比照 335.9 三個恆 null metric 或維持顯示，擇一並寫明理由。

瀏覽器實測，開啟 `/trading-radar` 確認：

1. 頂部大盤卡片出現「台股」「美股」兩個分頁，各帶既有的 `TaiwanMap`／`UsFlag` 圖示。
2. 台股分頁的內容與改動前**逐項相同**（regime、分數、十個 metric、支持／風險訊號、卡片外框配色）。
3. 美股分頁顯示 IXIC 的 regime、分數、最新點位、MA5／20／60／240、KD、大盤完成日量比與支持／風險訊號；**「最新點位」顯示實際數值，不是橘字「收盤價待補」**。
4. 美股分頁**看不到**「成交金額比」「NASDAQ 前一日」「SOX 前一日」三個 metric 格（是整格不出現，不是顯示 `—`）。
5. 美股分頁的「完成日 K」日期為 IXIC 自身的最新完成日，與台股分頁的日期**各自獨立**。
6. 切換頂部大盤分頁**不影響**下方個股表格的分頁狀態，反之亦然。
7. 個股卡片美股分頁的 IXIC 提示 `el-alert` 措辭已改為引導至頂部美股大盤分頁。
8. 台股大盤 `stale=true` 時，即使停在**美股**分頁，台股分頁標籤仍帶得到 stale 視覺標記（335.10 的可見性要求）；反之亦然。
9. 瀏覽器主控台（`read_console_messages`）無新增錯誤或警告。

---

## 完成報告

**實作日期：** 2026-08-16

實作由兩支 subagent 並行執行（後端繼承主 agent 模型；前端 Vue 依 CLAUDE.md 例外二固定 Sonnet 5），主 agent 負責查證、驗收與閘門。

### 實際改動檔案

**`backend`（生產程式碼）**

- `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java`
  - `Response` record 在 `market` 之後插入 `MarketSummary usMarket`（共 8 個 component），並補 javadoc：`market` 明確標註**不得改名為 `twMarket`**（已被 Redis 快照 JSON／`TradingRadarExportService`／前端多處讀取）、`usMarket` 標註其值即 `buildUsMarket()` 餵給美股個股評分的**同一份** summary 而非另算。
  - 新增一個 7 參數相容建構式承接 t335 前的 canonical 形狀；既有兩個相容建構式（6／5 參數）各多填一個 `null`。落地後共 8／7／6／5 四種 arity，互不衝突、無多載歧義。
- `backend/src/main/java/com/steven/assets/service/TradingRadarService.java`
  - `assemble()` 的 `Response` 組裝多傳 `usMarket.summary()`（直接取方法內既有的 `MarketState usMarket` 區域變數），並加註解禁止重呼叫 `buildUsMarket()` 或另做任何 repository／indicator／`evaluateMarket()` 查詢。
  - `buildUsMarket()` 原本寫死的 `"CLOSE_PENDING"` 改為三段判準：`price == null ? "CLOSE_PENDING" : latestEodDate.equals(mostRecentCompleted) ? "VERIFIED_CLOSE" : "PREVIOUS_CLOSE"`，沿用同方法內既有的 `latestEodDate`（`:693`）與 `mostRecentCompleted`（`:694`），零額外查詢。註解逐條記錄為何不能簡化成「`price != null` 就 `VERIFIED_CLOSE`」的兩側理由。

**`frontend`（生產程式碼）**

- `frontend/src/views/TradingRadarView.vue`（+59 / −28）
  - `<script setup>`：新增 `marketCardTab`／`usMarket`／`currentMarket`／`twStale`／`usStale` 五個響應式狀態；`radar` ref 初值加 `usMarket: {}`；`marketClass` 改依 `currentMarket` 取 regime。
  - `<template>`：卡片標題改為 `{{ marketCardTab }}大盤風險`；`as-of-group` 兩處改綁 `currentMarket`；`#header` 之後插入 `el-tabs`（台股／美股兩個 `el-tab-pane`，沿用同檔既有的 `TaiwanMap`／`UsFlag` import，**未新增元件或 import**）；stale 警示改綁 `currentMarket.stale` 且標題內插市場名、description 依分頁分歧；`.market-layout`／`.market-metrics`／`.reason-row` 整塊**只有一份**、全部改綁 `currentMarket`；三個美股恆 null 的 metric 各自加 `v-if="marketCardTab === '台股'"` 整格隱藏；個股分頁的 IXIC 提示 `el-alert` description 改為引導至頂部「美股大盤風險」分頁。

**`backend`（測試）**

- `TradingRadarUsStockEngineTest.java`：新增 6 條測試（詳見下方「驗證輸出」）。
- `TradingRadarSnapshotStoreTest.java`：新增 1 條，驗三個相容建構式在缺 `usMarket` 時填 `null` 且不拋例外。
- `TradingRadarUsMarketVolumeWiringTest.java`：訂正 javadoc——該檔原本逐字寫「觀測入口**只能**用 `buildMarketSnapshot(US_MARKET).summary()`」，335.3 落地後 `assembleAt(...).usMarket()` 即為第二個入口，該「只能」不再成立。僅改註解，未動測試邏輯。

**未改動：** BFF（`TradingRadarBffRoutes` 是純 rewrite passthrough，新欄位自動穿透，經 arch-auditor 確認「不改 BFF」為正確而非漏做）、`db/changelog/**`（零 DB 變更）、`TradingRadarExportService`（匯出維持只寫台股那組，335.17 排除項）、`RULE_VERSION`（`TW_RULES_V12` 前後端 hardcode 全部未動）。

### 驗證輸出

**後端測試（主 agent 獨立重跑一次，未採信 subagent 回報）**

```
[INFO] Tests run: 1018, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

clean-tree 對照為 1011，恰好 +7，與新增的 7 條測試相符。新增測試逐條：

1. `usMarket` 回傳的就是餵給美股個股評分的同一份大盤——`regime` 以 `ArgumentCaptor<StockInput>` 攔 `evaluateStock()` 後比對 `marketRegime().name()`（避開 enum vs String 陷阱）；`score` 以 spy 的 `doAnswer` 保留 `MarketResult`，並**依 `MarketInput.price()==19000` 辨識美股那一次**（非取 index——`evaluateMarket()` 每次 `assemble()` 被呼叫兩次）。另斷言 `marketInputs.size()==2`。
2. `usMarket` 與 `market` 各自獨立（台股 `RISK_OFF` vs 美股 `RISK_ON`）。
3. `latestEodDate == mostRecentCompleted` → `VERIFIED_CLOSE`，且 `stale=false`、`price` 非 null。
4. **落後一盤 → `PREVIOUS_CLOSE`**，且 `stale=true`、`price` **非 null**、`asOfDate` 為落後的那一日。這條是 335.4 的核心。
5. 日線為空的正常路徑 → `CLOSE_PENDING`，`price` 為 null，並加**負向** `risks()` 斷言（不含 `incompleteMarket()` 的訊息）。
6. 明確 `thenThrow` 走 `incompleteMarket()` → `CLOSE_PENDING`，加**正向** `risks()` 斷言。第 5、6 條期望值相同，靠這組互斥斷言確保不會靜默驗到對方的分支。
7. （`TradingRadarSnapshotStoreTest`）三個相容建構式均得 `usMarket() == null` 且不拋例外。

> **測試環境的既有脆弱性（非本次引入，值得記錄）：** 實作 subagent 最初兩次全量跑出現 5、7 個失敗，集中在幾支匯出排程與 Google Drive 相關的測試類（`AssetTransactionExportScheduleServiceTest`、`CommodityExportScheduleServiceTest`、`RealizedGainExportScheduleServiceTest`、`ExportScheduleGdriveTest`、`TradingExportGdriveTest`），且**每次失敗集合不同**；兩次都橫跨午夜。以 stash／restore 做對照確認：抽掉本次 backend diff 後 clean tree 為 1011 全綠、還原後 1018 全綠，證實與 Task 335 無關，屬近期「多個排程時間」工作的 wall-clock 相依 flake。主 agent 於午夜後重跑亦為 1018 全綠。**建議後續任務在午夜前後跑全量測試時一律做 clean-tree 對照**，否則「既有測試全綠」這條驗收無法判讀。

**前端**

- `npm test`（node `--test`，涵蓋 `src/utils/displayQuote.test.js`／`valuationEvidence.test.js`）：`# tests 9 / # pass 9 / # fail 0`。本次未改 `src/utils/`，`CLOSE_PENDING`／`VERIFIED_CLOSE` 的既有合併語意測試不受影響。
- `vite build`：`✓ 2401 modules transformed`、`✓ built in 4.82s`，僅既有的 chunk size 提醒。
- 335.6 的殘留自檢，實際輸出恰為預期的兩處：
  ```
  1007:const currentMarket = computed(() => marketCardTab.value === '美股' ? usMarket.value : market.value)
  1009:const twStale = computed(() => !!market.value.stale)
  ```
  改動前為 22 個命中（行號與任務檔預測清單逐一相符），證明其餘 20 處 markup 全數改綁 `currentMarket`。
- 驗證用的 `node_modules` 符號連結與 `dist/` 均已移除，worktree 無建置產物殘留。

### 架構符規查證

已派 `arch-auditor`（diff-scoped，只餵本次 6 個實作／測試檔的完整 diff，明確排除同 worktree 的 `spec/**` 與 `CLAUDE.md` 變更）。判定 **critical 0／major 1／minor 0**。

已查無發現且逐條附證據的項目：未觸碰任何 controller；DTO 維持不可變 record、無 JPA Entity 外洩；「不改 BFF」判定正確（`TradingRadarBffRoutes` 為純 rewrite passthrough）；`usMarket` 確為同一物件參照而非重算（`assemble()` 內 `buildUsMarket()` 僅呼叫一次，`Response` 與 `marketSummaryFor()` 取的是同一個 `MarketState.summary()`）；零新增外部抓取；零 DB 變更；`quoteStatus` 三值為既有語彙非新造；前端無裸 `fetch`／無新增 API 路徑／markup 未複製第二份。

**唯一的 major 已處置（本次刻意不在程式碼中收斂，理由如下）：**

> **IXIC 的 MA5/20/60/240 本次起同時顯示於兩個頁面，但兩頁走兩份不同實作。**「股市大盤查詢」頁走 `MarketIndexChartService.movingAverage`（BFF，BigDecimal 精確和後 `divide(2, HALF_UP)`），交易雷達美股分頁走 `TechnicalIndicatorService.nasdaqSimpleMa`（backend，**double 累加**後 `setScale(2, HALF_UP)`）。兩者讀同一張 `us_index_daily_history`、都不併即時價，本應同值，但在捨入邊界可差 0.01。
>
> **`spec/steering/structure.md` §3.2 鐵則 4 的既有具名例外不涵蓋這一組：** 例外 (3) 的免罪理由是「語意不同（含 live）」，但 `computeAllForNasdaq()` **刻意不併即時價**（IXIC 無對應 Redis 即時報價來源）；例外 (1)(2) 的「算術上逐位相同」論證前提是台股收盤 `numeric(12,2)`、和除以 5 碰不到 HALF_UP 邊界，而 `us_index_daily_history.close_point` 是 **`numeric(14,4)`**，四個視窗的平均都可能落在 `x.xx5`。
>
> **為何本次不順手收斂：** 兩份實作都早於 t335（`nasdaqSimpleMa` 為 Task 294.1），t335 只是讓其中一份的輸出**首次上畫面**、使觸發條件成立。收斂必然要改 `computeAllForNasdaq()` 的輸出，而該輸出經 `indicators(ind)` 進 `MarketInput` → 直接影響美股 regime 與買進閘門；Requirement 76 明文以「純揭露、`action`／`score`／`regime` 逐位不變」作為不升 `RULE_VERSION` 的前提（Task 281 先例），順手改會使該前提失效，且需跑美股 regime 回歸。
>
> **處置：** 已在 `spec/steering/structure.md` §3.2 鐵則 4 新增「具名例外之二（Task 335 登記，狀態：待收斂）」完整記錄上述事實與收斂正解，並同步把該節結尾的「這條例外只涵蓋 (1)(2)(3) 三份」改寫為涵蓋兩組例外、且要求引用時逐條核對免罪理由是否真的適用。收斂本身已另開追蹤任務。

### 與原計畫的偏差及原因

1. **spec 在實作前跑了三輪對抗式審查，共修 15 條 findings（0 critical／8 major／21 minor 去重後）。** 第三輪由多維度 workflow 執行（5 個查證維度 × 每條 finding 派獨立驗證者嘗試駁倒，22 條被駁回）。其中兩條 major 是實質技術缺陷，若未修會直接寫錯程式碼：
   - **`quoteStatus` 判準原本只看 `price` 是否 null、缺時間邊界。** 原稿寫「`price` 非 null → `VERIFIED_CLOSE`」，但 IXIC 日線落後一盤時 `stale=true` 而 `price` 仍非 null（`t324` 記錄的實測「IXIC `max(trading_date)` 停在 2026-08-11、缺 8/12」正是此穩態），會把舊收盤標成已驗證，違反 Requirement 7／Task 290 明文的「不得拿更舊昨收冒充」；另一側是 `findTopN...` 無完成日過濾、Yahoo 盤中抓取可能寫入當日未完成 bar。已改為三段判準並用 `equals` 同時擋兩側。
   - **335.6 的 grep 驗收判準與 335.10 的 stale 標記要求互相矛盾。** 分頁標籤要顯示「非當前分頁也 stale」就必須讀 `market.stale`，但原判準說「只要還有 `market.` 命中就是漏改」——照著驗會把正確實作改壞。已改為明確定義 `twStale`／`usStale` 兩個 computed 並把預期殘留數訂正為兩處。
2. **`TradingRadarSnapshotStoreTest` 的相容建構式測試最終斷言用 `isEqualTo` 而非 `isSameAs`。** 335.14 第 4 項允許該測試放在兩個檔的任一個，實作放在 `TradingRadarSnapshotStoreTest`（Redis 快照相容性的論述本就在該檔）。原先嘗試 `isSameAs` 並合理地失敗——`currentPolicyResp()` 每次建立新的 `MarketSummary` 而非重用同一實例；`isEqualTo` 才是真正釘住「既有讀取端行為不變」的斷言。
3. **`TradingRadarUsMarketVolumeWiringTest` 的 javadoc 訂正是 spec 第三輪新增的要求（F8），非原始計畫。** 屬純註解漂移修正，未動測試邏輯。
4. **編號撞號風險已知並已記錄。** Task 335 與 Requirement 76 同時被另外三個**未合併**的 worktree 佔用（`local-analysis-replace-api-e5fa7c`／`export-schedule-transactional-fix-fc806b`／`oil-gold-price-updates-d021fa`）。`scripts/spec-check.sh` 只比對 `origin/main`，對此類跨 worktree 撞號完全無感、照樣回報 `BLOCK: 0`。任務檔開頭已加避讓說明與改號時該動哪些檔的清單。merge 前已重新確認 `origin/main`（`e37803fc`）上 Task 335／Requirement 76 皆未被佔用，故保留原號。
