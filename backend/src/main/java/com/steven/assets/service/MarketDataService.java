package com.steven.assets.service;

import com.steven.assets.repository.StockRepository;
import com.steven.assets.util.MarketZones;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Duration;
import java.time.ZonedDateTime;
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
    private final StockRepository stockMasterRepo;

    public MarketDataService(@Value("${external-materials.base-url:http://external-materials-service:8080}")
                             String externalUrl,
                             StockRepository stockMasterRepo) {
        this.priceServiceClient = WebClient.builder().baseUrl(externalUrl).build();
        this.stockMasterRepo = stockMasterRepo;
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
        // stockName fallback：ext-materials 無配息資料時（如 SGOV 國庫債 ETF）stockName 會是 null，
        // 從 stock 主檔補上，讓 SnapshotForm BFF 即使 live cache 還沒 warm-up 也能拿到名稱
        if (result.stockName() == null || result.stockName().isBlank()) {
            String masterName = stockMasterRepo.findByCodeAndMarket(stockCode, market)
                    .map(s -> s.getName()).orElse(null);
            if (masterName != null && !masterName.isBlank()) {
                result = new DividendRateResult(result.stockCode(), result.market(),
                        result.dividendRate(), result.source(), result.description(), masterName);
            }
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

    // 過去 / 未來年度：固定假日、無臨時休市傳播需求 → 永久快取。
    private final Map<Integer, Map<String, String>> twHolidayCache = new ConcurrentHashMap<>();
    // 當年度：颱風假 / 臨時休市可能於「任何時刻」由 ext-materials 偵測寫入（早盤排程 / 開機 self-heal /
    // 手動 detect），故不能永久快取；改以短 TTL，確保同日新偵測到的休市在 TTL 內傳播至 business 側
    // （market-status / 07:30 分析 / 警示 / 備份 / 交易日曆），不受限於任何固定時窗。
    private record TimedHolidays(Map<String, String> value, long expiresAt) {}
    private final Map<Integer, TimedHolidays> twHolidayCurrentYearCache = new ConcurrentHashMap<>();
    private static final long CURRENT_YEAR_TTL_MS = 10 * 60 * 1000L;

    public Map<String, String> getTwHolidays(int year) {
        int currentYear = ZonedDateTime.now(MarketZones.TW_ZONE).getYear();
        if (year != currentYear) {
            Map<String, String> cached = twHolidayCache.get(year);
            if (cached != null) return cached;
            // 同樣不以空表毒化永久快取：ext 一次瞬斷（如切到他年度日曆時）不得讓該年度整年假日
            // 被鎖成零筆（連國定假日一起漏）；只快取成功值，失敗此次降級、下次重抓。
            Map<String, String> fresh = fetchTwHolidaysFromExt(year);
            if (!fresh.isEmpty()) twHolidayCache.put(year, fresh);
            return fresh;
        }
        long now = System.currentTimeMillis();
        TimedHolidays cached = twHolidayCurrentYearCache.get(year);
        if (cached != null && now < cached.expiresAt() && !cached.value().isEmpty()) {
            return cached.value();
        }
        Map<String, String> fresh = fetchTwHolidaysFromExt(year);
        // 抓取失敗（空 map）不得毒化快取：沿用前一次成功值（若有），僅此次降級回空。
        if (fresh.isEmpty()) {
            return cached != null ? cached.value() : fresh;
        }
        twHolidayCurrentYearCache.put(year, new TimedHolidays(fresh, now + CURRENT_YEAR_TTL_MS));
        return fresh;
    }

    /**
     * 花錢前先做一次「權威即時颱風假偵測」（Task 163），回傳今日台股是否休市（true＝颱風假 / 臨時休市）。
     * 主動觸發 ext 立刻爬 DGPA 停班公告並 upsert {@code tw_market_closure}，**直接採用 ext 回傳的權威
     * {@code closedToday}** 作短路依據，呼叫端據此不送 LLM 批次。
     *
     * <p>動機：07:30「今日股市分析」等下游在 submit 前會花費 LLM（昂貴）；DGPA 爬取免費。故不賭
     * 05:00–08:45 {@code TwClosurePoller} 是否已在此刻前偵測並傳播完成，而是在花錢前主動確認一次。
     *
     * <p>刻意**不動假日快取**：偵測結果以回傳值直接短路，不改讀 {@code twHolidayCurrentYearCache}——
     * 避免「evict 後重抓瞬斷→抗毒化 fallback 失效→整年假日（含國定假日）丟空→反把假日誤判交易日」。
     * best-effort：ext / DGPA 失敗 → 回 {@code false}（不主張休市），交由 {@link #isTwTradingDay}（既有快取，
     * 含 poller 先前偵測到的休市）判斷，保守維持交易日，與 {@link #getTwHolidays} 既有退化一致。
     */
    public boolean refreshTwClosureToday() {
        try {
            Boolean closed = priceServiceClient.post()
                    .uri("/internal/tw-closure/detect")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .map(m -> Boolean.TRUE.equals(m.get("closedToday")))
                    .block(Duration.ofSeconds(20));
            boolean closedToday = Boolean.TRUE.equals(closed);
            log.info("花費前置：即時偵測台股颱風假 closedToday={}", closedToday);
            return closedToday;
        } catch (Exception e) {
            log.warn("即時偵測台股颱風假失敗（改由既有假日快取判斷，保守維持交易日）：{}", e.getMessage());
            return false;
        }
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

    /**
     * LSE 公定假日（英國銀行假日 + LSE 額外休市日）。
     * 與美股不同處：UK 銀行假日落在週末一律 forward（下個非已佔用工作日），
     * Christmas + Boxing Day 並列時兩者都會 forward 並避免互撞。
     */
    public Map<String, String> getUkHolidays(int year) {
        Map<String, String> h = new LinkedHashMap<>();
        // 元旦：周末 → 下個週一
        addUkObserved(h, LocalDate.of(year, 1, 1), "New Year's Day");
        // 復活節：Good Friday + Easter Monday
        LocalDate goodFri = LocalDate.parse(goodFriday(year));
        h.put(goodFri.toString(), "Good Friday");
        h.put(goodFri.plusDays(3).toString(), "Easter Monday");
        // 5 月第一個週一 = Early May Bank Holiday
        h.put(nthWeekday(year, 5, DayOfWeek.MONDAY, 1), "Early May Bank Holiday");
        // 5 月最後一個週一 = Spring Bank Holiday
        h.put(lastWeekday(year, 5, DayOfWeek.MONDAY), "Spring Bank Holiday");
        // 8 月最後一個週一 = Summer Bank Holiday
        h.put(lastWeekday(year, 8, DayOfWeek.MONDAY), "Summer Bank Holiday");
        // Christmas + Boxing Day：周末時需相互避撞
        addUkObserved(h, LocalDate.of(year, 12, 25), "Christmas Day");
        addUkObserved(h, LocalDate.of(year, 12, 26), "Boxing Day");
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

    public boolean isUkTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        return !getUkHolidays(date.getYear()).containsKey(date.toString());
    }

    /**
     * 該市場該日是否為交易日（平日且非該市場國定假日）—— 警示觸發 / email / 市場狀態的單一入口。
     * 市場字串 → {@code isTw/isUs/isUkTradingDay}；其餘（含 {@code 0000} 台股大盤）視為台股。
     */
    public boolean isTradingDay(String market, LocalDate date) {
        if ("美股".equals(market)) return isUsTradingDay(date);
        if ("英股".equals(market)) return isUkTradingDay(date);
        return isTwTradingDay(date);
    }

    /**
     * 該市場此刻是否開盤中：交易時段（{@link MarketZones#isMarketOpen}）且當日為交易日（含國定假日判斷）。
     * 供 {@code StockPriceService.getMarketStatus} / 交易日曆 / Dashboard 使用，使假日顯示「休市」。
     */
    public boolean isMarketOpenNow(String market) {
        if (!MarketZones.isMarketOpen(market)) return false;
        LocalDate today = ZonedDateTime.now(MarketZones.resolve(market)).toLocalDate();
        return isTradingDay(market, today);
    }

    /**
     * UK 銀行假日「下個工作日」順移：周末或已被其他假日佔用，往後推到非週末且未佔用的日子。
     * 適用 Christmas + Boxing Day 連續落在週末的情形：兩者依序順移到 Mon + Tue。
     */
    private void addUkObserved(Map<String, String> h, LocalDate date, String name) {
        LocalDate observed = date;
        while (observed.getDayOfWeek() == DayOfWeek.SATURDAY
                || observed.getDayOfWeek() == DayOfWeek.SUNDAY
                || h.containsKey(observed.toString())) {
            observed = observed.plusDays(1);
        }
        h.put(observed.toString(), name);
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

    private static final java.util.Set<String> UK_ETF_WHITELIST = java.util.Set.of(
            "CSPX", "VWRA", "VUSA", "EIMI", "IWDA");

    public boolean isEtf(String stockCode, String market) {
        if (stockCode == null) return false;
        if ("台股".equals(market)) return stockCode.startsWith("00");
        if ("美股".equals(market)) return US_ETF_WHITELIST.contains(stockCode.toUpperCase());
        if ("英股".equals(market)) return UK_ETF_WHITELIST.contains(stockCode.toUpperCase());
        return false;
    }
}
