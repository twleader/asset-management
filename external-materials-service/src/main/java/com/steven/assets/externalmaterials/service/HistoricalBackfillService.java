package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.ExchangeRateFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.HistoricalBar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
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
    private final StockSourceQuery store;

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
                store.collectAllHeldCodes(twCodes, usCodes);

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

                for (String currency : store.collectTrackedCurrencies()) {
                    LocalDate rateMinDate = store.findMinRateDate(currency).orElse(null);
                    if (rateMinDate == null || since.isBefore(rateMinDate)) {
                        log.info("啟動補齊 {} 匯率 (minDate={}，補至 {})", currency, rateMinDate, since);
                        backfillExchangeRateFrom(currency, since);
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
     */
    public int backfillTwStock(String stockCode, LocalDate since, LocalDate until) {
        LocalDate maxDate = store.findMaxTradingDate(stockCode, "台股").orElse(null);
        LocalDate minDate = store.findMinTradingDate(stockCode, "台股").orElse(null);
        LocalDate start;
        if (maxDate == null) start = since;
        else if (minDate != null && since.isBefore(minDate)) start = since;
        else start = maxDate.plusDays(1);
        LocalDate end = (until != null) ? until : LocalDate.now();
        if (!start.isBefore(end)) return 0;

        log.info("回補台股 {} 歷史價格: {} ~ {}", stockCode, start, end);
        int count = 0;
        for (HistoricalBar bar : priceFetch.fetchTwHistoricalRange(stockCode, start, end)) {
            // 以使用者輸入的原始代號存入（FinMind 後綴版只用於 fetch，不汙染主鍵）
            if (store.existsHistory(stockCode, "台股", bar.tradingDate())) continue;
            store.upsertHistory(stockCode, "台股", bar.tradingDate(),
                    bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
            count++;
        }
        if (count > 0) log.info("台股 {} 匯入 {} 筆", stockCode, count);
        return count;
    }

    public int backfillUsStock(String stockCode, LocalDate since, LocalDate until) {
        LocalDate maxDate = store.findMaxTradingDate(stockCode, "美股").orElse(null);
        LocalDate minDate = store.findMinTradingDate(stockCode, "美股").orElse(null);
        LocalDate start;
        if (maxDate == null) start = since;
        else if (minDate != null && since.isBefore(minDate)) start = since;
        else start = maxDate.plusDays(1);
        LocalDate end = (until != null) ? until : LocalDate.now();
        if (!start.isBefore(end)) return 0;

        log.info("回補美股 {} 歷史價格: {} ~ {}", stockCode, start, end);
        int count = 0;
        for (HistoricalBar bar : priceFetch.fetchUsHistoricalRange(stockCode, start, end)) {
            if (store.existsHistory(stockCode, "美股", bar.tradingDate())) continue;
            store.upsertHistory(stockCode, "美股", bar.tradingDate(),
                    bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
            count++;
        }
        if (count > 0) log.info("美股 {} 匯入 {} 筆", stockCode, count);
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

    public Map<String, Object> backfillSingleStock(String stockCode, String market, LocalDate since, LocalDate until) {
        int count = "台股".equals(market)
                ? backfillTwStock(stockCode, since, until)
                : backfillUsStock(stockCode, since, until);
        return Map.of("stockCode", stockCode, "market", market, "records", count, "since", since.toString());
    }

    /** 全部持股 + USD 匯率，10 年回補。供 backend `/api/market-data/history/backfill` proxy。 */
    public Map<String, Object> backfillAll() {
        LocalDate tenYearsAgo = LocalDate.now().minusYears(10);
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        store.collectAllHeldCodes(twCodes, usCodes);

        int twTotal = 0, usTotal = 0;
        for (String code : twCodes) {
            twTotal += backfillTwStock(code, tenYearsAgo, null);
            sleep(600);
        }
        for (String code : usCodes) {
            usTotal += backfillUsStock(code, tenYearsAgo, null);
            sleep(2000);
        }
        int rateTotal = backfillExchangeRate("USD", tenYearsAgo);
        return Map.of(
                "twRecords", twTotal, "usRecords", usTotal,
                "exchangeRateRecords", rateTotal,
                "twStocks", twCodes.size(), "usStocks", usCodes.size());
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
