package com.steven.assets.service;

import com.steven.assets.dto.WatchStockDto;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 觀察清單衍生 view（不對應實體表）。
 * 觀察清單 = StockAlert 中所有 (stockCode, market) 去重 → 每筆組裝 live 報價 + 技術指標 + 該股票最近一次觸發資訊。
 *
 * 操作語意（與警示條件表共用 stock_alert 為唯一資料源）：
 *  - findAll  : SELECT stockCode, market, MIN(displayOrder) FROM stock_alert GROUP BY (stockCode, market)
 *  - reorder  : 把每個股票所有 alert 的 displayOrder 整組依新順序重新指派區段
 *
 * 移除觀察一律由「警示條件」頁刪掉該股票最後一筆 alert（StockAlertService.delete），
 * 觀察清單不再提供獨立的 delete 入口。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchStockService {

    /** 台股大盤（TAIEX）特殊代號：價格 / 指標走 twse_index_daily_history。 */
    public static final String TAIEX_INDEX_CODE = "0000";
    public static final String TAIEX_INDEX_NAME = "台股大盤";

    private final StockAlertRepository alertRepo;
    private final PriceQueryService priceQuery;
    private final StockPriceHistoryRepository historyRepo;
    private final StockRepository stockMasterRepo;
    private final TechnicalIndicatorService indicatorService;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;

    private static boolean isTaiex(String code, String market) {
        return TAIEX_INDEX_CODE.equals(code) && "台股".equals(market);
    }

    @Transactional(readOnly = true)
    public List<WatchStockDto.Response> findAll() {
        List<Object[]> rows = alertRepo.findDistinctStockCodeMarket();
        return rows.stream().map(row -> {
            String code = (String) row[0];
            String market = (String) row[1];
            return toResponse(code, market);
        }).toList();
    }

    /**
     * 拖曳重排觀察清單：把每個股票所有 alert 的 displayOrder 整組重排到新位置。
     * 群組內的相對順序維持不變（依舊 displayOrder 升冪）；群組之間依 orderedKeys 順序排成連續區段。
     */
    @Transactional
    public void reorder(List<WatchStockDto.Key> orderedKeys) {
        if (orderedKeys == null || orderedKeys.isEmpty()) return;
        int cursor = 0;
        for (WatchStockDto.Key key : orderedKeys) {
            if (key == null || key.getStockCode() == null || key.getMarket() == null) continue;
            List<StockAlert> alerts = alertRepo.findByStockCodeAndMarket(
                    key.getStockCode().trim().toUpperCase(), key.getMarket());
            alerts.sort(Comparator.comparingInt(a -> a.getDisplayOrder() != null ? a.getDisplayOrder() : 0));
            for (StockAlert a : alerts) {
                a.setDisplayOrder(cursor++);
                alertRepo.save(a);
            }
        }
    }

    private WatchStockDto.Response toResponse(String code, String market) {
        if (isTaiex(code, market)) {
            return toIndexResponse(code, market);
        }
        String stockName = stockMasterRepo.findByCodeAndMarket(code, market)
                .map(s -> s.getName()).orElse(code);
        WatchStockDto.Response r = WatchStockDto.Response.builder()
                .stockCode(code)
                .stockName(stockName)
                .market(market)
                .build();

        // 報價
        Optional<PriceQueryService.LivePrice> priceOpt = priceQuery.getLive(code, market);
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
            List<StockPriceHistory> recent = historyRepo.findRecentN(code, market, 2);
            if (!recent.isEmpty()) {
                StockPriceHistory latest = recent.get(0);
                if (r.getOpenPrice() == null) r.setOpenPrice(latest.getOpenPrice());
                if (r.getHighPrice() == null) r.setHighPrice(latest.getHighPrice());
                if (r.getLowPrice()  == null) r.setLowPrice(latest.getLowPrice());
                if (r.getVolume()    == null && latest.getVolume() != null) {
                    r.setVolume("台股".equals(market)
                            ? latest.getVolume() / 1000
                            : latest.getVolume());
                }
                if (r.getPreviousClose() == null) {
                    if (recent.size() >= 2) r.setPreviousClose(recent.get(1).getClosePrice());
                    else r.setPreviousClose(latest.getClosePrice());
                }
            }
        }

        // priceChange / changePercent 由 DTO 上的 price / previousClose 即時計算
        if (r.getPrice() != null && r.getPreviousClose() != null
                && r.getPreviousClose().signum() != 0) {
            BigDecimal diff = r.getPrice().subtract(r.getPreviousClose());
            r.setPriceChange(diff.setScale(4, RoundingMode.HALF_UP));
            r.setChangePercent(diff
                    .divide(r.getPreviousClose(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP));
        }

        // 警示條件：列出該股票所有 alert 條件 label（依 displayOrder 升冪）
        List<StockAlert> alerts = alertRepo.findByStockCodeAndMarket(code, market);
        // 「警示條件」欄與「警示」欄共用同一份 cutoff：最後交易日（或當日）及前一日內觸發者套紅字
        java.time.LocalDateTime cutoff = recentTradingDayCutoff(market, StockAlertService.FRESHNESS_TRADING_DAYS);
        // 不論警示是否設定／觸發，皆計算當前的月線(MA20)、季線(MA60)、年線(MA240)、KD；
        // 「警示條件」欄 MA% 條件的觸發價換算亦共用同一份即時均線值（同義欄位同一來源）
        TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(code, market);
        r.setConditions(buildConditions(alerts, cutoff, ind));

        // 警示彙總：取該股最近一筆 lastTriggeredAt，且只顯示最後交易日（或當日）及前一日內的觸發
        alerts.stream()
                .filter(a -> a.getLastTriggeredAt() != null)
                .filter(a -> cutoff == null || !a.getLastTriggeredAt().isBefore(cutoff))
                .max(Comparator.comparing(StockAlert::getLastTriggeredAt))
                .ifPresent(a -> {
                    r.setLastTriggeredAt(a.getLastTriggeredAt());
                    r.setLastTriggeredPrice(a.getLastTriggeredPrice());
                    r.setLastTriggeredAlertType(a.getAlertType());
                });

        r.setMonthlyMa(ind.monthlyMa());
        r.setQuarterlyMa(ind.quarterlyMa());
        r.setAnnualMa(ind.annualMa());
        r.setKValue(ind.k());
        r.setDValue(ind.d());

        return r;
    }

    /**
     * 0000 = 台股大盤（TAIEX）的 Response：價格 + OHLC 走 twse_index_daily_history。
     * 季線（MA60）/ KD / 年線等指標由 TechnicalIndicatorService 對 0000 的特例分支計算。
     * 大盤無買賣盤口、無成交量定義 → buyPrice / sellPrice / volume 為 null。
     */
    private WatchStockDto.Response toIndexResponse(String code, String market) {
        WatchStockDto.Response r = WatchStockDto.Response.builder()
                .stockCode(code)
                .stockName(TAIEX_INDEX_NAME)
                .market(market)
                .build();

        List<TwseIndexDailyHistory> recent = twseDailyRepo.findTop60ByOrderByTradingDateDesc();
        if (!recent.isEmpty()) {
            TwseIndexDailyHistory latest = recent.get(0);
            r.setPrice(latest.getClosePoint());
            r.setOpenPrice(latest.getOpenPoint());
            r.setHighPrice(latest.getHighPoint());
            r.setLowPrice(latest.getLowPoint());
            r.setTradingDate(latest.getTradingDate().toString());
            r.setClosed(true);
            if (recent.size() >= 2) {
                r.setPreviousClose(recent.get(1).getClosePoint());
            }
            if (r.getPrice() != null && r.getPreviousClose() != null
                    && r.getPreviousClose().signum() != 0) {
                BigDecimal diff = r.getPrice().subtract(r.getPreviousClose());
                r.setPriceChange(diff.setScale(4, RoundingMode.HALF_UP));
                r.setChangePercent(diff
                        .divide(r.getPreviousClose(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(4, RoundingMode.HALF_UP));
            }
        }

        // 警示條件：列出該股票所有 alert 條件 label
        List<StockAlert> alerts = alertRepo.findByStockCodeAndMarket(code, market);
        java.time.LocalDateTime cutoff = recentTradingDayCutoff(market, StockAlertService.FRESHNESS_TRADING_DAYS);
        // 先算指標：MA 欄與「警示條件」欄 MA% 觸發價共用同一份即時均線值（同義欄位同一來源）
        TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(code, market);
        r.setConditions(buildConditions(alerts, cutoff, ind));
        alerts.stream()
                .filter(a -> a.getLastTriggeredAt() != null)
                .filter(a -> cutoff == null || !a.getLastTriggeredAt().isBefore(cutoff))
                .max(Comparator.comparing(StockAlert::getLastTriggeredAt))
                .ifPresent(a -> {
                    r.setLastTriggeredAt(a.getLastTriggeredAt());
                    r.setLastTriggeredPrice(a.getLastTriggeredPrice());
                    r.setLastTriggeredAlertType(a.getAlertType());
                });

        r.setMonthlyMa(ind.monthlyMa());
        r.setQuarterlyMa(ind.quarterlyMa());
        r.setAnnualMa(ind.annualMa());
        r.setKValue(ind.k());
        r.setDValue(ind.d());
        return r;
    }

    /**
     * 把該股票所有 alert 排序成 condition list，含 label / active / triggered 旗標。
     * triggered = `alert.lastTriggeredAt` 落在 cutoff（最後交易日及前一日，共 2 個交易日）之內，前端據此套紅字。
     */
    private static List<WatchStockDto.Condition> buildConditions(List<StockAlert> alerts, LocalDateTime cutoff,
                                                                 TechnicalIndicatorService.FullIndicators ind) {
        return alerts.stream()
                .sorted(Comparator.comparingInt(a -> a.getDisplayOrder() != null ? a.getDisplayOrder() : 0))
                .map(a -> {
                    boolean triggered = a.getLastTriggeredAt() != null
                            && (cutoff == null || !a.getLastTriggeredAt().isBefore(cutoff));
                    return new WatchStockDto.Condition(
                            StockAlertService.buildLabel(a, ind),
                            Boolean.TRUE.equals(a.getActive()),
                            triggered);
                })
                .toList();
    }

    /** 回傳「最近 N 個交易日中最早一天的午夜」當作 cutoff；資料不足回 null（不過濾）。 */
    private LocalDateTime recentTradingDayCutoff(String market, int n) {
        List<java.time.LocalDate> dates = historyRepo
                .findDistinctTradingDatesByMarket(market,
                        org.springframework.data.domain.PageRequest.of(0, n));
        if (dates.size() < n) return null;
        return dates.get(dates.size() - 1).atStartOfDay();
    }
}
