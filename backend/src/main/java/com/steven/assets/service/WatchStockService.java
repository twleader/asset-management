package com.steven.assets.service;

import com.steven.assets.dto.WatchStockDto;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockPrice;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.WatchStock;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockPriceRepository;
import com.steven.assets.repository.WatchStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WatchStockService {

    private final WatchStockRepository watchRepo;
    private final StockPriceRepository priceRepo;
    private final StockAlertRepository alertRepo;
    private final StockPriceHistoryRepository historyRepo;
    private final TechnicalIndicatorService indicatorService;

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
                .stockName(req.getStockName())
                .market(req.getMarket())
                .displayOrder(maxOrder + 1)
                .build();
        return toResponse(watchRepo.save(w));
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
        WatchStockDto.Response r = WatchStockDto.Response.builder()
                .id(w.getId())
                .stockCode(w.getStockCode())
                .stockName(w.getStockName())
                .market(w.getMarket())
                .build();

        // 報價
        Optional<StockPrice> priceOpt = priceRepo.findByStockCodeAndMarket(w.getStockCode(), w.getMarket());
        priceOpt.ifPresent(sp -> {
            r.setPrice(sp.getPrice());
            r.setPriceChange(sp.getPriceChange());
            r.setChangePercent(sp.getChangePercent());
            r.setBuyPrice(sp.getBuyPrice());
            r.setSellPrice(sp.getSellPrice());
            r.setOpenPrice(sp.getOpenPrice());
            r.setPreviousClose(sp.getPreviousClose());
            r.setHighPrice(sp.getHighPrice());
            r.setLowPrice(sp.getLowPrice());
            r.setVolume(sp.getVolume());
            r.setTradingDate(sp.getTradingDate() != null ? sp.getTradingDate().toString() : null);
            r.setPriceUpdatedAt(sp.getUpdatedAt() != null ? sp.getUpdatedAt().toString() : null);
            r.setClosed(sp.getClosed());
            // 若 watch 沒有股名，用報價快取補
            if ((w.getStockName() == null || w.getStockName().isBlank()) && sp.getStockName() != null) {
                r.setStockName(sp.getStockName());
            }
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

        // 警示彙總：取該股最近一筆 lastTriggeredAt（僅顯示時間 + 觸發股價）
        List<StockAlert> alerts = alertRepo.findByStockCodeAndMarket(w.getStockCode(), w.getMarket());
        alerts.stream()
                .filter(a -> a.getLastTriggeredAt() != null)
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

}
