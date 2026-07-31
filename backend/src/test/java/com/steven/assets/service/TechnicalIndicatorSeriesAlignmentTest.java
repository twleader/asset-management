package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Task 261：走勢圖指標序列 indicatorSeries() 與單點 computeAll() 的同源判準。
 *
 * 這是本任務唯一的機械驗收條件——走勢圖與觀察清單表格顯示同一組 K/D，
 * 靠的就是「序列尾筆逐位等於 computeAll()」。兩者取數方式刻意不同
 * （computeAll 取最近 240 筆、序列版取全史），故必須真的分別 stub 兩支 repository 方法，
 * 不能拿吃同一份輸入的 computeFromSeries() 當比對對象（那樣恆等，測不到差異）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TechnicalIndicatorSeriesAlignmentTest {

    @Mock private StockPriceHistoryRepository historyRepo;
    @Mock private PriceQueryService priceQuery;
    @Mock private TwseIndexDailyHistoryRepository twseDailyRepo;

    private TechnicalIndicatorService service() {
        return new TechnicalIndicatorService(historyRepo, priceQuery, twseDailyRepo);
    }

    private static final String CODE = "2330";
    private static final String MARKET = "台股";
    /** 用已過去的日期當 end，避開「今日 live 併入」分支，讓兩條路徑吃到完全相同的資料。 */
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    /**
     * 300 筆升冪 OHLC：收盤走鋸齒（讓 KD 不會貼在 0 或 100），
     * 其中每 7 筆讓 high/low 為 null 以覆蓋 fallback close 的路徑。
     */
    private List<StockPriceHistory> ascRows(int n) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double close = 100 + (i % 17) * 1.5 + (i % 5) * 0.7;
            StockPriceHistory.StockPriceHistoryBuilder b = StockPriceHistory.builder()
                    .stockCode(CODE).market(MARKET)
                    .tradingDate(END.minusDays(n - 1 - i))
                    .closePrice(BigDecimal.valueOf(close));
            if (i % 7 != 0) {
                b.highPrice(BigDecimal.valueOf(close + 1.2))
                 .lowPrice(BigDecimal.valueOf(close - 0.9));
            }
            rows.add(b.build());
        }
        return rows;
    }

    private void givenSeries(List<StockPriceHistory> asc) {
        List<StockPriceHistory> desc = new ArrayList<>(asc).reversed();
        when(historyRepo.findRecentN(anyString(), anyString(), anyInt()))
                .thenReturn(desc.subList(0, Math.min(240, desc.size())));
        when(historyRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                anyString(), anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(asc);
        when(priceQuery.getLive(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void 序列尾筆的KD與均線必須逐位等於computeAll的單點值() {
        List<StockPriceHistory> asc = ascRows(300);
        givenSeries(asc);
        TechnicalIndicatorService svc = service();

        TechnicalIndicatorService.FullIndicators single = svc.computeAll(CODE, MARKET);
        List<TechnicalIndicatorService.IndicatorPoint> series =
                svc.indicatorSeries(CODE, MARKET, END.minusDays(60), END);

        assertThat(series).isNotEmpty();
        TechnicalIndicatorService.IndicatorPoint last = series.get(series.size() - 1);

        assertThat(last.k()).isEqualByComparingTo(single.k());
        assertThat(last.d()).isEqualByComparingTo(single.d());
        assertThat(last.ma20()).isEqualByComparingTo(single.monthlyMa());
        assertThat(last.ma60()).isEqualByComparingTo(single.quarterlyMa());
        assertThat(last.ma240()).isEqualByComparingTo(single.annualMa());
    }

    /**
     * MA 的 double 累加方向必須與 computeAll() 的 simpleMa 一致（新→舊）。
     * 反向累加時末位差會在 x.xx5 邊界翻面，實測 MA20 約 1.7% 的日子差 0.01。
     * 用多組隨機漫步價格掃過邊界，避免「這組 fixture 剛好沒踩到」的假通過。
     */
    @Test
    void 均線累加方向必須與computeAll一致否則末位會翻面() {
        java.util.Random rnd = new java.util.Random(42);
        for (int trial = 0; trial < 40; trial++) {
            List<StockPriceHistory> asc = new ArrayList<>();
            double price = 100 + rnd.nextInt(50);
            for (int i = 0; i < 300; i++) {
                price = Math.max(1, price + Math.round((rnd.nextDouble() - 0.5) * 400) / 100.0);
                asc.add(StockPriceHistory.builder()
                        .stockCode(CODE).market(MARKET)
                        .tradingDate(END.minusDays(300 - 1 - i))
                        .closePrice(BigDecimal.valueOf(price).setScale(2, java.math.RoundingMode.HALF_UP))
                        .build());
            }
            givenSeries(asc);
            TechnicalIndicatorService svc = service();

            TechnicalIndicatorService.FullIndicators single = svc.computeAll(CODE, MARKET);
            List<TechnicalIndicatorService.IndicatorPoint> series =
                    svc.indicatorSeries(CODE, MARKET, END.minusDays(5), END);
            TechnicalIndicatorService.IndicatorPoint last = series.get(series.size() - 1);

            assertThat(last.ma20()).as("第 %d 組 MA20", trial).isEqualByComparingTo(single.monthlyMa());
            assertThat(last.ma60()).as("第 %d 組 MA60", trial).isEqualByComparingTo(single.quarterlyMa());
            assertThat(last.ma240()).as("第 %d 組 MA240", trial).isEqualByComparingTo(single.annualMa());
        }
    }

    @Test
    void 序列倒數第二筆的KD必須等於computeAll的前期KD() {
        List<StockPriceHistory> asc = ascRows(300);
        givenSeries(asc);
        TechnicalIndicatorService svc = service();

        TechnicalIndicatorService.FullIndicators single = svc.computeAll(CODE, MARKET);
        List<TechnicalIndicatorService.IndicatorPoint> series =
                svc.indicatorSeries(CODE, MARKET, END.minusDays(60), END);

        TechnicalIndicatorService.IndicatorPoint prev = series.get(series.size() - 2);
        assertThat(prev.k()).isEqualByComparingTo(single.previousK());
        assertThat(prev.d()).isEqualByComparingTo(single.previousD());
    }

    @Test
    void J9與K3D2為K與D的兩種鏡像慣例() {
        List<StockPriceHistory> asc = ascRows(300);
        givenSeries(asc);

        List<TechnicalIndicatorService.IndicatorPoint> series =
                service().indicatorSeries(CODE, MARKET, END.minusDays(60), END);

        // 實作以「未捨入」的 k/d 算 J9/K3D2 後才捨入（與遞迴內部精度一致）；
        // 這裡只能拿已捨入的 k/d 反算，故誤差上限 = 3×0.005 + 2×0.005（k、d 各自捨入）
        // + 0.005（J9 自己捨入）= 0.03。實測確有 0.02 的差，正是不走二次捨入的理由。
        org.assertj.core.data.Offset<Double> tolerance = org.assertj.core.data.Offset.offset(0.031);
        for (TechnicalIndicatorService.IndicatorPoint p : series) {
            if (p.k() == null) continue;
            double expectedJ9 = 3 * p.d().doubleValue() - 2 * p.k().doubleValue();
            double expectedK3d2 = 3 * p.k().doubleValue() - 2 * p.d().doubleValue();
            assertThat(p.j9().doubleValue()).isCloseTo(expectedJ9, tolerance);
            assertThat(p.k3d2().doubleValue()).isCloseTo(expectedK3d2, tolerance);
        }
    }

    /** 使用者畫面實測值：K9=40.36、D9=32.74 → J9=17.50、K3D2=55.60。 */
    @Test
    void 畫面實測值的J9與K3D2方向正確() {
        double k = 40.36, d = 32.74;
        assertThat(BigDecimal.valueOf(3 * d - 2 * k).setScale(2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("17.50"));
        assertThat(BigDecimal.valueOf(3 * k - 2 * d).setScale(2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("55.60"));
    }

    @Test
    void 暖機不足九筆時KD欄為null視窗不足時均線欄為null() {
        List<StockPriceHistory> asc = ascRows(30);
        givenSeries(asc);

        List<TechnicalIndicatorService.IndicatorPoint> series =
                service().indicatorSeries(CODE, MARKET, END.minusDays(29), END);

        assertThat(series).hasSize(30);
        // 前 8 筆 KD 暖機不足
        for (int i = 0; i < 8; i++) {
            assertThat(series.get(i).k()).as("index %d 的 K 應為 null", i).isNull();
            assertThat(series.get(i).rsv()).isNull();
        }
        assertThat(series.get(8).k()).isNotNull();
        assertThat(series.get(8).rsv()).isNotNull();
        // MA20 要第 20 筆才有；MA60/MA240 全序列都不足
        assertThat(series.get(18).ma20()).isNull();
        assertThat(series.get(19).ma20()).isNotNull();
        assertThat(series.get(29).ma60()).isNull();
        assertThat(series.get(29).ma240()).isNull();
    }

    @Test
    void 台股大盤0000的序列尾筆必須等於computeAll的大盤值() {
        List<TwseIndexDailyHistory> asc = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            double close = 20000 + (i % 17) * 30 + (i % 5) * 11;
            TwseIndexDailyHistory h = new TwseIndexDailyHistory();
            h.setTradingDate(END.minusDays(300 - 1 - i));
            h.setClosePoint(BigDecimal.valueOf(close));
            if (i % 7 != 0) {
                h.setHighPoint(BigDecimal.valueOf(close + 25));
                h.setLowPoint(BigDecimal.valueOf(close - 18));
            }
            asc.add(h);
        }
        List<TwseIndexDailyHistory> desc = new ArrayList<>(asc).reversed();
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(anyInt()))
                .thenReturn(desc.subList(0, 240));
        when(twseDailyRepo.findByTradingDateBetweenOrderByTradingDateAsc(
                any(LocalDate.class), any(LocalDate.class))).thenReturn(asc);
        when(priceQuery.getLive(anyString(), anyString())).thenReturn(Optional.empty());

        TechnicalIndicatorService svc = service();
        TechnicalIndicatorService.FullIndicators single = svc.computeAll("0000", "台股");
        List<TechnicalIndicatorService.IndicatorPoint> series =
                svc.indicatorSeries("0000", "台股", END.minusDays(60), END);

        assertThat(series).isNotEmpty();
        TechnicalIndicatorService.IndicatorPoint last = series.get(series.size() - 1);
        assertThat(last.k()).isEqualByComparingTo(single.k());
        assertThat(last.d()).isEqualByComparingTo(single.d());
        assertThat(last.ma20()).isEqualByComparingTo(single.monthlyMa());
        assertThat(last.ma60()).isEqualByComparingTo(single.quarterlyMa());
        assertThat(last.ma240()).isEqualByComparingTo(single.annualMa());
    }
}
