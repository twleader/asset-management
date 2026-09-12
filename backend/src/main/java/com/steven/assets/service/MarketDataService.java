package com.steven.assets.service;

import com.steven.assets.integration.fubon.FubonEtfHoldingsParser;
import com.steven.assets.integration.fubon.FubonEtfHoldingsReasons;
import com.steven.assets.model.FubonEtfHoldingsSnapshot;
import com.steven.assets.repository.FubonEtfHoldingsSnapshotRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.dto.QuoteDetailDto;
import com.steven.assets.util.MarketZones;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市場資料代理層：殖利率 / ETF 持股 / 股利歷史 / 台股年度休市全數透過 ext-materials-service `/internal/*` 取得。
 *
 * 對外 API（外部行情 API）已集中在 ext-materials-service `MarketDataFetchService`，
 * 本服務僅保留：(1) 公開 record 類型；(2) 1 小時殖利率快取；(3) 當年度短期假日快取；
 *            (4) 美股 / 英股假日純計算（無外部 API）；(5) ETF 白名單判斷（純計算）。
 */
@Slf4j
@Service
public class MarketDataService {

    /** Bound the internal pure-read bridge so a stalled peer remains a child-local failure. */
    static final Duration QUOTE_DETAIL_TIMEOUT = Duration.ofSeconds(2);

    /** 美東時區，供 {@link #mostRecentCompletedUsTradingDay(Instant)} 換算「當地今天」。 */
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /** 美東收盤時刻，供判斷「已完成的最近一個美股交易日」（Task 294.2 起，Task 332 隨方法一併移入本類）。 */
    private static final LocalTime US_MARKET_CLOSE = LocalTime.of(16, 0);

    /** 台股市場代碼字面值，供 {@link #getEtfHoldings(String, String)} 分流判斷（Task 390）。 */
    private static final String TW_MARKET = "台股";

    private final WebClient priceServiceClient;
    private final StockRepository stockMasterRepo;
    private final FubonEtfHoldingsSnapshotRepository fubonEtfHoldingsSnapshotRepository;

    public MarketDataService(@Value("${external-materials.base-url:http://external-materials-service:8080}")
                             String externalUrl,
                             StockRepository stockMasterRepo,
                             FubonEtfHoldingsSnapshotRepository fubonEtfHoldingsSnapshotRepository) {
        this.priceServiceClient = WebClient.builder().baseUrl(externalUrl).build();
        this.stockMasterRepo = stockMasterRepo;
        this.fubonEtfHoldingsSnapshotRepository = fubonEtfHoldingsSnapshotRepository;
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
            Integer fillDays, BigDecimal previousClose,
            /**
             * 除權日（Task 357／Requirement 94），ISO {@code yyyy-MM-dd} 或 null。
             * 追加在既有欄位之後，供下游 bff／frontend 計算
             * anchorDate = {@code exDividendDate ?? exRightsDate}（357.3e-1／357.3e-2）。
             */
            String exRightsDate) {
        /** Compatibility constructor：357 之前既有的完整形狀（不含除權日）。 */
        public DividendRow(Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
                           String exDividendDate, BigDecimal yieldPct,
                           String cashPaymentDate, String stockPaymentDate,
                           Integer fillDays, BigDecimal previousClose) {
            this(year, cashDividend, stockDividend, exDividendDate, yieldPct,
                    cashPaymentDate, stockPaymentDate, fillDays, previousClose, null);
        }

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

    public QuoteDetailDto.Response getQuoteDetail(String stockCode, String market) {
        try {
            QuoteDetailDto.Response result = priceServiceClient.get().uri(uriBuilder -> uriBuilder.path("/internal/quote-detail")
                            .queryParam("code", stockCode).queryParam("market", market).build())
                    .retrieve().bodyToMono(QuoteDetailDto.Response.class).timeout(QUOTE_DETAIL_TIMEOUT).block();
            if (approvedQuoteDetailSnapshot(result, stockCode, market)) return result;
            if (result != null && !result.supported()) return unavailable(stockCode, market, false, "此市場不支援行情五檔");
        } catch (Exception e) {
            log.warn("呼叫 /internal/quote-detail 失敗 {} {}: {}", market, stockCode, e.getClass().getSimpleName());
        }
        return unavailable(stockCode, market, true, "暫時無法取得行情五檔");
    }

    private static QuoteDetailDto.Response unavailable(
            String stockCode, String market, boolean supported, String message) {
        return new QuoteDetailDto.Response(stockCode, null, market, supported, false, null, message,
                null, null, "UNKNOWN", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, List.<QuoteDetailDto.OrderBookLevel>of());
    }

    /** Only a complete, persisted producer snapshot may cross this no-request-time-vendor boundary. */
    private static boolean approvedQuoteDetailSnapshot(
            QuoteDetailDto.Response detail, String requestedCode, String requestedMarket) {
        if (detail == null || requestedCode == null || requestedMarket == null || !detail.supported() || !detail.available()
                || !requestedCode.equals(detail.stockCode()) || !requestedMarket.equals(detail.market())
                || !("FUBON_BOOKS".equals(detail.source()) || "YAHOO_TW".equals(detail.source()))
                || detail.stockName() == null || detail.stockName().isBlank()
                || detail.sourceTime() == null || detail.fetchedAt() == null || !"OPEN".equals(detail.marketStatus())
                || !positive(detail.price()) || !positive(detail.previousClose())
                || detail.levels() == null || detail.levels().size() != 5) {
            return false;
        }
        BigDecimal previousBid = null;
        BigDecimal previousAsk = null;
        java.util.Set<BigDecimal> bids = new java.util.HashSet<>();
        java.util.Set<BigDecimal> asks = new java.util.HashSet<>();
        for (int index = 0; index < 5; index++) {
            QuoteDetailDto.OrderBookLevel level = detail.levels().get(index);
            if (level == null || level.level() != index + 1 || !positive(level.bidPrice()) || !positive(level.askPrice())
                    || level.bidVolumeLots() == null || level.bidVolumeLots() <= 0
                    || level.askVolumeLots() == null || level.askVolumeLots() <= 0
                    || !bids.add(level.bidPrice().stripTrailingZeros()) || !asks.add(level.askPrice().stripTrailingZeros())
                    || (previousBid != null && previousBid.compareTo(level.bidPrice()) <= 0)
                    || (previousAsk != null && previousAsk.compareTo(level.askPrice()) >= 0)) {
                return false;
            }
            previousBid = level.bidPrice();
            previousAsk = level.askPrice();
        }
        return true;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
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

    /**
     * ETF 成分股持股明細。台股（{@link #TW_MARKET}）改讀本地
     * {@code fubon_etf_holdings_snapshot}（Task 389／390），不再呼叫
     * {@code external-materials-service} 的 {@code /internal/etf-holdings}；美股維持原本呼叫該
     * endpoint（Fubon 無美股覆蓋，繼續用既有 Yahoo／FinMind 路徑）。
     */
    public EtfHoldingsResult getEtfHoldings(String stockCode, String market) {
        if (TW_MARKET.equals(market)) {
            return getTwEtfHoldingsFromFubonSnapshot(stockCode, market);
        }
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

    /**
     * 台股 ETF 成分股：讀 {@code fubon_etf_holdings_snapshot}（Task 389 排程寫入）並用
     * {@link FubonEtfHoldingsParser} 解析（Task 390）。請求路徑不做供應商 I/O；external 的
     * 台股 Yahoo／FinMind 路徑仍保留給既有 in-process 歷史回補。
     */
    private EtfHoldingsResult getTwEtfHoldingsFromFubonSnapshot(String stockCode, String market) {
        FubonEtfHoldingsSnapshot snapshot;
        try {
            snapshot = fubonEtfHoldingsSnapshotRepository.findById(stockCode).orElse(null);
        } catch (RuntimeException exception) {
            return new EtfHoldingsResult(stockCode, market, false, null, null, "同步資料暫時無法讀取", List.of());
        }
        if (snapshot == null) {
            return new EtfHoldingsResult(stockCode, market, false, null, null, "尚無同步資料", List.of());
        }
        if (!Boolean.TRUE.equals(snapshot.getSuccess())) {
            return new EtfHoldingsResult(stockCode, market, false, null, null,
                    "本次同步查詢失敗：" + FubonEtfHoldingsReasons.sanitize(snapshot.getReason()), List.of());
        }
        Optional<FubonEtfHoldingsParser.Parsed> parsed = FubonEtfHoldingsParser.parse(stockCode, snapshot.getRawResponseJson());
        if (parsed.isEmpty() || !TW_MARKET.equals(snapshot.getMarket()) || !stockCode.equals(snapshot.getEtfStockCode())) {
            return new EtfHoldingsResult(stockCode, market, false, null, null, "同步資料格式不可用", List.of());
        }
        FubonEtfHoldingsParser.Parsed result = parsed.get();
        String asOfDate = result.sourceDate() == null ? null : result.sourceDate().toString();
        return new EtfHoldingsResult(stockCode, market, true, "Fubon", asOfDate,
                result.holdings().isEmpty() ? "無股票成分資料" : null, result.holdings());
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

    // ─── 假日 / 交易日（台股 TWSE primary / DGPA provisional proxy；NYSE 純計算） ──

    // 當年度：颱風假 / 臨時休市可能於「任何時刻」由 ext-materials 偵測寫入（早盤排程 / 開機 self-heal /
    // 手動 detect），故不能永久快取；改以短 TTL，確保同日新偵測到的休市在 TTL 內傳播至 business 側
    // （market-status / 07:30 分析 / 警示 / 備份 / 交易日曆），不受限於任何固定時窗。
    private record TimedHolidays(Map<String, String> value, long expiresAt) {}
    private final Map<Integer, TimedHolidays> twHolidayCurrentYearCache = new ConcurrentHashMap<>();
    private static final long CURRENT_YEAR_TTL_MS = 10 * 60 * 1000L;

    public Map<String, String> getTwHolidays(int year) {
        int currentYear = ZonedDateTime.now(MarketZones.TW_ZONE).getYear();
        if (year != currentYear) {
            // 非當年度每次 proxy，由 external 的 source-aware cache 唯一管理 DGPA 暫行 → TWSE 升級。
            // business 端不可永久短路，否則先讀到的 DGPA 會遮蔽後續發布的 TWSE。
            return fetchTwHolidaysFromExt(year);
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
     * <p>動機：08:45「今日股市分析」等下游在 submit 前會花費 LLM（昂貴）；DGPA 爬取免費。故不賭
     * 05:00–07:00 {@code TwClosurePoller} 是否已在此刻前偵測並傳播完成，而是在花錢前主動確認一次。
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
        addNewYearObserved(h, year, "New Year's Day");
        h.put(nthWeekday(year, 1, DayOfWeek.MONDAY, 3), "MLK Day");
        h.put(nthWeekday(year, 2, DayOfWeek.MONDAY, 3), "Presidents' Day");
        h.put(goodFriday(year), "Good Friday");
        h.put(lastWeekday(year, 5, DayOfWeek.MONDAY), "Memorial Day");
        // Juneteenth became a NYSE full-day holiday in 2022.  It was a federal
        // holiday from 2021, but the exchange remained open on 2021-06-18/21;
        // applying observed rules to earlier years would shift session windows.
        if (year >= 2022) addObserved(h, year, 6, 19, "Juneteenth");
        addObserved(h, year, 7, 4, "Independence Day");
        h.put(nthWeekday(year, 9, DayOfWeek.MONDAY, 1), "Labor Day");
        h.put(nthWeekday(year, 11, DayOfWeek.THURSDAY, 4), "Thanksgiving");
        addObserved(h, year, 12, 25, "Christmas");
        addUsExceptionalClosures(h, year);
        return h;
    }

    /**
     * Full-day NYSE closures that are not recurring holidays.  Keep this list
     * explicit and date-based: a missing exceptional closure must never be
     * guessed from a weekday, and adding a future date requires an auditable
     * exchange announcement.
     */
    private static void addUsExceptionalClosures(Map<String, String> holidays, int year) {
        Map<String, String> exceptional = Map.ofEntries(
                Map.entry("2001-09-11", "NYSE closure - September 11 attacks"),
                Map.entry("2001-09-12", "NYSE closure - September 11 attacks"),
                Map.entry("2001-09-13", "NYSE closure - September 11 attacks"),
                Map.entry("2001-09-14", "NYSE closure - September 11 attacks"),
                Map.entry("2004-06-11", "NYSE closure - President Reagan funeral"),
                Map.entry("2007-01-02", "NYSE closure - President Ford funeral"),
                Map.entry("2012-10-29", "NYSE closure - Hurricane Sandy"),
                Map.entry("2012-10-30", "NYSE closure - Hurricane Sandy"),
                Map.entry("2018-12-05", "NYSE closure - President George H.W. Bush funeral"),
                Map.entry("2025-01-09", "NYSE closure - President Carter funeral"));
        exceptional.forEach((date, reason) -> {
            if (date.startsWith(Integer.toString(year) + "-")) holidays.put(date, reason);
        });
    }

    /** NYSE New Year's rule: Sunday is observed Monday; Saturday is not observed Friday. */
    private static void addNewYearObserved(Map<String, String> holidays, int year, String label) {
        LocalDate date = LocalDate.of(year, 1, 1);
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) date = date.plusDays(1);
        if (date.getDayOfWeek() != DayOfWeek.SATURDAY) holidays.put(date.toString(), label);
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

    /**
     * 可區分「交易日／休市日／日曆不可得」的台股交易日判定（Task 291）。
     *
     * <p>既有 {@link #isTwTradingDay(LocalDate)} 在假日 proxy 失敗時會以空 map 降級，適合原本
     * 只求不中斷的呼叫端；匯率 as-of 不可把「日曆讀不到」誤當交易日，否則可能選到錯誤日期並
     * 沿用 stale 匯率。因此本方法在平日拿到空年度日曆時回 {@link Optional#empty()}，由雷達
     * fail closed。週末不需要查日曆即可確定休市，直接回 {@code Optional.of(false)}。</p>
     */
    public Optional<Boolean> isTwTradingDayKnown(LocalDate date) {
        if (date == null) return Optional.empty();
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return Optional.of(false);
        }
        Map<String, String> holidays = getTwHolidays(date.getYear());
        if (holidays == null || holidays.isEmpty()) return Optional.empty();
        return Optional.of(!holidays.containsKey(date.toString()));
    }

    /**
     * Read-only calendar lookup for a bounded request context.  Unlike
     * {@link #isTwTradingDayKnown(LocalDate)}, this method must never refresh
     * the holiday proxy: a missing or expired local snapshot is an unknown
     * calendar and callers must fail closed.
     */
    public Optional<Boolean> isTwTradingDayCachedOnly(LocalDate date) {
        if (date == null) return Optional.empty();
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return Optional.of(false);
        TimedHolidays cached = twHolidayCurrentYearCache.get(date.getYear());
        if (cached == null || cached.value() == null || cached.value().isEmpty()
                || System.currentTimeMillis() >= cached.expiresAt()) {
            return Optional.empty();
        }
        return Optional.of(!cached.value().containsKey(date.toString()));
    }

    /**
     * 可區分「交易日／休市日／日曆不可得」的跨市場交易日判定。
     * 台股沿用 ext-materials 的權威日曆；美股與英股使用本地完整假日表。
     * 回測與配息 session 計數不得把日曆讀取失敗當成平日，因此台股未知時回空值。
     */
    public Optional<Boolean> isTradingDayKnown(String market, LocalDate date) {
        if (date == null) return Optional.empty();
        if ("美股".equals(market) || "US".equalsIgnoreCase(String.valueOf(market))) {
            return Optional.of(isUsTradingDay(date));
        }
        if ("英股".equals(market) || "UK".equalsIgnoreCase(String.valueOf(market))) {
            return Optional.of(isUkTradingDay(date));
        }
        return isTwTradingDayKnown(date);
    }

    /**
     * Local/cache-only counterpart of {@link #isTradingDayKnown(String, LocalDate)}.
     * US/UK calendars are deterministic local tables; Taiwan requires a still-valid
     * local authority snapshot and never falls through to the external proxy.
     */
    public Optional<Boolean> isTradingDayCachedOnly(String market, LocalDate date) {
        if (date == null) return Optional.empty();
        if ("美股".equals(market) || "US".equalsIgnoreCase(String.valueOf(market))) {
            return Optional.of(isUsTradingDay(date));
        }
        if ("英股".equals(market) || "UK".equalsIgnoreCase(String.valueOf(market))) {
            return Optional.of(isUkTradingDay(date));
        }
        return isTwTradingDayCachedOnly(date);
    }

    /**
     * Produces a request-bounded future-session list without external I/O.
     * Any unknown date invalidates the whole result so a caller cannot silently
     * treat a weekday as an open Taiwan session.
     */
    public Optional<List<LocalDate>> futureTradingSessionsCachedOnly(
            String market, LocalDate startExclusive, int requiredSessions, int maxCalendarDays) {
        if (startExclusive == null || requiredSessions < 0 || maxCalendarDays < 0) return Optional.empty();
        if (requiredSessions == 0) return Optional.of(List.of());
        List<LocalDate> sessions = new java.util.ArrayList<>();
        for (int i = 1; i <= maxCalendarDays && sessions.size() < requiredSessions; i++) {
            LocalDate date = startExclusive.plusDays(i);
            Optional<Boolean> known = isTradingDayCachedOnly(market, date);
            if (known == null || known.isEmpty()) return Optional.empty();
            if (known.get()) sessions.add(date);
        }
        return Optional.of(List.copyOf(sessions));
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
     * 已完成（收盤時刻已過）的最近一個美股交易日——「美股日線該有哪一天」的<b>單一</b>權威答案（Task 332）。
     *
     * <p>原為 {@code TradingRadarService.mostRecentCompletedUsTradingDay(Instant)} 的 private 方法
     * （Task 294.2）：IXIC 大盤不像台股組有 Redis 即時價可退回判斷（刻意不併入即時價，見 294.1 背景），
     * 故直接用美東收盤時刻界定「已完成」——收盤前，今天尚不能算數，須往前一個交易日找。</p>
     *
     * <p>Task 332 把它提升為共用方法：交易雷達（判 IXIC 日線是否 stale）與
     * {@link IndexDailyRefreshScheduler}（判要不要回補）若各持一把尺，就會出現
     * 「雷達說 stale、自癒說皆為最新」的分歧（Requirement 75 的原始事故）。
     * <b>兩邊一律呼叫本方法，不得各自複製一份邏輯。</b></p>
     */
    public LocalDate mostRecentCompletedUsTradingDay(Instant decisionInstant) {
        ZonedDateTime nowNy = decisionInstant.atZone(NEW_YORK);
        LocalDate day = nowNy.toLocalTime().isBefore(US_MARKET_CLOSE)
                ? nowNy.toLocalDate().minusDays(1)
                : nowNy.toLocalDate();
        for (int i = 0; i < 14; i++) {
            if (isUsTradingDay(day)) return day;
            day = day.minusDays(1);
        }
        return day;
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
