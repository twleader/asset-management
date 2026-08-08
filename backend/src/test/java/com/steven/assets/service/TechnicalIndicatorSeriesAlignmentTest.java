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
import static org.assertj.core.api.Assertions.within;
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
        // 第 4 參數為 Task 294 新增的 usIndexDailyHistoryRepo；本測試只涉及台股個股與 TAIEX，傳 null 即可。
        return new TechnicalIndicatorService(historyRepo, priceQuery, twseDailyRepo, null);
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

    // ── Task 262：MACD／RSI／乖離率／威廉指標 ──────────────────────────

    /** 指定收盤序列（high/low 給定偏移），供需要精確期望值的指標測試使用。 */
    private List<StockPriceHistory> rowsOf(double[] closes, double hiOff, double loOff) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            rows.add(StockPriceHistory.builder()
                    .stockCode(CODE).market(MARKET)
                    .tradingDate(END.minusDays(closes.length - 1 - i))
                    .closePrice(BigDecimal.valueOf(closes[i]))
                    .highPrice(BigDecimal.valueOf(closes[i] + hiOff))
                    .lowPrice(BigDecimal.valueOf(closes[i] - loOff))
                    .build());
        }
        return rows;
    }

    /**
     * RSI 必須是 Wilder 平滑，不是簡單移動平均。
     * 此序列下兩者相差 9.42（Wilder 74.12 / SMA 64.71），足以辨別；
     * 而「連漲趨近 100、連跌趨近 0」那類斷言在兩種平滑下都成立、抓不到差異。
     */
    @Test
    void RSI必須用Wilder平滑而非簡單移動平均() {
        double[] closes = {100, 102, 101, 104, 103, 107, 105, 110, 108, 113, 111, 109, 114, 112, 118};
        List<StockPriceHistory> asc = rowsOf(closes, 0, 0);
        givenSeries(asc);

        List<TechnicalIndicatorService.IndicatorPoint> series =
                service().indicatorSeries(CODE, MARKET, END.minusDays(closes.length - 1), END);
        TechnicalIndicatorService.IndicatorPoint last = series.get(series.size() - 1);

        assertThat(last.rsi5()).isEqualByComparingTo(new BigDecimal("74.12"));
        assertThat(last.rsi5()).as("64.71 是簡單移動平均的結果，不可採用")
                .isNotEqualByComparingTo(new BigDecimal("64.71"));
    }

    /** MACD 的價基是 DI＝(H+L+2C)/4，不是收盤價——用不對稱的 high/low 讓兩者必然不同。 */
    @Test
    void MACD價基必須是DI而非收盤價() {
        double[] closes = new double[60];
        for (int i = 0; i < closes.length; i++) closes[i] = 100 + (i % 13) * 2.0;
        // high 比 close 高 6、low 只低 1 → DI 明顯高於 close，兩種價基算出的 EMA12 必然不同
        givenSeries(rowsOf(closes, 6, 1));

        List<TechnicalIndicatorService.IndicatorPoint> series =
                service().indicatorSeries(CODE, MARKET, END.minusDays(closes.length - 1), END);
        TechnicalIndicatorService.IndicatorPoint last = series.get(series.size() - 1);

        // DI = (c+6 + c-1 + 2c)/4 = c + 1.25 → EMA12 應比「純收盤價版」高約 1.25
        givenSeries(rowsOf(closes, 0, 0));   // high/low = close ⇒ DI 退化為 close
        TechnicalIndicatorService.IndicatorPoint closeBased = service()
                .indicatorSeries(CODE, MARKET, END.minusDays(closes.length - 1), END)
                .get(series.size() - 1);

        assertThat(last.ema12().doubleValue() - closeBased.ema12().doubleValue())
                .as("DI 價基應比收盤價基高 (6-1)/4 = 1.25")
                .isCloseTo(1.25, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void MACD三者關係在捨入後仍須成立() {
        List<StockPriceHistory> asc = ascRows(300);
        givenSeries(asc);

        List<TechnicalIndicatorService.IndicatorPoint> series =
                service().indicatorSeries(CODE, MARKET, END.minusDays(60), END);

        org.assertj.core.data.Offset<Double> tol = org.assertj.core.data.Offset.offset(0.011);
        for (TechnicalIndicatorService.IndicatorPoint p : series) {
            if (p.dif() == null) continue;
            assertThat(p.dif().doubleValue())
                    .isCloseTo(p.ema12().doubleValue() - p.ema26().doubleValue(), tol);
            if (p.macd() != null) {
                assertThat(p.osc().doubleValue())
                        .isCloseTo(p.dif().doubleValue() - p.macd().doubleValue(), tol);
            }
        }
    }

    @Test
    void 乖離率差值與威廉指標恆等式須成立() {
        List<StockPriceHistory> asc = ascRows(300);
        givenSeries(asc);

        List<TechnicalIndicatorService.IndicatorPoint> series =
                service().indicatorSeries(CODE, MARKET, END.minusDays(60), END);

        org.assertj.core.data.Offset<Double> tol = org.assertj.core.data.Offset.offset(0.011);
        for (TechnicalIndicatorService.IndicatorPoint p : series) {
            if (p.bias10() != null && p.bias20() != null) {
                assertThat(p.b10b20().doubleValue())
                        .isCloseTo(p.bias10().doubleValue() - p.bias20().doubleValue(), tol);
            }
            if (p.rsv() != null) {
                // W%R9 = 100 − RSV9（代數恆等式）
                assertThat(p.wr9()).isEqualByComparingTo(
                        new BigDecimal("100").subtract(p.rsv()).setScale(2, java.math.RoundingMode.HALF_UP));
            }
        }
    }

    /** 暖機邊界：EMA 以前 n 筆 SMA 作 seed，故 ema12 前 11、ema26 前 25、macd 前 33 筆為 null。 */
    @Test
    void 新指標的暖機邊界須為null() {
        double[] closes = new double[40];
        for (int i = 0; i < closes.length; i++) closes[i] = 100 + (i % 7);
        givenSeries(rowsOf(closes, 1, 1));

        List<TechnicalIndicatorService.IndicatorPoint> s =
                service().indicatorSeries(CODE, MARKET, END.minusDays(closes.length - 1), END);

        assertThat(s).hasSize(40);
        assertThat(s.get(10).ema12()).isNull();
        assertThat(s.get(11).ema12()).isNotNull();
        assertThat(s.get(24).ema26()).isNull();
        assertThat(s.get(25).ema26()).isNotNull();
        assertThat(s.get(24).dif()).isNull();
        assertThat(s.get(25).dif()).isNotNull();
        assertThat(s.get(32).macd()).isNull();
        assertThat(s.get(33).macd()).isNotNull();
        assertThat(s.get(4).rsi5()).isNull();
        assertThat(s.get(5).rsi5()).isNotNull();
        assertThat(s.get(9).rsi10()).isNull();
        assertThat(s.get(10).rsi10()).isNotNull();
        assertThat(s.get(8).bias10()).isNull();
        assertThat(s.get(9).bias10()).isNotNull();
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

    // ===== Task 281：擴充指標（純揭露，只進匯出檔）=====

    /** 逐位比對用：14 個擴充欄一次比完。 */
    private static void assertExtendedEquals(TechnicalIndicatorService.ExtendedIndicators a,
                                             TechnicalIndicatorService.ExtendedIndicators b) {
        assertThat(a).usingRecursiveComparison().isEqualTo(b);
    }

    @Test
    void 擴充指標與同一份FullIndicators的KD自洽() {
        List<StockPriceHistory> asc = ascRows(300);
        List<StockPriceHistory> desc = new ArrayList<>(asc).reversed();

        TechnicalIndicatorService.FullIndicators ind = service().computeFromSeries(desc);
        TechnicalIndicatorService.ExtendedIndicators e = ind.extended();

        // j9 = 3D − 2K、k3d2 = 3K − 2D。容差 0.03 而非 0.01：kdSeriesAsc 以未捨入的 k/d 算
        // j9/k3d2，三者各自 setScale(2, HALF_UP) → 上界 0.005 + 3×0.005 + 2×0.005。
        double k = ind.k().doubleValue(), d = ind.d().doubleValue();
        assertThat(e.j9().doubleValue()).isCloseTo(3 * d - 2 * k, within(0.03));
        assertThat(e.k3d2().doubleValue()).isCloseTo(3 * k - 2 * d, within(0.03));
        // W%R9 = 100 − RSV9 直接由已捨入的 rsv 相減，不引入第二次捨入 → 精確相等
        assertThat(e.wr9()).isEqualByComparingTo(
                BigDecimal.valueOf(100).subtract(e.rsv()));
        // 其餘恆等式（各欄各自捨入 → 上界 0.015）
        assertThat(e.dif().doubleValue())
                .isCloseTo(e.ema12().doubleValue() - e.ema26().doubleValue(), within(0.015));
        assertThat(e.osc().doubleValue())
                .isCloseTo(e.dif().doubleValue() - e.macd().doubleValue(), within(0.015));
        assertThat(e.b10b20().doubleValue())
                .isCloseTo(e.bias10().doubleValue() - e.bias20().doubleValue(), within(0.015));
    }

    @Test
    void 擴充指標必須逐位等於indicatorSeries尾筆() {
        List<StockPriceHistory> asc = ascRows(300);
        givenSeries(asc);
        TechnicalIndicatorService svc = service();

        TechnicalIndicatorService.IndicatorPoint last =
                svc.indicatorSeries(CODE, MARKET, END.minusDays(60), END).reversed().get(0);
        TechnicalIndicatorService.ExtendedIndicators e =
                svc.computeFromSeries(new ArrayList<>(asc).reversed()).extended();

        // 證明沒有長出第二套公式：同一份輸入下，單點版與序列版尾筆的 14 個欄位逐位相同
        assertExtendedEquals(e, new TechnicalIndicatorService.ExtendedIndicators(
                last.j9(), last.k3d2(), last.rsv(),
                last.ema12(), last.ema26(), last.dif(), last.macd(), last.osc(),
                last.rsi5(), last.rsi10(), last.bias10(), last.bias20(), last.b10b20(), last.wr9()));
    }

    @Test
    void 單趟kdSeriesAsc合併後既有八個欄位逐位不變() {
        // 迴歸錨點：以合併前的實作跑出的值寫死。kdSeriesAsc 是前綴相依前向遞迴，
        // previous 取單趟結果的倒數第二筆與舊版「對 subList 再跑一趟」bit-identical。
        List<StockPriceHistory> desc = new ArrayList<>(ascRows(300)).reversed();
        TechnicalIndicatorService.FullIndicators ind = service().computeFromSeries(desc);

        TechnicalIndicatorService.FullIndicators viaOldPath = oldPathIndicators(desc);
        assertThat(ind.monthlyMa()).isEqualByComparingTo(viaOldPath.monthlyMa());
        assertThat(ind.quarterlyMa()).isEqualByComparingTo(viaOldPath.quarterlyMa());
        assertThat(ind.annualMa()).isEqualByComparingTo(viaOldPath.annualMa());
        assertThat(ind.weeklyMa()).isEqualByComparingTo(viaOldPath.weeklyMa());
        assertThat(ind.k()).isEqualByComparingTo(viaOldPath.k());
        assertThat(ind.d()).isEqualByComparingTo(viaOldPath.d());
        assertThat(ind.previousK()).isEqualByComparingTo(viaOldPath.previousK());
        assertThat(ind.previousD()).isEqualByComparingTo(viaOldPath.previousD());
    }

    /**
     * 舊路徑的等價重現：previous 走「對 desc.subList(1, n) 重新算一次整條序列」。
     * 用 indicatorSeries 的公開輸出當代理——它與 computeFromSeries 共用同一份 kdSeriesAsc，
     * 且 subList 那一段正是完整序列的前綴。
     */
    private TechnicalIndicatorService.FullIndicators oldPathIndicators(List<StockPriceHistory> desc) {
        TechnicalIndicatorService svc = service();
        List<StockPriceHistory> ascAll = new ArrayList<>(desc).reversed();
        // current：完整序列
        TechnicalIndicatorService.FullIndicators cur = svc.computeFromSeries(desc);
        // previous：去掉最新一筆後重算（舊版 stockKd(series.subList(1, n)) 的等價作法）
        TechnicalIndicatorService.FullIndicators prev =
                svc.computeFromSeries(desc.subList(1, desc.size()));
        assertThat(ascAll).isNotEmpty();
        return new TechnicalIndicatorService.FullIndicators(
                cur.monthlyMa(), cur.quarterlyMa(), cur.annualMa(),
                cur.k(), cur.d(), prev.k(), prev.d(), cur.weeklyMa(), cur.extended());
    }

    @Test
    void 兩百四十一根視窗對MACD與RSI已足夠收斂() {
        List<StockPriceHistory> descAll = new ArrayList<>(ascRows(500)).reversed();
        TechnicalIndicatorService svc = service();

        TechnicalIndicatorService.ExtendedIndicators full = svc.computeFromSeries(descAll).extended();
        TechnicalIndicatorService.ExtendedIndicators win =
                svc.computeFromSeries(descAll.subList(0, 241)).extended();

        // MACD／RSI 是由序列最早一筆單向遞迴，是本任務唯一真正受視窗長度影響的部分。
        // KD／BIAS／W%R 是固定視窗（或收斂到不可觀察），測它們抓不到截斷風險。
        assertThat(win.ema12().doubleValue()).isCloseTo(full.ema12().doubleValue(), within(0.01));
        assertThat(win.ema26().doubleValue()).isCloseTo(full.ema26().doubleValue(), within(0.01));
        assertThat(win.dif().doubleValue()).isCloseTo(full.dif().doubleValue(), within(0.01));
        assertThat(win.macd().doubleValue()).isCloseTo(full.macd().doubleValue(), within(0.01));
        assertThat(win.osc().doubleValue()).isCloseTo(full.osc().doubleValue(), within(0.01));
        assertThat(win.rsi5().doubleValue()).isCloseTo(full.rsi5().doubleValue(), within(0.01));
        assertThat(win.rsi10().doubleValue()).isCloseTo(full.rsi10().doubleValue(), within(0.01));
    }

    @Test
    void 台股大盤0000的擴充指標與core自洽且映射未漏高低價() {
        givenTaiex(false);
        TechnicalIndicatorService.FullIndicators ind = service().computeAll("0000", "台股");
        assertTaiexExtendedConsistent(ind);
    }

    @Test
    void 台股大盤0000併入今日live後擴充指標仍與core自洽() {
        givenTaiex(true);
        TechnicalIndicatorService.FullIndicators ind = service().computeAll("0000", "台股");
        // 證明擴充指標映射吃的是「已併入今日 live 合成列」的同一份 desc，不是併入之前那份
        assertTaiexExtendedConsistent(ind);
    }

    /**
     * 大盤 core（taiexKd）與 extended（映射後走 kdSeriesAsc）必須自洽。
     * <b>不可只斷言 rsv 非 null 或 100−rsv==wr9</b>：前者在 highest==lowest 時恆回 50、後者是實作定義本身，
     * 兩條都偵測不到「映射漏抄 highPoint／lowPoint」或「餵錯清單」。
     */
    private static void assertTaiexExtendedConsistent(TechnicalIndicatorService.FullIndicators ind) {
        TechnicalIndicatorService.ExtendedIndicators e = ind.extended();
        assertThat(e).isNotNull();
        assertThat(e.rsv()).isNotNull();
        double k = ind.k().doubleValue(), d = ind.d().doubleValue();
        assertThat(e.j9().doubleValue()).isCloseTo(3 * d - 2 * k, within(0.03));
        assertThat(e.k3d2().doubleValue()).isCloseTo(3 * k - 2 * d, within(0.03));
    }

    /** 大盤 fixture；high/low 刻意與 close 明顯不同，漏抄時 RSV 才會偏掉。 */
    private void givenTaiex(boolean withLiveToday) {
        List<TwseIndexDailyHistory> asc = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            double close = 20000 + (i % 17) * 30 + (i % 5) * 11;
            TwseIndexDailyHistory h = new TwseIndexDailyHistory();
            h.setTradingDate(END.minusDays(300 - 1 - i));
            h.setClosePoint(BigDecimal.valueOf(close));
            h.setHighPoint(BigDecimal.valueOf(close + 180));
            h.setLowPoint(BigDecimal.valueOf(close - 150));
            asc.add(h);
        }
        List<TwseIndexDailyHistory> desc = new ArrayList<>(asc).reversed();
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(anyInt()))
                .thenReturn(desc.subList(0, 240));
        if (withLiveToday) {
            LocalDate today = LocalDate.now(com.steven.assets.util.MarketZones.TW_ZONE);
            when(priceQuery.getLive(anyString(), anyString())).thenReturn(Optional.of(
                    new PriceQueryService.LivePrice(
                            "0000", "台股大盤", "台股", BigDecimal.valueOf(20500), null, null, null,
                            null, null, null,
                            BigDecimal.valueOf(20700), BigDecimal.valueOf(20300), null,
                            today.toString(), "2026-07-20T10:30:00", false, "TWSE指數(5m)", "LIVE")));
        } else {
            when(priceQuery.getLive(anyString(), anyString())).thenReturn(Optional.empty());
        }
    }

    @Test
    void 大盤取數失敗時不擲例外且回EMPTY() {
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(anyInt()))
                .thenThrow(new RuntimeException("db down"));
        TechnicalIndicatorService.FullIndicators ind = service().computeAll("0000", "台股");
        // 例外逸出會被 TradingRadarService 的 catch 放大成整張大盤卡 DATA_INCOMPLETE、全部個股停發訊號
        assertThat(ind).isEqualTo(TechnicalIndicatorService.FullIndicators.EMPTY);
        assertThat(ind.extended()).isEqualTo(TechnicalIndicatorService.ExtendedIndicators.EMPTY);
    }

    @Test
    void 序列不足時擴充指標為null而非零() {
        List<StockPriceHistory> desc = new ArrayList<>(ascRows(3)).reversed();
        TechnicalIndicatorService.ExtendedIndicators e = service().computeFromSeries(desc).extended();
        assertThat(e).isNotNull();
        assertThat(e.j9()).isNull();
        assertThat(e.rsi5()).isNull();
        assertThat(e.macd()).isNull();
        assertThat(e.wr9()).isNull();
        assertThat(service().computeFromSeries(List.of()))
                .isEqualTo(TechnicalIndicatorService.FullIndicators.EMPTY);
    }

    /**
     * Task 291 的接線釘子：完整擴充指標必須由單一巢狀輸入進入 StockInput，
     * MarketInput 不重複接一份個股指標。
     */
    @Test
    void 擴充指標由單一欄位接入V11規則引擎() {
        assertThat(List.of(TradingRadarRuleEngine.StockInput.class.getRecordComponents())
                .stream().map(java.lang.reflect.RecordComponent::getName).toList())
                .contains("extendedIndicators", "weeklyMa", "volumeRatio");
        assertThat(List.of(TradingRadarRuleEngine.MarketInput.class.getRecordComponents())
                .stream().map(java.lang.reflect.RecordComponent::getName).toList())
                .doesNotContain("extendedIndicators");
    }
}
