# [t228] 台股大盤盤中即時判斷（`TW_RULES_V6`）

**對應 Requirements:** Requirement 43（今日交易雷達——台股規則式買賣決策輔助；本任務為「Requirement 43 修訂（規則版本 `TW_RULES_V6`，Task 228）—— 大盤盤中即時判斷」章節的實作）
**前置任務:** 無（Task 217 已完成並在 main，本任務在其上疊加）
**Liquibase changeset:** 無（不新增資料表／欄位，`twse_index_daily_history` 結構不變；只新增 Redis key 用法與兩個純衍生的 DTO 欄位）

## 背景

**現在的行為（Task 217，`TW_RULES_V4`）：** 台股大盤指數（`0000/台股`）的技術指標與 regime 判斷只讀 `twse_index_daily_history`，該表只有「完成日」OHLC，由 `external-materials-service` 的 `TwseIndexPoller` 在盤後（14:00 / 17:00 / 隔日 08:30 Asia/Taipei）批次寫入。盤中（09:00–13:30）這張表最新一列永遠是前一交易日的資料。`external-materials-service` 的 `StockSourceQuery.collectAllStockCodes` / `collectHeldStockCodes` 明確把 `0000/台股` 排除在個股即時價 Redis 輪詢名單之外（`WHERE NOT (stock_code = '0000' AND market = '台股')`），理由是「走 twse_index_daily_history，不打 TWSE mis API」。

`TradingRadarService.buildMarket()`（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java:121-165`）因此判斷：

```java
boolean stale = rows.isEmpty()
        || !rows.get(0).getTradingDate().equals(currentTwTradingDay());
```

`rows` 只來自 `twseRepo.findTopNByOrderByTradingDateDesc(241)`（`twse_index_daily_history`）。只要今天還沒收盤入庫，`stale` 恆為 `true`。stale 時（`TradingRadarRuleEngine.StockInput.marketStale`）：不給個股 `RISK_ON` 的 `+8` 加分、買進閘門一律關閉（不產生 `BUY_CANDIDATE`／`ADD_CANDIDATE`），但 `RISK_OFF` 的 `-15` 扣分與 veto 仍生效。實務結果：整個台股交易時段（09:00–13:30）大盤卡幾乎恆顯示 stale、恆暫停買進訊號，只有 14:00 之後盤後批次入庫才會恢復正常判斷——但此時已收盤，訊號對隔日盤中已無意義。

`TechnicalIndicatorService.computeAllForTaiex()`（`backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java:145-166`）同樣只讀 `twse_index_daily_history`，**沒有**像同檔案內服務一般個股用的 `computeAll()` 那樣「今日已有 live 價但未入完成日 K 時暫加一筆」的邏輯（`computeAll()` 見同檔 58-88 行）。

**這是 Task 217 時的刻意設計**（`spec/requirements.md` Requirement 43「Task 217」修訂區塊原文）：「不得以『補一個即時大盤來源』規避——本 Requirement 明訂零外部行情抓取」。**本任務推翻這個決定**——使用者於 2026-07-20 明確要求加入大盤即時資料，並確認接受因此增加的外部依賴（Yahoo Finance，經既有的 `MacroDataFetchClient.fetchIndexIntraday`）。保留的原則不變：仍不得呼叫任何 AI／LLM API；仍不得因使用者「重新整理」頁面觸發抓取（抓取由獨立背景排程驅動，`TradingRadarService` 只讀 Redis／PostgreSQL 既有值）。

> **後續失效（Task 249，2026-07-29）：** 上一段最後那句「仍不得因使用者『重新整理』頁面觸發抓取」**已被推翻**——使用者要求按下「重新整理」就要抓到最新股價，故新增 `POST /api/trading-radar/refresh` 走同步回補後再重算。詳見 `spec/tasks/t249_trading_radar_manual_price_refresh.md`。**`GET /api/trading-radar` 與 SSE 盤中自動更新仍適用原句**（零外部行情抓取），本段其餘內容不變。

**正確行為（本任務）：** 大盤加入跟個股完全同一套 Redis 即時價機制（`price:台股:0000`）。盤中若 Redis 有今日的即時點位，`stale` 應為 `false`，MA／KD／regime 分數應反映即時點位，讓「今日交易雷達」在盤中真的能對台股大盤走勢做出反應；只有在「完成日 K 未到今日、且 Redis 也抓不到今日即時價」的雙重缺資料情況下，才維持既有的保守 stale 行為。

## 要做什麼

### 1. `external-materials-service`：新增 `TaiexIndexPoller`，把大盤盤中點位寫進 Redis

新增檔案 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/TaiexIndexPoller.java`：

```java
package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.MacroDataFetchClient;
import com.steven.assets.externalmaterials.client.MacroDataFetchClient.IndexIntradayPoint;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 台股大盤（0000）盤中即時點位 → Redis（Task 228，Requirement 43 修訂 V6）。
 *
 * 推翻 Task 217「零外部行情抓取」的決定：改為比照個股既有機制，把大盤盤中點位寫進
 * price:台股:0000（與個股 price:{market}:{code} 同一 schema），供 TradingRadarService／
 * TechnicalIndicatorService 透過既有 PriceQueryService.getLive 讀取。twse_index_daily_history
 * 的既有盤後批次（TwseIndexPoller）完全不受影響，仍是完成日 K 的唯一權威來源；本類別只餵
 * Redis 的盤中即時層。
 *
 * 抓取來源沿用既有 MacroDataFetchClient.fetchIndexIntraday("TWSE")（Yahoo ^TWII 5 分 K，
 * 「股市大盤查詢」頁「當日走勢」圖表已在用同一支方法，Requirement 18），不新增外部 API 依賴。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaiexIndexPoller {

    private static final String TAIEX_CODE = "0000";
    private static final String TW_MARKET = "台股";

    private final MacroDataFetchClient macroClient;
    private final PriceCacheWriter writer;
    private final StockSourceQuery source;
    private final MarketClock clock;

    /** 盤中輪詢：週一～五 09:00–13:30 Asia/Taipei，每 2 分鐘，與個股 PricePoller.scheduledTwIntradayUpdate 同頻率。 */
    @Scheduled(cron = "0 0/2 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTaiexIntradayUpdate() {
        if (!clock.isTwMarketOpen()) return;
        try {
            updateOnce();
        } catch (Exception e) {
            log.warn("大盤盤中點位更新失敗: {}", e.getMessage());
        }
    }

    /** package-private：供測試直接呼叫，略過 cron/isTwMarketOpen 判斷。 */
    void updateOnce() {
        List<IndexIntradayPoint> points = macroClient.fetchIndexIntraday("TWSE");
        BigDecimal latestClose = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).close() != null) {
                latestClose = points.get(i).close();
                break;
            }
        }
        // 查無有效點位：本輪不寫，保留 Redis 上一輪真實值（比照個股 TWSE z='-' 的既有慣例，
        // 不得以昨收或空值覆寫；見 PricePoller.updatePrices 對 Optional.empty() 的處理）。
        if (latestClose == null) return;

        List<StockSourceQuery.ClosePoint> prevRows = source.loadRecentTaiexCloses(1);
        BigDecimal previousClose = prevRows.isEmpty() ? null : prevRows.get(prevRows.size() - 1).close();

        PriceResult result = new PriceResult(
                TAIEX_CODE, TW_MARKET, latestClose,
                null, null,                 // change / changePct：由 PriceCacheWriter 依 previousClose 自算
                "TWSE指數(5m)",              // 含括號 → PriceCacheWriter 不會把這筆併入 IntradayTickStore
                "台股大盤",
                null, null,                 // buyPrice / sellPrice：指數無此概念
                null,                       // openPrice：MA/KD 判斷不吃這欄，留白不臆造
                previousClose,
                null, null,                 // highPrice / lowPrice：交給 PriceCacheWriter 內的 IntradayHighLowTracker 自動聚合
                null);                      // volume：指數無成交量概念
        writer.write(result, false);
    }
}
```

**約束：**
- 不得修改 `TwseIndexPoller`（既有盤後批次），兩者職責分離。
- 不得移除或修改 `StockSourceQuery` 對 `0000/台股` 的排除（那是排除「個股 TWSE mis API 輪詢清單」，大盤本來就不該走那條路徑；本任務用的是獨立的 `TaiexIndexPoller`，不是把 0000 塞回 `collectAllStockCodes`／`collectHeldStockCodes`）。
- `PriceResult` 的 `source` 欄位**必須含括號**（如 `"TWSE指數(5m)"`），依 `PriceCacheWriter.write()` 既有邏輯（`isActualTrade = src != null && !src.contains("(")`），這樣才不會把大盤點位誤併入個股專用的 `IntradayTickStore`（那是給個股「今日走勢」用的 tick 序列，跟大盤既有的 transient `fetchIndexIntraday` 圖表資料源是兩條獨立路徑，不得混用）。
- 抓不到有效點位（`points` 為空或全部 `close()` 為 `null`）時**不得**呼叫 `writer.write(...)`，直接 return。

### 1b. 同步排程登錄表 `SchedulePublicBffController.JOBS`

新增的 `@Scheduled` 方法**必須**登錄進 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`。該檔 class-level Javadoc 明文要求「新增／調整任何 `@Scheduled` 時，務必同步更新下方 `JOBS` 清單」，且既有清單已把同性質的 `PricePoller.scheduledTwIntradayUpdate`（純內部背景輪詢、非使用者可設定排程）登錄在內（第 101-103 行），證明這張表涵蓋所有 `@Scheduled` 方法，不是只登錄使用者可設定的排程。

- 在 `JOBS` 清單第 100-103 行「`===== external-materials-service（24）=====`」區塊內、緊接在既有的「台股即時價（盤中）」條目（第 101-103 行）之後，插入一筆：
  ```java
  new ScheduledJobDto(EXTERNAL, "即時行情", "台股大盤即時點位（盤中）",
          "盤中每 2 分鐘更新台股大盤（0000）即時點位至 Redis（Task 228）",
          "交易日 09:00–13:00 每 2 分鐘", "0 0/2 9-13 * * MON-FRI", TPE),
  ```
- 這個新方法屬於「一法一筆」的一般情況（不像 `TwClosurePoller` 一法兩標合併），所以以下**全部 5 處**計數敘述都要同步 +1，改完後彼此仍須一致，不得只改其中幾處留下矛盾：
  - 第 13 行 class Javadoc：`external-materials-service`（24 個）→（25 個）
  - 第 16-17 行 class Javadoc：「external 24 筆對應 25 個標註」→「external 25 筆對應 26 個標註」（`TwClosurePoller` 一法兩標的既有落差不變，只是底數各 +1）
  - 第 51 行 `JOBS` 欄位上方註解：「全系統排程清單（37 筆）」→「全系統排程清單（38 筆）」
  - 第 100 行區塊註解：「`===== external-materials-service（24）=====`」→「`===== external-materials-service（25）=====`」
  - 第 187 行 `GET /api/bff/schedule-list` 端點的 Javadoc：「回傳全系統排程清單（37 筆靜態資料）」→「（38 筆靜態資料）」

### 2. `TechnicalIndicatorService.computeAllForTaiex()`：比照個股既有邏輯，暫加今日即時點位

修改 `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java` 第 145-166 行的 `computeAllForTaiex()`，改為：

```java
/**
 * 0000 台股大盤：從 twse_index_daily_history 計算 MA20 / MA60 / MA240 / KD；若完成日 K 尚未到今日
 * 但 Redis 有今日即時點位（Task 228），比照 computeAll() 對一般個股的既有作法暫加一筆到序列最前。
 * OHLC 在 v1.21 之後才補；舊資料 high/low/open 可能為 null，KD 計算時 fallback 用 close。
 */
private FullIndicators computeAllForTaiex() {
    try {
        List<TwseIndexDailyHistory> desc = new ArrayList<>(twseDailyRepo.findTopNByOrderByTradingDateDesc(240));
        LocalDate today = LocalDate.now();
        if (desc.isEmpty() || !today.equals(desc.get(0).getTradingDate())) {
            Optional<PriceQueryService.LivePrice> liveOpt = priceQuery.getLive("0000", "台股");
            if (liveOpt.isPresent() && liveOpt.get().tradingDate() != null
                    && today.toString().equals(liveOpt.get().tradingDate())) {
                PriceQueryService.LivePrice live = liveOpt.get();
                TwseIndexDailyHistory t = new TwseIndexDailyHistory();
                t.setTradingDate(today);
                t.setClosePoint(live.price());
                t.setHighPoint(live.highPrice() != null ? live.highPrice() : live.price());
                t.setLowPoint(live.lowPrice() != null ? live.lowPrice() : live.price());
                t.setOpenPoint(live.openPrice());
                desc.add(0, t);
            }
        }
        if (desc.isEmpty()) return FullIndicators.EMPTY;

        BigDecimal ma20  = taiexSimpleMa(desc, 20);
        BigDecimal ma60  = taiexSimpleMa(desc, 60);
        BigDecimal ma240 = taiexSimpleMa(desc, 240);

        KdValues currentKd = taiexKd(desc);
        KdValues previousKd = desc.size() > 1
                ? taiexKd(desc.subList(1, desc.size()))
                : KdValues.EMPTY;
        return new FullIndicators(
                ma20, ma60, ma240,
                currentKd.k(), currentKd.d(),
                previousKd.k(), previousKd.d());
    } catch (Exception e) {
        log.warn("compute TAIEX indicators failed", e);
        return FullIndicators.EMPTY;
    }
}
```

**約束：**
- `today` 用不加時區的 `LocalDate.now()`，與同檔案 `computeAll()`（第 65 行）完全一致的寫法，不要為大盤另外引入 `Asia/Taipei` 時區判斷（避免同一服務內兩種時區推算並存）。
- `twseDailyRepo.findTopNByOrderByTradingDateDesc(240)` 回傳的 `List` 必須包一層 `new ArrayList<>(...)` 再操作，否則 `desc.add(0, t)` 可能因底層是不可變列表而丟 `UnsupportedOperationException`。
- `taiexSimpleMa` / `taiexKd` 兩個 helper 方法簽名與內容不變（仍吃 `List<TwseIndexDailyHistory>`），不要重寫。
- `TwseIndexDailyHistory` 用 `new TwseIndexDailyHistory()` + setter（該 entity 是 `@Data @NoArgsConstructor @AllArgsConstructor`，沒有 `@Builder`），不要假設有 builder。

### 3. `TradingRadarDto.MarketSummary`：新增 `intraday` 與 `liveUpdatedAt`

修改 `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java` 的 `MarketSummary` record（目前在 24-43 行），在 `risks` 之後新增兩個欄位：

```java
public record MarketSummary(
        String regime,
        String regimeLabel,
        Integer score,
        boolean dataComplete,
        /** 大盤最新完成日 K 非當前交易日、且 Redis 亦無今日即時價：買進閘門關閉、不採計 RISK_ON 加分（Task 217.1，語意於 Task 228 擴充）。 */
        boolean stale,
        String asOfDate,
        BigDecimal price,
        BigDecimal changePercent,
        BigDecimal monthlyMa,
        BigDecimal quarterlyMa,
        BigDecimal annualMa,
        BigDecimal kValue,
        BigDecimal dValue,
        String quarterlyConfirmation,
        String annualConfirmation,
        List<String> reasons,
        List<String> risks,
        /** regime 是否由 Redis 今日即時點位算出（相對於「已入庫完成日 K」）（Task 228）。 */
        boolean intraday,
        /** intraday=true 時為 Redis 即時價的 updatedAt（ISO 字串）；否則為 null（Task 228）。 */
        String liveUpdatedAt
) {}
```

**約束：** 這是 Java record，新增欄位會讓所有既有的 positional 建構呼叫編譯失敗——下一步要同步修正 `TradingRadarService.java` 內僅有的兩個建構點。不要另外新增 overload 或次要建構子，直接改 record 定義與呼叫端。

### 4. `TradingRadarService.buildMarket()`：即時價融合與 stale 語意調整

修改 `backend/src/main/java/com/steven/assets/service/TradingRadarService.java` 第 121-165 行的 `buildMarket()`：

```java
private MarketState buildMarket() {
    try {
        List<TwseIndexDailyHistory> rows = twseRepo.findTopNByOrderByTradingDateDesc(241);
        List<BigDecimal> closes = rows.stream().map(TwseIndexDailyHistory::getClosePoint).toList();
        LocalDate currentTradingDay = currentTwTradingDay();
        LocalDate latestEodDate = rows.isEmpty() ? null : rows.get(0).getTradingDate();
        boolean todayEodPresent = latestEodDate != null && latestEodDate.equals(currentTradingDay);

        Optional<PriceQueryService.LivePrice> liveOpt = priceQueryService.getLive(TAIEX_CODE, TW_MARKET);
        boolean liveFreshToday = !todayEodPresent && liveOpt.isPresent()
                && liveOpt.get().tradingDate() != null
                && currentTradingDay.toString().equals(liveOpt.get().tradingDate());

        BigDecimal price;
        BigDecimal changePercent;
        if (liveFreshToday) {
            price = liveOpt.get().price();
            changePercent = closes.isEmpty() ? null : changePercent(price, closes.get(0));
        } else {
            price = closes.isEmpty() ? null : closes.get(0);
            changePercent = closes.size() >= 2 ? changePercent(closes.get(0), closes.get(1)) : null;
        }

        TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(TAIEX_CODE, TW_MARKET);
        TradingRadarRuleEngine.Confirmation c60 = ruleEngine.confirm(closes, 60);
        TradingRadarRuleEngine.Confirmation c240 = ruleEngine.confirm(closes, 240);
        TradingRadarRuleEngine.MarketResult result = ruleEngine.evaluateMarket(
                new TradingRadarRuleEngine.MarketInput(
                        price,
                        changePercent,
                        indicators(ind),
                        c60,
                        c240));

        // stale＝「完成日 K 未到今日」且「Redis 也無今日即時價」時才成立；任一者成立即非 stale（Task 228）。
        boolean stale = !todayEodPresent && !liveFreshToday;
        String asOf = rows.isEmpty() ? null : rows.get(0).getTradingDate().toString();
        String liveUpdatedAt = liveFreshToday ? liveOpt.get().updatedAt() : null;
        TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                result.regime().name(),
                regimeLabel(result.regime()),
                result.score(),
                result.regime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE,
                stale,
                asOf,
                price,
                changePercent,
                ind.monthlyMa(),
                ind.quarterlyMa(),
                ind.annualMa(),
                ind.k(),
                ind.d(),
                c60.name(),
                c240.name(),
                result.reasons(),
                result.risks(),
                liveFreshToday,
                liveUpdatedAt);
        return new MarketState(summary, result.regime(), stale);
    } catch (Exception e) {
        log.warn("今日交易雷達：大盤資料組裝失敗", e);
        return incompleteMarket("讀取大盤資料失敗，所有個股暫停產生交易訊號。");
    }
}
```

同檔案 394-409 行的 `incompleteMarket(String message)` 也要同步補上新增的兩個欄位（皆固定值，因為讀取本身就失敗了）：

```java
private MarketState incompleteMarket(String message) {
    TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
            TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE.name(),
            regimeLabel(TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE),
            null,
            false,
            true,
            null,
            null, null, null, null, null, null, null,
            TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
            TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
            List.of(),
            List.of(message),
            false,
            null);
    // 讀不到大盤時保守視為 stale：買進閘門一律關閉。
    return new MarketState(summary, TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE, true);
}
```

**約束（不得違反，這是本任務最容易踩雷之處）：**
- `ruleEngine.confirm(closes, 60)` / `confirm(closes, 240)` 的 `closes` 變數**必須維持只含 `twseRepo` 查出的完成日收盤**，不得把即時價塞進 `closes` 列表。這是刻意的：Task 217.3 已經證明過，把盤中即時價塞進「兩收盤日確認」會讓確認狀態在均線附近逐 tick 翻轉（`OVERSOLD_WATCH ↔ TRIAL_CANDIDATE` 的原始 bug）。大盤沿用同一原則，`price`／`changePercent`／`ind`（MA／KD）可以吃即時價，但 `c60`／`c240` 不行。
- `indicatorService.computeAll(TAIEX_CODE, TW_MARKET)` 內部（見第 2 步）已經會自己去讀 Redis 即時價並融合進 MA／KD，`buildMarket()` **不需要**、也**不應該**自己再手動把即時價塞進另一份序列給 `computeAll` ——避免同一份即時價被兩套邏輯各自詮釋出不一致的融合結果。
- `changePercent` 在 `liveFreshToday` 時用 `changePercent(price, closes.get(0))`——`closes.get(0)` 此時是「最近一根完成日收盤」（因為 `!todayEodPresent` 為真時 `closes.get(0)` 還是昨收），語意等同「即時價 vs 昨收」，不是「即時價 vs 自己」。
- `liveFreshToday` 的判斷式**必須**先檢查 `!todayEodPresent`——如果完成日 K 已經到今天了（`todayEodPresent=true`），即使 Redis 剛好還留著今天的即時價快取，也應該優先信任已入庫的完成日 K（`intraday` 應為 `false`），不要因為 Redis 沒即時過期就誤判成 intraday。
- `TAIEX_CODE`、`TW_MARKET`、`currentTwTradingDay()` 都是本檔案既有的 private 常數／方法（第 44-46、82-89 行），直接沿用，不要重新定義。

### 5. `TradingRadarRuleEngine.RULE_VERSION`：升版

修改 `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java` 第 18 行：

```java
public static final String RULE_VERSION = "TW_RULES_V6";
```

### 6. 前端 `TradingRadarView.vue`：文案與即時更新時間顯示

修改 `frontend/src/views/TradingRadarView.vue`：

- 第 25 行 fallback 字串同步升版：
  ```html
  <el-tag size="small" effect="plain" type="info" class="rule-tag">{{ radar.ruleVersion || 'TW_RULES_V6' }}</el-tag>
  ```
- 第 340 行 `radar` 的 `ref()` 初始狀態也硬編了同一個 fallback 版號，必須同步改掉，否則 API 回應抵達前的初始渲染會短暫顯示舊版號：
  ```js
  const radar = ref({ market: {}, stocks: [], skippedNonTwStocks: 0, ruleVersion: 'TW_RULES_V6' })
  ```
- 第 27 行「完成日 K」旁新增即時更新時間（`intraday=true` 時顯示）。`.card-head` 目前是 `display:flex; justify-content:space-between`，且目前只有 2 個直接子節點（標題 div、`.as-of` span）——若把新的 `.as-of.live-as-of` span 直接當成第 3 個直接子節點加進去，`space-between` 會把三者等距分散在整列，而不是讓「完成日 K」與「即時更新」相鄰顯示。因此**必須**把兩個 `.as-of` span 包進同一個 group div，讓 `.card-head` 仍只有 2 個直接子節點：
  ```html
  <div class="as-of-group">
    <span class="as-of">完成日 K：{{ market.asOfDate || '資料不足' }}</span>
    <span v-if="market.intraday" class="as-of live-as-of">即時更新：{{ fmtTime(market.liveUpdatedAt) }}</span>
  </div>
  ```
  並在既有 scoped style（`.card-head { ... }` 附近，約第 603 行）新增：
  ```css
  .as-of-group { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
  ```
- 第 31-38 行的 stale 警示（原文宣稱「大盤指數只有收盤後才入庫，盤中無即時值」，Task 228 起不再成立），改為：
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
- 在 `<script setup>` 區塊（第 331 行起，緊鄰既有的 `fmtNumber`／`fmtPct` 定義，約第 512-520 行附近）新增 `fmtTime`：
  ```js
  function fmtTime(value) {
    if (!value) return '—'
    const d = new Date(value)
    if (Number.isNaN(d.getTime())) return '—'
    return d.toLocaleTimeString('zh-TW', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  }
  ```
- `.stale-alert` 附近若有 scoped style 區塊，`.live-as-of` 可不特別加樣式（沿用 `.as-of` 既有樣式即可），不要為此新增大量 CSS。

**約束：** 不得刪除或改變 `market.stale=true` 時既有的「暫停買進」行為說明語氣過強或過弱——新文案必須讓使用者理解這是「本次抓取失敗的暫時退化」而非「這個功能本來就不支援即時」。

## 驗證

**後端建置與既有測試（本專案無 root pom，`backend`／`external-materials-service`／`bff` 是三個各自獨立的 Maven module，`mvn -pl` 對它們無效，必須逐一 `cd` 進該模組目錄執行）：**
```bash
cd backend && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q compile && cd ..
cd external-materials-service && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q compile && cd ..
cd bff && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q compile && cd ..
cd backend && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q test -Dtest=TradingRadarRuleEngineTest,TradingRadarNotificationTransitionTest && cd ..
```

**新增測試（與本任務同一 commit；以下三個檔案目前都不存在，實作者依專案既有命名慣例——production 類名加 Test 字尾——自訂檔名）：**

1. 新增一個測試檔於 `backend/src/test/java/com/steven/assets/service/`，用 Mockito mock `StockPriceHistoryRepository`／`PriceQueryService`／`TwseIndexDailyHistoryRepository`，涵蓋 `TechnicalIndicatorService.computeAll` 對大盤（`stockCode="0000", market="台股"`）的行為：
   - `twseDailyRepo` 回傳的最新一列非今日、且 `priceQuery.getLive("0000","台股")` 回傳 `tradingDate=今日` 的即時價 → MA/KD 計算含這筆即時價（斷言 MA20 等於「即時價 + 前 19 筆完成日收盤」的平均）。
   - `twseDailyRepo` 最新一列已是今日 → 不重複融合即時價（即使 `priceQuery.getLive` 也回今日資料）。
   - `priceQuery.getLive` 回傳 `Optional.empty()` 或 `tradingDate` 非今日 → 行為與 Task 217 之前完全一致（純讀 `twse_index_daily_history`）。

2. 新增一個測試檔於 `backend/src/test/java/com/steven/assets/service/`，涵蓋 `TradingRadarService.buildMarket()` 的新行為（`TradingRadarService` 建構子依賴數量以實際原始碼為準——寫這份任務檔時是 12 個，Task 223 併入 main 後新增 `ExchangeRateHistoryRepository` 變成 13 個，實作或之後合併衝突時請重新核對，全部要 mock 出來；其餘與大盤無關的欄位可回傳空集合／`Optional.empty()` 以隔離測試範圍到 `buildMarket()`）：
   - `MarketDataService.isTradingDay("台股", any())` 要 stub 成 `true`，讓 `currentTwTradingDay()` 解析為 `LocalDate.now(Asia/Taipei)`，測試斷言才有意義。
   - `AssetSnapshotRepository.findLatestWithStocks()` 回 `Optional.empty()`、`StockAlertRepository.findDistinctStockCodeMarket()` 回空 list，讓 `get()` 只需組裝大盤區塊即可回傳。
   - 情境一：`twseRepo` 最新列非今日、`priceQueryService.getLive("0000","台股")` 回今日即時價 → 斷言 `market.stale=false`、`market.intraday=true`、`market.liveUpdatedAt` 非 null。
   - 情境二：`twseRepo` 最新列非今日、`priceQueryService.getLive` 回 `Optional.empty()` → 斷言 `market.stale=true`、`market.intraday=false`（既有 Task 217 行為不變）。
   - 情境三：`twseRepo` 最新列就是今日 → 斷言 `market.stale=false`、`market.intraday=false`，即使 mock 的 `priceQueryService.getLive` 也回今日即時價（完成日 K 優先）。
   - 情境四：驗證 `ruleEngine.confirm` 收到的 `closes` 參數不含即時價——可用 `ArgumentCaptor<List<BigDecimal>>` 擷取傳給 mock 過的 `TradingRadarRuleEngine`（若改用 mock 而非真實引擎）比對長度與內容皆等於純完成日收盤序列；若採真實引擎，改為斷言分數／regime 在「即時價劇烈偏離完成日收盤」時仍與「兩日確認只用完成日資料」的預期一致（不因即時價單點大幅波動而讓 confirm 結果翻轉）。

3. 新增一個測試檔於 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/`，mock `MacroDataFetchClient`／`PriceCacheWriter`／`StockSourceQuery`，涵蓋 `TaiexIndexPoller.updateOnce()`：
   - `fetchIndexIntraday("TWSE")` 回傳的點位全為 `close=null`（或空 list）→ 斷言 `writer.write(...)` **未被呼叫**。
   - 回傳含至少一筆非 null 收盤 → 斷言 `writer.write(...)` 被呼叫一次，且傳入的 `PriceResult.stockCode()=="0000"`、`market()=="台股"`、`price()` 等於最後一筆非 null 收盤、`source()` 含 `"("`。

若跑 Mockito 測試遇到 Java 版本相關的 byte-buddy 錯誤，逐模組加 `-DargLine`：
```bash
cd backend && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q test -DargLine="-Dnet.bytebuddy.experimental=true" && cd ..
cd external-materials-service && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q test -DargLine="-Dnet.bytebuddy.experimental=true" && cd ..
```

**建置全部模組（同樣逐一 `cd` 進模組目錄，無 root pom）：**
```bash
cd backend && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q package && cd ..
cd external-materials-service && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q package && cd ..
cd bff && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q package && cd ..
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite build && cd ..
```

**跑起來真的有這個功能（本專案沒有 dev server，image rebuild + container recreate 才算改好）：**
```bash
docker compose -p asset-management build --no-cache external-materials-service business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services frontend
curl -s http://localhost:8080/actuator/health
# 交易時段內觀察 external-materials-service log 應出現大盤盤中點位更新（非錯誤）；
# 交易時段內呼叫（帶合法 X-User-Id 等 header，見 CLAUDE.md「免 OAuth 端到端測試」）
#   GET /api/bff/trading-radar
# 斷言回應 market.stale=false、market.intraday=true、market.liveUpdatedAt 非 null（若當下確實是台股交易時段）；
# 非交易時段或 Redis 尚無資料時應優雅回退為 Task 217 既有行為（stale 依完成日 K 判斷），不得整頁 500。
docker exec -it asset-management-redis-1 redis-cli GET "price:台股:0000"
# 交易時段內應可看到 JSON payload；非交易時段可能為既有值或不存在，皆屬正常。
```

## 完成報告

**實際改動檔案：**
- 新增 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/TaiexIndexPoller.java`
- 修改 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`（新增 1 筆 `ScheduledJobDto`，同步 5 處計數註解 12/24→12/25、37→38）
- 修改 `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`（`computeAllForTaiex()` 加即時融合）
- 修改 `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java`（`MarketSummary` 新增 `intraday`／`liveUpdatedAt`，class doc 版號 V4→V6）
- 修改 `backend/src/main/java/com/steven/assets/service/TradingRadarService.java`（`buildMarket()`／`incompleteMarket()`）
- 修改 `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`（`RULE_VERSION` V4→V6）
- 修改 `frontend/src/views/TradingRadarView.vue`（fallback 版號 ×2、stale 文案、`.as-of-group`、`fmtTime`、CSS）
- 新增測試：`backend/src/test/java/com/steven/assets/service/TradingRadarMarketFreshnessTest.java`（4 案例）、`backend/src/test/java/com/steven/assets/service/TechnicalIndicatorTaiexLiveBlendTest.java`（3 案例）、`external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/TaiexIndexPollerTest.java`（3 案例）

**驗證輸出：**
- `backend`／`external-materials-service`／`bff` 三模組各自 `mvn compile` 皆 `BUILD SUCCESS`。
- `backend`：`TradingRadarMarketFreshnessTest`（4）、`TechnicalIndicatorTaiexLiveBlendTest`（3）、既有 `TradingRadarRuleEngineTest`（16）合計 23 個測試全過，`Failures: 0, Errors: 0`。
- `external-materials-service`：`TaiexIndexPollerTest`（3）全過。
- 前端未在本機 `vite build`（此 worktree 未 `npm install`，`node_modules` 不存在，屬本專案 worktree 慣例）；語法正確性與實際功能改由 Docker image rebuild 驗證。

**與原計畫的偏差：**
- 驗證段原寫 `mvn -q -pl backend,external-materials-service -am compile`，實測本專案**無 root pom**，三模組互相獨立，`-pl` 無效；已改為逐模組 `cd` 執行並同步修正本檔驗證段指令（與另一支不相關任務 t223 的第一輪審查發現一致，屬本專案共通特性非本任務獨有）。
- 實作 1b 步驟時，`scripts/spec-check.sh` 的 B6 CHECK 提示回頭核對 `design.md:115` 的排程總數，發現該處「共 36 筆 ＝ business-services 12 ＋ external-materials-service 24」**在 Task 228 動工前就已經是錯的**：以 `grep -c "new ScheduledJobDto("` 直接數 `SchedulePublicBffController.JOBS` 實際筆數＝business 15、external（加入本任務新增那筆前）28，並以 `grep -rn "@Scheduled"` 逐檔核對 external 真實標註數＝29（扣除 3 處出現在 Javadoc 註解裡的非標註「@Scheduled」文字比對後）；不是本任務造成的新漂移，但既然在同一個 CHECK 觸發下順手發現，已一併把 `SchedulePublicBffController.java` 5 處計數與 `design.md:115` 校正為加入本任務新排程後的正確值（15／29／44，external 29 筆對應 30 個標註），並在兩處都註明「12／24／36 是 Task 228 之前既有的漂移」以免後續誤以為是本任務算錯。
