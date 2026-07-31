package com.steven.assets.service;

import com.steven.assets.dto.WatchStockDto;
import com.steven.assets.model.TwseIndexDailyHistory;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
                stockMasterRepo, indicatorService, twseDailyRepo);
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
                tradingDate, "2026-07-31T13:28:00", false, "TWSE指數(5m)");
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

    /** 完成日 K 已含今日（14:00 之後）：不併 live，且不得呼叫 getLive（等價形短路）。 */
    @Test
    void 盤後_完成日K已含今日_不併live() {
        when(twseDailyRepo.findTop60ByOrderByTradingDateDesc()).thenReturn(descRows(TODAY));

        WatchStockDto.Response r = findAllFirst();

        assertThat(r.price()).isEqualByComparingTo("39933.30");
        assertThat(r.tradingDate()).isEqualTo(TODAY.toString());
        assertThat(r.closed()).isTrue();
        assertThat(r.previousClose()).isEqualByComparingTo("40039.18");   // 次新一筆
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
     * <p>「股市大盤查詢」頁的當日卡昨收走另一套獨立實作（`GdpTwseBffController.previousCloseBefore`，
     * private、字串字典序比對、跑在 bff 模組），判準原文為「tradingDate 嚴格早於 beforeDate 的最後一筆」。
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
}
