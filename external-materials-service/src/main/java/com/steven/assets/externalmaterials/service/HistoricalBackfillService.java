package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.CommodityFetchClient;
import com.steven.assets.externalmaterials.client.ExchangeRateFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.HistoricalBar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 歷史價格 / 匯率回補（10 年回補 + 缺口補齊 + 增量更新）。
 *
 * 從 business-services HistoricalDataService 搬遷至此，集中外部 API 呼叫於 external-materials-service：
 *  - 台股收盤：FinMind TaiwanStockPrice（PriceFetchClient.fetchTwHistoricalRange）
 *  - 美股收盤：Yahoo Finance chart API（PriceFetchClient.fetchUsHistoricalRange）
 *  - 匯率：FinMind TaiwanExchangeRate（ExchangeRateFetchClient.fetchRange）
 *
 * 寫入：StockSourceQuery.upsertHistory / upsertExchangeRate（共用 Postgres，不引入 JPA entity）。
 *
 * 觸發：(1) 啟動時 ApplicationReadyEvent 自動補齊；(2) business-services 透過 /internal/backfill/* 同步觸發。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalBackfillService {

    private final PriceFetchClient priceFetch;
    private final ExchangeRateFetchClient rateFetch;
    private final CommodityFetchClient commodityFetch;
    private final StockSourceQuery store;
    private final MarketDataFetchService etfFetch;

    /** 資產配置圓餅圖「台股個股 / 美股個股」顯示的個股數（與 BFF top10 對齊）。 */
    private static final int LOOKTHROUGH_TOP_N = 10;

    /**
     * 啟動時自動補齊：app ready 後在背景執行（延遲 8s 等 DB / 連線就緒）。
     * 若主檔股票無資料、最新資料超過 7 天未更新、或最早資料晚於 10 年前，從 10 年前回補。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startupBackfill() {
        Thread.ofVirtual().name("startup-backfill").start(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
            log.info("啟動補齊：開始檢查所有持股歷史價格與匯率缺漏...");
            try {
                LocalDate since = LocalDate.now().minusYears(10);
                LocalDate staleThreshold = LocalDate.now().minusDays(7);

                Set<String> twCodes = new LinkedHashSet<>();
                Set<String> usCodes = new LinkedHashSet<>();
                Set<String> ukCodes = new LinkedHashSet<>();
                store.collectAllHeldCodes(twCodes, usCodes, ukCodes);
                // 併入「ETF 透視 top10 成份股」（Task 129）：純穿透成份股（如 2317 鴻海）不在主檔 / 持股，
                // 否則點圓餅圖成份段時走勢圖空白。失敗不影響主清單。
                try {
                    collectLookthroughTopConstituents(twCodes, usCodes);
                } catch (Exception e) {
                    log.warn("透視成份股清單收集失敗（啟動補齊）: {}", e.getMessage());
                }

                for (String code : twCodes) {
                    LocalDate maxDate = store.findMaxTradingDate(code, "台股").orElse(null);
                    LocalDate minDate = store.findMinTradingDate(code, "台股").orElse(null);
                    if (maxDate == null || maxDate.isBefore(staleThreshold)
                            || (minDate != null && since.isBefore(minDate))) {
                        log.info("啟動補齊台股 {} (maxDate={}, minDate={})", code, maxDate, minDate);
                        backfillTwStock(code, since, null);
                        sleep(600);
                    }
                }
                for (String code : usCodes) {
                    LocalDate maxDate = store.findMaxTradingDate(code, "美股").orElse(null);
                    LocalDate minDate = store.findMinTradingDate(code, "美股").orElse(null);
                    if (maxDate == null || maxDate.isBefore(staleThreshold)
                            || (minDate != null && since.isBefore(minDate))) {
                        log.info("啟動補齊美股 {} (maxDate={}, minDate={})", code, maxDate, minDate);
                        backfillUsStock(code, since, null);
                        sleep(2000);
                    }
                }
                for (String code : ukCodes) {
                    LocalDate maxDate = store.findMaxTradingDate(code, "英股").orElse(null);
                    LocalDate minDate = store.findMinTradingDate(code, "英股").orElse(null);
                    if (maxDate == null || maxDate.isBefore(staleThreshold)
                            || (minDate != null && since.isBefore(minDate))) {
                        log.info("啟動補齊英股 {} (maxDate={}, minDate={})", code, maxDate, minDate);
                        backfillUkStock(code, since, null);
                        sleep(2000);
                    }
                }

                for (String currency : store.collectTrackedCurrencies()) {
                    LocalDate rateMinDate = store.findMinRateDate(currency).orElse(null);
                    if (rateMinDate == null || since.isBefore(rateMinDate)) {
                        log.info("啟動補齊 {} 匯率 (minDate={}，補至 {})", currency, rateMinDate, since);
                        backfillExchangeRateFrom(currency, since);
                    }
                }

                // 油價／金價十年每日收盤（Requirement 40）：無資料或最早日晚於 since 就補滿十年
                for (String code : CommodityFetchClient.SYMBOLS.keySet()) {
                    LocalDate minDate = store.findMinCommodityDate(code).orElse(null);
                    if (minDate == null || since.isBefore(minDate)) {
                        log.info("啟動補齊 {} 收盤價 (minDate={}，補至 {})", code, minDate, since);
                        backfillCommodityFrom(code, since);
                        sleep(2000);
                    }
                }
                log.info("啟動補齊完成");
            } catch (Exception e) {
                log.warn("啟動補齊失敗（可能 DB 尚未就緒）: {}", e.getMessage());
            }
        });
    }

    /**
     * 回補單一台股歷史收盤。若 since 早於目前最早紀錄（向前缺漏）就從 since 抓；否則從 maxDate+1 增量。
     *
     * 「今日列」獨佔規則（Requirement 7 / Task 84）：本路徑遇到市場時區當日 bar 一律 skip，
     * 由 ClosePersister 在收盤後寫入。理由：Yahoo / FinMind 盤中也會回一根「今日 partial bar」
     * （open/high/low + 此刻 last trade 當 close），若直接 upsert 就會在 DB 充當「收盤」與 Redis 脫鉤。
     */
    public int backfillTwStock(String stockCode, LocalDate since, LocalDate until) {
        LocalDate today = LocalDate.now(MarketClock.TW_ZONE);
        LocalDate maxDate = store.findMaxTradingDate(stockCode, "台股").orElse(null);
        LocalDate minDate = store.findMinTradingDate(stockCode, "台股").orElse(null);
        LocalDate start;
        if (maxDate == null) start = since;
        else if (minDate != null && since.isBefore(minDate)) start = since;
        else start = maxDate.plusDays(1);
        LocalDate end = (until != null) ? until : today;
        if (!start.isBefore(end)) return 0;

        log.info("回補台股 {} 歷史價格: {} ~ {}", stockCode, start, end);
        int count = 0;
        for (HistoricalBar bar : priceFetch.fetchTwHistoricalRange(stockCode, start, end)) {
            if (bar.tradingDate().equals(today)) {
                log.debug("跳過台股 {} 今日 ({}) bar — 今日列獨佔給 ClosePersister", stockCode, today);
                continue;
            }
            // 以使用者輸入的原始代號存入（FinMind 後綴版只用於 fetch，不汙染主鍵）
            if (store.existsHistory(stockCode, "台股", bar.tradingDate())) continue;
            if (store.upsertHistory(stockCode, "台股", bar.tradingDate(),
                    bar.open(), bar.high(), bar.low(), bar.close(), bar.volume())) {
                count++;   // 被拒的非正收盤列不得算成已寫入（Task 279）
            }
        }
        if (count > 0) log.info("台股 {} 匯入 {} 筆", stockCode, count);
        return count;
    }

    /**
     * 歷史修復結果（Task 258）。
     *
     * @param codesProcessed    實際處理的代號數
     * @param rowsOverwritten   實際覆寫的列數（含寫回相同值者）
     * @param codesWithNoSource 權威來源在該區間完全沒回傳 bar 的代號數
     * @param codesFailed       抓取擲例外的代號數（已 graceful 跳過，不中斷整批）
     */
    public record RepairSummary(
            String market, LocalDate from, LocalDate to,
            int codesProcessed, int rowsOverwritten, int codesWithNoSource, int codesFailed) {}

    /**
     * 對指定市場與日期區間重新抓取權威日線並<b>覆寫</b>既有列（Task 258）。
     *
     * <p><b>為什麼不能重用 {@link #backfillTwStock}：</b>它有兩道各自獨立的阻擋使它修不了既有列——
     * (1) 起點一律 {@code maxDate.plusDays(1)}，故區間中段永遠碰不到（腐化標的的 {@code maxDate} 就是
     * 最近交易日 → {@code start} 落在明天 → {@code !start.isBefore(end)} → {@code return 0}）；
     * (2) for-loop 內 {@code if (store.existsHistory(...)) continue;} 明確 skip-if-exists。
     * {@code StockSourceQuery.upsertHistory} 本身是真 upsert（先 SELECT id、存在則 UPDATE），
     * 但那兩道守門讓 backfill 路徑從不對既有日期呼叫它。故本方法是新路徑，不是既有路徑加參數。</p>
     *
     * <p><b>刻意不做偵測式判定：</b>採「重抓權威值直接覆寫」而非「先判斷哪列是錯的再修」。後者需要一套
     * 啟發式規則，會有把真平盤誤判成腐化而覆寫掉正確資料的風險；覆寫式做法對真平盤無害（寫回相同的值），
     * 且順帶修好「盤中 tick 被當收盤」那一類而不需要偵測它們。</p>
     *
     * <p><b>來源查無時保留既有列</b>，不刪除、不猜值、不以鄰日內插——來源查無可能是真休市、可能是該檔
     * 當日無交易、也可能是來源暫時故障，三者都不足以支撐刪資料或填近似值（Requirement 7 禁止回寫充數）。</p>
     *
     * @param stockCode 指定單檔；null／空白時取 {@code collectAllStockCodes} 該市場的那一份
     */
    public RepairSummary repairRange(String market, String stockCode, LocalDate from, LocalDate to) {
        if (!"台股".equals(market) && !"美股".equals(market) && !"英股".equals(market)) {
            throw new IllegalArgumentException("market 只接受 台股 / 美股 / 英股，收到：" + market);
        }
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("from 不得晚於 to（from=" + from + ", to=" + to + "）");
        }

        List<String> codes;
        if (stockCode == null || stockCode.isBlank()) {
            Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
            store.collectAllStockCodes(tw, us, uk);
            codes = new ArrayList<>("美股".equals(market) ? us : "英股".equals(market) ? uk : tw);
        } else {
            codes = List.of(stockCode.trim());
        }

        // 今日列獨佔給 ClosePersister（Task 84）；市場時區由 MarketClock.zoneOf 分流
        LocalDate today = LocalDate.now(MarketClock.zoneOf(market));
        // 逐檔節流沿用 startupBackfill 的既有值：台股 600ms、美/英股 2000ms
        long throttle = "台股".equals(market) ? 600L : 2000L;

        log.info("歷史修復 {} {} ~ {}：{} 檔", market, from, to, codes.size());
        int rows = 0, noSource = 0, failed = 0, processed = 0;
        boolean first = true;
        for (String code : codes) {
            if (!first) sleep(throttle);
            first = false;
            processed++;
            try {
                List<HistoricalBar> bars = switch (market) {
                    case "美股" -> priceFetch.fetchUsHistoricalRange(code, from, to);
                    case "英股" -> priceFetch.fetchUkHistoricalRange(code, from, to);
                    default -> priceFetch.fetchTwHistoricalRange(code, from, to);
                };
                if (bars == null || bars.isEmpty()) {
                    log.warn("歷史修復 {} {}：來源在 {} ~ {} 無任何 bar，既有列保留不動",
                            market, code, from, to);
                    noSource++;
                    continue;
                }
                int wrote = 0;
                for (HistoricalBar bar : bars) {
                    if (bar.tradingDate().equals(today)) continue;   // 今日列獨佔
                    // 與 backfill 的差別就在這裡：不檢查 existsHistory，一律覆寫
                    if (store.upsertHistory(code, market, bar.tradingDate(),
                            bar.open(), bar.high(), bar.low(), bar.close(), bar.volume())) {
                        wrote++;   // 被拒的非正收盤列不得算成已寫入（Task 279）
                    }
                }
                rows += wrote;
            } catch (Exception e) {
                log.warn("歷史修復 {} {} 失敗（不中斷整批）: {}", market, code, e.getMessage());
                failed++;
            }
        }
        log.info("歷史修復完成 {} {} ~ {}：處理 {} 檔、覆寫 {} 列、來源查無 {} 檔、失敗 {} 檔",
                market, from, to, processed, rows, noSource, failed);
        return new RepairSummary(market, from, to, processed, rows, noSource, failed);
    }

    public int backfillUsStock(String stockCode, LocalDate since, LocalDate until) {
        LocalDate today = LocalDate.now(MarketClock.US_ZONE);
        LocalDate maxDate = store.findMaxTradingDate(stockCode, "美股").orElse(null);
        LocalDate minDate = store.findMinTradingDate(stockCode, "美股").orElse(null);
        LocalDate start;
        if (maxDate == null) start = since;
        else if (minDate != null && since.isBefore(minDate)) start = since;
        else start = maxDate.plusDays(1);
        LocalDate end = (until != null) ? until : today;
        if (!start.isBefore(end)) return 0;

        log.info("回補美股 {} 歷史價格: {} ~ {}", stockCode, start, end);
        int count = 0;
        for (HistoricalBar bar : priceFetch.fetchUsHistoricalRange(stockCode, start, end)) {
            if (bar.tradingDate().equals(today)) {
                log.debug("跳過美股 {} 今日 ({}) bar — 今日列獨佔給 ClosePersister", stockCode, today);
                continue;
            }
            if (store.existsHistory(stockCode, "美股", bar.tradingDate())) continue;
            if (store.upsertHistory(stockCode, "美股", bar.tradingDate(),
                    bar.open(), bar.high(), bar.low(), bar.close(), bar.volume())) {
                count++;   // 被拒的非正收盤列不得算成已寫入（Task 279）
            }
        }
        if (count > 0) log.info("美股 {} 匯入 {} 筆", stockCode, count);
        return count;
    }

    public int backfillUkStock(String stockCode, LocalDate since, LocalDate until) {
        LocalDate today = LocalDate.now(MarketClock.LON_ZONE);
        LocalDate maxDate = store.findMaxTradingDate(stockCode, "英股").orElse(null);
        LocalDate minDate = store.findMinTradingDate(stockCode, "英股").orElse(null);
        LocalDate start;
        if (maxDate == null) start = since;
        else if (minDate != null && since.isBefore(minDate)) start = since;
        else start = maxDate.plusDays(1);
        LocalDate end = (until != null) ? until : today;
        if (!start.isBefore(end)) return 0;

        log.info("回補英股 {} 歷史價格: {} ~ {}", stockCode, start, end);
        int count = 0;
        for (HistoricalBar bar : priceFetch.fetchUkHistoricalRange(stockCode, start, end)) {
            if (bar.tradingDate().equals(today)) {
                log.debug("跳過英股 {} 今日 ({}) bar — 今日列獨佔給 ClosePersister", stockCode, today);
                continue;
            }
            if (store.existsHistory(stockCode, "英股", bar.tradingDate())) continue;
            if (store.upsertHistory(stockCode, "英股", bar.tradingDate(),
                    bar.open(), bar.high(), bar.low(), bar.close(), bar.volume())) {
                count++;   // 被拒的非正收盤列不得算成已寫入（Task 279）
            }
        }
        if (count > 0) log.info("英股 {} 匯入 {} 筆", stockCode, count);
        return count;
    }

    /** 強制從 since 抓（忽略 maxDate），用於補中間缺漏。 */
    public int backfillExchangeRateFrom(String currency, LocalDate since) {
        if (!since.isBefore(LocalDate.now())) return 0;
        log.info("強制回補 {} 匯率: {} ~ today", currency, since);
        return upsertRates(currency, since);
    }

    /** 增量補：從 max(rate_date)+1 至今。 */
    public int backfillExchangeRate(String currency, LocalDate since) {
        LocalDate maxDate = store.findMaxRateDate(currency).orElse(null);
        LocalDate start = maxDate != null ? maxDate.plusDays(1) : since;
        if (!start.isBefore(LocalDate.now())) return 0;
        log.info("增量回補 {} 匯率: {} ~ today", currency, start);
        return upsertRates(currency, start);
    }

    private int upsertRates(String currency, LocalDate since) {
        int count = 0;
        for (ExchangeRateFetchClient.RateBar r : rateFetch.fetchRange(currency, since)) {
            store.upsertExchangeRate(currency, r.rateDate(), r.buyRate(), r.sellRate());
            count++;
        }
        if (count > 0) log.info("{} 匯率匯入 {} 筆", currency, count);
        return count;
    }

    /** 強制從 since 抓原物料收盤價（忽略 maxDate），用於首次補滿十年與補中間缺漏（Requirement 40）。 */
    public int backfillCommodityFrom(String code, LocalDate since) {
        if (!since.isBefore(LocalDate.now())) return 0;
        log.info("強制回補 {} 收盤價: {} ~ today", code, since);
        return upsertCommodities(code, since);
    }

    /** 增量補：從 max(price_date)+1 至今（Requirement 40）。 */
    public int backfillCommodity(String code, LocalDate since) {
        LocalDate maxDate = store.findMaxCommodityDate(code).orElse(null);
        LocalDate start = maxDate != null ? maxDate.plusDays(1) : since;
        if (!start.isBefore(LocalDate.now())) return 0;
        log.info("增量回補 {} 收盤價: {} ~ today", code, start);
        return upsertCommodities(code, start);
    }

    private int upsertCommodities(String code, LocalDate since) {
        int count = 0;
        for (CommodityFetchClient.CommodityBar b : commodityFetch.fetchRange(code, since, LocalDate.now())) {
            store.upsertCommodityPrice(
                    code,
                    b.priceDate(),
                    b.closePrice(),
                    b.provider(),
                    b.sourceUrl(),
                    b.sourceAvailableAt(),
                    b.fetchedAt());
            count++;
        }
        if (count > 0) log.info("{} 收盤價匯入 {} 筆", code, count);
        return count;
    }

    public Map<String, Object> backfillSingleStock(String stockCode, String market, LocalDate since, LocalDate until) {
        int count;
        if ("台股".equals(market)) count = backfillTwStock(stockCode, since, until);
        else if ("英股".equals(market)) count = backfillUkStock(stockCode, since, until);
        else count = backfillUsStock(stockCode, since, until);
        return Map.of("stockCode", stockCode, "market", market, "records", count, "since", since.toString());
    }

    /** 全部持股 + USD 匯率，10 年回補。供 backend `/api/market-data/history/backfill` proxy。 */
    public Map<String, Object> backfillAll() {
        LocalDate tenYearsAgo = LocalDate.now().minusYears(10);
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        Set<String> ukCodes = new LinkedHashSet<>();
        store.collectAllHeldCodes(twCodes, usCodes, ukCodes);
        try {
            collectLookthroughTopConstituents(twCodes, usCodes);
        } catch (Exception e) {
            log.warn("透視成份股清單收集失敗（backfillAll）: {}", e.getMessage());
        }

        int twTotal = 0, usTotal = 0, ukTotal = 0;
        for (String code : twCodes) {
            twTotal += backfillTwStock(code, tenYearsAgo, null);
            sleep(600);
        }
        for (String code : usCodes) {
            usTotal += backfillUsStock(code, tenYearsAgo, null);
            sleep(2000);
        }
        for (String code : ukCodes) {
            ukTotal += backfillUkStock(code, tenYearsAgo, null);
            sleep(2000);
        }
        int rateTotal = backfillExchangeRate("USD", tenYearsAgo);
        return Map.of(
                "twRecords", twTotal, "usRecords", usTotal, "ukRecords", ukTotal,
                "exchangeRateRecords", rateTotal,
                "twStocks", twCodes.size(), "usStocks", usCodes.size(), "ukStocks", ukCodes.size());
    }

    // ===== ETF 透視 top10 成份股回補（Task 129）=====

    /**
     * 每日重算 ETF 透視 top10 成份股並增量補齊歷史收盤（Task 129）。
     * 成份股不在即時抓價集合（collectAllStockCodes），靠此 cron 跟上每日收盤；既有列走 maxDate+1 增量、
     * 全新進榜成份股補滿 10 年（今日列仍獨佔給 ClosePersister）。18:30 TW：TW 已收盤、前夜美股收盤已可得。
     */
    @Scheduled(cron = "0 30 18 * * *", zone = "Asia/Taipei")
    public void dailyLookthroughBackfill() {
        Thread.ofVirtual().name("lookthrough-backfill").start(() -> {
            try {
                LocalDate since = LocalDate.now().minusYears(10);
                Set<String> twCodes = new LinkedHashSet<>();
                Set<String> usCodes = new LinkedHashSet<>();
                collectLookthroughTopConstituents(twCodes, usCodes);
                log.info("每日透視成份股回補：台股 {} 檔、美股 {} 檔", twCodes.size(), usCodes.size());
                for (String code : twCodes) { backfillTwStock(code, since, null); sleep(600); }
                for (String code : usCodes) { backfillUsStock(code, since, null); sleep(2000); }
                log.info("每日透視成份股回補完成");
            } catch (Exception e) {
                log.warn("每日透視成份股回補失敗: {}", e.getMessage());
            }
        });
    }

    /**
     * 重算「資產配置圓餅圖」台股 / 美股透視 top10 成份股代號，併入 twCodes / usCodes。
     * 演算法鏡像 BFF buildLookthrough / buildUsLookthrough（成份股同樣讀 MarketDataFetchService.getEtfHoldings，
     * 12h cache，與圓餅圖同一份資料）：
     *  - 台股：ETF（00 開頭）依權重正規化 cv×w/Σw；直接持股整筆；以代號加總；取前 10
     *  - 美股：ETF 依真實權重 cv×w/100（不正規化）；非 ETF 整筆；以代號加總；取前 10
     * 無代號成份股（MoneyDJ 名稱補不到代號）略過 —— 無法回補也不開放點擊。
     * 落入 top10 的直接持股 / ETF 自身代號已在主檔，後續回補會被 existsHistory / maxDate 條件自然 skip。
     */
    void collectLookthroughTopConstituents(Set<String> twCodes, Set<String> usCodes) {
        List<StockSourceQuery.HeldValueRow> holdings = store.collectLatestSnapshotHoldingsWithValue();
        if (holdings.isEmpty()) return;
        Map<String, BigDecimal> twAgg = new HashMap<>();
        Map<String, BigDecimal> usAgg = new HashMap<>();
        for (StockSourceQuery.HeldValueRow h : holdings) {
            String code = h.stockCode();
            if (code == null || code.isBlank()) continue;
            BigDecimal cv = h.currentValue() == null ? BigDecimal.ZERO : h.currentValue();
            String market = h.market();
            if ("台股".equals(market)) {
                if (code.startsWith("00")) addTwEtfConstituents(twAgg, code, cv);
                else twAgg.merge(code, cv, BigDecimal::add);
            } else if ("美股".equals(market)) {
                addUsConstituents(usAgg, code, cv);
            }
            // 英股無透視 tab，略過
        }
        addTopN(twAgg, twCodes, LOOKTHROUGH_TOP_N);
        addTopN(usAgg, usCodes, LOOKTHROUGH_TOP_N);
    }

    /** 台股 ETF：依成分股權重正規化分配 cv（cv×w/Σw），以代號加總（無代號者略過）。 */
    private void addTwEtfConstituents(Map<String, BigDecimal> agg, String etfCode, BigDecimal cv) {
        MarketDataFetchService.EtfHoldingsResult r = etfFetch.getEtfHoldings(etfCode, "台股");
        if (r == null || r.holdings() == null || r.holdings().isEmpty()) return;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (MarketDataFetchService.EtfHolding h : r.holdings()) {
            if (h.weight() != null && h.weight().compareTo(BigDecimal.ZERO) > 0) {
                weightSum = weightSum.add(h.weight());
            }
        }
        if (weightSum.compareTo(BigDecimal.ZERO) <= 0) return;
        for (MarketDataFetchService.EtfHolding h : r.holdings()) {
            String code = h.stockCode();
            if (code == null || code.isBlank()) continue;
            if (h.weight() == null || h.weight().compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal share = cv.multiply(h.weight()).divide(weightSum, 4, RoundingMode.HALF_UP);
            agg.merge(code, share, BigDecimal::add);
        }
    }

    /** 美股：ETF 依真實權重 cv×w/100（不正規化）；非 ETF（holdings 空）整筆計入該代號。 */
    private void addUsConstituents(Map<String, BigDecimal> agg, String code, BigDecimal cv) {
        MarketDataFetchService.EtfHoldingsResult r = etfFetch.getEtfHoldings(code, "美股");
        if (r == null || r.holdings() == null || r.holdings().isEmpty()) {
            agg.merge(code, cv, BigDecimal::add);  // 個股整筆計入
            return;
        }
        for (MarketDataFetchService.EtfHolding h : r.holdings()) {
            String hc = h.stockCode();
            if (hc == null || hc.isBlank()) continue;
            if (h.weight() == null || h.weight().compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal frac = h.weight().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            if (frac.compareTo(BigDecimal.ONE) > 0) frac = BigDecimal.ONE;  // 防呆：單檔權重 > 100%
            agg.merge(hc, cv.multiply(frac), BigDecimal::add);
        }
    }

    /** 取 agg 中市值前 n 大的代號加入 out。 */
    private static void addTopN(Map<String, BigDecimal> agg, Set<String> out, int n) {
        agg.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(n)
                .forEach(e -> out.add(e.getKey()));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
