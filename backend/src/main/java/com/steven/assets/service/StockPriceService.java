package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.ExchangeRateHistory;
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
        return priceQuery.getAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public StockPriceDto getPrice(String stockCode, String market) {
        return priceQuery.getLive(stockCode, market).map(this::toDto).orElse(null);
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
            "ukTime", ZonedDateTime.now(MarketZones.LON_ZONE).toLocalDateTime().toString()
        );
    }

    @Transactional(readOnly = true)
    public LiveAssetsResponse getLiveAssets() {
        Optional<AssetSnapshot> latestOpt = snapshotRepo.findLatestWithStocks();
        if (latestOpt.isEmpty()) return null;
        AssetSnapshot snapshot = latestOpt.get();

        BigDecimal exchangeRate = snapshot.getUsdExchangeRate();
        if (exchangeRate == null || exchangeRate.compareTo(BigDecimal.ZERO) == 0) {
            exchangeRate = rateHistRepo.findClosestRate("USD", LocalDate.now())
                    .map(ExchangeRateHistory::getMidRate)
                    .orElse(BigDecimal.valueOf(32));
        }

        List<LiveStockItem> stockItems = new ArrayList<>();
        BigDecimal liveStockValue = BigDecimal.ZERO;
        LocalDateTime latestUpdate = null;

        for (StockHolding sh : snapshot.getStocks()) {
            Optional<PriceQueryService.LivePrice> liveOpt =
                    priceQuery.getLive(sh.getStockCode(), sh.getMarket());
            BigDecimal price = null;
            Boolean closed = null;
            String tradingDate = null;
            LocalDateTime updatedAt = null;

            if (liveOpt.isPresent()) {
                PriceQueryService.LivePrice lp = liveOpt.get();
                price = lp.price();
                closed = lp.closed();
                tradingDate = lp.tradingDate();
                if (lp.updatedAt() != null) {
                    try {
                        updatedAt = LocalDateTime.parse(lp.updatedAt());
                        if (latestUpdate == null || updatedAt.isAfter(latestUpdate)) {
                            latestUpdate = updatedAt;
                        }
                    } catch (Exception ignored) {}
                }
            }

            BigDecimal liveValue = null;
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
            } else if (sh.getCurrentValue() != null) {
                liveValue = sh.getCurrentValue();
                liveStockValue = liveStockValue.add(liveValue);
            }

            String shName = stockMasterRepo.findByCodeAndMarket(sh.getStockCode(), sh.getMarket())
                    .map(s -> s.getName()).orElse(sh.getStockCode());
            stockItems.add(new LiveStockItem(
                sh.getStockCode(), shName, sh.getMarket(), sh.getShares(),
                price, liveValue, closed, tradingDate
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
            latestUpdate != null ? latestUpdate.toString() : null
        );
    }

    private StockPriceDto toDto(PriceQueryService.LivePrice lp) {
        String name = stockMasterRepo.findByCodeAndMarket(lp.stockCode(), lp.market())
                .map(s -> s.getName())
                .orElse(lp.stockName() != null ? lp.stockName() : lp.stockCode());
        return new StockPriceDto(
                lp.stockCode(), name, lp.market(),
                lp.price(), lp.priceChange(), lp.changePercent(),
                lp.tradingDate(), lp.updatedAt(), lp.closed(), lp.source()
        );
    }

    public record StockPriceDto(
        String stockCode, String stockName, String market,
        BigDecimal price, BigDecimal priceChange, BigDecimal changePercent,
        String tradingDate, String updatedAt, Boolean closed, String source
    ) {}

    public record LiveStockItem(
        String stockCode, String stockName, String market,
        BigDecimal shares, BigDecimal currentPrice, BigDecimal liveValue,
        Boolean closed, String tradingDate
    ) {}

    public record LiveAssetsResponse(
        Long snapshotId, String snapshotDate, BigDecimal exchangeRate,
        BigDecimal totalDeposit, BigDecimal totalFundValue,
        BigDecimal liveStockValue, BigDecimal liveTotalAssets,
        List<LiveStockItem> stocks,
        boolean twMarketOpen, boolean usMarketOpen, boolean ukMarketOpen,
        String priceUpdatedAt
    ) {}
}
