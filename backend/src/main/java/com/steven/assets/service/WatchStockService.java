package com.steven.assets.service;

import com.steven.assets.dto.WatchStockDto;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.WatchStock;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.WatchStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WatchStockService {

    /** 台股大盤（TAIEX）特殊代號：可加入觀察清單，價格走 twse_index_daily_history。 */
    public static final String TAIEX_INDEX_CODE = "0000";
    public static final String TAIEX_INDEX_NAME = "台股大盤";

    private final WatchStockRepository watchRepo;
    private final PriceQueryService priceQuery;
    private final StockAlertRepository alertRepo;
    private final StockPriceHistoryRepository historyRepo;
    private final StockRepository stockMasterRepo;
    private final TechnicalIndicatorService indicatorService;
    private final StockPriceService stockPriceService;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;

    private static boolean isTaiex(String code, String market) {
        return TAIEX_INDEX_CODE.equals(code) && "台股".equals(market);
    }

    @Transactional(readOnly = true)
    public List<WatchStockDto.Response> findAll() {
        return watchRepo.findAllByOrderByDisplayOrderAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public WatchStockDto.Response create(WatchStockDto.Request req) {
        if (req.getStockCode() == null || req.getStockCode().isBlank())
            throw new IllegalArgumentException("股票代號必填");
        if (req.getMarket() == null || req.getMarket().isBlank())
            throw new IllegalArgumentException("市場必填");

        String code = req.getStockCode().trim().toUpperCase();
        watchRepo.findByStockCodeAndMarket(code, req.getMarket()).ifPresent(w -> {
            throw new IllegalArgumentException("此股票已在觀察清單中");
        });

        int maxOrder = watchRepo.findAllByOrderByDisplayOrderAsc().stream()
                .mapToInt(w -> w.getDisplayOrder() != null ? w.getDisplayOrder() : 0)
                .max().orElse(0);

        WatchStock w = WatchStock.builder()
                .stockCode(code)
                .market(req.getMarket())
                .displayOrder(maxOrder + 1)
                .build();
        WatchStock saved = watchRepo.save(w);

        // 0000 = 大盤：不寫入 stock 主檔（避免進入排程抓價），不觸發 manualRefresh
        if (isTaiex(code, req.getMarket())) {
            return toResponse(saved);
        }

        // 同步寫入 stock 主檔（單一名稱來源）
        String name = req.getStockName() != null ? req.getStockName().trim() : "";
        if (name.isEmpty()) {
            name = stockMasterRepo.findByCodeAndMarket(code, req.getMarket())
                    .map(s -> s.getName()).orElse(code);
        }
        stockMasterRepo.upsert(code, req.getMarket(), name);

        // 立即觸發 price-service 抓一次（會涵蓋所有持股 + 觀察清單，包含這檔新加入的）
        try {
            stockPriceService.manualRefresh();
        } catch (Exception e) {
            log.warn("新增觀察 {} {} 後即時抓價失敗: {}", req.getMarket(), code, e.getMessage());
        }

        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        watchRepo.deleteById(id);
    }

    @Transactional
    public void reorder(List<Long> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            final int idx = i;
            watchRepo.findById(orderedIds.get(i)).ifPresent(w -> {
                w.setDisplayOrder(idx);
                watchRepo.save(w);
            });
        }
    }

    private WatchStockDto.Response toResponse(WatchStock w) {
        if (isTaiex(w.getStockCode(), w.getMarket())) {
            return toIndexResponse(w);
        }
        String stockName = stockMasterRepo.findByCodeAndMarket(w.getStockCode(), w.getMarket())
                .map(s -> s.getName()).orElse(w.getStockCode());
        WatchStockDto.Response r = WatchStockDto.Response.builder()
                .id(w.getId())
                .stockCode(w.getStockCode())
                .stockName(stockName)
                .market(w.getMarket())
                .build();

        // 報價
        Optional<PriceQueryService.LivePrice> priceOpt = priceQuery.getLive(w.getStockCode(), w.getMarket());
        priceOpt.ifPresent(sp -> {
            r.setPrice(sp.price());
            r.setPriceChange(sp.priceChange());
            r.setChangePercent(sp.changePercent());
            r.setBuyPrice(sp.buyPrice());
            r.setSellPrice(sp.sellPrice());
            r.setOpenPrice(sp.openPrice());
            r.setPreviousClose(sp.previousClose());
            r.setHighPrice(sp.highPrice());
            r.setLowPrice(sp.lowPrice());
            r.setVolume(sp.volume());
            r.setTradingDate(sp.tradingDate());
            r.setPriceUpdatedAt(sp.updatedAt());
            r.setClosed(sp.closed());
        });

        // 對於非交易時間或新加入觀察股票，買賣/開盤/昨收/最高/最低/成交量可能為 null
        // → 用最近的歷史收盤資料（StockPriceHistory）回填
        if (r.getOpenPrice() == null || r.getHighPrice() == null || r.getLowPrice() == null
                || r.getVolume() == null || r.getPreviousClose() == null) {
            List<StockPriceHistory> recent = historyRepo.findRecentN(w.getStockCode(), w.getMarket(), 2);
            if (!recent.isEmpty()) {
                StockPriceHistory latest = recent.get(0);
                if (r.getOpenPrice() == null) r.setOpenPrice(latest.getOpenPrice());
                if (r.getHighPrice() == null) r.setHighPrice(latest.getHighPrice());
                if (r.getLowPrice()  == null) r.setLowPrice(latest.getLowPrice());
                if (r.getVolume()    == null && latest.getVolume() != null) {
                    // 歷史成交量單位為「股」，台股換算為「張」
                    r.setVolume("台股".equals(w.getMarket())
                            ? latest.getVolume() / 1000
                            : latest.getVolume());
                }
                // 若報價已有 price 但無昨收，且歷史只有一筆 → 以該筆為昨收
                // 若有兩筆，prev = 第二筆 close
                if (r.getPreviousClose() == null) {
                    if (recent.size() >= 2) r.setPreviousClose(recent.get(1).getClosePrice());
                    else r.setPreviousClose(latest.getClosePrice());
                }
            }
        }

        // priceChange / changePercent 由 DTO 上的 price / previousClose 即時計算
        // （含上面從歷史回填後的 previousClose）
        if (r.getPrice() != null && r.getPreviousClose() != null
                && r.getPreviousClose().signum() != 0) {
            java.math.BigDecimal diff = r.getPrice().subtract(r.getPreviousClose());
            r.setPriceChange(diff.setScale(4, java.math.RoundingMode.HALF_UP));
            r.setChangePercent(diff
                    .divide(r.getPreviousClose(), 6, java.math.RoundingMode.HALF_UP)
                    .multiply(java.math.BigDecimal.valueOf(100))
                    .setScale(4, java.math.RoundingMode.HALF_UP));
        }

        // 警示彙總：取該股最近一筆 lastTriggeredAt，且只顯示最近 3 個交易日內的觸發
        List<StockAlert> alerts = alertRepo.findByStockCodeAndMarket(w.getStockCode(), w.getMarket());
        java.time.LocalDateTime cutoff = recentTradingDayCutoff(w.getMarket(), 3);
        alerts.stream()
                .filter(a -> a.getLastTriggeredAt() != null)
                .filter(a -> cutoff == null || !a.getLastTriggeredAt().isBefore(cutoff))
                .max(Comparator.comparing(StockAlert::getLastTriggeredAt))
                .ifPresent(a -> {
                    r.setLastTriggeredAt(a.getLastTriggeredAt());
                    r.setLastTriggeredPrice(a.getLastTriggeredPrice());
                    r.setLastTriggeredAlertType(a.getAlertType());
                });

        // 不論警示是否設定／觸發，皆計算當前的季線(MA60)、KD
        TechnicalIndicatorService.Indicators ind = indicatorService.compute(w.getStockCode(), w.getMarket());
        r.setQuarterlyMa(ind.quarterlyMa());
        r.setKValue(ind.k());
        r.setDValue(ind.d());

        return r;
    }

    /**
     * 0000 = 台股大盤（TAIEX）的 Response：價格走 twse_index_daily_history。
     * 只有收盤點位，沒有 OHLC / 量 / KD；季線（MA60）取近 60 個交易日收盤平均。
     */
    private WatchStockDto.Response toIndexResponse(WatchStock w) {
        WatchStockDto.Response r = WatchStockDto.Response.builder()
                .id(w.getId())
                .stockCode(w.getStockCode())
                .stockName(TAIEX_INDEX_NAME)
                .market(w.getMarket())
                .build();

        List<TwseIndexDailyHistory> recent = twseDailyRepo.findTop60ByOrderByTradingDateDesc();
        if (recent.isEmpty()) return r;

        TwseIndexDailyHistory latest = recent.get(0);
        r.setPrice(latest.getClosePoint());
        r.setTradingDate(latest.getTradingDate().toString());
        r.setClosed(true);
        if (recent.size() >= 2) {
            r.setPreviousClose(recent.get(1).getClosePoint());
        }
        if (r.getPrice() != null && r.getPreviousClose() != null
                && r.getPreviousClose().signum() != 0) {
            java.math.BigDecimal diff = r.getPrice().subtract(r.getPreviousClose());
            r.setPriceChange(diff.setScale(4, java.math.RoundingMode.HALF_UP));
            r.setChangePercent(diff
                    .divide(r.getPreviousClose(), 6, java.math.RoundingMode.HALF_UP)
                    .multiply(java.math.BigDecimal.valueOf(100))
                    .setScale(4, java.math.RoundingMode.HALF_UP));
        }

        // 季線 MA60：近 60 筆收盤平均（不足 60 筆則用所有可用筆數平均）
        java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
        for (TwseIndexDailyHistory row : recent) sum = sum.add(row.getClosePoint());
        r.setQuarterlyMa(sum.divide(java.math.BigDecimal.valueOf(recent.size()),
                2, java.math.RoundingMode.HALF_UP));

        return r;
    }

    /** 回傳「最近 N 個交易日中最早一天的午夜」當作 cutoff；資料不足回 null（不過濾）。 */
    private java.time.LocalDateTime recentTradingDayCutoff(String market, int n) {
        List<java.time.LocalDate> dates = historyRepo
                .findDistinctTradingDatesByMarket(market,
                        org.springframework.data.domain.PageRequest.of(0, n));
        if (dates.size() < n) return null;
        return dates.get(dates.size() - 1).atStartOfDay();
    }
}
