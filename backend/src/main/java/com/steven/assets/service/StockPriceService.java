package com.steven.assets.service;

import com.steven.assets.model.StockPrice;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * - 盤中每 10 分鐘更新一次
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

    // ===================== 排程更新 =====================

    /**
     * 每 10 分鐘執行一次
     * 根據交易時間決定是否需要更新台股/美股
     */
    @Scheduled(fixedRate = 300_000, initialDelay = 10_000) // 5分鐘, 啟動10秒後開始
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
    }

    /**
     * 從最新快照取得所有持有的股票代號
     * 使用 JOIN FETCH 查詢避免 LazyInitializationException
     */
    public void collectHeldStockCodes(Set<String> twCodes, Set<String> usCodes) {
        Optional<AssetSnapshot> latestOpt = snapshotRepo.findLatestWithStocks();
        if (latestOpt.isEmpty()) return;

        AssetSnapshot latest = latestOpt.get();
        for (StockHolding sh : latest.getStocks()) {
            if ("美股".equals(sh.getMarket())) {
                usCodes.add(sh.getStockCode());
            } else {
                twCodes.add(sh.getStockCode());
            }
        }
    }

    /**
     * 批次更新指定市場的股價
     */
    @Transactional
    public void updatePrices(Set<String> codes, String market, boolean markClosed) {
        String marketName = market;
        LocalDateTime now = LocalDateTime.now();

        for (String code : codes) {
            try {
                MarketDataService.PriceResult result = marketDataService.getStockPrice(code, marketName);
                if (result.price() == null) continue;

                StockPrice sp = priceRepo.findByStockCodeAndMarket(code, market)
                        .orElse(StockPrice.builder()
                                .stockCode(code)
                                .market(market)
                                .build());

                sp.setPrice(result.price());
                sp.setPriceChange(result.change());
                sp.setChangePercent(result.changePct());
                sp.setTradingDate(LocalDate.now("美股".equals(market) ? US_ZONE : TW_ZONE));
                sp.setUpdatedAt(now);
                sp.setClosed(markClosed);
                sp.setSource(result.source());

                // 嘗試從最新快照取得股票名稱
                if (sp.getStockName() == null) {
                    sp.setStockName(code);
                }

                priceRepo.save(sp);
                log.debug("更新 {} {} 價格: {}", marketName, code, result.price());

                // 避免太頻繁呼叫 API
                Thread.sleep(500);
            } catch (Exception e) {
                log.warn("更新 {} {} 股價失敗: {}", marketName, code, e.getMessage());
            }
        }
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
     */
    @Transactional
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

            stockItems.add(new LiveStockItem(
                sh.getStockCode(),
                sh.getStockName(),
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
        return new StockPriceDto(
            sp.getStockCode(),
            sp.getStockName(),
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
