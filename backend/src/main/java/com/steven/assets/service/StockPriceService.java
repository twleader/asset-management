package com.steven.assets.service;

import com.steven.assets.model.Stock;
import com.steven.assets.model.StockPrice;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.WatchStock;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockPriceRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.WatchStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 股價即時更新服務
 * - 台股交易時間：週一～五 09:00～13:30 (台灣時間)
 * - 美股交易時間：週一～五 09:30～16:00 (美東時間) = 台灣 22:30～隔日 05:00
 * - 盤中每 2 分鐘更新一次
 * - 收盤後存收盤價，不再更新直到下次開盤
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockPriceService {

    private final StockPriceRepository priceRepo;
    private final AssetSnapshotRepository snapshotRepo;
    private final ExchangeRateHistoryRepository rateHistRepo;
    private final MarketDataService marketDataService;
    private final StockAlertService stockAlertService;
    private final WatchStockRepository watchStockRepo;
    private final StockRepository stockMasterRepo;
    private final StockPriceHistoryRepository historyRepo;

    /** 自身代理 — 為了讓 {@link #persistPrice} 的 {@code @Transactional} 能透過 Spring proxy 生效（同類內呼叫會繞過代理）。 */
    @Autowired
    @Lazy
    private StockPriceService self;

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final ZoneId US_ZONE = ZoneId.of("America/New_York");

    // ===================== 交易時間判斷 =====================

    /**
     * 台股是否在交易時間
     * 週一～五 09:00～13:30 台灣時間
     */
    public boolean isTwMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(TW_ZONE);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;

        LocalTime time = now.toLocalTime();
        return !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(13, 30));
    }

    /**
     * 美股是否在交易時間
     * 週一～五 09:30～16:00 美東時間
     */
    public boolean isUsMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(US_ZONE);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;

        LocalTime time = now.toLocalTime();
        return !time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(16, 0));
    }

    /**
     * 台股是否剛收盤（13:30～13:45 之間，用於觸發收盤價更新）
     */
    private boolean isTwMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(TW_ZONE);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;

        LocalTime time = now.toLocalTime();
        return time.isAfter(LocalTime.of(13, 30)) && time.isBefore(LocalTime.of(13, 50));
    }

    /**
     * 美股是否剛收盤（16:00～16:20 美東時間）
     */
    private boolean isUsMarketJustClosed() {
        ZonedDateTime now = ZonedDateTime.now(US_ZONE);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;

        LocalTime time = now.toLocalTime();
        return time.isAfter(LocalTime.of(16, 0)) && time.isBefore(LocalTime.of(16, 20));
    }

    // ===================== 啟動補抓 =====================

    /**
     * 啟動完成後，補抓 stock 主檔中尚無 stock_price 紀錄的股票
     * 主檔涵蓋持股、觀察、警示，凡列入主檔皆應有即時報價可查。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissingPricesOnStartup() {
        Set<String> twMissing = new LinkedHashSet<>();
        Set<String> usMissing = new LinkedHashSet<>();
        for (Stock s : stockMasterRepo.findAll()) {
            if (priceRepo.findByStockCodeAndMarket(s.getCode(), s.getMarket()).isPresent()) continue;
            if ("美股".equals(s.getMarket())) usMissing.add(s.getCode());
            else twMissing.add(s.getCode());
        }
        if (twMissing.isEmpty() && usMissing.isEmpty()) return;
        log.info("啟動補抓主檔中無即時報價的股票：台股 {} 檔、美股 {} 檔", twMissing.size(), usMissing.size());
        new Thread(() -> {
            if (!twMissing.isEmpty()) updatePrices(twMissing, "台股", !isTwMarketOpen());
            if (!usMissing.isEmpty()) updatePrices(usMissing, "美股", !isUsMarketOpen());
        }, "startup-price-backfill").start();
    }

    // ===================== 排程更新 =====================

    /**
     * 每 10 分鐘執行一次
     * 根據交易時間決定是否需要更新台股/美股
     */
    @Scheduled(fixedRate = 120_000, initialDelay = 10_000) // 2分鐘, 啟動10秒後開始
    public void scheduledPriceUpdate() {
        boolean twOpen = isTwMarketOpen();
        boolean usOpen = isUsMarketOpen();
        boolean twJustClosed = isTwMarketJustClosed();
        boolean usJustClosed = isUsMarketJustClosed();

        if (!twOpen && !usOpen && !twJustClosed && !usJustClosed) {
            log.debug("台股/美股均非交易時間，跳過更新");
            return;
        }

        // 取得最新快照中持有的所有股票代號
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        collectHeldStockCodes(twCodes, usCodes);

        if (twOpen || twJustClosed) {
            log.info("更新台股即時價格 ({} 檔)...", twCodes.size());
            updatePrices(twCodes, "台股", twJustClosed);
        }

        if (usOpen || usJustClosed) {
            log.info("更新美股即時價格 ({} 檔)...", usCodes.size());
            updatePrices(usCodes, "美股", usJustClosed);
        }

        // 股價更新後立即檢查到價警示
        stockAlertService.checkAlerts();
    }

    /**
     * 從最新快照取得所有持有的股票代號
     * 使用 JOIN FETCH 查詢避免 LazyInitializationException
     */
    public void collectHeldStockCodes(Set<String> twCodes, Set<String> usCodes) {
        Optional<AssetSnapshot> latestOpt = snapshotRepo.findLatestWithStocks();
        if (latestOpt.isPresent()) {
            for (StockHolding sh : latestOpt.get().getStocks()) {
                if ("美股".equals(sh.getMarket())) {
                    usCodes.add(sh.getStockCode());
                } else {
                    twCodes.add(sh.getStockCode());
                }
            }
        }
        // 觀察清單中的股票一併納入更新
        for (WatchStock w : watchStockRepo.findAll()) {
            if ("美股".equals(w.getMarket())) {
                usCodes.add(w.getStockCode());
            } else {
                twCodes.add(w.getStockCode());
            }
        }
    }

    /**
     * 批次更新指定市場的股價
     *
     * 注意：本方法 **不可** 加 {@code @Transactional}。Yahoo Finance / NASDAQ HTTP 呼叫
     * 偶爾會卡到 30 秒（Yahoo crumb 重試），若整個迴圈包在同一個 transaction，
     * HikariCP 連線會被外部 HTTP 等待長時間占住，造成連線池耗盡 → 使用者前端「存檔」拿不到連線而 timeout。
     * 將 DB 寫入拆到 {@link #persistPrice} 的短 transaction，HTTP 期間不占連線。
     */
    public void updatePrices(Set<String> codes, String market, boolean markClosed) {
        LocalDateTime now = LocalDateTime.now();

        for (String code : codes) {
            try {
                MarketDataService.PriceResult result = marketDataService.getStockPrice(code, market);
                if (result.price() == null) continue;

                self.persistPrice(code, market, result, markClosed, now);
                log.debug("更新 {} {} 價格: {}", market, code, result.price());

                // 避免太頻繁呼叫 API
                Thread.sleep(500);
            } catch (Exception e) {
                log.warn("更新 {} {} 股價失敗: {}", market, code, e.getMessage());
            }
        }
    }

    /**
     * 將單檔股價寫入 DB（短 transaction，不跨 HTTP 呼叫）。
     */
    @Transactional
    public void persistPrice(String code, String market,
                             MarketDataService.PriceResult result,
                             boolean markClosed, LocalDateTime now) {
        StockPrice sp = priceRepo.findByStockCodeAndMarket(code, market)
                .orElse(StockPrice.builder()
                        .stockCode(code)
                        .market(market)
                        .build());

        sp.setPrice(result.price());
        if (result.buyPrice()      != null) sp.setBuyPrice(result.buyPrice());
        if (result.sellPrice()     != null) sp.setSellPrice(result.sellPrice());
        if (result.openPrice()     != null) sp.setOpenPrice(result.openPrice());
        if (result.previousClose() != null) sp.setPreviousClose(result.previousClose());
        if (result.highPrice()     != null) sp.setHighPrice(result.highPrice());
        if (result.lowPrice()      != null) sp.setLowPrice(result.lowPrice());
        if (result.volume()        != null) sp.setVolume(result.volume());

        // 若資料源未提供昨收，從歷史最近一筆收盤價回填
        // → priceChange / changePercent (entity @Transient) 才能算得出來
        if (sp.getPreviousClose() == null) {
            historyRepo.findRecentN(code, market, 1).stream().findFirst()
                    .ifPresent(h -> sp.setPreviousClose(h.getClosePrice()));
        }

        sp.setTradingDate(resolveTradingDate(code, market));
        sp.setUpdatedAt(now);
        sp.setClosed(markClosed);
        sp.setSource(result.source());

        // 股票名稱寫入 stock 主檔（單一來源）
        if (result.stockName() != null && !result.stockName().isBlank()
                && !result.stockName().equalsIgnoreCase(code)) {
            stockMasterRepo.upsert(code, market, result.stockName());
        }

        priceRepo.save(sp);
    }

    /**
     * 解析股價對應的交易日。
     *
     * **語意**：`trading_date` 代表「這筆價格資料對應的真實交易日」，**不是**「我們抓資料的當下日期」。
     *
     * 這個區別在盤外手動刷新時很關鍵：TWSE mis API 在週日深夜或非交易時段仍會回傳上一個交易日的最後成交資料，
     * 若直接 stamp 為 `LocalDate.now()` 會讓 cache 顯示「今天有資料」，污染 KD/MA 等技術指標計算
     * （`TechnicalIndicatorService.compute` 會把 `tradingDate == today` 的快取當成今日 K 棒併入序列）。
     *
     * 規則：
     * - 市場目前在交易時段（含剛收盤的 20 分鐘窗口）→ 用今日，因為資料確實來自今天
     * - 否則（盤外、週末、假日）→ 用該股票 `stock_price_history` 中最近一筆的 trading_date
     * - 若連歷史紀錄都沒有 → fallback 到今天
     */
    private LocalDate resolveTradingDate(String stockCode, String market) {
        boolean isUs = "美股".equals(market);
        ZoneId zone = isUs ? US_ZONE : TW_ZONE;
        boolean liveSession = isUs
                ? (isUsMarketOpen() || isUsMarketJustClosed())
                : (isTwMarketOpen() || isTwMarketJustClosed());
        if (liveSession) {
            return LocalDate.now(zone);
        }
        return historyRepo.findRecentN(stockCode, market, 1).stream()
                .findFirst()
                .map(h -> h.getTradingDate())
                .orElse(LocalDate.now(zone));
    }

    // ===================== 查詢 API =====================

    /**
     * 取得所有股票的最新價格
     */
    @Transactional(readOnly = true)
    public List<StockPriceDto> getAllPrices() {
        return priceRepo.findAllByOrderByMarketAscStockCodeAsc().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * 取得單一股票最新價格
     */
    @Transactional(readOnly = true)
    public StockPriceDto getPrice(String stockCode, String market) {
        return priceRepo.findByStockCodeAndMarket(stockCode, market)
                .map(this::toDto)
                .orElse(null);
    }

    /**
     * 手動觸發更新（不限交易時間）
     *
     * 注意：本方法 **不可** 加 {@code @Transactional}。會在迴圈裡逐檔呼叫 Yahoo Finance / NASDAQ HTTP，
     * 一檔可能卡到 30 秒（Yahoo crumb 重試）。若整段包在 transaction 裡，HikariCP 連線會被外部 HTTP
     * 等待長時間占住，造成連線池耗盡 → 使用者前端「存檔」拿不到連線而 timeout。
     * DB 寫入由內部 {@link #persistPrice} 各自走短 transaction。
     */
    public Map<String, Object> manualRefresh() {
        Set<String> twCodes = new LinkedHashSet<>();
        Set<String> usCodes = new LinkedHashSet<>();
        collectHeldStockCodes(twCodes, usCodes);

        updatePrices(twCodes, "台股", false);
        updatePrices(usCodes, "美股", false);

        return Map.of(
            "twUpdated", twCodes.size(),
            "usUpdated", usCodes.size(),
            "twMarketOpen", isTwMarketOpen(),
            "usMarketOpen", isUsMarketOpen()
        );
    }

    /**
     * 取得市場狀態
     */
    public Map<String, Object> getMarketStatus() {
        return Map.of(
            "twMarketOpen", isTwMarketOpen(),
            "usMarketOpen", isUsMarketOpen(),
            "twTime", ZonedDateTime.now(TW_ZONE).toLocalDateTime().toString(),
            "usTime", ZonedDateTime.now(US_ZONE).toLocalDateTime().toString()
        );
    }

    // ===================== 即時資產估算 =====================

    /**
     * 以最新快照持倉 × 當前快取股價，即時計算總資產估值。
     * - 存款、基金沿用快照靜態值
     * - 股票部位依 StockPrice 快取即時計算（美股換算為台幣）
     */
    @Transactional(readOnly = true)
    public LiveAssetsResponse getLiveAssets() {
        Optional<AssetSnapshot> latestOpt = snapshotRepo.findLatestWithStocks();
        if (latestOpt.isEmpty()) {
            return null;
        }
        AssetSnapshot snapshot = latestOpt.get();

        // 取最新 USD/TWD 匯率（優先快照匯率，其次資料庫最近一筆）
        BigDecimal exchangeRate = snapshot.getUsdExchangeRate();
        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) == 0) {
            exchangeRate = rateHistRepo.findClosestRate("USD", LocalDate.now())
                    .map(ExchangeRateHistory::getMidRate)
                    .orElse(BigDecimal.valueOf(32));
        }

        // 計算各股即時市值
        List<LiveStockItem> stockItems = new ArrayList<>();
        BigDecimal liveStockValue = BigDecimal.ZERO;
        LocalDateTime latestUpdate = null;

        for (StockHolding sh : snapshot.getStocks()) {
            Optional<StockPrice> spOpt = priceRepo.findByStockCodeAndMarket(sh.getStockCode(), sh.getMarket());
            BigDecimal price = null;
            Boolean closed = null;
            String tradingDate = null;
            LocalDateTime updatedAt = null;

            if (spOpt.isPresent()) {
                StockPrice sp = spOpt.get();
                price = sp.getPrice();
                closed = sp.getClosed();
                tradingDate = sp.getTradingDate() != null ? sp.getTradingDate().toString() : null;
                updatedAt = sp.getUpdatedAt();
                if (latestUpdate == null || (updatedAt != null && updatedAt.isAfter(latestUpdate))) {
                    latestUpdate = updatedAt;
                }
            }

            BigDecimal liveValue = null;
            if (price != null && sh.getShares() != null) {
                if ("美股".equals(sh.getMarket())) {
                    liveValue = sh.getShares().multiply(price).multiply(exchangeRate)
                            .setScale(0, RoundingMode.HALF_UP);
                } else {
                    liveValue = sh.getShares().multiply(price)
                            .setScale(0, RoundingMode.HALF_UP);
                }
                liveStockValue = liveStockValue.add(liveValue);
            } else if (sh.getCurrentValue() != null) {
                // 無快取則 fallback 至快照存值
                liveValue = sh.getCurrentValue();
                liveStockValue = liveStockValue.add(liveValue);
            }

            String shName = stockMasterRepo.findByCodeAndMarket(sh.getStockCode(), sh.getMarket())
                    .map(s -> s.getName()).orElse(sh.getStockCode());
            stockItems.add(new LiveStockItem(
                sh.getStockCode(),
                shName,
                sh.getMarket(),
                sh.getShares(),
                price,
                liveValue,
                closed,
                tradingDate
            ));
        }

        BigDecimal totalDeposit = snapshot.getTotalDeposit() != null ? snapshot.getTotalDeposit() : BigDecimal.ZERO;
        BigDecimal totalFundValue = snapshot.getTotalFundValue() != null ? snapshot.getTotalFundValue() : BigDecimal.ZERO;
        BigDecimal liveTotalAssets = totalDeposit.add(totalFundValue).add(liveStockValue);

        return new LiveAssetsResponse(
            snapshot.getId(),
            snapshot.getSnapshotDate().toString(),
            exchangeRate,
            totalDeposit,
            totalFundValue,
            liveStockValue,
            liveTotalAssets,
            stockItems,
            isTwMarketOpen(),
            isUsMarketOpen(),
            latestUpdate != null ? latestUpdate.toString() : null
        );
    }

    // ===================== DTO =====================

    public record StockPriceDto(
        String stockCode,
        String stockName,
        String market,
        BigDecimal price,
        BigDecimal priceChange,
        BigDecimal changePercent,
        String tradingDate,
        String updatedAt,
        Boolean closed,
        String source
    ) {}

    private StockPriceDto toDto(StockPrice sp) {
        String name = stockMasterRepo.findByCodeAndMarket(sp.getStockCode(), sp.getMarket())
                .map(s -> s.getName()).orElse(sp.getStockCode());
        return new StockPriceDto(
            sp.getStockCode(),
            name,
            sp.getMarket(),
            sp.getPrice(),
            sp.getPriceChange(),
            sp.getChangePercent(),
            sp.getTradingDate().toString(),
            sp.getUpdatedAt().toString(),
            sp.getClosed(),
            sp.getSource()
        );
    }

    public record LiveStockItem(
        String stockCode,
        String stockName,
        String market,
        BigDecimal shares,
        BigDecimal currentPrice,   // 快取股價（原幣）
        BigDecimal liveValue,      // 即時市值（台幣）
        Boolean closed,            // true = 收盤價，false/null = 盤中價
        String tradingDate
    ) {}

    public record LiveAssetsResponse(
        Long snapshotId,
        String snapshotDate,
        BigDecimal exchangeRate,   // USD/TWD 匯率
        BigDecimal totalDeposit,   // 存款（快照靜態值）
        BigDecimal totalFundValue, // 基金（快照靜態值）
        BigDecimal liveStockValue, // 股票即時總市值（台幣）
        BigDecimal liveTotalAssets,// 即時總資產
        List<LiveStockItem> stocks,
        boolean twMarketOpen,
        boolean usMarketOpen,
        String priceUpdatedAt      // 股價最後更新時間
    ) {}
}
