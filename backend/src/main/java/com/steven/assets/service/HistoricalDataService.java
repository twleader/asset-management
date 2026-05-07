package com.steven.assets.service;

import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Slf4j
@Service
public class HistoricalDataService {

    private final StockPriceHistoryRepository priceHistRepo;
    private final PriceQueryService priceQuery;
    private final ExchangeRateHistoryRepository rateHistRepo;
    private final com.steven.assets.repository.FundMasterRepository fundMasterRepo;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;
    private final WebClient priceServiceClient;


    public HistoricalDataService(
            StockPriceHistoryRepository priceHistRepo,
            PriceQueryService priceQuery,
            ExchangeRateHistoryRepository rateHistRepo,
            com.steven.assets.repository.FundMasterRepository fundMasterRepo,
            TwseIndexDailyHistoryRepository twseDailyRepo,
            @Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.priceHistRepo = priceHistRepo;
        this.priceQuery = priceQuery;
        this.rateHistRepo = rateHistRepo;
        this.fundMasterRepo = fundMasterRepo;
        this.twseDailyRepo = twseDailyRepo;
        this.priceServiceClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  歷史收盤價回補：proxy 至 external-materials-service
    //  business-services 不再直接呼叫 FinMind / Yahoo（spec/requirements.md:108-109）
    // ═══════════════════════════════════════════════════════════════════════

    /** 盤中分鐘級資料 bar：開盤後某 5 分鐘的 OHLC。 */
    public record IntradayBar(LocalDateTime time, BigDecimal open, BigDecimal high,
                               BigDecimal low, BigDecimal close) {}

    /** 內部 DTO：對應 ext-materials-service 的回應格式（time 為 ISO 字串）。 */
    private record IntradayBarDto(String time, BigDecimal open, BigDecimal high,
                                   BigDecimal low, BigDecimal close) {}

    /**
     * 警示盤中觸發補抓專用：5 分鐘 K 線。
     * 對外呼叫已搬到 ext-materials-service /internal/intraday-5m（FinMind/Yahoo 集中）。
     */
    public List<IntradayBar> fetchIntraday5m(String stockCode, String market, int daysBack) {
        try {
            IntradayBarDto[] resp = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/intraday-5m")
                            .queryParam("code", stockCode)
                            .queryParam("market", market)
                            .queryParam("daysBack", daysBack).build())
                    .retrieve()
                    .bodyToMono(IntradayBarDto[].class)
                    .block();
            if (resp == null) return List.of();
            List<IntradayBar> bars = new ArrayList<>();
            for (IntradayBarDto d : resp) {
                bars.add(new IntradayBar(LocalDateTime.parse(d.time()),
                        d.open(), d.high(), d.low(), d.close()));
            }
            return bars;
        } catch (Exception e) {
            log.warn("呼叫 ext-materials-service /internal/intraday-5m 失敗 ({} {}): {}",
                    market, stockCode, e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Backfill proxy methods — 對外抓的職責在 external-materials-service，
    //  business-services 透過 /internal/backfill/* 觸發，不再自己呼叫 FinMind / Yahoo。
    //  啟動時 10 年回補也搬到 external-materials-service 的 HistoricalBackfillService.startupBackfill()。
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public Map<String, Object> backfillSingleStock(String stockCode, String market, LocalDate since, LocalDate until) {
        try {
            return priceServiceClient.post()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/internal/backfill/stock")
                                .queryParam("code", stockCode)
                                .queryParam("market", market)
                                .queryParam("since", since.toString());
                        if (until != null) uriBuilder.queryParam("until", until.toString());
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            log.warn("呼叫 ext-materials-service /internal/backfill/stock 失敗: {}", e.getMessage());
            return Map.of("stockCode", stockCode, "market", market, "records", 0,
                    "since", since.toString(), "error", e.getMessage());
        }
    }

    public Map<String, Object> backfillSingleStock(String stockCode, String market, LocalDate since) {
        return backfillSingleStock(stockCode, market, since, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> backfillAll() {
        try {
            return priceServiceClient.post()
                    .uri("/internal/backfill/all")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (Exception e) {
            log.warn("呼叫 ext-materials-service /internal/backfill/all 失敗: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    public int backfillExchangeRate(String currency, LocalDate since) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = priceServiceClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/internal/backfill/exchange-rate")
                            .queryParam("currency", currency)
                            .queryParam("since", since.toString())
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return resp == null ? 0 : ((Number) resp.getOrDefault("records", 0)).intValue();
        } catch (Exception e) {
            log.warn("呼叫 ext-materials-service /internal/backfill/exchange-rate 失敗: {}", e.getMessage());
            return 0;
        }
    }

    public int backfillExchangeRateFrom(String currency, LocalDate since) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = priceServiceClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/internal/backfill/exchange-rate-from")
                            .queryParam("currency", currency)
                            .queryParam("since", since.toString())
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return resp == null ? 0 : ((Number) resp.getOrDefault("records", 0)).intValue();
        } catch (Exception e) {
            log.warn("呼叫 ext-materials-service /internal/backfill/exchange-rate-from 失敗: {}", e.getMessage());
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  每日排程：匯率（17:00 收盤後，proxy 至 ext-materials-service + 本地清理舊資料）
    //  股票收盤價排程已搬到 external-materials-service ClosePersister：
    //    台股 13:32（Redis dump）+ 16:00（FinMind 校驗）；
    //    美股 16:02 ET（Redis dump）+ 18:00 ET（FinMind 校驗）。
    //  啟動 10 年回補已搬到 external-materials-service HistoricalBackfillService.startupBackfill。
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 收盤後本地清理：刪除 10 年以前的 stock_price_history、exchange_rate_history。
     * 匯率排程（盤中 BOT 5 分鐘 / 收盤 17:00 FinMind）已搬到 ext-materials-service ExchangeRatePoller。
     */
    @Scheduled(cron = "0 30 17 * * MON-FRI", zone = "Asia/Taipei")
    @Transactional
    public void purgeOldHistory() {
        LocalDate cutoff = LocalDate.now().minusYears(10);
        long stockDeleted = priceHistRepo.deleteByTradingDateBefore(cutoff);
        if (stockDeleted > 0) log.info("已清除 {} 筆超過 10 年的股價歷史資料", stockDeleted);
        java.util.Set<String> currencies = new java.util.LinkedHashSet<>();
        currencies.add("USD");
        for (com.steven.assets.model.FundMaster fm : fundMasterRepo.findByActiveTrue()) {
            String c = fm.getCurrency();
            if (c != null && !c.isBlank() && !"TWD".equalsIgnoreCase(c)) currencies.add(c.toUpperCase());
        }
        for (String c : currencies) {
            long n = rateHistRepo.deleteByCurrencyAndRateDateBefore(c, cutoff);
            if (n > 0) log.info("已清除 {} 筆超過 10 年的 {} 匯率資料", n, c);
        }
    }

    /** 手動觸發 BOT 即期匯率抓取（前端 /api/market-data/exchange-rate/refresh proxy）。 */
    public void fetchBotExchangeRate(String currency) {
        try {
            priceServiceClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/internal/exchange-rate/refresh-bot")
                            .queryParam("currency", currency).build())
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            log.warn("呼叫 ext-materials-service /internal/exchange-rate/refresh-bot 失敗: {}", e.getMessage());
        }
    }

    /** 手動清理 X 年以前的單一幣別匯率（給 /api/market-data/exchange-rate/refresh 用）。 */
    @Transactional
    public void purgeOldExchangeRates(String currency, int keepYears) {
        LocalDate cutoff = LocalDate.now().minusYears(keepYears);
        long deleted = rateHistRepo.deleteByCurrencyAndRateDateBefore(currency, cutoff);
        if (deleted > 0) {
            log.info("已清除 {} 筆超過 {} 年的 {} 匯率資料", deleted, keepYears, currency);
        }
    }


    // ═══════════════════════════════════════════════════════════════════════
    //  查詢 API
    // ═══════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<StockPriceHistory> getStockHistory(String stockCode, String market, LocalDate start, LocalDate end) {
        // 0000 = 台股大盤：歷史走勢來自 twse_index_daily_history（含 OHLC）
        if ("0000".equals(stockCode) && "台股".equals(market)) {
            return twseDailyRepo.findByTradingDateBetweenOrderByTradingDateAsc(start, end).stream()
                    .map(d -> taiexToStockHistory(d, stockCode, market))
                    .toList();
        }
        List<StockPriceHistory> history = new ArrayList<>(
            priceHistRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(stockCode, market, start, end)
        );

        // 若查詢範圍包含今天，從 StockPrice 快取補上今日即時價。
        // 「股價」線必須是實際成交價，因此 source 含括號（如 "TWSE(買賣中價)"、"TWSE(前收)"）
        // 之估算值不予拼入，避免今日這格出現非實際成交價（例：台積電 2262.5 違反 5 元 tick）。
        ZoneId tz = "美股".equals(market) ? ZoneId.of("America/New_York") : ZoneId.of("Asia/Taipei");
        LocalDate today = LocalDate.now(tz);
        if (!end.isBefore(today) && !today.isBefore(start)) {
            boolean alreadyHasToday = history.stream().anyMatch(h -> h.getTradingDate().equals(today));
            if (!alreadyHasToday) {
                priceQuery.getLive(stockCode, market).ifPresent(sp -> {
                    String src = sp.source();
                    boolean isActualTrade = src != null && !src.contains("(");
                    if (isActualTrade && sp.price() != null && sp.tradingDate() != null
                            && today.toString().equals(sp.tradingDate())) {
                        history.add(StockPriceHistory.builder()
                            .stockCode(stockCode)
                            .market(market)
                            .tradingDate(today)
                            .closePrice(sp.price())
                            .build());
                    }
                });
            }
        }

        return history;
    }

    /**
     * 批次查詢多支股票在指定日期（或最近之前）的收盤價
     * 回傳 Map: "市場_代號" -> 收盤價
     */
    public record SnapshotPriceDto(String stockCode, String market, BigDecimal price, String tradingDate) {}

    @Transactional(readOnly = true)
    public List<SnapshotPriceDto> getPricesOnDate(List<Map<String, String>> stocks, LocalDate date) {
        List<SnapshotPriceDto> result = new ArrayList<>();
        for (Map<String, String> s : stocks) {
            String code = s.get("code");
            String mktStr = s.get("market");
            priceHistRepo.findClosestPrice(code, mktStr, date).ifPresent(h ->
                result.add(new SnapshotPriceDto(
                    code, mktStr,
                    h.getClosePrice(),
                    h.getTradingDate().toString()
                ))
            );
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<ExchangeRateHistory> getExchangeRateHistory(String currency, LocalDate start, LocalDate end) {
        return rateHistRepo.findByCurrencyAndRateDateBetweenOrderByRateDateAsc(currency, start, end);
    }

    @Transactional(readOnly = true)
    public List<ExchangeRateHistory> getAllExchangeRates(String currency) {
        return rateHistRepo.findByCurrencyOrderByRateDateAsc(currency);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ExchangeRateHistory> getLatestExchangeRate(String currency) {
        return rateHistRepo.findClosestRate(currency, LocalDate.now());
    }

    /** 取得指定日期或之前最近一筆匯率（用於歷史交易日匯率查詢） */
    @Transactional(readOnly = true)
    public java.util.Optional<ExchangeRateHistory> getExchangeRateOnDate(String currency, LocalDate date) {
        return rateHistRepo.findClosestRate(currency, date);
    }

    /** 股票名稱查詢：proxy 至 ext-materials-service /internal/stock-name。 */
    @SuppressWarnings("unchecked")
    public String fetchTwStockName(String code) {
        return fetchStockName(code, "台股");
    }

    public String fetchUsStockName(String code) {
        return fetchStockName(code, "美股");
    }

    private String fetchStockName(String code, String market) {
        try {
            Map<String, String> resp = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/stock-name")
                            .queryParam("code", code).queryParam("market", market).build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(m -> {
                        @SuppressWarnings("unchecked")
                        Map<String, String> r = (Map<String, String>) m;
                        return r;
                    })
                    .block();
            return resp == null ? "" : resp.getOrDefault("name", "");
        } catch (Exception e) {
            log.warn("呼叫 /internal/stock-name 失敗 {} {}: {}", market, code, e.getMessage());
            return "";
        }
    }

    /** 把大盤日線 row 包成 StockPriceHistory 形狀，讓走勢圖等通用 API 直接使用。 */
    private static StockPriceHistory taiexToStockHistory(TwseIndexDailyHistory d, String code, String market) {
        return StockPriceHistory.builder()
                .stockCode(code)
                .market(market)
                .tradingDate(d.getTradingDate())
                .openPrice(d.getOpenPoint())
                .highPrice(d.getHighPoint())
                .lowPrice(d.getLowPoint())
                .closePrice(d.getClosePoint())
                .build();
    }
}
