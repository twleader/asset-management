package com.steven.assets.service;

import com.steven.assets.model.StockPrice;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
}
