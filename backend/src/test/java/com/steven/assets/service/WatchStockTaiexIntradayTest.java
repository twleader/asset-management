package com.steven.assets.service;

import com.steven.assets.dto.WatchStockDto;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertGroup;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockAlertGroupRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 觀察清單 `0000` 台股大盤那一列的報價來源（Task 263，Requirement 14 修訂）。
 *
 * <p>完成日 K（`twse_index_daily_history`）的今日列要等 `TwseIndexPoller` 的 14:00 排程才寫入，
 * 故 09:00–14:00 整個盤中「最新一筆」恆為<b>昨日</b>。原本無條件讀它並把 `closed` 寫死 `true`，
 * 於是這一列整個盤中停在昨天，而同一列的 MA／KD 由 `computeAllForTaiex()` 算出、已含今日即時
 * 點位——畫面同時呈現「今天的指標」與「昨天的股價」。
 *
 * <p>現行規則：完成日 K 未到今日、且 Redis `price:台股:0000` 的 tradingDate 等於台北今日時，
 * price / OHLC / tradingDate 五欄一律取自即時值且 `closed=false`；其餘情形維持完成日 K 行為。
 * 判定條件與 `TechnicalIndicatorService.computeAllForTaiex()` 語意等價（值逐次相同）。
 */
@ExtendWith(MockitoExtension.class)
class WatchStockTaiexIntradayTest {

    @Mock private StockAlertRepository alertRepo;
    @Mock private StockAlertGroupRepository groupRepo;
    @Mock private PriceQueryService priceQuery;
    @Mock private StockPriceHistoryRepository historyRepo;
    @Mock private StockRepository stockMasterRepo;
    @Mock private TechnicalIndicatorService indicatorService;
    @Mock private TwseIndexDailyHistoryRepository twseDailyRepo;

    private WatchStockService service;

    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));

    @BeforeEach
    void setUp() {
        service = new WatchStockService(alertRepo, groupRepo, priceQuery, historyRepo,
                stockMasterRepo, indicatorService,
                new TaiexDisplayPriceService(priceQuery, twseDailyRepo), twseDailyRepo);
        when(priceQuery.displaySession("台股")).thenReturn(new PriceQueryService.DisplaySession(
                PriceQueryService.DisplayPhase.OPEN, TODAY, TODAY));
        // toIndexResponse 必然走到 computeAll；未 stub 的 mock 回 null，builder 的 ind.monthlyMa() 會 NPE
        when(indicatorService.computeAll("0000", "台股"))
                .thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);
        List<Object[]> keys = new ArrayList<>();
        keys.add(new Object[]{"0000", "台股"});
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(keys);
    }

    /** 含跳日的完成日 K（降冪）：7/30、7/29、7/27（週末缺 7/25–7/26）、7/24。 */
    private List<TwseIndexDailyHistory> descRows(LocalDate latest) {
        List<TwseIndexDailyHistory> rows = new ArrayList<>();
        rows.add(row(latest, "39933.30", "40048.94", "41155.42", "39404.65"));
        rows.add(row(latest.minusDays(1), "40039.18", "41491.48", "41698.39", "39384.85"));
        rows.add(row(latest.minusDays(3), "43634.19", "43585.92", "43686.15", "42969.48"));
        rows.add(row(latest.minusDays(6), "43654.84", "44769.39", "44769.39", "43607.40"));
        return rows;
    }

    private TwseIndexDailyHistory row(LocalDate d, String close, String open, String high, String low) {
        TwseIndexDailyHistory h = new TwseIndexDailyHistory();
        h.setTradingDate(d);
        h.setClosePoint(new BigDecimal(close));
        h.setOpenPoint(new BigDecimal(open));
        h.setHighPoint(new BigDecimal(high));
        h.setLowPoint(new BigDecimal(low));
        return h;
    }

    private PriceQueryService.LivePrice live(String tradingDate, String price,
                                             String open, String high, String low) {
        return new PriceQueryService.LivePrice(
                "0000", "台股大盤", "台股", new BigDecimal(price),
                new BigDecimal("99999.99"),                       // previousClose：刻意給離譜值，證明不會被採用
                new BigDecimal("-1"), new BigDecimal("-1"),       // priceChange / changePercent：同上
                new BigDecimal("1"), new BigDecimal("2"),         // buyPrice / sellPrice：大盤必須忽略
                open == null ? null : new BigDecimal(open),
                high == null ? null : new BigDecimal(high),
                low == null ? null : new BigDecimal(low),
                123L,                                             // volume：大盤必須忽略
                tradingDate, "2026-07-31T13:28:00", false, "TWSE指數(5m)", "LIVE");
    }

    private WatchStockDto.Response findAllFirst() {
        return service.findAll().get(0);
    }

    @Test
    void 盤中_完成日K未到今日且live為今日_五欄取live且closed為false() {
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(descRows(TODAY.minusDays(1)));
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(
                live(TODAY.toString(), "43200.1094", "42656.0000", "43214.3600", "41610.4100")));

        WatchStockDto.Response r = findAllFirst();

        assertThat(r.price()).isEqualByComparingTo("43200.1094");
        assertThat(r.openPrice()).isEqualByComparingTo("42656.0000");
        assertThat(r.highPrice()).isEqualByComparingTo("43214.3600");
        assertThat(r.lowPrice()).isEqualByComparingTo("41610.4100");
        assertThat(r.tradingDate()).isEqualTo(TODAY.toString());
        assertThat(r.closed()).isFalse();
        // 昨收＝嚴格早於顯示日（今日）的最後一筆完成日 K，即 recent.get(0)；不得取 live payload 的 99999.99
        assertThat(r.previousClose()).isEqualByComparingTo("39933.30");
    }

    @Test
    void 盤中_Redis無值_退回完成日K最新一筆且closed為true() {
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(descRows(TODAY.minusDays(1)));
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.empty());

        WatchStockDto.Response r = findAllFirst();

        assertThat(r.price()).isEqualByComparingTo("39933.30");
        assertThat(r.openPrice()).isEqualByComparingTo("40048.94");
        assertThat(r.highPrice()).isEqualByComparingTo("41155.42");
        assertThat(r.lowPrice()).isEqualByComparingTo("39404.65");
        assertThat(r.tradingDate()).isEqualTo(TODAY.minusDays(1).toString());
        assertThat(r.closed()).isTrue();
        assertThat(r.previousClose()).isEqualByComparingTo("40039.18");   // 次新一筆
    }

    /** 開盤最初數分鐘 Yahoo 尚無今日格時的守門：昨日點位不得被當成今日報價。 */
    @Test
    void 盤中_live為昨日_同樣退回完成日K() {
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(descRows(TODAY.minusDays(1)));
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(
                live(TODAY.minusDays(1).toString(), "40010.4100", "39888.2800", "41155.4200", "39404.6500")));

        WatchStockDto.Response r = findAllFirst();

        assertThat(r.price()).isEqualByComparingTo("39933.30");          // 完成日 K 的收盤，非 live 的 40010.41
        assertThat(r.tradingDate()).isEqualTo(TODAY.minusDays(1).toString());
        assertThat(r.closed()).isTrue();
        assertThat(r.previousClose()).isEqualByComparingTo("40039.18");
    }

    /** 開盤中即使異常已有今日 K，仍以當日 live 為準；未收盤的日 K 不得升級成 VERIFIED_CLOSE。 */
    @Test
    void 盤中_完成日K異常已有今日_仍採當日live() {
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(descRows(TODAY));
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(
                live(TODAY.toString(), "43200.1094", "42656.0000", "43214.3600", "41610.4100")));

        WatchStockDto.Response r = findAllFirst();

        assertThat(r.price()).isEqualByComparingTo("43200.1094");
        assertThat(r.tradingDate()).isEqualTo(TODAY.toString());
        assertThat(r.closed()).isFalse();
        assertThat(r.quoteStatus()).isEqualTo("LIVE");
        assertThat(r.previousClose()).isEqualByComparingTo("40039.18");
    }

    @Test
    void 盤後_當日完成日K尚未寫入_回傳待補而非昨日點位() {
        when(priceQuery.displaySession("台股")).thenReturn(new PriceQueryService.DisplaySession(
                PriceQueryService.DisplayPhase.AFTER_CLOSE, TODAY, TODAY));
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(descRows(TODAY.minusDays(1)));
        when(twseDailyRepo.findById(TODAY)).thenReturn(Optional.empty());

        WatchStockDto.Response r = findAllFirst();

        assertThat(r.price()).isNull();
        assertThat(r.tradingDate()).isEqualTo(TODAY.toString());
        assertThat(r.closed()).isFalse();
        assertThat(r.quoteStatus()).isEqualTo("CLOSE_PENDING");
    }

    @Test
    void 盤後_當日完成日K已寫入_回傳已驗證收盤() {
        List<TwseIndexDailyHistory> rows = descRows(TODAY);
        when(priceQuery.displaySession("台股")).thenReturn(new PriceQueryService.DisplaySession(
                PriceQueryService.DisplayPhase.AFTER_CLOSE, TODAY, TODAY));
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(rows);
        when(twseDailyRepo.findById(TODAY)).thenReturn(Optional.of(rows.get(0)));

        WatchStockDto.Response r = findAllFirst();

        assertThat(r.price()).isEqualByComparingTo("39933.30");
        assertThat(r.closed()).isTrue();
        assertThat(r.quoteStatus()).isEqualTo("VERIFIED_CLOSE");
    }

    /** 大盤無買賣盤口、無成交量定義——即使 live payload 帶了這三欄也不得採用。 */
    @Test
    void 四種情形_買賣盤口與成交量恆為null() {
        // (a) 盤中取 live（payload 刻意帶 buyPrice/sellPrice/volume）
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(descRows(TODAY.minusDays(1)));
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(
                live(TODAY.toString(), "43200.1094", "42656.0000", "43214.3600", "41610.4100")));
        assertNoQuoteFields(findAllFirst());

        // (b) live 為昨日 → 退回完成日 K
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(
                live(TODAY.minusDays(1).toString(), "40010.4100", null, null, null)));
        assertNoQuoteFields(findAllFirst());

        // (c) Redis 無值
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.empty());
        assertNoQuoteFields(findAllFirst());

        // (d) 完成日 K 已含今日
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(descRows(TODAY));
        assertNoQuoteFields(findAllFirst());
    }

    private void assertNoQuoteFields(WatchStockDto.Response r) {
        assertThat(r.buyPrice()).isNull();
        assertThat(r.sellPrice()).isNull();
        assertThat(r.volume()).isNull();
        assertThat(r.stockName()).isEqualTo("台股大盤");
    }

    /**
     * 漲跌幅一律由 price 與 previousClose 現算，不取 Redis payload 的既算值（那是依它自己那份昨收算的）。
     * 43200.1094 − 39933.30 = 3266.8094；3266.8094 ÷ 39933.30 = 0.08180664758…
     * → setScale(6, HALF_UP) 第 7 位小數為 6 故進位 = 0.081807 → ×100 → setScale(4) = 8.1807。
     */
    @Test
    void 漲跌與漲跌幅為現算值() {
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(descRows(TODAY.minusDays(1)));
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(
                live(TODAY.toString(), "43200.1094", "42656.0000", "43214.3600", "41610.4100")));

        WatchStockDto.Response r = findAllFirst();

        assertThat(r.priceChange()).isEqualByComparingTo("3266.8094");
        assertThat(r.changePercent()).isEqualByComparingTo("8.1807");
    }

    /**
     * 昨收規則的單側迴歸錨點：日線表中 trading_date 嚴格早於當列 tradingDate 的最後一筆，
     * 跳日（7/27 → 7/24 跨週末）不影響判定。
     *
     * <p>「股市大盤查詢」頁的當日卡昨收走另一套獨立實作（`MarketIndexChartService.previousCloseBefore`，
     * package-private、字串字典序比對、跑在 bff 模組），判準原文為「tradingDate 嚴格早於 beforeDate 的最後一筆」。
     * 兩者不共用程式碼也不在同一個 Maven 模組，本測試<b>釘不住那一側</b>——此處記下判準僅供日後人工比對。
     */
    @Test
    void 昨收取嚴格早於顯示日的最後一筆_跳日不影響() {
        List<TwseIndexDailyHistory> rows = descRows(TODAY.minusDays(1));
        // 顯示日為 rows.get(2) 的日期（TODAY-4）時，昨收應為 rows.get(3)（TODAY-7），中間跨週末
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(List.of(rows.get(2), rows.get(3)));

        WatchStockDto.Response r = findAllFirst();

        assertThat(r.tradingDate()).isEqualTo(TODAY.minusDays(4).toString());
        assertThat(r.previousClose()).isEqualByComparingTo("43654.84");
    }
    // Task 563：目前觀察列的 52 週門檻價位（不涉及觸發與通知）。
    private void ordinaryStock() {
        reset(alertRepo, priceQuery, indicatorService);
        List<Object[]> keys = new ArrayList<>();
        keys.add(new Object[]{"VOO", "美股"});
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(keys);
        when(indicatorService.computeAll("VOO", "美股")).thenReturn(
                new TechnicalIndicatorService.FullIndicators(new BigDecimal("701.60"),
                        new BigDecimal("704.33"), null, null, null, null, null, null,
                        TechnicalIndicatorService.ExtendedIndicators.EMPTY, null));
    }

    private StockAlert condition(String type, String threshold, Long group, int order, Integer period) {
        return StockAlert.builder().stockCode("VOO").market("美股").alertType(type)
                .threshold(new BigDecimal(threshold)).groupId(group).displayOrder(order)
                .maPeriod(period).active(true).build();
    }

    private List<StockPriceHistory> stockRange(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> StockPriceHistory.builder()
                .stockCode("VOO").market("美股").tradingDate(TODAY.minusDays(i + 2))
                .closePrice(new BigDecimal("600")).highPrice(new BigDecimal("700"))
                .lowPrice(new BigDecimal("500")).build()).toList();
    }

    private void liveAndHistory(List<StockPriceHistory> history) {
        // 展示價刻意為另一個價格；門檻只准用 getLive 的 600，不能以展示價 900 擴大區間。
        when(priceQuery.getDisplayPrice("VOO", "美股")).thenReturn(Optional.of(
                live(TODAY.toString(), "900", "900", "900", "900")));
        when(priceQuery.getLive("VOO", "美股")).thenReturn(Optional.of(
                live(TODAY.toString(), "600", null, null, null)));
        when(historyRepo.findRecentN("VOO", "美股", 241)).thenReturn(history);
    }

    @Test
    void 觀察頁_60百分比完整複合條件與單條多群組共用一次區間() {
        ordinaryStock();
        liveAndHistory(stockRange(240));
        List<StockAlert> alerts = List.of(
                condition("POS52W_ABOVE", "50", null, 0, null),
                condition("POS52W_BELOW", "60", 10L, 14, null),
                condition("MA_BELOW_PCT", "5", 10L, 10, 60),
                condition("KD_D_BELOW", "15", 10L, 11, null),
                condition("KD_K_GT_D", "0", 10L, 12, null),
                condition("MA_BELOW_PCT", "5", 10L, 13, 20),
                condition("POS52W_ABOVE", "100", 20L, 20, null));
        when(alertRepo.findByStockCodeAndMarket("VOO", "美股")).thenReturn(alerts);
        when(groupRepo.findByStockCodeAndMarket("VOO", "美股")).thenReturn(List.of(
                StockAlertGroup.builder().id(20L).displayOrder(20).active(true).build(),
                StockAlertGroup.builder().id(10L).displayOrder(10).active(false)
                        .lastTriggeredAt(LocalDateTime.now()).build()));
        WatchStockDto.Response response = findAllFirst();
        assertThat(response.conditions()).hasSize(3);
        assertThat(response.conditions().get(0).label()).isEqualTo("52 週位置高於 50%（600）");
        assertThat(response.conditions().get(1).label()).isEqualTo(
                "低於季線 5%（669.11） 且 D 值低於 15 且 K 值大於 D 值 且 低於月線 5%（666.52） 且 52 週位置低於 60%（620）");
        assertThat(response.conditions().get(1).active()).isFalse();
        assertThat(response.conditions().get(1).triggered()).isTrue();
        assertThat(response.conditions().get(1).group()).isTrue();
        assertThat(response.conditions().get(2).label()).isEqualTo("52 週位置高於 100%（700）");
        verify(priceQuery).getLive("VOO", "美股");
        verify(historyRepo).findRecentN("VOO", "美股", 241);
        verifyNoInteractions(twseDailyRepo);
    }

    @Test
    void 觀察頁_沒有52週條件不額外讀現價與高低() {
        ordinaryStock();
        when(alertRepo.findByStockCodeAndMarket("VOO", "美股")).thenReturn(
                List.of(condition("KD_D_BELOW", "15", null, 0, null)));
        assertThat(findAllFirst().conditions().get(0).label()).isEqualTo("D 值低於 15");
        verify(priceQuery, never()).getLive(anyString(), anyString());
        verify(historyRepo, never()).findRecentN("VOO", "美股", 241);
    }

    @Test
    void 觀察頁_52週歷史不足明示資料不足() {
        ordinaryStock();
        liveAndHistory(stockRange(239));
        when(alertRepo.findByStockCodeAndMarket("VOO", "美股")).thenReturn(
                List.of(condition("POS52W_BELOW", "60", null, 0, null)));
        assertThat(findAllFirst().conditions().get(0).label()).isEqualTo("52 週位置低於 60%（價位資料不足）");
    }

    @Test
    void 觀察頁_52週現價缺失不借用展示價() {
        ordinaryStock();
        when(historyRepo.findRecentN("VOO", "美股", 2)).thenReturn(List.of());
        when(historyRepo.findRecentN("VOO", "美股", 241)).thenReturn(stockRange(240));
        when(alertRepo.findByStockCodeAndMarket("VOO", "美股")).thenReturn(
                List.of(condition("POS52W_BELOW", "60", null, 0, null)));
        assertThat(findAllFirst().conditions().get(0).label()).endsWith("（價位資料不足）");
    }

    @Test
    void 觀察頁_52週查詢失敗保留其他條件與整列() {
        ordinaryStock();
        when(priceQuery.getLive("VOO", "美股")).thenThrow(new IllegalStateException("unavailable"));
        when(alertRepo.findByStockCodeAndMarket("VOO", "美股")).thenReturn(List.of(
                condition("POS52W_BELOW", "60", null, 0, null),
                condition("KD_D_BELOW", "15", null, 1, null)));
        assertThat(findAllFirst().conditions()).extracting(WatchStockDto.Condition::label)
                .containsExactly("52 週位置低於 60%（價位資料不足）", "D 值低於 15");
    }

    @Test
    void 觀察大盤_52週門檻使用大盤歷史並加點數() {
        List<TwseIndexDailyHistory> history = java.util.stream.IntStream.range(0, 240)
                .mapToObj(i -> row(TODAY.minusDays(i + 2), "40000", "40000", "50000", "30000")).toList();
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(List.of());
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(history);
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(
                live(TODAY.toString(), "40000", null, null, null)));
        StockAlert a = condition("POS52W_BELOW", "60", null, 0, null);
        a.setStockCode("0000"); a.setMarket("台股");
        when(alertRepo.findByStockCodeAndMarket("0000", "台股")).thenReturn(List.of(a));
        assertThat(findAllFirst().conditions().get(0).label()).isEqualTo("52 週位置低於 60%（42000 點）");
        verify(twseDailyRepo).findTopNByOrderByTradingDateDesc(241);
        verify(historyRepo, never()).findRecentN(anyString(), anyString(), eq(241));
    }

}
