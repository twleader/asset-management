# [t337] 今日股市分析改由本機規則引擎產生，LLM 成本歸零且可隨時切回

**對應 Requirements:** Requirement 78（今日股市分析改由本機規則引擎產生——LLM 成本歸零，且隨時可切回）
**前置任務:** 無
**Liquibase changeset:** `v1.105.0-market-analysis-engine.sql`

---

## 背景

### 現在的行為

`backend/src/main/java/com/steven/assets/service/MarketAnalysisService.java`（1101 行）每次觸發「今日股市分析」時，走 `submitBatch(...)`：

1. 讀本地 DB 組成文字 prompt（`buildSystemPrompt` / `buildUserPrompt`）
2. 送 Anthropic **Batch API**：`model` 預設 `claude-opus-5`、`ThinkingConfigAdaptive`、`MAX_TOKENS = 16000`、`OutputConfig.effort` 預設 `medium`
3. 落 `daily_market_analysis` 狀態 `PROCESSING` ＋ `batch_id`，由 `pollPendingBatches()` 在批次 `ENDED` 後收尾、解析 JSON、落 `OK`、寄每日 email

觸發來源是 `MarketAnalysisScheduler` 每分鐘 tick 比對 `market_analysis_send_time` 表的**每個啟用時點各觸發一次**（`generateForSend`，強制重跑），亦即成本與「設了幾個寄送時段」成正比，且完全無人值守。

### 為什麼可以改成本機算

自 Task 179 移除 `web_search` server tool 後，這支功能的**輸入已經 100% 來自本地 DB**：

- `twse_index_daily_history` — 台股大盤日線
- `us_index_daily_history` — 美股 `DJI` / `SPX` / `IXIC` / `SOX`
- `news_headline` — 本地爬蟲新聞

程式把這些結構化數值拼成文字 prompt 送給模型，再把模型回的 JSON 讀回結構化欄位。其中**技術面與籌碼面本來就是算術**。尤其 `twse_institutional_daily` 已經存了 typed numeric 的三大法人買賣超，現行卻是轉成文字塞進 prompt 讓模型讀回來——本機直接算不但免費，而且更精準、可稽核。

### 會退化的部分（明示接受，不得在程式碼或畫面上粉飾）

LLM 目前提供兩件本機規則引擎做不到的事：

1. `summary` 的自然語言研判
2. 跨領域新聞因果推理（例如由「IBM 暴跌、軟體股 AI 獲利兌現」推導到台股半導體情緒）

本機版的 `summary` 由模板拼接、`newsHighlights` 由關鍵詞權重挑選，**只回答「哪幾則新聞最可能相關」，不回答「它為什麼影響台股」**。切換開關就是為了讓這個代價可被隨時撤回。

### ⚠ 規則權重是先驗設定，不是回測校準的結果

本任務的加權分數、門檻與訊號權重均由既有技術分析慣例直接設定，**未經任何樣本外回測驗證**。本專案雖已有回測基礎建設（`RadarBacktestExecution` / `RadarWalkForwardPlan` / `TradingRadarCalibrationSelector`），但將其套用於大盤日內方向預測是另一個量級的工作，**明確不在本任務範圍**。

**任何人不得將本引擎的 `confidence` 解讀為經驗勝率**——它衡量的是「本次各訊號彼此同向的程度」，不是「歷史上這樣的訊號組合有多常猜對」。此定性必須同時出現在 `LocalMarketAnalysisEngine` 的 class javadoc 與前端頁面的常駐說明文字，不得只寫在 spec。

---

## 要做什麼

### 資料庫現況（已於 2026-08-15 對運行中 DB 實查，直接照用，不要再去翻 `db/changelog/**`）

`market_analysis_setting`（單列，`CHECK (id = 1)`）目前欄位：

| Column | Type | Nullable | Default |
|---|---|---|---|
| `id` | integer | not null | 1 |
| `model` | varchar(64) | not null | `'claude-opus-5'` |
| `updated_at` | timestamp without time zone | not null | `now()` |
| `effort` | varchar(16) | not null | `'medium'` |
| `enabled` | boolean | not null | true |

**沒有 `engine` 欄位。** 運行中 `databasechangelog` 最新五筆為 `v1.104.0-realized-gain-export-schedule-multi-time`、`v1.103.0-commodity-export-schedule-multi-time`、`v1.102.0-export-schedule-multi-time`、`v1.101.0-radar-notification-action-policy-version`、`v1.100.0-radar-dividend-fetch-attempt`，與 `db.changelog-master.yaml` 尾端一致，故 **`v1.105.0` 未被佔用**。若實作當下重查發現已被其他 worktree 佔用，須連同檔名、changeset id 與本檔一次調整。

`twse_institutional_daily` 是 **append-only observation 表**（同一個 `trading_date` 會有多列，`observed_at` 不同）：

| Column | Type | 說明 |
|---|---|---|
| `trading_date` | date | |
| `foreign_net` | numeric(20,2) | 外資買賣超，**單位為「元」**（實測 2026-08-14 值為 `45351330421.00`，即 453.5 億） |
| `trust_net` | numeric(20,2) | 投信 |
| `dealer_net` | numeric(20,2) | 自營 |
| `total_net` | numeric(20,2) | 合計 |
| `observed_at` | timestamptz | not null |
| `status` | varchar(20) | not null；只有 `'AVAILABLE'` 的列可用 |

有 CHECK constraint `ck_twse_institutional_available_values`：`status <> 'AVAILABLE' OR (trading_date, foreign_net, trust_net, dealer_net, total_net, source_url 皆 NOT NULL)`。索引 `idx_twse_institutional_decision btree (trading_date DESC, observed_at DESC)`。

`twse_index_daily_history` 相關欄位：`trading_date` (date)、`close_point` (numeric(12,2), NOT NULL)、`trade_value` (numeric(20,0)，成交金額，單位為元；實測 2026-08-14 為 `1109746702006`)。

`us_index_daily_history`：`index_code` (varchar(16))、`trading_date` (date)、`close_point` (numeric(14,4), NOT NULL)。

- [x] **337.1 Liquibase changeset `v1.105.0-market-analysis-engine.sql`**：新增 `engine` 欄位並回填。須冪等，並在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` **最尾端**註冊（沿用既有 `- include: { file: db/changelog/changes/<檔名>, relativeToChangelogFile: false }` 格式）。

    ```sql
    ALTER TABLE market_analysis_setting ADD COLUMN IF NOT EXISTS engine VARCHAR(16) NOT NULL DEFAULT 'local';
    UPDATE market_analysis_setting SET engine = 'local' WHERE engine IS NULL OR engine = '';
    ```

    **既有列一律回填 `local`。** 若沿用 `llm`，部署後成本毫無變化，本任務就白做了。
    **不得**修改 changeset 內的任何字元（含註解）後又重跑——Liquibase checksum 含註解，改了會 `ValidationFailed` 導致 business crash loop。

- [x] **337.2 `MarketAnalysisSetting` entity 加 `engine` 欄位**：`backend/src/main/java/com/steven/assets/model/MarketAnalysisSetting.java` 新增 `@Column(name = "engine", length = 16, nullable = false) private String engine;`，比照既有 `model` / `effort` 的寫法。

- [x] **337.3 `MarketAnalysisService.resolveEngine()`**：範本是既有 `resolveEffort()`（`:218-223`）的三段式寫法（讀單列設定 → 白名單過濾 → 後備常數）。**不要照 `resolveModel()`**——後者（`:210-215`）只有 `.filter(m -> m != null && !m.isBlank())` 驗非空、**沒有白名單**：

    ```java
    /** 可選分析引擎白名單——技術白名單（非使用者可自訂之業務分類），比照 AVAILABLE_MODELS，不套用「Enum 必須入庫由 /api/settings 管理」規範。 */
    private static final List<MarketAnalysisSettingsDto.EngineOption> AVAILABLE_ENGINES = List.of(
            new MarketAnalysisSettingsDto.EngineOption("local", "本機規則引擎（免費）"),
            new MarketAnalysisSettingsDto.EngineOption("llm", "Claude（付費）")
    );
    private static final String DEFAULT_ENGINE = "local";
    ```

    `resolveEngine()` 後備值為 `local`。

- [x] **337.4 `MarketAnalysisSettingsDto` 加 `engine` 與 `availableEngines`**：現有 record 定義為

    ```java
    public record MarketAnalysisSettingsDto(
            String model, String effort, Boolean enabled,
            List<ModelOption> availableModels, List<EffortOption> availableEfforts,
            List<SendTime> sendTimes) { ... }
    ```

    改為在最前面加 `String engine`、在 `availableEfforts` 之後加 `List<EngineOption> availableEngines`，並新增巢狀 `public record EngineOption(String id, String label) {}`。所有建構呼叫端一併更新。

- [x] **337.5 `getSettings()` / `updateSettings(...)` 支援 engine**：`getSettings()` 沿用既有「現值若不在白名單則補入清單開頭」防呆（既有 `models` / `efforts` 兩處已有此寫法，照抄）。`updateSettings(String model, String effort, Boolean enabled, String engine)` 新增第四個參數，維持既有語意：**null 表示該欄不變、四者至少須提供一項**（既有訊息「未提供任何可更新的設定（model / effort / enabled）」須同步補上 engine）；不支援的 engine 值拋 `IllegalArgumentException("不支援的分析引擎：" + engine)`。新建列時比照既有寫法先以 `resolveEngine()` 補齊未指定欄，避免 NOT NULL 違反。`MarketAnalysisController.updateSettings(@RequestBody Map<String, Object> body)`（`backend/.../controller/MarketAnalysisController.java:75`）一併解析 `engine` 欄位。

- [x] **337.6 新增 `LocalMarketAnalysisEngine`（`backend/src/main/java/com/steven/assets/service/LocalMarketAnalysisEngine.java`）**

    **這是本任務的核心，邊界不得妥協：評分核心必須是純函式。**
    `MarketAnalysisService` 已 1101 行、職責已含批次送出／批次收尾／新聞消毒／發布日回抓，評分邏輯再併進去就無法在不啟 Spring context 下測試（CLAUDE.md 明列「業務邏輯必須能在不啟動 Spring context、不連資料庫的情況下單元測試」）。

    故本類別：
    - **不注入任何 Repository、不做 IO、不讀時鐘**
    - 接受已載入的值，回傳既有的 `com.steven.assets.dto.MarketAnalysisResult`（record 欄位為 `bias, confidence, summary, keyFactors, newsHighlights, twContext, usContext`；巢狀 `NewsHighlight(title, source, url, publishedAt)`）
    - 資料載入全部留在 `MarketAnalysisService`

    建議簽章（可調整，但純函式性質不可變）：

    ```java
    public MarketAnalysisResult evaluate(
            LocalDate analysisDate,
            List<double[]> taiexCloses,        // {epochDay, close} 由舊到新，來自既有 twseCloses()
            List<double[]> taiexTradeValues,   // {epochDay, tradeValue} 由舊到新，見 337.6b
            Map<String, List<double[]>> usCloses,   // key = DJI/SPX/IXIC/SOX，來自既有 usCloses()
            TechnicalIndicatorService.FullIndicators taiexIndicators,  // 既有大盤 MA/KD，見 337.7
            InstitutionalNet institutionalLatest,   // 可為 null（當日法人資料尚未產生）
            List<InstitutionalNet> institutionalRecent3,
            List<News> recentNews)
    ```

    `InstitutionalNet` 為**本任務新增**的傳輸用 record，宣告為 `LocalMarketAnalysisEngine` 的巢狀型別（全樹目前無同名型別）：

    ```java
    /** 法人買賣超的傳輸用值：金額單位為「元」（非億元），由 MarketAnalysisService 自 twse_institutional_daily 選列後傳入。 */
    public record InstitutionalNet(
            java.time.LocalDate tradingDate,
            java.math.BigDecimal foreignNet,
            java.math.BigDecimal trustNet,
            java.math.BigDecimal dealerNet,
            java.math.BigDecimal totalNet) {}
    ```

- [x] **337.6b `taiexTradeValues` 的載入方式**

    既有 `twseCloses()`（`MarketAnalysisService.java:744-753`）只回 `{epochDay, close}`，不含成交金額。於 `MarketAnalysisService` 新增一支**私有**載入方法回 `{epochDay, tradeValue}`，**重用既有的 repository 查詢** `twseRepo.findByTradingDateGreaterThanEqualOrderByTradingDateAsc(since)`（`twseCloses()` 用的同一支），**不得新增第二個 repository 查詢方法**。`trade_value` 可為 null（該列略過，量能訊號依 337.9 邊界不計分）。

    為避免兩次查詢，亦可把 `twseCloses()` 改為一次讀出後同時回收盤與成交金額——**若採此法，須確保 LLM 路徑用到的既有 `twseCloses()` 行為與輸出完全不變**。

- [x] **337.7 大盤 MA／KD 一律取自 `TechnicalIndicatorService`，不得重算**

    `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java` 已有大盤專用路徑 `computeAllForTaiex()`（private，由 `computeAll(stockCode, market)` 在 `isTaiex(...)` 為真時委派），回傳 `FullIndicators`：

    ```java
    public record FullIndicators(
            BigDecimal monthlyMa,    // MA20
            BigDecimal quarterlyMa,  // MA60
            BigDecimal annualMa,     // MA240
            BigDecimal k, BigDecimal d,
            BigDecimal previousK, BigDecimal previousD,
            BigDecimal weeklyMa,     // MA5（台股慣例的 5 個交易日）
            ExtendedIndicators extended)
    ```

    `TechnicalIndicatorService.java:338`（`private List<StockPriceHistory> taiexSeriesAsc(...)` 的**方法** javadoc，不是類別 javadoc）明載「**全站 KD 只有 `kdSeriesAsc` 這一份**」。在本引擎另寫第二份 MA／KD 即違反該既有約束，且會讓同一個大盤 KD 在交易雷達與本頁給出不同數字——正是 CLAUDE.md「同義欄位、同一 business service API」要防的情形。

    **但入口不可以是 `computeAll("0000", "台股")`——那會混用價基。**

    該路徑內部委派的 `computeAllForTaiex()`（`:546`，private）在最新日線不是今日時，會**從 Redis 取即時價合成一列今日 bar 併入序列**：

    ```java
    // TechnicalIndicatorService.java:551-563
    if (desc.isEmpty() || !today.equals(desc.get(0).getTradingDate())) {
        Optional<PriceQueryService.LivePrice> liveOpt = priceQuery.getLive("0000", "台股");
        … desc.add(0, t);   // 併入今日 live 合成列
    }
    ```

    而本機引擎的收盤與量能訊號取自 `MarketAnalysisService.twseCloses()`（`:744-753`），**只讀 `twse_index_daily_history`**——今日那列要等盤後 poller 才入庫。兩者相接的後果是：**盤中觸發時 MA／KD 已含今日即時價、而收盤仍是前一交易日**，同一份判斷混用兩個價基。本專案已有同類前例被登記在 `spec/design.md` 的觀察清單（Task 263「畫面同時呈現『今天的指標』與『昨天的股價』」）。

    而分析**確實可能在盤中觸發**：`MarketAnalysisScheduler` 是 `0 * * * * MON-FRI` 每分鐘 tick 比對使用者可自訂的多個 `market_analysis_send_time`；管理者也可隨時手動 `POST /api/market-analysis/generate`（本檔驗證段第 6 步就是）。

    **本任務指定的做法：呼叫既有的 `computeFromSeries(List<StockPriceHistory> series)`**（`TechnicalIndicatorService.java:176`）。它是 **package-private**，而 `MarketAnalysisService` 就在**同一個 package** `com.steven.assets.service`（兩檔第 1 行皆為 `package com.steven.assets.service;`），**可直接呼叫，無須修改任何可見性**。它的既有 javadoc（`:172-175`）正是為此而寫：

    > 對已準備好的降序 OHLC 序列使用同一套 MA／KD 核心。交易雷達會先還原權息再呼叫，**避免在此服務重複資料存取或混用價基**。

    餵入的序列為**純 DB 完成日序列**：把 `twse_index_daily_history` 映射成 `StockPriceHistory`（**降序**，最新在前）。映射邏輯**沿用既有的 private static `toRow(TwseIndexDailyHistory d, String stockCode, String market)`（`:327-334`，只填 `closePrice`／`highPrice`／`lowPrice`）**，將其可見性放寬為 package-private 即可——那是純欄位映射、不是計算入口，**不會**製造繞過 `isTaiex(...)` 的第二條計算路徑（這與放寬 `computeAllForTaiex()` 的性質完全不同）。

    如此同時滿足三件事：用同一份 KD 核心、價基與收盤同源、不動任何 public API。

    **不得**修改 `computeAllForTaiex()`（`:546`）的可見性、**不得**新增第二個 public 委派方法、**不得**在本引擎另寫 MA／KD。

    RSI／MACD／量能若既有實作只適用個股序列（`computeFromSeries(List<StockPriceHistory>)`），得於本引擎新增實作，但**須在註解指明其與 `TechnicalIndicatorService.ExtendedIndicators` 同名指標（`rsi5` / `rsi10` / `dif` / `macd` / `osc`）的參數是否一致**，避免日後被誤認為同一組值。

- [x] **337.8 法人籌碼的讀取與選列規則**

    `backend/src/main/java/com/steven/assets/repository/TwseInstitutionalDailyRepository.java` 目前**只有一個查詢方法**：

    ```java
    List<TwseInstitutionalDaily> findVisibleRange(LocalDate from, LocalDate to, Instant decisionInstant);
    // WHERE tradingDate BETWEEN :from AND :to AND observedAt <= :decisionInstant
    // ORDER BY tradingDate ASC, observedAt ASC
    ```

    **重用它，不得新增第二個查詢方法。** 選列規則須與 `TradingRadarMarketFeatureResolver.completeInstitutional`（`:582-594`）**同判準**，該方法的完整條件為 `status == "AVAILABLE"` 且 `tradingDate`、`observedAt`、`foreignNet`、`trustNet`、`dealerNet`、`totalNet`、`provider`、`sourceUrl` **八個欄位皆非 null**：
    - 只採符合上述八欄完整條件的列
    - 另加一條與該既有方法相同的守門：`tradingDate` **不得晚於** `analysisDate`（`completeInstitutional:586` 的 `!row.getTradingDate().isAfter(target)`）
    - 同一個 `trading_date` 有多列時，**取 `observedAt` 最大的那列**（`findVisibleRange` 已按 `observedAt ASC` 排序，故同日取最後一筆）
    - 取最新交易日 ＋ 往前三個交易日的累計
    - **`findVisibleRange` 的三個參數寫死如下**：`from` = `analysisDate.minusDays(14)`（足以涵蓋三個交易日 ＋ 連假）、`to` = `analysisDate`、`decisionInstant` = `Instant.now()`。`decisionInstant` 的語意是「此刻可見的 observation」，取 `now()` 即代表不做 point-in-time 回溯（本功能是產生「今天」的判斷，非回測）；此選擇須在程式碼註解寫明，避免日後被誤改成交易日邊界而改變可見列

    **嚴禁**由 `news_headline` 的 title／summary 反向解析法人數值——`TwseInstitutionalDaily` 的 class javadoc 明文禁止（「不得由 `news_headline` 的 title／summary 反向解析」）。

    **單位換算務必正確**：`foreign_net` 等欄位單位為**元**。`keyFactors` 顯示為「億元」時須除以 1e8。實測 2026-08-14 `foreign_net = 45351330421.00` → 顯示「453.5 億」。寫錯會差 8 個數量級。

- [x] **337.9 評分規則：每個訊號都要能對應到一條 `keyFactors`**

    加權總分由下列訊號累加，每個訊號回傳 `[-2, +2]` 的方向分 ＋ 一個具名常數權重。**命中非零方向分者必須在 `keyFactors` 產生一條含實際數值的中文敘述**——這是本引擎相對 LLM 的核心優勢（可稽核），不得只輸出結論。

    | 面向 | 訊號 | 來源 |
    |---|---|---|
    | 台股技術面 | (a) 收盤對 MA5／MA20／MA60／MA240 的位置與均線排列 | `taiexCloses` ＋ `FullIndicators` |
    | | (b) K／D 值與金叉／死叉（用 `k`/`d`/`previousK`/`previousD`） | `FullIndicators` |
    | | (c) MACD OSC 方向 | 本引擎計算（見 337.7 註解要求） |
    | | (d) RSI 超買超賣 | 同上 |
    | | (e) 量能：當日 `trade_value` 對近 20 交易日均值的比值 | `taiexTradeValues` |
    | 美股連動 | `SOX`／`IXIC`／`SPX` 最新交易日對前一交易日漲跌幅 | `usCloses` |
    | 籌碼面 | 外資最新日 ＋ 近三日累計買賣超 | `institutionalLatest` / `institutionalRecent3` |

    **權重約束（須在程式碼註解寫明理由）：**
    - `SOX` 權重須高於其餘美股指數——台股半導體權值占比高，既有 prompt 已將費半列為關鍵輸入
    - 外資權重須高於投信與自營——既有 prompt 已將「外資、自營商連日賣超」列為關鍵因素

    **邊界一（資料不足）：** 任一訊號因資料不足（暖機不足、當日法人資料尚未產生、序列為空）而無法計算時，該訊號**不計分也不進 `keyFactors`**。**不得以 0 分或 null 當作中性值混入**——那會讓「沒有資料」與「訊號中性」在總分上不可分辨。

    **邊界二（價基一致性，見 337.7）：** (a)(b)(c)(d)(e) 五個台股訊號所用的收盤、量能與 MA／KD **必須來自同一份完成日序列**。實作上這由 337.7 指定的 `computeFromSeries(純 DB 降序序列)` 保證——**不得**一邊用會併入 Redis live 的 `computeAll(...)` 算 MA／KD、一邊用只讀 DB 的 `twseCloses()` 取收盤，那在盤中觸發時會產生「今天的指標配昨天的股價」。此約束須在程式碼註解寫明理由。

- [x] **337.10 `bias` 與 `confidence` 的產生規則**

    - 加權總分正規化到 `[-100, +100]`，以**對稱門檻**決定 `bias`（`BULLISH` / `BEARISH` / `NEUTRAL`）。門檻為具名常數，不得散落魔術數字。
    - `confidence` **不得由總分絕對值直接換算**——那會讓「單一極端訊號」與「多訊號一致」得到相同信心。須為：
      「實際參與計分的訊號中，方向與最終 `bias` 相同者的加權占比」× 「實際參與計分訊號數 ÷ 訊號總數」（資料完整度折減）
    - 值域維持 `[0, 100]` 整數，沿用既有 `clampConfidence`

- [x] **337.11 `newsHighlights`：排序挑選，不做語意理解，不套 `sanitizeNews()`**

    從既有 `fetchRecentLocalNews(date)` 的結果中挑 **3～6 則**，排序鍵依序：

    1. **非 `news` category 的量化快照優先**。`com.steven.assets.model.News` 的既有常數為 `CATEGORY_NEWS = "news"`、`CATEGORY_TWSE_INSTITUTIONAL = "twse-institutional"`、`CATEGORY_TWSE_TURNOVER = "twse-turnover"`；另有以字串形式存在的 `fx`／`us-market`／`kr-market`／`kr-intraday`／`ma-cross`。判準為 `category != null && !"news".equals(category)`，與既有 `buildLocalNewsBlock()` 的分桶條件完全相同（該方法讓 TWSE 量化資訊排在一般新聞之前，理由是「數量少但訊號最強」）。
    2. 標題命中財經關鍵詞的權重
    3. `published_at` 由新到舊

    `title` / `source` / `url` / `publishedAt` **一律照 `news_headline` 原樣填**（`publishedAt` 為 `Instant`，轉 `Asia/Taipei` 的 `LocalDate` 後取 `toString()`，與既有 `appendLocalNews` 的做法一致）。

    **不得套用 `sanitizeNews()`。** 該方法的三層防護（精確日期驗證、回抓原文校正發布日、中港澳地區封鎖）針對的是**模型自報**的不可信輸出；本路徑資料直接來自本地爬蟲、發布日已由 producer 驗證過。既有註解已載明本地新聞「來源可信、發布日精準，直接餵入（不經 `sanitizeNews`；那層只處理模型輸出）」——該註解實際位於 `MarketAnalysisService.java:674`，即 `buildUserPrompt` 內呼叫 `buildLocalNewsBlock` 之前，**不是** `buildLocalNewsBlock` 自身的 javadoc。

    **惟 `url` 仍須經既有 `safeHttpUrl()` 只留 http(s)**——那是前端 `<a href>` XSS 的防禦縱深，與資料是否可信無關。

- [x] **337.12 `summary` / `twContext` / `usContext` 模板拼接，且須自我標示為機器產生**

    三個欄位由命中的訊號組出中文敘述（例如「台股收盤 45,811.01 點，位於季線之上、月線之下；KD 於 K=62.3／D=58.1 呈黃金交叉；外資近三日累計買超 453.5 億元」）。

    `summary` **開頭或結尾須明確標示本次判斷由本機規則引擎產生、非 LLM 研判**——使歷史列表與每日 email 在純文字閱讀時即可分辨兩種來源，不必回查 `model` 欄位。

- [x] **337.13 `generateInternal` 分岔：本機路徑同步、不經 `PROCESSING`**

    現有簽章 `private DailyMarketAnalysis generateInternal(LocalDate date, String trigger, boolean skipIfAlreadyOk, boolean resetEmailSent)`，內部已用 `generateLock`（`ReentrantLock`）序列化，並有兩道守門：`PROCESSING` 即略過、`skipIfAlreadyOk && STATUS_OK` 即略過。

    **這兩道守門與 `generateLock` 對兩條路徑一致適用**，不得只在 LLM 路徑保留。守門之後依 `resolveEngine()` 分岔：

    - `"local"` → 新增 `generateLocal(date, trigger, existing, resetEmailSent)`：載入資料 → 呼叫引擎 → **`applyLocalResult(row, result)`（見 337.13b，不得用既有 `applyResult`）** → 直接落 `STATUS_OK` → 寄信判斷 → `save`
    - `"llm"` → 既有 `submitBatch(...)`，**一行不改**

    本機路徑：
    - **不設 `batch_id`**（維持 null），不產生 `PROCESSING` 列，故 `pollPendingBatches()` 永遠撈不到它——這是正確的，因為沒有批次要收尾
    - 引擎拋例外時落 `STATUS_FAILED` ＋ `errorMessage`（沿用既有 `truncate(msg, 1000)`），**不得往外拋**，維持既有「不中斷排程／手動觸發」契約
    - 沿用既有 `clearContent(row)`（重跑既有 OK 列時先清上一次內容）與 `resetEmailSent` 語意

- [x] **337.13b 本機路徑不得呼叫既有 `applyResult(...)`——它內含 `sanitizeNews()`，會在號稱零外部呼叫的路徑上發 outbound HTTP**

    既有 `applyResult(row, r)`（`MarketAnalysisService.java:840-850`）最後一行是：

    ```java
    row.setNewsHighlights(objectMapper.writeValueAsString(
            sanitizeNews(r.newsHighlights(), row.getAnalysisDate())));   // :848-849
    ```

    而 `sanitizeNews()`（`:866-914`）在第 `:898-903` 行對**不在** `TRUSTED_LOCAL_NEWS_HOSTS`（`:947-948`，只有 `twse.com.tw`／`wantgoo.com`／`moneydj.com`／`ltn.com.tw`／`udn.com`）的連結呼叫 `fetchPublishedDate(url)` **回抓原文網頁**。而 `fx`／`us-market`／`kr-market`／`kr-intraday` 這幾類量化快照的來源網域**不在該白名單內**。

    照既有 `applyResult` 實作的後果有三個，每一個都會使本任務失敗：
    1. 337.11 明文禁止的 `sanitizeNews` 一定會執行
    2. 337.20(g) 的欄位對應斷言必定失敗（`:910-911` 會用 `date.toString()` 覆寫 `publishedAt`、用 `safeHttpUrl` 覆寫 `url`，且可能整筆剔除）
    3. 一條被宣稱「100% 本地 DB、零外部呼叫」的路徑會偷偷發 outbound HTTP——這會直接讓本檔驗證段第 7 步（斷言日誌無外部呼叫）失去意義

    **本任務指定的做法**：新增 `applyLocalResult(DailyMarketAnalysis row, MarketAnalysisResult r)`，內容與 `applyResult` 相同（`normalizeBias`／`clampConfidence`／`summary`／`twContext`／`usContext`／`keyFactors` 序列化皆照舊），**惟 `newsHighlights` 直接 `objectMapper.writeValueAsString(r.newsHighlights())`、不過 `sanitizeNews`**。既有 `applyResult` 保持原樣供 LLM 路徑使用，**一行不改**。

- [x] **337.14 `model` 欄位寫 `local-rule-engine:v1`**

    本機路徑將 `daily_market_analysis.model` 設為 `local-rule-engine:v1`（沿用既有 `truncate(model, 64)`）。

    **規則權重或門檻日後調整時須提升此版本號**，理由與 `TradingRadarRuleEngine.RULE_VERSION` 相同：否則歷史紀錄無法分辨是哪一版規則產生的判斷。前端歷史列表既有的模型顯示欄位因而自然顯示引擎來源，無須新增欄位。

- [x] **337.15 `ANTHROPIC_API_KEY` 未設定時，`local` 引擎必須正常運作**

    現行 `submitBatch` 在 `apiKey == null || apiKey.isBlank()` 時落 `STATUS_NOT_CONFIGURED`。**本機路徑不得檢查金鑰。**

    這是本任務最實際的驗收點之一：拔掉金鑰後今日股市分析仍應每日正常產出。`NOT_CONFIGURED` 只在 `engine=llm` 且無金鑰時出現。

- [x] **337.16 email 與排程行為完全不變**

    `MarketAnalysisEmailDispatcher.dispatchDaily`、`email_sent_at` 冪等記號、`market_analysis_send_time` 多時段各跑一次各寄一封、`MarketAnalysisScheduler` 每分鐘 tick 與交易日閘門——全部維持既有語意，**不得為本任務修改**。

    寄信時機在本機路徑由「批次收尾時」變為「產生當下」，但**判斷條件三項完全不變**：
    1. `DailyMarketAnalysis.STATUS_OK.equals(row.getStatus())`
    2. `row.getEmailSentAt() == null`
    3. `marketDataService.isTwTradingDay(date)`

    第 3 項（颱風假／臨時休市的第二次交易日驗證）在本機路徑仍須保留。雖然本機路徑不存在「送出後、收尾前」的時間差，但保留可確保兩條路徑寄信條件完全一致、日後不會因單邊修改而分歧。

- [x] **337.17 前端：`TodayMarketAnalysisView.vue` 新增引擎下拉**

    檔案：`frontend/src/views/TodayMarketAnalysisView.vue`

    於既有「分析模型」「思考深度」兩個下拉**之前**新增「分析引擎」下拉，選項為 `本機規則引擎（免費）` / `Claude（付費）`（由後端 `availableEngines` 提供，不在前端寫死）。

    - 選 `local` 時，「分析模型」與「思考深度」兩個下拉須**停用（`disabled`）而非隱藏**——它們仍是 `llm` 模式的有效設定，隱藏會讓使用者以為設定遺失
    - 頁面須有一段**常駐說明**，載明本機引擎的 `confidence` 為訊號一致性、非經回測驗證的勝率（與背景段的 ⚠ 定性一致）
    - 切換引擎沿用既有 `PUT /api/bff/today-market-analysis/settings`（ADMIN 權限），**不新增端點**

    > **前端變更依 CLAUDE.md 規定由固定模型的 subagent 執行**（Claude Code：`sonnet 5` / `high`）。

- [x] **337.17b 完成回饋必須依實際 `status` 分支，不得沿用非同步文案**

    `frontend/src/views/TodayMarketAnalysisView.vue` 的 `regenerate()`（約 `:523-536`）目前**無條件**顯示：

    ```js
    ElMessage.success('已送出分析（批次處理中，完成後自動更新）')
    ```

    這對 `local` 路徑是**錯誤敘述**——本機引擎同步完成，沒有任何批次。須改為依 `bffApi.todayMarketAnalysis.generate()` 的回傳 `status` 分支：
    `OK` → 完成類文案（例如「已完成本機分析」）；`PROCESSING` → 維持既有批次文案；`FAILED` → 錯誤；`NOT_CONFIGURED` → 警告。

    **射程涵蓋畫面上所有非同步文案，不只 toast**：兩支 view 的 `v-loading` 覆蓋層文案（`element-loading-text`）同樣寫死——`TodayMarketAnalysisView.vue:2` 的 `'送出批次分析中…'` 與 `AssetAllocationAdviceView.vue:2` 的 `'AI 產生配置建議中（可能需數十秒）…'`——且在 `local` 路徑**確實會顯示**（前者 `regenerate():524-525` 同時設 `generating` 與 `loading`；後者 `generate():803` 走非 silent 的 `load()`，`load():691` 設 `loading`）。兩者皆須改為不預設批次／AI 的中性敘述（或依當前引擎切換）。

    **順帶更正兩處已成假斷言的既有註解**：`frontend/src/api/index.js:329`（「產生建議（非同步）：立即回一筆 PROCESSING」）與 `backend/src/main/java/com/steven/assets/controller/MarketAnalysisController.java:57`（「非同步；送出 Batch 後立即回傳 PROCESSING 列」）對預設的 `local` 檔位皆為假，改為「`local` 同步回終態；`hybrid`／`llm` 回 `PROCESSING` 後輪詢」。

- [x] **337.18 BFF 契約不變**

    `TodayMarketAnalysisBffController` 既有 `Mono.zip` 聚合（`{today, history, settings}`）維持原形狀，`settings` 內容因 DTO 新增欄位而自然增長。既有 `SecurityConfig` 對 `/api/bff/today-market-analysis/**` 的 ADMIN 規則不變。**本任務不新增任何 BFF endpoint。**

- [x] **337.19 不新增 `@Scheduled`**

    本任務只改既有觸發路徑的分岔，不新增任何排程。故 `SchedulePublicBffController.JOBS` 筆數不變，`SchedulePublicBffControllerTest` 的 `hasSize` 斷言不需更動。

- [x] **337.20 測試**

    本專案目前**沒有** `MarketAnalysisService` 的既有測試類（`backend/src/test/java/com/steven/assets/service/` 下無對應檔案），本任務須新建。至少涵蓋：

    - (a) 引擎純函式在固定輸入下產出確定的 `bias` / `confidence` / `keyFactors`——**測試不得啟動 Spring context、不得連 DB**（這同時證明 337.6 的可測試性約束真的成立）
    - (b) 訊號因資料不足而缺席時不計分、不進 `keyFactors`，且 `confidence` 因完整度折減而下降
    - (c) 單一極端訊號的 `confidence` **低於**多訊號一致的 `confidence`（證明未由總分絕對值直接換算）
    - (d) `engine=local` 且 `apiKey` 為空字串時仍落 `STATUS_OK`、不落 `NOT_CONFIGURED`，且全程**零 Anthropic client 互動**（以替身斷言）
    - (e) `engine=llm` 時既有批次路徑行為不回歸
    - (f) `engine` 白名單驗證與 `updateSettings` 的「null 不變」語意
    - (g) 本機路徑產出的 `newsHighlights` 與來源 `news_headline` 的對應關係為：`title`／`source` **逐字相等**、`url` 等於 `safeHttpUrl(來源 url)`、`publishedAt` 等於 `來源 publishedAt.atZone(Asia/Taipei).toLocalDate().toString()`（**不得**斷言四欄全部逐字相等——後兩欄依 337.11 本就有規定的轉換），且未經 `sanitizeNews()` 的日期覆寫或整筆剔除；另斷言量化快照 category 排在一般新聞之前
    - (h) 本機路徑寄信條件與 LLM 路徑一致（非交易日不寄、`email_sent_at` 已設不重寄）
    - (i) 大盤 MA／KD 取自 `TechnicalIndicatorService.computeFromSeries(...)` 這一份既有核心（證明未複製第二份實作）。**不得**斷言「與交易雷達讀到的數值恆等」——交易雷達走的是會併入 Redis live 合成列的 `computeAll(TAIEX_CODE, TW_MARKET)`，盤中兩者價基本就不同，那樣斷言會是假的。正確斷言是「餵入相同序列時，本路徑與 `computeFromSeries` 輸出相同」
    - (j) **價基一致性**：以「最新 DB 日線為前一交易日、Redis 有今日即時價」的情境驅動，斷言本機引擎用到的 MA／KD 與收盤／量能來自**同一份完成日序列**（即 MA／KD 不含今日 live 合成列）

    `TechnicalIndicatorService` 的既有測試不得因本次改動而失敗。

    > **Mockito on Java 21/25 注意**：跑測試時用 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`，**不要用 `-DargLine`**（會覆蓋掉專案的時區設定，導致大量測試 error，且錯誤訊息會偽裝成 byte-buddy 問題）。

---

## 驗證

```bash
# 1. 後端測試（注意用 extraArgLine，不是 argLine）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 2. 前端 build
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build
```

```bash
# 3. 重建映像（JVM service 一律 --no-cache，否則 cached build 可能不含變更）
cp /Users/steven/Project/asset-management/.env . 2>/dev/null; \
docker compose -p asset-management build --no-cache business-services
```

```bash
# 4. recreate（business 換 IP 後 BFF 會握舊 IP 回 500 且不自癒，故一併 restart bff）
docker compose -p asset-management up -d --no-deps --force-recreate business-services && \
sleep 20 && docker compose -p asset-management restart bff
```

```bash
# 5. 確認 changeset 已套用
docker exec asset-postgres psql -U assets -d assets \
  -c "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 2" \
  -c "\d market_analysis_setting"
```

```bash
# 6. 實機驗證：在 business 容器內以 X-User-* header 模擬管理者觸發分析（免 Google OAuth）
docker exec asset-business-services curl -s -X POST \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/market-analysis/generate
```

驗收斷言：回傳 `status` 為 `OK`、`model` 為 `local-rule-engine:v1`、`bias` 非 `UNKNOWN`、`keyFactors` 為非空陣列。

```bash
# 7. 斷言日誌中沒有任何 Anthropic 批次送出紀錄
docker logs --tail 200 asset-business-services 2>&1 | grep -i "批次已送出\|batches" || echo "PASS: 無批次送出"
```

---

## 完成報告

### 後端（337.1–337.16、337.18–337.20）已完成

**新增檔案**

| 檔案 | 內容 |
|---|---|
| `backend/src/main/resources/db/changelog/changes/v1.105.0-market-analysis-engine.sql` | `engine` 欄（`VARCHAR(16) NOT NULL DEFAULT 'local'`）＋回填 `local`；冪等 |
| `backend/src/main/java/com/steven/assets/service/LocalMarketAnalysisEngine.java` | 純函式評分核心（12 個訊號、具名權重／門檻、`InstitutionalNet` 巢狀 record、`RULE_VERSION`） |
| `backend/src/test/java/com/steven/assets/service/LocalMarketAnalysisEngineTest.java` | 12 個純函式測試（不啟 Spring context、不連 DB） |
| `backend/src/test/java/com/steven/assets/service/MarketAnalysisServiceLocalEngineTest.java` | 19 個服務層測試（引擎分岔、寄信條件、價基一致性、籌碼選列） |

**修改檔案**

| 檔案 | 變更 |
|---|---|
| `backend/src/main/resources/db/changelog/db.changelog-master.yaml` | 尾端註冊 v1.105.0 |
| `backend/src/main/java/com/steven/assets/model/MarketAnalysisSetting.java` | 新增 `engine` 欄位 |
| `backend/src/main/java/com/steven/assets/dto/MarketAnalysisSettingsDto.java` | 新增 `engine`／`availableEngines` ＋巢狀 `EngineOption` |
| `backend/src/main/java/com/steven/assets/service/MarketAnalysisService.java` | `AVAILABLE_ENGINES`／`resolveEngine()`／`getSettings()`／4 參數 `updateSettings(...)`／`generateInternal` 分岔／`generateLocal(...)`／`applyLocalResult(...)`／`institutionalRecent(...)`／`twseRows`＋`closesOf`＋`tradeValuesOf`＋`taiexSeriesDesc`；`safeHttpUrl` 放寬為 package-private static |
| `backend/src/main/java/com/steven/assets/controller/MarketAnalysisController.java` | `updateSettings` 解析 `engine` |
| `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java` | `toRow(TwseIndexDailyHistory,…)` 放寬為 package-private（純欄位映射，非計算入口） |

**驗證**：`mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true`
→ `Tests run: 1049, Failures: 0, Errors: 0, Skipped: 0`（實作前基準線為 1018，新增 31 個）。

### 與原計畫的偏差

1. **本機路徑的日線視窗為 2 年後取尾端 240 筆**，非 LLM 路徑的 `date.minusYears(1)`。
   理由：MA240 需要 240 個交易日暖機，一年（約 245 個交易日）沒有餘裕；240 筆亦與
   `computeAllForTaiex()` 的既有視窗一致。`twseCloses()` 本身的簽章與輸出<b>完全未變</b>，
   LLM 路徑行為不受影響（337.6b 允許此作法）。
2. **籌碼面除外資外另加投信／自營兩個訊號**（權重 0.6／0.4）。
   337.9 的權重約束明文要求「外資權重須高於投信與自營」，該約束必須有對應訊號才成立。
3. **337.9(c) MACD 由本引擎以收盤價為價基自算**，與
   `TechnicalIndicatorService.ExtendedIndicators.osc`（DI ＝ (H+L+2C)/4 價基）<b>參數不一致</b>，
   已依 337.7 要求在 `macdSignal` 的 javadoc 寫明；RSI10 則與既有 `rsi10` 參數完全相同
   （收盤價基、Wilder 平滑、10 期），亦已註明。
4. **337.17（前端）未做**——依派工範圍由專責前端的 subagent 處理，checkbox 保持未勾。
