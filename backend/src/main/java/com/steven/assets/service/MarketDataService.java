package com.steven.assets.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市場資料代理層：殖利率 / ETF 持股 / 股利歷史 / TWSE 假日 全數透過 ext-materials-service `/internal/*` 取得。
 *
 * 對外 API（外部行情 API）已集中在 ext-materials-service `MarketDataFetchService`，
 * 本服務僅保留：(1) 公開 record 類型；(2) 1 小時殖利率快取；(3) per-year 假日快取；
 *            (4) 美股假日純計算（無外部 API）；(5) ETF 白名單判斷（純計算）。
 */
@Slf4j
@Service
public class MarketDataService {

    private final WebClient priceServiceClient;

    public MarketDataService(@Value("${external-materials.base-url:http://external-materials-service:8080}")
                             String externalUrl) {
        this.priceServiceClient = WebClient.builder().baseUrl(externalUrl).build();
    }

    public record DividendRateResult(
            String stockCode, String market, BigDecimal dividendRate,
            String source, String description, String stockName) {
        public DividendRateResult(String stockCode, String market, BigDecimal dividendRate,
                                  String source, String description) {
            this(stockCode, market, dividendRate, source, description, null);
        }
    }

    public record EtfHolding(String stockCode, String stockName, BigDecimal weight, BigDecimal shares) {}

    public record EtfHoldingsResult(
            String stockCode, String market, boolean supported, String source,
            String asOfDate, String message, List<EtfHolding> holdings) {}

    public record DividendRow(
            Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
            String exDividendDate, BigDecimal yieldPct,
            String cashPaymentDate, String stockPaymentDate,
            Integer fillDays, BigDecimal previousClose) {
        public DividendRow(Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
                           String exDividendDate, BigDecimal yieldPct) {
            this(year, cashDividend, stockDividend, exDividendDate, yieldPct, null, null, null, null);
        }
    }

    public record DividendHistoryResult(
            String stockCode, String market, String source, String message, List<DividendRow> rows) {}

    public record PriceResult(
            String stockCode, String market, BigDecimal price,
            BigDecimal change, BigDecimal changePct, String source,
            String stockName,
            BigDecimal buyPrice, BigDecimal sellPrice,
            BigDecimal openPrice, BigDecimal previousClose,
            BigDecimal highPrice, BigDecimal lowPrice, Long volume) {
        public PriceResult(String stockCode, String market, BigDecimal price,
                           BigDecimal change, BigDecimal changePct, String source) {
            this(stockCode, market, price, change, changePct, source, null,
                    null, null, null, null, null, null, null);
        }
        public PriceResult(String stockCode, String market, BigDecimal price,
                           BigDecimal change, BigDecimal changePct, String source, String stockName) {
            this(stockCode, market, price, change, changePct, source, stockName,
                    null, null, null, null, null, null, null);
        }
    }

    // ─── 殖利率（1 小時 in-memory cache + proxy）───────────────────────────────

    private record CachedDividendRate(DividendRateResult result, long expiresAt) {}
    private final Map<String, CachedDividendRate> dividendRateCache = new ConcurrentHashMap<>();
    private static final long DIVIDEND_RATE_TTL_MS = 60 * 60 * 1000L;

    public DividendRateResult getDividendRate(String stockCode, String market) {
        String cacheKey = market + "_" + stockCode;
        long now = System.currentTimeMillis();
        CachedDividendRate cached = dividendRateCache.get(cacheKey);
        if (cached != null && cached.expiresAt > now) return cached.result;

        DividendRateResult result;
        try {
            result = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/dividend-rate")
                            .queryParam("code", stockCode).queryParam("market", market).build())
                    .retrieve()
                    .bodyToMono(DividendRateResult.class)
                    .block();
            if (result == null) {
                result = new DividendRateResult(stockCode, market, null, "N/A", "查無配息資料", null);
            }
        } catch (Exception e) {
            log.warn("呼叫 /internal/dividend-rate 失敗 {} {}: {}", market, stockCode, e.getMessage());
            result = new DividendRateResult(stockCode, market, null, "N/A",
                    "查詢失敗：" + e.getMessage(), null);
        }
        dividendRateCache.put(cacheKey, new CachedDividendRate(result, now + DIVIDEND_RATE_TTL_MS));
        return result;
    }

    // ─── ETF 持股（proxy）─────────────────────────────────────────────────────

    public EtfHoldingsResult getEtfHoldings(String stockCode, String market) {
        try {
            EtfHoldingsResult r = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/etf-holdings")
                            .queryParam("code", stockCode).queryParam("market", market).build())
                    .retrieve()
                    .bodyToMono(EtfHoldingsResult.class)
                    .block();
            if (r != null) return r;
        } catch (Exception e) {
            log.warn("呼叫 /internal/etf-holdings 失敗 {} {}: {}", market, stockCode, e.getMessage());
        }
        return new EtfHoldingsResult(stockCode, market, false, null, null,
                "external-materials-service 不可用", List.of());
    }

    public DividendHistoryResult getDividendHistory(String stockCode, String market, int years) {
        try {
            DividendHistoryResult r = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/dividend-history")
                            .queryParam("code", stockCode).queryParam("market", market)
                            .queryParam("years", years).build())
                    .retrieve()
                    .bodyToMono(DividendHistoryResult.class)
                    .block();
            if (r != null) return r;
        } catch (Exception e) {
            log.warn("呼叫 /internal/dividend-history 失敗 {} {}: {}", market, stockCode, e.getMessage());
        }
        return new DividendHistoryResult(stockCode, market, null,
                "external-materials-service 不可用", List.of());
    }

    // ─── 假日 / 交易日（TWSE 從 ext-materials proxy；NYSE 純計算） ─────────────

    private final Map<Integer, Map<String, String>> twHolidayCache = new ConcurrentHashMap<>();

    public Map<String, String> getTwHolidays(int year) {
        return twHolidayCache.computeIfAbsent(year, this::fetchTwHolidaysFromExt);
    }

    private Map<String, String> fetchTwHolidaysFromExt(int year) {
        try {
            Map<String, String> r = priceServiceClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/tw-holidays")
                            .queryParam("year", year).build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                    .block();
            return r != null ? r : Map.of();
        } catch (Exception e) {
            log.warn("呼叫 /internal/tw-holidays 失敗 year={}: {}", year, e.getMessage());
            return Map.of();
        }
    }

    public Map<String, String> getUsHolidays(int year) {
        Map<String, String> h = new LinkedHashMap<>();
        addObserved(h, year, 1, 1, "New Year's Day");
        h.put(nthWeekday(year, 1, DayOfWeek.MONDAY, 3), "MLK Day");
        h.put(nthWeekday(year, 2, DayOfWeek.MONDAY, 3), "Presidents' Day");
        h.put(goodFriday(year), "Good Friday");
        h.put(lastWeekday(year, 5, DayOfWeek.MONDAY), "Memorial Day");
        addObserved(h, year, 6, 19, "Juneteenth");
        addObserved(h, year, 7, 4, "Independence Day");
        h.put(nthWeekday(year, 9, DayOfWeek.MONDAY, 1), "Labor Day");
        h.put(nthWeekday(year, 11, DayOfWeek.THURSDAY, 4), "Thanksgiving");
        addObserved(h, year, 12, 25, "Christmas");
        return h;
    }

    public boolean isTwTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !getTwHolidays(date.getYear()).containsKey(date.toString());
    }

    public boolean isUsTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !getUsHolidays(date.getYear()).containsKey(date.toString());
    }

    private void addObserved(Map<String, String> h, int year, int month, int day, String name) {
        LocalDate date = LocalDate.of(year, month, day);
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) date = date.minusDays(1);
        else if (dow == DayOfWeek.SUNDAY) date = date.plusDays(1);
        h.put(date.toString(), name);
    }

    private String nthWeekday(int year, int month, DayOfWeek dow, int n) {
        LocalDate d = LocalDate.of(year, month, 1);
        int count = 0;
        while (true) {
            if (d.getDayOfWeek() == dow && ++count == n) return d.toString();
            d = d.plusDays(1);
        }
    }

    private String lastWeekday(int year, int month, DayOfWeek dow) {
        LocalDate d = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        while (d.getDayOfWeek() != dow) d = d.minusDays(1);
        return d.toString();
    }

    private String goodFriday(int year) {
        int a = year % 19, b = year / 100, c = year % 100;
        int d = b / 4, e = b % 4, f = (b + 8) / 25;
        int g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day).minusDays(2).toString();
    }

    // ─── ETF 判斷（純計算）────────────────────────────────────────────────────

    private static final java.util.Set<String> US_ETF_WHITELIST = java.util.Set.of(
            "VOO", "VT", "VTI", "VGT", "VYM", "VNQ", "VXUS",
            "SPY", "QQQ", "DIA", "IVV", "IWM",
            "AVGO", "SCHD", "JEPI", "JEPQ");

    public boolean isEtf(String stockCode, String market) {
        if (stockCode == null) return false;
        if ("台股".equals(market)) return stockCode.startsWith("00");
        if ("美股".equals(market)) return US_ETF_WHITELIST.contains(stockCode.toUpperCase());
        return false;
    }
}
