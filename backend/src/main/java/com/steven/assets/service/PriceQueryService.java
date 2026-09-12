package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.util.MarketZones;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 即時行情唯讀查詢：
 * - live：先讀 Redis (`price:{market}:{code}`)，miss 則 fallback 至 stock_price_history 最近一筆
 * - 全域列舉：透過 `price:index:{market}` set 列出所有有 cache 的代號
 * - 畫面持股列舉：只由呼叫端給定代號，絕不把全域 cache 聯集進來
 *
 * 寫入由 price-service 負責；business-services 不再直接抓外部 API。
 * 對外 endpoint /api/market-data/prices 等介面不變。
 */
@Slf4j
@Service
public class PriceQueryService {

    /**
     * 交易雷達手動回補的等待上界（Task 249）。
     * 鏈路預算：nginx /api/ 60s > axios 45s > 本值 30s > external 端自身約 20s（個股並行 ＋ 大盤 12s 上限）。
     */
    private static final long EXTERNAL_REFRESH_TIMEOUT_SECONDS = 30;

    private final StringRedisTemplate redis;
    private final StockPriceHistoryRepository historyRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient priceServiceClient;
    private final MarketDataService marketDataService;
    private final Clock clock;

    private static final Set<String> TRUSTED_TW_CLOSE_SOURCES = Set.of(
            "TWSE_MI_INDEX", "TPEX_DAILY_CLOSE", "FINMIND_TW_CLOSE");

    @Autowired
    public PriceQueryService(StringRedisTemplate redis,
                             StockPriceHistoryRepository historyRepo,
                             @Value("${external-materials.base-url:http://external-materials-service:8080}") String priceServiceUrl,
                             MarketDataService marketDataService) {
        this(redis, historyRepo, priceServiceUrl, marketDataService, Clock.systemUTC());
    }

    PriceQueryService(StringRedisTemplate redis,
                      StockPriceHistoryRepository historyRepo,
                      String priceServiceUrl,
                      MarketDataService marketDataService,
                      Clock clock) {
        this.redis = redis;
        this.historyRepo = historyRepo;
        this.priceServiceClient = WebClient.builder().baseUrl(priceServiceUrl).build();
        this.marketDataService = marketDataService;
        this.clock = clock;
    }

    /** Redis JSON payload 對應結構（對外 DTO 與舊 StockPriceDto 形狀相容）。 */
    public record LivePrice(
            String stockCode,
            String stockName,
            String market,
            BigDecimal price,
            BigDecimal previousClose,
            BigDecimal priceChange,
            BigDecimal changePercent,
            BigDecimal buyPrice,
            BigDecimal sellPrice,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            Long volume,
            String tradingDate,
            String updatedAt,
            Boolean closed,
            String source,
            String quoteStatus
    ) {
        public BigDecimal change() { return priceChange; }
        public BigDecimal changePct() { return changePercent; }
    }

    public enum DisplayPhase { OPEN, AFTER_CLOSE, PREVIOUS_SESSION }

    public record DisplaySession(
            DisplayPhase phase,
            LocalDate targetTradingDate,
            LocalDate marketToday
    ) {}

    public record PriceKey(String stockCode, String market) {}

    public Optional<LivePrice> getLive(String stockCode, String market) {
        Optional<LivePrice> cached = readRedis(stockCode, market);
        if (cached.isPresent()) return cached;
        return fallbackToHistory(stockCode, market);
    }

    /**
     * Read the explicitly requested quote keys with one Redis MGET.  Callers that already own
     * the bounded price series (the Trading Radar list path) pass it here so a Redis miss never
     * falls back to a per-symbol repository query.  Missing/malformed cache values deliberately
     * remain per-key misses; they must not borrow a quote from a same-code row in another market.
     */
    public Map<PriceKey, Optional<LivePrice>> getLiveBatch(
            Set<PriceKey> required,
            Map<PriceKey, List<StockPriceHistory>> historyByKey) {
        if (required == null || required.isEmpty()) return Map.of();
        List<PriceKey> keys = required.stream()
                .filter(key -> key != null && key.stockCode() != null && key.market() != null)
                .distinct().sorted(Comparator.comparing(PriceKey::market).thenComparing(PriceKey::stockCode)).toList();
        if (keys.isEmpty()) return Map.of();
        List<String> redisKeys = keys.stream()
                .map(key -> "price:" + key.market() + ":" + key.stockCode()).toList();
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(redisKeys);
        } catch (RuntimeException unavailable) {
            values = Collections.nCopies(keys.size(), null);
        }
        if (values == null || values.size() != keys.size()) values = Collections.nCopies(keys.size(), null);
        Map<PriceKey, Optional<LivePrice>> result = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            PriceKey key = keys.get(index);
            Optional<LivePrice> value = Optional.empty();
            String json = values.get(index);
            if (json != null) {
                try {
                    value = Optional.of(parse(json));
                } catch (Exception malformed) {
                    log.warn("Redis 行情批次解析失敗 {}", redisKeys.get(index));
                }
            }
            if (value.isEmpty()) {
                value = fallbackToBoundedHistory(key, historyByKey == null ? List.of()
                        : historyByKey.getOrDefault(key, List.of()));
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 顯示層唯一報價入口。開盤中才接受當日 Redis 成交；盤後、盤前與休市日只接受
     * {@code stock_price_history} 中目標交易日且帶可信 {@code close_source} 的完成收盤。
     */
    public Optional<LivePrice> getDisplayPrice(String stockCode, String market) {
        // Task 290 的 exact-date/provenance gate 僅收緊台股。美股與英股沿用既有
        // Redis-first、miss 才取最近完成日的語意，避免 nullable migration 讓既有海外歷史列
        // 在部署後被誤判為 CLOSE_PENDING。
        if (!"台股".equals(market)) return getLive(stockCode, market);

        DisplaySession session = displaySession(market);
        if (session.phase() == DisplayPhase.OPEN) {
            Optional<LivePrice> cached = readRedis(stockCode, market)
                    .filter(p -> session.marketToday().toString().equals(p.tradingDate()));
            if (cached.isPresent()) return Optional.of(withStatus(cached.get(), "LIVE", false));

            LocalDate previous = previousTradingDay(market, session.marketToday().minusDays(1))
                    .orElse(session.marketToday().minusDays(1));
            return exactTrustedHistory(stockCode, market, previous, "PREVIOUS_CLOSE")
                    .or(() -> Optional.of(pending(stockCode, market, previous)));
        }

        String status = session.phase() == DisplayPhase.AFTER_CLOSE
                ? "VERIFIED_CLOSE" : "PREVIOUS_CLOSE";
        return exactTrustedHistory(stockCode, market, session.targetTradingDate(), status)
                .or(() -> Optional.of(pending(stockCode, market, session.targetTradingDate())));
    }

    public DisplaySession displaySession(String market) {
        ZoneId zone = MarketZones.resolve(market);
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate today = now.toLocalDate();
        boolean tradingToday = marketDataService.isTradingDay(market, today);
        LocalTime time = now.toLocalTime();
        if (tradingToday
                && !time.isBefore(MarketZones.openTime(market))
                && time.isBefore(MarketZones.closeTime(market))) {
            return new DisplaySession(DisplayPhase.OPEN, today, today);
        }
        if (tradingToday && !time.isBefore(MarketZones.closeTime(market))) {
            return new DisplaySession(DisplayPhase.AFTER_CLOSE, today, today);
        }
        LocalDate start = tradingToday ? today.minusDays(1) : today;
        LocalDate target = previousTradingDay(market, start).orElse(start);
        return new DisplaySession(DisplayPhase.PREVIOUS_SESSION, target, today);
    }

    public boolean isTrustedClose(StockPriceHistory row) {
        if (row == null || row.getCloseSource() == null || row.getCloseSource().isBlank()) return false;
        return !"台股".equals(row.getMarket())
                || TRUSTED_TW_CLOSE_SOURCES.contains(row.getCloseSource());
    }

    private Optional<LocalDate> previousTradingDay(String market, LocalDate start) {
        LocalDate candidate = start;
        for (int i = 0; i < 14; i++, candidate = candidate.minusDays(1)) {
            if (marketDataService.isTradingDay(market, candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private Optional<LivePrice> exactTrustedHistory(
            String stockCode, String market, LocalDate tradingDate, String quoteStatus) {
        return historyRepo.findByStockCodeAndMarketAndTradingDate(stockCode, market, tradingDate)
                .filter(this::isTrustedClose)
                .map(row -> historyToLive(row, market, stockCode, quoteStatus));
    }

    private Optional<LivePrice> readRedis(String stockCode, String market) {
        String key = "price:" + market + ":" + stockCode;
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) return Optional.of(parse(json));
        } catch (Exception e) {
            log.warn("Redis 讀取或解析失敗 {}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * ETF 淨值與折溢價（Task 214）。{@code premiumDiscountPct} 為百分比數值（1.2 = 溢價 1.2%）：
     * 台股取自證交所已算好的折溢價欄、美股為 (市價−淨值)/淨值。
     *
     * @param navAsOf 淨值資料時點（台股 {@code yyyyMMdd HH:mm:ss}、美股 {@code yyyy-MM-dd}）
     */
    public record EtfNav(String stockCode, String market, BigDecimal nav,
                         BigDecimal premiumDiscountPct, String navAsOf, String source) {}

    /**
     * 取 ETF 淨值／折溢價；查無回 {@code Optional.empty()}。
     *
     * <p><b>刻意不 fallback 到 DB</b>（與 {@link #getLive} 不同）：淨值不存在於 {@code stock_price_history}，
     * 且「查無」是正常且常見的情形——個股本來就沒有淨值。呼叫端據此留白即可，不要補 0 或補字串。
     */
    public Optional<EtfNav> getEtfNav(String stockCode, String market) {
        String key = "price:etfnav:" + market + ":" + stockCode;
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(parseEtfNav(json, stockCode, market));
        } catch (Exception e) {
            log.warn("ETF 淨值讀取失敗 {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** Exact-key ETF NAV MGET counterpart of {@link #getLiveBatch(Set, Map)}. */
    public Map<PriceKey, Optional<EtfNav>> getEtfNavBatch(Set<PriceKey> required) {
        if (required == null || required.isEmpty()) return Map.of();
        List<PriceKey> keys = required.stream()
                .filter(key -> key != null && key.stockCode() != null && key.market() != null)
                .distinct().sorted(Comparator.comparing(PriceKey::market).thenComparing(PriceKey::stockCode)).toList();
        if (keys.isEmpty()) return Map.of();
        List<String> redisKeys = keys.stream()
                .map(key -> "price:etfnav:" + key.market() + ":" + key.stockCode()).toList();
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(redisKeys);
        } catch (RuntimeException unavailable) {
            values = Collections.nCopies(keys.size(), null);
        }
        if (values == null || values.size() != keys.size()) values = Collections.nCopies(keys.size(), null);
        Map<PriceKey, Optional<EtfNav>> result = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            PriceKey key = keys.get(index);
            try {
                result.put(key, values.get(index) == null ? Optional.empty()
                        : Optional.of(parseEtfNav(values.get(index), key.stockCode(), key.market())));
            } catch (Exception malformed) {
                log.warn("ETF 淨值批次解析失敗 {}", redisKeys.get(index));
                result.put(key, Optional.empty());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private EtfNav parseEtfNav(String json, String stockCode, String market) throws Exception {
        JsonNode n = mapper.readTree(json);
        return new EtfNav(
                n.path("stockCode").asText(stockCode), n.path("market").asText(market),
                decimalOrNull(n, "nav"), decimalOrNull(n, "premiumDiscountPct"),
                n.path("navAsOf").isMissingNode() ? null : n.path("navAsOf").asText(null),
                n.path("source").isMissingNode() ? null : n.path("source").asText(null));
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : new BigDecimal(v.asText());
    }

    public List<LivePrice> getAll() {
        List<LivePrice> all = new ArrayList<>();
        for (String market : new String[]{"台股", "美股", "英股"}) {
            String indexKey = "price:index:" + market;
            try {
                Set<String> codes = redis.opsForSet().members(indexKey);
                if (codes == null) continue;
                for (String code : codes) readRedis(code, market).ifPresent(all::add);
            } catch (Exception e) {
                log.warn("Redis index 讀取失敗 {}: {}", indexKey, e.getMessage());
            }
        }
        all.sort(Comparator.comparing(LivePrice::market).thenComparing(LivePrice::stockCode));
        return all;
    }

    /**
     * 只對呼叫端明確提供的必要代號逐檔套用顯示狀態閘門。
     *
     * <p>Dashboard 的持股視圖不能因為全域 Redis index 累積歷史代號而逐步放大；
     * 全域列舉仍由 {@link #getAll()} 保留給真正需要它的既有呼叫端。</p>
     */
    public List<LivePrice> getAllDisplayPrices(Set<PriceKey> required) {
        List<LivePrice> all = new ArrayList<>();
        Set<PriceKey> keys = new LinkedHashSet<>(required == null ? Set.of() : required);
        keys.stream()
                .filter(key -> !("台股".equals(key.market()) && "0000".equals(key.stockCode())))
                .forEach(key -> getDisplayPrice(key.stockCode(), key.market()).ifPresent(all::add));
        all.sort(Comparator.comparing(LivePrice::market).thenComparing(LivePrice::stockCode));
        return all;
    }

    /**
     * 觸發 price-service 背景刷新所有持股（fire-and-forget）。
     *
     * 抓 20+ 檔股票每檔最多 ~10s（含 fallback），整批可能 30s+；
     * BFF dashboard realtime 端點若同步等它，前端 axios 30s timeout 會炸。
     * Price-service 本身有 2 分鐘 cron，refresh 觸發只是想加速一次性刷新；
     * 不必等結果，下一次輪詢自然會讀到 Redis 最新內容。
     */
    /**
     * 同步觸發交易雷達專用的台股行情回補並等待完成（Task 249）。
     *
     * <p>與上方 {@link #triggerRefresh()} 的差別有二：(a) 只抓台股個股 ＋ 大盤，不碰美股／英股；
     * (b) <b>同步等待</b>，因為使用者按下「重新整理」的期待就是「抓完再給我結果」。
     * 逾時（{@code block(Duration)} 逾時時 Reactor 拋 {@code IllegalStateException}）與任何失敗
     * 一律由呼叫端降級處理，不得讓它變成 5xx。</p>
     */
    public JsonNode refreshTradingRadarPrices() {
        return priceServiceClient.post()
                .uri("/internal/refresh/tw-radar")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(java.time.Duration.ofSeconds(EXTERNAL_REFRESH_TIMEOUT_SECONDS));
    }

    public java.util.Map<String, Object> triggerRefresh() {
        priceServiceClient.post()
                .uri("/internal/refresh")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .subscribe(
                        resp -> log.info("price-service refresh 完成 tw={} us={}",
                                resp.path("twUpdated").asInt(0),
                                resp.path("usUpdated").asInt(0)),
                        err -> log.warn("呼叫 price-service /internal/refresh 失敗: {}", err.getMessage())
                );
        return java.util.Map.of("triggered", true);
    }

    private LivePrice parse(String json) throws Exception {
        JsonNode n = mapper.readTree(json);
        return new LivePrice(
                text(n, "stockCode"),
                text(n, "stockName"),
                text(n, "market"),
                bd(n, "price"),
                bd(n, "previousClose"),
                bd(n, "priceChange"),
                bd(n, "changePercent"),
                bd(n, "buyPrice"),
                bd(n, "sellPrice"),
                bd(n, "openPrice"),
                bd(n, "highPrice"),
                bd(n, "lowPrice"),
                n.hasNonNull("volume") ? n.get("volume").asLong() : null,
                text(n, "tradingDate"),
                text(n, "updatedAt"),
                n.hasNonNull("closed") ? n.get("closed").asBoolean() : null,
                text(n, "source"),
                text(n, "quoteStatus") != null
                        ? text(n, "quoteStatus")
                        : (n.path("closed").asBoolean(false) ? "VERIFIED_CLOSE" : "LIVE")
        );
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static BigDecimal bd(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        try { return new BigDecimal(v.asText()); } catch (Exception e) { return null; }
    }

    /** Redis miss → 從 stock_price_history 取最近一筆收盤當作 live。 */
    private Optional<LivePrice> fallbackToHistory(String stockCode, String market) {
        return historyRepo.findRecentN(stockCode, market, 1).stream()
                .findFirst()
                .map(h -> historyToLive(h, market, stockCode, "PREVIOUS_CLOSE"));
    }

    private Optional<LivePrice> fallbackToBoundedHistory(PriceKey key, List<StockPriceHistory> supplied) {
        if (supplied == null || supplied.isEmpty()) return Optional.empty();
        List<StockPriceHistory> rows = supplied.stream().filter(row -> row != null && row.getTradingDate() != null)
                .sorted(Comparator.comparing(StockPriceHistory::getTradingDate).reversed()).toList();
        if (rows.isEmpty()) return Optional.empty();
        StockPriceHistory latest = rows.getFirst();
        StockPriceHistory previous = rows.stream()
                .filter(row -> row.getTradingDate().isBefore(latest.getTradingDate())).findFirst().orElse(null);
        BigDecimal close = latest.getClosePrice();
        BigDecimal previousClose = previous == null ? null : previous.getClosePrice();
        BigDecimal change = close != null && previousClose != null ? close.subtract(previousClose) : null;
        BigDecimal changePercent = close != null && previousClose != null && previousClose.signum() > 0
                ? change.multiply(BigDecimal.valueOf(100)).divide(previousClose, 6, java.math.RoundingMode.HALF_UP)
                : null;
        return Optional.of(new LivePrice(key.stockCode(), null, key.market(), close, previousClose, change,
                changePercent, null, null, latest.getOpenPrice(), latest.getHighPrice(), latest.getLowPrice(),
                latest.getVolume(), latest.getTradingDate().toString(),
                LocalDateTime.now(MarketZones.TW_ZONE).toString(), true, latest.getCloseSource(), "PREVIOUS_CLOSE"));
    }

    private LivePrice historyToLive(
            StockPriceHistory h, String market, String code, String quoteStatus) {
        BigDecimal close = h.getClosePrice();
        BigDecimal prev = historyRepo.findClosestPrice(code, market, h.getTradingDate().minusDays(1))
                .map(StockPriceHistory::getClosePrice).orElse(null);
        BigDecimal change = (close != null && prev != null) ? close.subtract(prev) : null;
        BigDecimal changePct = null;
        if (close != null && prev != null && prev.signum() > 0) {
            changePct = change.multiply(BigDecimal.valueOf(100))
                    .divide(prev, 6, java.math.RoundingMode.HALF_UP);
        }
        return new LivePrice(
                code, null, market,
                close, prev, change, changePct,
                null, null,
                h.getOpenPrice(), h.getHighPrice(), h.getLowPrice(), h.getVolume(),
                h.getTradingDate().toString(),
                // Task 252：顯示用時間戳，顯式指定台北（切換後 systemDefault() 雖等於台北，但明示優於隱式）
                LocalDateTime.now(MarketZones.TW_ZONE).toString(),
                true,
                h.getCloseSource(),
                quoteStatus
        );
    }

    private LivePrice pending(String code, String market, LocalDate targetDate) {
        return new LivePrice(
                code, null, market,
                null, null, null, null,
                null, null, null, null, null, null,
                targetDate.toString(),
                LocalDateTime.now(clock.withZone(MarketZones.TW_ZONE)).toString(),
                false, null, "CLOSE_PENDING");
    }

    private LivePrice withStatus(LivePrice row, String status, boolean closed) {
        return new LivePrice(
                row.stockCode(), row.stockName(), row.market(), row.price(), row.previousClose(),
                row.priceChange(), row.changePercent(), row.buyPrice(), row.sellPrice(),
                row.openPrice(), row.highPrice(), row.lowPrice(), row.volume(), row.tradingDate(),
                row.updatedAt(), closed, row.source(), status);
    }

}
