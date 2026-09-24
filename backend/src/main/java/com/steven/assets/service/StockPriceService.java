package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 股價查詢 facade（已不再抓價）。
 *
 * 抓價、排程、收盤紀錄已搬至獨立的 price-service 微服務，盤中寫 Redis、盤後寫 stock_price_history。
 * 本類僅作 BFF / 前端對外契約的 stable façade：
 * - getAllPrices / getPrice：從 PriceQueryService 讀 Redis（miss 則 fallback 至歷史表）
 * - manualRefresh：觸發 price-service 同步刷新
 * - getMarketStatus：時區交易時段 + 交易日判斷（含國定假日，經 MarketDataService.isMarketOpenNow；與 price-service 獨立計算，無 Redis 依賴）
 * - getLiveAssets：以最新快照持倉 × Redis live 報價即時計算總資產
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockPriceService {

    private static final DateTimeFormatter API_LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();

    private final AssetSnapshotRepository snapshotRepo;
    private final ExchangeRateHistoryRepository rateHistRepo;
    private final StockRepository stockMasterRepo;
    private final PriceQueryService priceQuery;
    private final MarketDataService marketDataService;

    // 「市場是否開盤」單一入口：MarketDataService.isMarketOpenNow（MarketZones 時段 + 交易日含國定假日），
    // 使 getMarketStatus / 交易日曆 / Dashboard 在國定假日顯示「休市」而非「開盤中」。
    // 開收盤時刻與時區的單一來源仍為 MarketZones（CLAUDE.md「相同的資料只能存一份」）。
    public boolean isTwMarketOpen() {
        return marketDataService.isMarketOpenNow("台股");
    }

    public boolean isUsMarketOpen() {
        return marketDataService.isMarketOpenNow("美股");
    }

    public boolean isUkMarketOpen() {
        return marketDataService.isMarketOpenNow("英股");
    }

    @Transactional(readOnly = true)
    public List<StockPriceDto> getAllPrices() {
        Set<PriceQueryService.PriceKey> required = snapshotRepo.findLatestWithStocks()
                .map(snapshot -> snapshot.getStocks().stream()
                        .map(row -> new PriceQueryService.PriceKey(row.getStockCode(), row.getMarket()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                .orElseGet(LinkedHashSet::new);
        Map<PriceQueryService.PriceKey, String> stockNames = loadStockNames(required);
        return priceQuery.getAllDisplayPrices(required).stream()
                .map(price -> toDto(price, stockNames))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public StockPriceDto getPrice(String stockCode, String market) {
        return priceQuery.getDisplayPrice(stockCode, market).map(this::toDto).orElse(null);
    }

    /**
     * 觸發 price-service 同步抓價、寫 Redis。回傳統計。
     */
    public Map<String, Object> manualRefresh() {
        Map<String, Object> r = new HashMap<>(priceQuery.triggerRefresh());
        r.putIfAbsent("twMarketOpen", isTwMarketOpen());
        r.putIfAbsent("usMarketOpen", isUsMarketOpen());
        r.putIfAbsent("ukMarketOpen", isUkMarketOpen());
        return r;
    }

    public Map<String, Object> getMarketStatus() {
        return Map.of(
            "twMarketOpen", isTwMarketOpen(),
            "usMarketOpen", isUsMarketOpen(),
            "ukMarketOpen", isUkMarketOpen(),
            "twTime", ZonedDateTime.now(MarketZones.TW_ZONE).toLocalDateTime().toString(),
            "usTime", ZonedDateTime.now(MarketZones.US_ZONE).toLocalDateTime().toString(),
            "ukTime", ZonedDateTime.now(MarketZones.LON_ZONE).toLocalDateTime().toString(),
            // Requirement 68：唯一的 display-session 規則決定「最後交易日；開盤即當日」。
            "twTradingDate", priceQuery.displaySession("台股").targetTradingDate().toString(),
            "usTradingDate", priceQuery.displaySession("美股").targetTradingDate().toString(),
            "ukTradingDate", priceQuery.displaySession("英股").targetTradingDate().toString()
        );
    }

    @Transactional(readOnly = true)
    public LiveAssetsResponse getLiveAssets() {
        Optional<AssetSnapshot> latestOpt = snapshotRepo.findLatestWithStocks();
        if (latestOpt.isEmpty()) return null;
        AssetSnapshot snapshot = latestOpt.get();
        Set<PriceQueryService.PriceKey> holdingKeys = snapshot.getStocks().stream()
                .map(row -> new PriceQueryService.PriceKey(row.getStockCode(), row.getMarket()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<PriceQueryService.PriceKey, String> stockNames = loadStockNames(holdingKeys);

        BigDecimal exchangeRate = snapshot.getUsdExchangeRate();
        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) == 0) {
            exchangeRate = rateHistRepo.findClosestRate("USD", LocalDate.now())
                    .map(ExchangeRateHistory::getMidRate)
                    .orElse(BigDecimal.valueOf(32));
        }

        List<LiveStockItem> stockItems = new ArrayList<>();
        BigDecimal liveStockValue = BigDecimal.ZERO;
        Instant latestUpdate = null;

        for (StockHolding sh : snapshot.getStocks()) {
            LocalDate targetTradingDate = priceQuery.displaySession(sh.getMarket()).targetTradingDate();
            Optional<PriceQueryService.LivePrice> liveOpt =
                    priceQuery.getDisplayPrice(sh.getStockCode(), sh.getMarket());
            BigDecimal price = null;
            Boolean closed = null;
            String tradingDate = null;
            Instant updatedAt = null;
            // 昨收／漲跌／漲跌幅：與即時價同一筆 LivePrice 帶出（同一 tick），供匯出「股票（即時）」用（Task 200）
            BigDecimal previousClose = null;
            BigDecimal priceChange = null;
            BigDecimal changePercent = null;
            String quoteStatus = null;
            String source = null;

            if (liveOpt.isPresent()) {
                PriceQueryService.LivePrice lp = liveOpt.get();
                price = lp.price();
                closed = lp.closed();
                tradingDate = lp.tradingDate();
                previousClose = lp.previousClose();
                priceChange = lp.priceChange();
                changePercent = lp.changePercent();
                quoteStatus = lp.quoteStatus();
                source = lp.source();
                updatedAt = parseLiveUpdatedAt(lp.updatedAt());
                if (updatedAt != null && (latestUpdate == null || updatedAt.isAfter(latestUpdate))) {
                    latestUpdate = updatedAt;
                }
            }

            BigDecimal liveValue = null;
            String valuationSource;
            if (price != null && sh.getShares() != null) {
                // 英股 UCITS ETF（CSPX.L 等）為 USD 計價，與美股共用 USD 匯率
                if ("美股".equals(sh.getMarket()) || "英股".equals(sh.getMarket())) {
                    liveValue = sh.getShares().multiply(price).multiply(exchangeRate)
                            .setScale(0, RoundingMode.HALF_UP);
                } else {
                    liveValue = sh.getShares().multiply(price)
                            .setScale(0, RoundingMode.HALF_UP);
                }
                liveStockValue = liveStockValue.add(liveValue);
                valuationSource = classifyValuationSource(tradingDate, targetTradingDate);
            } else if (sh.getCurrentValue() != null) {
                liveValue = sh.getCurrentValue();
                liveStockValue = liveStockValue.add(liveValue);
                valuationSource = "SNAPSHOT_VALUE";
            } else {
                // 無法估值仍如既有算法不加總；provenance 必須誠實，不假稱 target-date price。
                valuationSource = "SNAPSHOT_VALUE";
            }

            String shName = stockNames.getOrDefault(
                    new PriceQueryService.PriceKey(sh.getStockCode(), sh.getMarket()), sh.getStockCode());
            stockItems.add(new LiveStockItem(
                sh.getStockCode(), shName, sh.getMarket(), sh.getShares(),
                price, liveValue, closed, tradingDate,
                previousClose, priceChange, changePercent, quoteStatus
                , sh.getId(), source, targetTradingDate.toString(), valuationSource,
                updatedAt
            ));
        }

        BigDecimal totalDeposit = snapshot.getTotalDeposit() != null ? snapshot.getTotalDeposit() : BigDecimal.ZERO;
        BigDecimal totalFundValue = snapshot.getTotalFundValue() != null ? snapshot.getTotalFundValue() : BigDecimal.ZERO;
        BigDecimal liveTotalAssets = totalDeposit.add(totalFundValue).add(liveStockValue);

        return new LiveAssetsResponse(
            snapshot.getId(),
            snapshot.getSnapshotDate().toString(),
            exchangeRate, totalDeposit, totalFundValue, liveStockValue, liveTotalAssets,
            stockItems,
            isTwMarketOpen(), isUsMarketOpen(), isUkMarketOpen(),
            formatApiUpdatedAt(latestUpdate)
        );
    }

    /**
     * The public live-assets contract exposes the aggregate quote time as a Taipei wall-clock
     * LocalDateTime without an offset. Keep the Instant above for cross-market ordering, and only
     * convert at the API boundary so the per-stock provenance timestamps remain Instants.
     */
    private static String formatApiUpdatedAt(Instant value) {
        return value == null ? null : API_LOCAL_DATE_TIME.format(
                LocalDateTime.ofInstant(value, MarketZones.TW_ZONE));
    }

    /**
     * 將 Redis/歷史相容的報價時間正規化為 Instant。帶 offset 的值使用其明示時間；快取
     * 契約中的無 offset 時間一律是 Asia/Taipei 牆鐘時間，不可依市場時區重新解讀。損壞
     * 值只降級為 null，不能中斷 live-assets 讀取。
     */
    private static Instant parseLiveUpdatedAt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeException ignored) {
            // 下一個相容格式。
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeException ignored) {
            // 下一個相容格式。
        }
        try {
            return LocalDateTime.parse(raw).atZone(MarketZones.TW_ZONE).toInstant();
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static String classifyValuationSource(String tradingDate, LocalDate target) {
        if (tradingDate == null || tradingDate.isBlank()) return "UNVERIFIED_SESSION_PRICE";
        try {
            LocalDate actual = LocalDate.parse(tradingDate);
            if (actual.equals(target)) return "TARGET_SESSION_PRICE";
            if (actual.isBefore(target)) return "PREVIOUS_SESSION_PRICE";
        } catch (RuntimeException ignored) {
            // 日期 malformed 一律不可冒充 target trading date。
        }
        return "UNVERIFIED_SESSION_PRICE";
    }

    private StockPriceDto toDto(PriceQueryService.LivePrice lp) {
        String name = stockMasterRepo.findByCodeAndMarket(lp.stockCode(), lp.market())
                .map(s -> s.getName())
                .orElse(lp.stockName() != null ? lp.stockName() : lp.stockCode());
        return toDto(lp, name);
    }

    private StockPriceDto toDto(PriceQueryService.LivePrice lp, Map<PriceQueryService.PriceKey, String> stockNames) {
        String name = stockNames.getOrDefault(new PriceQueryService.PriceKey(lp.stockCode(), lp.market()),
                lp.stockName() != null ? lp.stockName() : lp.stockCode());
        return toDto(lp, name);
    }

    private StockPriceDto toDto(PriceQueryService.LivePrice lp, String name) {
        return new StockPriceDto(
                lp.stockCode(), name, lp.market(),
                lp.price(), lp.priceChange(), lp.changePercent(),
                lp.tradingDate(), lp.updatedAt(), lp.closed(), lp.source(), lp.quoteStatus()
        );
    }

    /** 只以本次輸出的 bounded 代號做一次主檔查詢，市場仍由 map key 精確辨識。 */
    private Map<PriceQueryService.PriceKey, String> loadStockNames(Collection<PriceQueryService.PriceKey> keys) {
        Set<String> codes = keys == null ? Set.of() : keys.stream()
                .map(PriceQueryService.PriceKey::stockCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (codes.isEmpty()) return Map.of();
        List<Stock> masters = stockMasterRepo.findAllByCodeIn(codes);
        if (masters == null || masters.isEmpty()) return Map.of();
        return masters.stream().collect(Collectors.toMap(
                stock -> new PriceQueryService.PriceKey(stock.getCode(), stock.getMarket()),
                Stock::getName,
                (first, ignored) -> first,
                LinkedHashMap::new));
    }

    public record StockPriceDto(
        String stockCode, String stockName, String market,
        BigDecimal price, BigDecimal priceChange, BigDecimal changePercent,
        String tradingDate, String updatedAt, Boolean closed, String source, String quoteStatus
    ) {}

    public record LiveStockItem(
        String stockCode, String stockName, String market,
        BigDecimal shares, BigDecimal currentPrice, BigDecimal liveValue,
        Boolean closed, String tradingDate,
        // 即時報價衍生欄（Task 200）：昨收／漲跌／漲跌幅(%)，與 currentPrice 同一 LivePrice tick
        BigDecimal previousClose, BigDecimal priceChange, BigDecimal changePercent,
        String quoteStatus,
        // Requirement 68 provenance：維持舊欄位與金額算法，只在末端向後相容新增。
        Long holdingId, String source, String targetTradingDate, String valuationSource,
        Instant updatedAt
    ) {
        /** 舊 renderer/tests 的相容建構子；新增 provenance 欄位不影響既有資料金額。 */
        public LiveStockItem(String stockCode, String stockName, String market,
                             BigDecimal shares, BigDecimal currentPrice, BigDecimal liveValue,
                             Boolean closed, String tradingDate,
                             BigDecimal previousClose, BigDecimal priceChange, BigDecimal changePercent,
                             String quoteStatus) {
            this(stockCode, stockName, market, shares, currentPrice, liveValue, closed, tradingDate,
                    previousClose, priceChange, changePercent, quoteStatus, null, null, null,
                    currentPrice == null ? "SNAPSHOT_VALUE" : "UNVERIFIED_SESSION_PRICE", null);
        }

        /** Task 424 前的完整 provenance 建構子，維持既有 renderer 與測試呼叫相容。 */
        public LiveStockItem(String stockCode, String stockName, String market,
                             BigDecimal shares, BigDecimal currentPrice, BigDecimal liveValue,
                             Boolean closed, String tradingDate,
                             BigDecimal previousClose, BigDecimal priceChange, BigDecimal changePercent,
                             String quoteStatus, Long holdingId, String source,
                             String targetTradingDate, String valuationSource) {
            this(stockCode, stockName, market, shares, currentPrice, liveValue, closed, tradingDate,
                    previousClose, priceChange, changePercent, quoteStatus, holdingId, source,
                    targetTradingDate, valuationSource, null);
        }
    }

    public record LiveAssetsResponse(
        Long snapshotId, String snapshotDate, BigDecimal exchangeRate,
        BigDecimal totalDeposit, BigDecimal totalFundValue,
        BigDecimal liveStockValue, BigDecimal liveTotalAssets,
        List<LiveStockItem> stocks,
        boolean twMarketOpen, boolean usMarketOpen, boolean ukMarketOpen,
        String priceUpdatedAt
    ) {}
}
