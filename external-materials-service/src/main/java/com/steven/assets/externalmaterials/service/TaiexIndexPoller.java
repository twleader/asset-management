package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.MacroDataFetchClient;
import com.steven.assets.externalmaterials.client.MacroDataFetchClient.DayQuote;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * 抓取來源為 MacroDataFetchClient.fetchIndexIntradayDay("TWSE")（Task 263 新增；與「股市大盤查詢」頁
 * 「當日走勢」圖表用的 fetchIndexIntraday 同一個 Yahoo ^TWII 5 分 K URL、同一支 curlGetWithRetry，
 * 但回傳含所屬日期與當日 open/high/low），不新增外部 API 依賴。
 */
@Slf4j
@Service
public class TaiexIndexPoller {

    private static final String TAIEX_CODE = "0000";
    private static final String TW_MARKET = "台股";

    private final MacroDataFetchClient macroClient;
    private final PriceCacheWriter writer;
    private final StockSourceQuery source;
    private final MarketClock clock;
    private final FubonTaiexIndexStore fubonIndexStore;

    @Autowired
    public TaiexIndexPoller(
            MacroDataFetchClient macroClient,
            PriceCacheWriter writer,
            StockSourceQuery source,
            MarketClock clock,
            FubonTaiexIndexStore fubonIndexStore) {
        this.macroClient = macroClient;
        this.writer = writer;
        this.source = source;
        this.clock = clock;
        this.fubonIndexStore = fubonIndexStore;
    }

    /** Compatibility constructor for existing package-level unit tests. */
    TaiexIndexPoller(
            MacroDataFetchClient macroClient,
            PriceCacheWriter writer,
            StockSourceQuery source,
            MarketClock clock) {
        this(macroClient, writer, source, clock, null);
    }

    /** 盤中輪詢：週一～五 09:00–13:30 Asia/Taipei，每 2 分鐘；這是獨立大盤 cadence，交易雷達台股個股為每 10 秒。 */
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
        LocalDate today = LocalDate.now(MarketClock.TW_ZONE);
        if (fubonIndexStore != null) {
            FubonTaiexIndexStore.FindResult current = fubonIndexStore.findForTradingDate(today);
            if (current.status() == FubonTaiexIndexStore.ReadStatus.FOUND && current.canonical() != null) {
                writer.writeTaiwanIndexLive(fubonPriceResult(current.canonical()));
                return;
            }
        }
        DayQuote quote = macroClient.fetchIndexIntradayDay("TWSE");

        // 日期守門（Task 263）：來源回傳橫跨最近五個交易日，「最新交易日」不等於「今日」——
        // Yahoo 尚未產生今日第一根 5 分格時它就是昨日，而 cron 第一輪落在 09:00:00 整、今日
        // 09:00 那格要到 09:05 才收，故每個交易日開盤都會撞到一次。原本只判「非 null」，
        // 於是把昨日點位當今日 tick 寫入，並經 IntradayHighLowTracker 污染當日最低（該聚合
        // 只取 min，錯值不會被後續正確值修正，會持續整個交易日）。
        //
        // 查無有效點位／非今日：本輪不寫，保留 Redis 上一輪真實值（比照個股 TWSE z='-' 的既有
        // 慣例，不得以昨收或空值覆寫；見 PricePoller.updatePrices 對 Optional.empty() 的處理）。
        if (quote == null || quote.latestClose() == null || quote.freshnessInstant() == null) return;
        if (!today.equals(quote.date())) {
            log.debug("大盤點位屬 {} 非今日 {}，本輪不寫入", quote.date(), today);
            return;
        }

        // 昨收取「嚴格早於本批點位日期」的最後一筆完成日 K。不可直接取最新一筆——TwseIndexPoller
        // 於 14:00 寫入今日完成日 K 後「最新一筆」即為今日，昨收會變成今日收盤。現行 cron（9-13）
        // 與 isTwMarketOpen()（上界 13:30）使本方法不會在 14:00 後執行，故此為防禦性處理。
        // loadRecentTaiexCloses 回傳為升冪，由後往前找第一筆嚴格早於 quote.date() 者。
        List<StockSourceQuery.ClosePoint> prevRows = source.loadRecentTaiexCloses(2);
        BigDecimal previousClose = null;
        for (int i = prevRows.size() - 1; i >= 0; i--) {
            if (prevRows.get(i).date().isBefore(quote.date())) {
                previousClose = prevRows.get(i).close();
                break;
            }
        }

        PriceResult result = new PriceResult(
                TAIEX_CODE, TW_MARKET, quote.latestClose(),
                null, null,                 // change / changePct：由 PriceCacheWriter 依 previousClose 自算
                "TWSE指數(5m)",              // 含括號 → PriceCacheWriter 不會把這筆併入 IntradayTickStore
                "台股大盤",
                null, null,                 // buyPrice / sellPrice：指數無此概念
                quote.open(),               // 當日第一格開盤（Task 263：原本留 null，觀察清單「開盤」欄因此恆為空白）
                previousClose,
                quote.high(), quote.low(),  // 來源當日全部 5 分格的最高／最低
                null,                       // volume：指數無成交量概念
                quote.date(), quote.freshnessInstant());

        // aggregateHighLow=false：來源已給當日權威 high/low，不再與 price:dayhl:* 的本地累計 merge。
        // 本地聚合的存在理由是「外部 API 不提供 dayrange」（NASDAQ 對 ETF 的 keyStats 為 null），
        // 對 Yahoo 5 分 K 不成立；且聚合的 max/min 語意使誤入的極值無法被後續正確值修正。
        writer.write(result, false, false);
    }

    private PriceResult fubonPriceResult(FubonTaiexIndexStore.CanonicalIndex canonical) {
        return new PriceResult(
                TAIEX_CODE, TW_MARKET, canonical.indexPoint(), null, null, "FUBON_INDICES", "台股大盤",
                null, null, null, previousCloseBefore(canonical.tradingDate()), null, null, null,
                canonical.tradingDate(), canonical.providerUpdatedAt());
    }

    private BigDecimal previousCloseBefore(LocalDate tradingDate) {
        List<StockSourceQuery.ClosePoint> rows = source.loadRecentTaiexCloses(2);
        for (int index = rows.size() - 1; index >= 0; index--) {
            StockSourceQuery.ClosePoint row = rows.get(index);
            if (row.date().isBefore(tradingDate)) return row.close();
        }
        return null;
    }
}
