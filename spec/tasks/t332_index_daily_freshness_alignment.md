# [t332] 海外指數日線的「新鮮度」判準與交易雷達同源

**對應 Requirements:** Requirement 75（海外指數日線的「新鮮度」判準與交易雷達同源——不得在真的落後一盤時
回報正常，以免美股列的 `regime` 與 `market_volume_turnover` 整組轉 stale 卻無人察覺）
**前置任務:** 無
**Liquibase changeset:** 無（不動 schema）

## 背景

### 現在的錯誤行為（t324.4 已登記的實測）

2026-08-13 21:58 Asia/Taipei（＝ET 09:58，8/12 那盤早已收盤）實測：

- `us_index_daily_history` 的 IXIC `max(trading_date)` 仍為 **2026-08-11**，缺 8/12。
- 同一時刻 `IndexDailyRefreshScheduler` 的開機自癒卻明確輸出
  **「self-heal：海外指數日線皆為最新，略過」**。

該排程為 `@Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")`，
台北 8/13 07:00 ＝ ET 8/12 19:00，**在 8/12 收盤（16:00 ET）之後**，理應已能取得 8/12 的日線。

### 成因：兩把不同的尺

`backend/src/main/java/com/steven/assets/service/IndexDailyRefreshScheduler.java`：

```java
/** 最新日線超過此天數即視為過時（容忍週末 + 1 個假日）。 */
private static final int STALE_DAYS = 4;                                    // 第 40 行
...
LocalDate staleBefore = LocalDate.now().minusDays(STALE_DAYS);              // 第 58 行
boolean anyStale = MacroHistoryService.OVERSEAS_INDEX_CODES.stream()
        .anyMatch(code -> latestTradingDate(code)
                .map(d -> d.isBefore(staleBefore)).orElse(true));           // 第 59–61 行
```

`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`：

```java
private LocalDate mostRecentCompletedUsTradingDay(Instant decisionInstant) {   // 第 357 行，private
    ZonedDateTime nowNy = decisionInstant.atZone(NEW_YORK);
    LocalDate day = nowNy.toLocalTime().isBefore(US_MARKET_CLOSE)
            ? nowNy.toLocalDate().minusDays(1)
            : nowNy.toLocalDate();
    for (int i = 0; i < 14; i++) {
        if (marketDataService.isTradingDay(US_MARKET, day)) return day;
        day = day.minusDays(1);
    }
    return day;
}
...
LocalDate mostRecentCompleted = mostRecentCompletedUsTradingDay(decisionInstant);   // 第 704 行
boolean stale = latestEodDate == null || latestEodDate.isBefore(mostRecentCompleted);
```

| 判斷者 | 判準 | 「落後 1～3 盤」時的結論 |
|---|---|---|
| `IndexDailyRefreshScheduler` | 落後**超過 4 個日曆天**才算過時 | 正常 |
| `TradingRadarService`（美股大盤） | **落後一盤即** stale | stale |

連帶效果：美股列的 `regime`（權重 `.70`）與 `market_volume_turnover`（權重 `.30`）整組轉 stale，
**美股短期信心度由 89 掉到 63**（Task 323 完成報告的前後比對實測值）。

### 正確行為

對「美股指數日線是不是最新」，全系統只有一個答案：落後一盤即算落後，自癒與排程都要據此判斷、
真的落後時要留下可查的 WARN，並在同一交易日內還有第二次補救機會。

### 明確不得做的事

- **不得只把自癒門檻放寬。** t324.4 逐字寫明：放寬只會讓它更常宣稱正常，不會讓資料變新。
  本任務的方向是**收緊自癒判準去對齊雷達**，不是反向對齊。
- **不得**改交易雷達的 stale 判定、任何規則參數、權重或 `TradingRadarRuleEngine.RULE_VERSION`。
- **不得**改 `MacroHistoryService.refreshUsIndexDaily(code)` 的回補行為本身。
- **不得**把美股逐盤判準套到非美股指數上（理由見 332.3）。

## 要做什麼

- [ ] **332.1 spec**（本檔 ＋ `requirements.md` Requirement 75 ＋ `design.md`「海外指數日線新鮮度判準與
      交易雷達同源」段 ＋ `tasks.md` 索引）。已隨本檔一併完成。

- [ ] **332.2 把「最近一個已完成美股交易日」提升為單一共用實作。**
      現行 `TradingRadarService.mostRecentCompletedUsTradingDay(Instant)`（第 357 行）為 private。
      將該邏輯搬到兩邊都能呼叫的具名位置（建議放 `MarketDataService`，該類別已持有交易日曆），並：
  - **`IndexDailyRefreshScheduler` 目前並未注入 `MarketDataService`**——實測其欄位只有
    `private final MacroHistoryService macroHistoryService;` 與
    `private final UsIndexDailyHistoryRepository usDailyRepo;`（`TradingRadarService` 才已持有
    `marketDataService`）。故本項須替該排程**新增這個依賴**（`@RequiredArgsConstructor` 加一個 final 欄位），
    並確認不產生循環依賴（`MarketDataService` 不得反過來依賴 `IndexDailyRefreshScheduler`）。
  - `TradingRadarService` 改為**委派**給共用方法，**行為必須完全不變**（含美東收盤時刻界定「已完成」、
    收盤前退回前一日、往回找上限 14 天這三點）。原方法的 Javadoc（說明 IXIC 大盤不像台股組有 Redis
    即時價可退回判斷、故直接用美東收盤時刻界定「已完成」）須一併搬移，不得遺失。
  - `US_MARKET_CLOSE` 與 `NEW_YORK` 兩個常數若隨之搬移，須確認 `TradingRadarService` 其餘用到它們的
    地方一併調整、編譯通過。
  - **不得各自複製一份邏輯**——本任務要解決的正是「兩處對『該有哪一天』的認定不同源」。

- [ ] **332.3 自癒改用逐盤判準，但只對美股指數。**
      `IndexDailyRefreshScheduler.selfHealStaleOnStartup()` 的 stale 判斷改為分兩類：
  - **美股指數**：`IXIC`／`SPX`／`DJI`／`SOX`（`MacroHistoryService.OVERSEAS_INDEX_CODES` 中的美股者）
    ＋ `MacroHistoryService.TOTAL_RETURN_US_INDEX_CODES`（SP500TR）→
    `latestTradingDate(code) < mostRecentCompletedUsTradingDay(Instant.now())` 即過時（查無列亦為過時）。
  - **非美股指數**：`N225`／`KOSPI`／`FTSE`／`DAX` → **維持既有 `STALE_DAYS = 4` 的日曆天容忍**。
  - **判準分岔的理由必須寫在程式碼註解裡，而且要寫可查證的版本**（否則日後會被「統一一下比較乾淨」改掉）：
    交易雷達只讀 IXIC；本專案的交易日曆涵蓋**台、美、英三個市場**——實測
    `MarketDataService.isTradingDay(String market, LocalDate date)` 為
    `if ("美股".equals(market)) return isUsTradingDay(date); if ("英股".equals(market)) return isUkTradingDay(date); return isTwTradingDay(date);`，
    且 `getUkHolidays(int year)` 是完整維護的 LSE／英國銀行假日表（含順移邏輯）。
    故正確理由是：**N225／KOSPI／DAX 無對應日曆**，套用逐盤判準會因當地假日恆判過時、每次啟動全量回補；
    **FTSE 雖有英股日曆可用，但雷達不讀它、且其發布時點與美股不同**，本任務一併沿用 4 日容忍、不擴大改動面。
    **不得**在註解寫「非美股市場都沒有交易日曆」——那與 `isTradingDay` 的實作不符。
  - 美股指數清單須以具名常數表達，**不得**用「code 不在某個 hardcode 字串陣列裡就當非美股」這類
    反向判斷；新增指數時要能一眼看出它屬於哪一類。
  - 既有的「延遲 30 秒等依賴就緒、跑在具名背景執行緒、例外只記 WARN 不外擲」三個行為維持不變。

- [ ] **332.4 排程跑完要驗收，落後要留下可查的日誌。**
      既有 `scheduledRefreshAll()`（`0 0 7 * * TUE-SAT`）在 `refreshAll(...)` 完成後，
      須再檢查一次美股指數是否已追上 `mostRecentCompletedUsTradingDay`：
  - 未追上時以 **WARN** 記錄「哪一個指數／它目前的最新交易日／應該要有哪一天」，
    每個落後的指數各記一行（或一行含完整清單，但三項資訊都要在）。
  - 已追上時維持既有 INFO 收尾行即可，不新增噪音。
  - 原始事故正是「回補宣稱成功、資料其實沒進來、日誌看不出異常」——本項就是為了讓下次能查得到。

- [ ] **332.5 同一交易日內的補救檢查。**
      新增 `@Scheduled(cron = "0 0 9,12 * * TUE-SAT", zone = "Asia/Taipei")`
      （台北 09:00 與 12:00，涵蓋「07:00 時來源尚未發布當日 bar」的情形）：
  - **只有在美股指數確實落後 `mostRecentCompletedUsTradingDay` 時才呼叫 `refreshAll(...)`**；
    已追上就直接返回、不呼叫任何外部來源，並以 DEBUG／不記錄的方式帶過（不得每次都印 INFO 洗版）。
  - 觸發時以 INFO 記錄觸發原因（哪個指數落後、目前最新、應該要有哪一天）。
  - **不得**改成每分鐘輪詢，**不得**在追上後仍反覆呼叫外部來源。
  - `refreshAll(...)` 的 tag 參數傳入可辨識的字串（例如 `"gap-check"`），讓日誌能區分是哪一條路徑觸發的。
  - **不得放大外部請求量。** 現行 `refreshAll(String)` 對 `OVERSEAS_INDEX_CODES` 8 檔 ＋
    `TOTAL_RETURN_US_INDEX_CODES` 1 檔**全部**呼叫 `refreshUsIndexDaily`（Yahoo `range=10y`）。
    判準由 4 日容忍收緊為逐盤後，觸發頻率會明顯上升（凌晨重啟、來源延遲發布時的兩次補救檢查都可能觸發），
    若沿用全量回補，等於每次都對 Yahoo 打 9 次 10 年請求。故**自癒（332.3）與補救檢查（本項）觸發時，
    只回補判定為落後的那幾檔美股指數**——須為此新增一個「只補指定 code 清單」的路徑
    （既有 `refreshAll(String)` 保留給每日 07:00 全量回補，不得改動其語意）。
    既有的指數間 `Thread.sleep(500)` 禮貌間隔不得移除。TWSE 報酬指數增量與含息報酬指數收尾步驟
    只在全量回補時執行，落後補救不重複跑。

- [ ] **332.6 排程列表頁同步，筆數要連同 t334 一起算（只加自己的 +1 會漂移）。**
      332.5 新增了一個 `@Scheduled` annotation（在 **business-services**），而同一分支的
      `t334_us_valuation_history_derivation.md` 的 334.4 也新增一個（在 **external-materials-service**）。
      現況為 **51 筆 ＝ business 20 ＋ external 31**，兩者都落地後應為
      **business 21 ／ external 32 ／總計 53**。須同步更新的位置（實測共 4 個檔案 9 處）：
  - `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`
    的 `JOBS` 清單本體，以及 `:13`（類別 Javadoc「business-services（20 個）與
    external-materials-service（31 個）」）、`:53` 與 `:217`（皆為「全系統排程清單（51 筆）」字樣）、
    `:55`（`// ===== business-services（20）=====` 分隔註解）、
    `:117`（`// ===== external-materials-service（31）=====` 分隔註解）
  - `bff/src/test/java/com/steven/assets/bff/schedulelist/SchedulePublicBffControllerTest.java`
    的三個斷言：`hasSize(51)`、`"業務服務"` `hasSize(20)`、`"外部行情服務"` `hasSize(31)`
    → 改為 53／21／32。**不改這裡會直接 test failing。**
  - `spec/design.md` 的排程清單段（已於本次 spec 變更改為 53 ＝ 21 ＋ 32）

      > **若最後只落地本任務、另一支沒進同一次 commit，本任務只 +1 自己這一邊**
      > （t334 單獨落地 → 52 ＝ business 20 ＋ external 32；t332 單獨落地 → 52 ＝ business 21 ＋ external 31）。
      > 兩支任務由不同 subagent 執行，各自無條件寫 53 會讓先落地的那一支直接 test failing。
      > **落地前先實查 `SchedulePublicBffController` 當下的實際筆數，以它為準 +1，不要照抄本檔的 51。**

      動工前先讀該類別確認實際欄位格式（`ScheduledJobDto` 為不可變 record：service／category／name／
      description／schedule 白話／cron／zone），並確認既有「海外指數日線回補」那一筆的描述是否需一併更新。

- [ ] **332.7 測試（與實作同一支任務，不得延後）。**
      `backend`，須能在不啟 Spring context、不連 DB 的情況下驗證判準（以替身或直接測純函式）：
  - (a) 美股指數落後**一盤**時判為過時——即「現行 `STALE_DAYS=4` 下會判為正常」的情境
    （這一案就是本任務的迴歸錨點，缺它等於沒測到重點）。
  - (b) 非美股指數落後一盤時**不**判過時；落後超過 4 個日曆天才判過時。
  - (c) 美股指數已追上時，補救檢查**不**觸發回補（斷言回補方法零呼叫）。
  - (d) 回補後仍落後會產生 WARN（可用 log appender 斷言，或把「是否落後」抽成可測的純函式後斷言其結果）。
  - (e) `mostRecentCompletedUsTradingDay` 搬移後行為不變：收盤前退回前一交易日、收盤後取當日、
    遇假日往回找——以與搬移前相同的輸入斷言相同輸出。
  - `TradingRadarService` 既有相關測試須全數維持通過（本任務不得改變雷達行為）。

- [ ] **332.8 建置與部署驗證**（見下方「驗證」段）。

## 驗證

### 建置與測試

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
```

### 部署

```bash
docker compose -p asset-management build --no-cache business-services bff
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff
```

### 實機查證（唯讀）

```bash
docker compose -p asset-management exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F'|' -c \"SELECT index_code, max(trading_date), count(*) FROM us_index_daily_history GROUP BY 1 ORDER BY 1;\""
```

```bash
docker compose -p asset-management logs --since 10m business-services | grep -i "self-heal\|海外指數日線\|gap-check"
```

```bash
docker compose -p asset-management exec -T business-services curl -fsS -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/trading-radar
```

**通過判準：**

1. 重啟後日誌可見自癒的判斷結果；若當下 IXIC 落後，須看到它**啟動回補**而不是「皆為最新，略過」。
2. `us_index_daily_history` 中 IXIC 的 `max(trading_date)` 等於當下的「最近一個已完成美股交易日」
   （驗證時以美東時間換算，收盤前取前一交易日）。
3. `GET /api/trading-radar` 中美股列的 `MARKET_LIQUIDITY.regime` 與 `market_volume_turnover`
   為 **AVAILABLE**（非 STALE）。
4. 台股列的 `regime`／`market_volume_turnover` 語意不變（盤前為 STALE 屬既有誠實語意，非缺陷）。
5. `ruleVersion` 維持 `TW_RULES_V12`、`actionPolicyVersion` 維持 `EVIDENCE_GATE_V1`。
6. 排程列表頁筆數 +1 且新項目資訊正確。

## 完成報告

**2026-08-15 完成，已實機部署並驗證。**

### 落地內容

- **共用方法落點**：`MarketDataService.mostRecentCompletedUsTradingDay(Instant)`（該類別本就是交易日曆
  權威）。原 `TradingRadarService` 的 private 方法改為**純委派**，行為與 Javadoc 完整搬移，
  雷達 stale 判定一字未改。`IndexDailyRefreshScheduler` 依規劃**新增** `MarketDataService` 依賴
  （原本只有 `macroHistoryService`／`usDailyRepo`）；`MarketDataService` 只依賴 base-url ＋
  `StockRepository`，無循環依賴。
- **判準分岔**：新增 `US_INDEX_CODES`／`NON_US_INDEX_CODES` 兩份**正面列舉**常數，
  並以 static 區塊守門——兩者的聯集必須等於 `OVERSEAS_INDEX_CODES ∪ TOTAL_RETURN_US_INDEX_CODES`，
  日後新增指數卻漏分類會**在啟動當下失敗**，而不是靜默走錯判準。
- **驗收與補救**：07:00 全量回補後再驗收一次，未追上以 WARN 記「指數／目前最新／應該要有哪一天」；
  新增 `0 0 9,12 * * TUE-SAT` 補救檢查，只在確實落後時觸發。
- **窄路徑**：新增 `refreshCodes`（只補判定為落後的那幾檔），既有 `refreshAll(String)` 全量版
  語意不動、保留給 07:00；TWSE 報酬指數增量與含息報酬指數收尾只在全量路徑執行。
- **bff**：`SchedulePublicBffController` 新增一筆，並更新既有「海外指數日線回補」的 description
  （原文寫「最新日線過時才抓」與實際全量回補不符）。

### 與任務檔的一處刻意偏離（據實記載）

332.5 字面是「只回補判定為落後的那幾檔**美股**指數」，但**自癒**同時也要顧非美股（4 日容忍）。
照字面實作會使「只有非美股落後」時什麼都不補、砍掉既有自癒能力。實作採
「只回補判定為落後的那幾檔（美股逐盤 ∪ 非美股 4 日）」——同樣滿足「不放大外部請求量」的本意，
且未讓既有行為退步。

### 測試

`backend` **979 通過／0 失敗**、`bff` **89 通過／0 失敗**。
新增 `IndexDailyFreshnessAlignmentTest` 14 案，涵蓋 332.7 (a)–(e)：落後一盤即判過時
（含「舊 `STALE_DAYS=4` 在同一輸入下會判正常」的迴歸錨點）、查無列視為落後、非美股落後一盤不判／
滿 4 日仍容忍／超過 4 日才判、FTSE 沿用容忍、追上時零回補呼叫且 `fillRecentTwseReturnIndexGaps`
零呼叫、WARN 三項資訊齊全、搬移後 `mostRecentCompletedUsTradingDay` 行為不變（收盤前退回、
16:00 取當日、跳過週末與 2026-07-03 順移假日）。

> **既有兩支雷達測試需補 mock stub（非可迴避）**：`TradingRadarUsStockEngineTest` 與
> `TradingRadarUsMarketVolumeWiringTest` 把 `MarketDataService` 宣告為 mock 且只 stub 了
> `isTradingDay(...)`；共用方法搬家後，未 stub 的新方法回 `null` → `latestEodDate.isBefore(null)`
> NPE → 被既有 catch 吞成 `incompleteMarket()`。補的 stub 語意等同原本「每日皆為交易日」，
> **斷言一字未改、production 行為不變**。production 端刻意**不加** `mostRecentCompleted == null`
> 防護——那會在 null 時判成「不 stale」，方向正好與本任務相反。

### 實機驗證（2026-08-15 19:14 Asia/Taipei，`--no-cache` 重建 ＋ recreate）

- 開機自癒實際輸出 `self-heal：海外指數日線皆為最新，略過`——當下 **正確**：
  台北 19:14 ＝ ET 07:14（8/15 那盤尚未收），最近一個已完成美股交易日為 **2026-08-14**，
  而 `us_index_daily_history` 的 IXIC／SPX／DJI／SOX／SP500TR `max(trading_date)` 正是 2026-08-14。
  收緊後的判準在「資料確實最新」時仍回報最新，未產生假警報。
- `GET /api/trading-radar` 美股列的 `MARKET_LIQUIDITY.regime` 與 `market_volume_turnover`
  皆為 **AVAILABLE 32/32**（非 STALE）。
- `ruleVersion` 維持 `TW_RULES_V12`、`actionPolicyVersion` 維持 `EVIDENCE_GATE_V1`。
  business／bff 日誌零 `Connection refused`／500。
- 排程清單：本任務落地時為 52（business 21 ＋ external 31）；t334 一併落地後為
  **53 ＝ business 21 ＋ external 32**。

> **未能在本次驗證中觀察到的情境**：真正「落後一盤」時的 WARN 與 09:00／12:00 補救檢查的實際觸發——
> 那需要來源端真的漏發一盤才會發生，無法在驗證窗內人為製造。該路徑由上述 (a)(c)(d) 三組單元測試覆蓋。
