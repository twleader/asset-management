package com.steven.assets.service;

import com.steven.assets.dto.WatchStockDto;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertGroup;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockAlertGroupRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * 複合條件群組（Task 253）：群組成員仍是 stock_alert 的列（stock_code / market 皆有值），
 * 故 findDistinctStockCodeMarket 的 GROUP BY 自動涵蓋、觀察清單不會漏股票；但成員在本頁
 * <b>不逐條顯示</b>，而是與 stock_alert_group 那一列合併成單一 Condition（label 以「 且 」串接），
 * 否則 AND 語意在畫面上會被讀成數條互不相干的 OR 條件。
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
    /** 複合條件群組（Task 253）：條件欄合併顯示與拖曳重排都需要，成員的 displayOrder 從群組值起算 */
    private final StockAlertGroupRepository groupRepo;
    private final PriceQueryService priceQuery;
    private final StockPriceHistoryRepository historyRepo;
    private final StockRepository stockMasterRepo;
    private final TechnicalIndicatorService indicatorService;
    private final TaiexDisplayPriceService taiexDisplayPriceService;

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
     * 拖曳重排觀察清單：把每個股票所有警示條件的 displayOrder 整組重排到新位置。
     * 同一股票內的相對順序維持不變（依舊 displayOrder 升冪）；股票之間依 orderedKeys 順序排成連續區段。
     *
     * <p><b>複合條件群組（Task 253）在此吃「一個」cursor 值，成員不各吃一個。</b>
     * 成員的 displayOrder 在 Task 253 之後另有語意——它決定群組內條件的串接順序（buildGroupLabel），
     * 且必須從群組自己的 displayOrder 起算；若沿用舊寫法讓成員也跟著 {@code cursor++}，
     * 使用者在觀察頁拖一次就會 (a) 把群組內條件順序洗成任意值、合併 label 的條件順序跟著變，
     * (b) 完全沒同步 {@code stock_alert_group.display_order}，警示頁的混合排序與剛拖出來的觀察清單脫節。
     * 故成員一律略過 cursor，改在群組拿到新值後重寫為「群組新值 + 成員索引」。
     *
     * <p>成員的值會與後續股票的 cursor 值重疊（例：群組拿到 3、三個成員拿 3/4/5，下一檔股票從 4 起算），
     * 這是 Task 253.12 既定的設計——成員不出現在警示頁混合清單、也不單獨出現在觀察清單，
     * 只影響 {@code findDistinctStockCodeMarket} 的 {@code MIN(display_order)}；
     * 而成員值恆 ≥ 群組值 ≥ 該股票的第一個 cursor 值，MIN 不會被壓低，觀察清單順序不受影響。
     */
    @Transactional
    public void reorder(List<WatchStockDto.Key> orderedKeys) {
        if (orderedKeys == null || orderedKeys.isEmpty()) return;
        int cursor = 0;
        for (WatchStockDto.Key key : orderedKeys) {
            if (key == null || key.stockCode() == null || key.market() == null) continue;
            String code = key.stockCode().trim().toUpperCase();
            List<StockAlert> alerts = alertRepo.findByStockCodeAndMarket(code, key.market());
            alerts.sort(Comparator.comparingInt(a -> a.getDisplayOrder() != null ? a.getDisplayOrder() : 0));

            // 獨立條件（groupId == null）：一條吃一個 cursor 值，行為與 Task 253 前相同
            for (StockAlert a : alerts) {
                if (a.getGroupId() != null) continue;
                a.setDisplayOrder(cursor++);
                alertRepo.save(a);
            }

            // 群組成員依 groupId 收攏；alerts 已依 displayOrder 升冪，groupingBy 保留出現順序 → 子清單即原本的成員順序
            Map<Long, List<StockAlert>> membersByGroup = alerts.stream()
                    .filter(a -> a.getGroupId() != null)
                    .collect(Collectors.groupingBy(StockAlert::getGroupId, LinkedHashMap::new, Collectors.toList()));

            List<StockAlertGroup> groups = groupRepo.findByStockCodeAndMarket(code, key.market());
            groups.sort(Comparator.comparingInt(g -> g.getDisplayOrder() != null ? g.getDisplayOrder() : 0));
            for (StockAlertGroup g : groups) {
                int base = cursor++;
                g.setDisplayOrder(base);
                groupRepo.save(g);
                // 維持 Task 253.12 的不變式：第 i 個成員 = 群組 displayOrder + i
                List<StockAlert> members = membersByGroup.getOrDefault(g.getId(), List.of());
                for (int i = 0; i < members.size(); i++) {
                    members.get(i).setDisplayOrder(base + i);
                    alertRepo.save(members.get(i));
                }
            }
        }
    }

    private WatchStockDto.Response toResponse(String code, String market) {
        if (isTaiex(code, market)) {
            return toIndexResponse(code, market);
        }
        String stockName = stockMasterRepo.findByCodeAndMarket(code, market)
                .map(s -> s.getName()).orElse(code);

        // 報價（先湊齊區域變數，最後一次 build 出不可變 record）
        BigDecimal price = null, priceChange = null, changePercent = null,
                buyPrice = null, sellPrice = null, openPrice = null, previousClose = null,
                highPrice = null, lowPrice = null;
        Long volume = null;
        String tradingDate = null, priceUpdatedAt = null, quoteStatus = null;
        Boolean closed = null;

        Optional<PriceQueryService.LivePrice> priceOpt = priceQuery.getDisplayPrice(code, market);
        if (priceOpt.isPresent()) {
            PriceQueryService.LivePrice sp = priceOpt.get();
            price = sp.price();
            priceChange = sp.priceChange();
            changePercent = sp.changePercent();
            buyPrice = sp.buyPrice();
            sellPrice = sp.sellPrice();
            openPrice = sp.openPrice();
            previousClose = sp.previousClose();
            highPrice = sp.highPrice();
            lowPrice = sp.lowPrice();
            volume = sp.volume();
            tradingDate = sp.tradingDate();
            priceUpdatedAt = sp.updatedAt();
            closed = sp.closed();
            quoteStatus = sp.quoteStatus();
        }

        // 對於非交易時間或新加入觀察股票，買賣/開盤/昨收/最高/最低/成交量可能為 null
        // → 用最近的歷史收盤資料（StockPriceHistory）回填
        if (!"CLOSE_PENDING".equals(quoteStatus)
                && (openPrice == null || highPrice == null || lowPrice == null
                || volume == null || previousClose == null)) {
            List<StockPriceHistory> recent = historyRepo.findRecentN(code, market, 2);
            if (!recent.isEmpty()) {
                StockPriceHistory latest = recent.get(0);
                if (openPrice == null) openPrice = latest.getOpenPrice();
                if (highPrice == null) highPrice = latest.getHighPrice();
                if (lowPrice  == null) lowPrice = latest.getLowPrice();
                if (volume    == null && latest.getVolume() != null) {
                    volume = "台股".equals(market)
                            ? latest.getVolume() / 1000
                            : latest.getVolume();
                }
                if (previousClose == null) {
                    previousClose = recent.size() >= 2
                            ? recent.get(1).getClosePrice()
                            : latest.getClosePrice();
                }
            }
        }

        // priceChange / changePercent 由 price / previousClose 即時計算
        if (price != null && previousClose != null && previousClose.signum() != 0) {
            BigDecimal diff = price.subtract(previousClose);
            priceChange = diff.setScale(4, RoundingMode.HALF_UP);
            changePercent = diff
                    .divide(previousClose, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
        }

        // 警示條件：列出該股票所有 alert 條件 label（依 displayOrder 升冪）
        List<StockAlert> alerts = alertRepo.findByStockCodeAndMarket(code, market);
        // 複合條件群組（Task 253）：成員也在上面的 alerts 裡，但不逐條顯示，改與群組合併成一條 Condition
        List<StockAlertGroup> groups = groupRepo.findByStockCodeAndMarket(code, market);
        // 「警示條件」欄與「警示」欄共用同一份 cutoff：最後交易日（或當日）及前一日內觸發者套紅字
        java.time.LocalDateTime cutoff = recentTradingDayCutoff(market, StockAlertService.FRESHNESS_TRADING_DAYS);
        // 不論警示是否設定／觸發，皆計算當前的月線(MA20)、季線(MA60)、年線(MA240)、KD；
        // 「警示條件」欄 MA% 條件的觸發價換算亦共用同一份即時均線值（同義欄位同一來源）
        TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(code, market);
        List<WatchStockDto.Condition> conditions = buildConditions(alerts, groups, cutoff, ind);

        // 警示彙總：取該股最近一筆觸發（獨立條件與群組一起比），且只顯示最後交易日（或當日）及前一日內的觸發
        LastAlert last = lastAlert(alerts, groups, cutoff);

        return WatchStockDto.Response.builder()
                .stockCode(code)
                .stockName(stockName)
                .market(market)
                .price(price)
                .priceChange(priceChange)
                .changePercent(changePercent)
                .buyPrice(buyPrice)
                .sellPrice(sellPrice)
                .openPrice(openPrice)
                .previousClose(previousClose)
                .highPrice(highPrice)
                .lowPrice(lowPrice)
                .volume(volume)
                .tradingDate(tradingDate)
                .priceUpdatedAt(priceUpdatedAt)
                .closed(closed)
                .quoteStatus(quoteStatus)
                .conditions(conditions)
                .lastTriggeredAt(last.triggeredAt())
                .lastTriggeredPrice(last.price())
                .lastTriggeredAlertType(last.alertType())
                .monthlyMa(ind.monthlyMa())
                .quarterlyMa(ind.quarterlyMa())
                .annualMa(ind.annualMa())
                .kValue(ind.k())
                .dValue(ind.d())
                .build();
    }

    /**
     * 0000 = 台股大盤（TAIEX）的 Response。
     *
     * <p><b>報價欄分盤中／盤後兩層（Task 263）。</b>完成日 K（{@code twse_index_daily_history}）的今日列
     * 要等 {@code TwseIndexPoller} 的 14:00 排程才寫入，故 09:00–14:00 整個盤中「最新一筆」恆為<b>昨日</b>；
     * 原本無條件讀它、並把 {@code closed} 寫死 {@code true}，於是這一列整個盤中停在昨天，而同一列的
     * MA／KD 由 {@code computeAllForTaiex()} 算出、已含今日即時點位——畫面同時呈現「今天的指標」與
     * 「昨天的股價」。現行規則：完成日 K 未到今日、且 Redis {@code price:台股:0000} 的 tradingDate
     * 等於台北今日時，price / OHLC / tradingDate 五欄一律取自即時值且 {@code closed=false}；其餘情形
     * 維持既有的完成日 K 行為。判定條件與 {@code TechnicalIndicatorService.computeAllForTaiex()}
     * <b>語意等價</b>（值逐次相同），否則同列的股價與指標會再次落在不同日期。
     *
     * <p>{@code previousClose} 兩層<b>共用同一條規則</b>：日線表中 trading_date 嚴格早於當列
     * tradingDate 的最後一筆。<b>刻意不讀 Redis payload 的 previousClose</b>——「股市大盤查詢」頁
     * 當日卡的昨收讀同一張表、用同一條規則，兩頁的昨收是同義欄位必須同源。
     *
     * <p>季線（MA60）/ KD / 年線等指標由 TechnicalIndicatorService 對 0000 的特例分支計算。
     * 大盤無買賣盤口、無成交量定義 → buyPrice / sellPrice / volume 為 null。
     */
    private WatchStockDto.Response toIndexResponse(String code, String market) {
        TaiexDisplayPriceService.DisplayQuote display = taiexDisplayPriceService.resolve();
        BigDecimal price = display.price();
        BigDecimal previousClose = display.previousClose();
        BigDecimal priceChange = price != null && previousClose != null
                ? price.subtract(previousClose).setScale(4, RoundingMode.HALF_UP)
                : null;
        BigDecimal changePercent = display.changePercent() == null ? null
                : display.changePercent().setScale(4, RoundingMode.HALF_UP);
        BigDecimal openPrice = display.open();
        BigDecimal highPrice = display.high();
        BigDecimal lowPrice = display.low();
        String tradingDate = display.tradingDate();
        Boolean closed = display.closed();

        // 警示條件：列出該股票所有 alert 條件 label
        List<StockAlert> alerts = alertRepo.findByStockCodeAndMarket(code, market);
        // 大盤同樣可能掛複合條件群組；這一段與 toResponse 各有一份，兩邊都要合併，
        // 只改一邊會讓大盤的複合條件在觀察頁散開成數條獨立條件顯示（Task 253）
        List<StockAlertGroup> groups = groupRepo.findByStockCodeAndMarket(code, market);
        java.time.LocalDateTime cutoff = recentTradingDayCutoff(market, StockAlertService.FRESHNESS_TRADING_DAYS);
        // 先算指標：MA 欄與「警示條件」欄 MA% 觸發價共用同一份即時均線值（同義欄位同一來源）
        TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(code, market);
        List<WatchStockDto.Condition> conditions = buildConditions(alerts, groups, cutoff, ind);

        LastAlert last = lastAlert(alerts, groups, cutoff);

        return WatchStockDto.Response.builder()
                .stockCode(code)
                .stockName(TAIEX_INDEX_NAME)
                .market(market)
                .price(price)
                .priceChange(priceChange)
                .changePercent(changePercent)
                .openPrice(openPrice)
                .previousClose(previousClose)
                .highPrice(highPrice)
                .lowPrice(lowPrice)
                .tradingDate(tradingDate)
                .closed(closed)
                .priceUpdatedAt(display.updatedAt())
                .quoteStatus(display.quoteStatus())
                .conditions(conditions)
                .lastTriggeredAt(last.triggeredAt())
                .lastTriggeredPrice(last.price())
                .lastTriggeredAlertType(last.alertType())
                .monthlyMa(ind.monthlyMa())
                .quarterlyMa(ind.quarterlyMa())
                .annualMa(ind.annualMa())
                .kValue(ind.k())
                .dValue(ind.d())
                .build();
    }

    /**
     * 把該股票所有警示條件排序成 condition list，含 label / active / triggered 旗標。
     * triggered = 觸發時點落在 cutoff（最後交易日及前一日，共 2 個交易日）之內，前端據此套紅字。
     *
     * <p>獨立條件（{@code groupId == null}）維持逐條一列；<b>複合條件群組合併成一列</b>：
     * label 由 {@link StockAlertService#buildGroupLabel} 以「 且 」串接（警示頁 / 觀察頁 / email / 補發
     * 四條路徑共用這一支，不得各自串接），active 與 triggered 一律取群組那一列
     * ——成員的 {@code active} 恆為 true、{@code last_triggered_*} 五欄永遠是 null，拿成員的值判斷會恆為「未觸發」。
     *
     * <p>兩者合併後依 displayOrder 升冪：獨立條件用自身的、群組用群組那一列的
     * （成員的 displayOrder 只決定群組內條件的串接順序，不參與此排序）。
     */
    private static List<WatchStockDto.Condition> buildConditions(List<StockAlert> alerts,
                                                                 List<StockAlertGroup> groups,
                                                                 LocalDateTime cutoff,
                                                                 TechnicalIndicatorService.FullIndicators ind) {
        List<OrderedCondition> merged = new ArrayList<>();

        for (StockAlert a : alerts) {
            if (a.getGroupId() != null) continue;   // 群組成員不單獨列出，改由下方合併成一條
            merged.add(new OrderedCondition(
                    a.getDisplayOrder() != null ? a.getDisplayOrder() : 0,
                    new WatchStockDto.Condition(
                            StockAlertService.buildLabel(a, ind),
                            Boolean.TRUE.equals(a.getActive()),
                            triggeredWithin(a.getLastTriggeredAt(), cutoff),
                            false)));
        }

        // 成員取自同一份 alerts（成員的 stock_code / market 與群組相同，必然在這份清單裡），不另發查詢
        Map<Long, List<StockAlert>> membersByGroup = alerts.stream()
                .filter(a -> a.getGroupId() != null)
                .collect(Collectors.groupingBy(StockAlert::getGroupId));
        if (groups != null) {
            for (StockAlertGroup g : groups) {
                List<StockAlert> members = new ArrayList<>(membersByGroup.getOrDefault(g.getId(), List.of()));
                // 成員被外力清空的孤兒群組：label 會是空字串，寧可整條不顯示也不要在畫面上留一列空白
                if (members.isEmpty()) continue;
                // buildGroupLabel 要求成員已依 displayOrder 升冪（那是群組內條件的串接順序）
                members.sort(Comparator.comparingInt(m -> m.getDisplayOrder() != null ? m.getDisplayOrder() : 0));
                merged.add(new OrderedCondition(
                        g.getDisplayOrder() != null ? g.getDisplayOrder() : 0,
                        new WatchStockDto.Condition(
                                StockAlertService.buildGroupLabel(members, ind),
                                Boolean.TRUE.equals(g.getActive()),
                                triggeredWithin(g.getLastTriggeredAt(), cutoff),
                                true)));   // 前端據此掛「複合」標籤（251.22）
            }
        }

        merged.sort(Comparator.comparingInt(OrderedCondition::order));
        return merged.stream().map(OrderedCondition::condition).toList();
    }

    /** 獨立條件與群組合併成同一個 displayOrder 排序空間的暫存單元（排完即丟，不外流）。 */
    private record OrderedCondition(int order, WatchStockDto.Condition condition) {}

    /** 「警示」欄的最近觸發彙總；無任何符合 cutoff 的觸發時三個欄位皆為 null。 */
    private record LastAlert(LocalDateTime triggeredAt, BigDecimal price, String alertType) {}

    /**
     * 「警示」欄彙總：獨立條件與複合條件群組放在一起取最近一次觸發（同一份 cutoff）。
     * 命中群組時 alertType 填 {@code "GROUP"} —— 群組沒有單一 alert_type 可填，
     * 而前端只用這個值區分顯示樣式，不做語意解析。
     *
     * <p>時間相同時保留先掃到的那一筆（獨立條件優先），與原本 {@code Stream.max} 的 tie-break 行為一致。
     * 非交易時段的 {@code computeTriggeredAt} 會退回「最後交易日的收盤時點」，同一檔股票的多筆觸發
     * 拿到相同時間戳是常態，故這裡必須是穩定的判斷（用嚴格的 isAfter，不是 !isBefore）。
     */
    private static LastAlert lastAlert(List<StockAlert> alerts, List<StockAlertGroup> groups, LocalDateTime cutoff) {
        LocalDateTime bestAt = null;
        BigDecimal bestPrice = null;
        String bestType = null;
        for (StockAlert a : alerts) {
            // 成員的 last_triggered_* 一律不寫（觸發狀態只記在群組上），這裡明寫過濾以免日後有髒資料混進彙總
            if (a.getGroupId() != null) continue;
            if (!triggeredWithin(a.getLastTriggeredAt(), cutoff)) continue;
            if (bestAt == null || a.getLastTriggeredAt().isAfter(bestAt)) {
                bestAt = a.getLastTriggeredAt();
                bestPrice = a.getLastTriggeredPrice();
                bestType = a.getAlertType();
            }
        }
        if (groups != null) {
            for (StockAlertGroup g : groups) {
                if (!triggeredWithin(g.getLastTriggeredAt(), cutoff)) continue;
                if (bestAt == null || g.getLastTriggeredAt().isAfter(bestAt)) {
                    bestAt = g.getLastTriggeredAt();
                    bestPrice = g.getLastTriggeredPrice();
                    bestType = "GROUP";
                }
            }
        }
        return new LastAlert(bestAt, bestPrice, bestType);
    }

    /** 觸發時點是否落在 cutoff（最後交易日及前一日）之內；cutoff 為 null 表示資料不足、不過濾。 */
    private static boolean triggeredWithin(LocalDateTime triggeredAt, LocalDateTime cutoff) {
        return triggeredAt != null && (cutoff == null || !triggeredAt.isBefore(cutoff));
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
