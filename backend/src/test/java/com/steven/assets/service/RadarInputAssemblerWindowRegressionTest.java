package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Task 356.4g：取數視窗由 250 擴大到 500 之後，<b>日K 路徑的全部中間值必須逐位不變</b>。
 *
 * <p>兩臂的差別只有「呼叫端備妥了多長的序列、以及那段區間查得到哪些除權息事件」，
 * 完全比照 {@code TradingRadarService.prepareTechnicalData} 的形狀；日K 契約列數
 * （{@code dailyContractRows = 241}）兩臂相同。</p>
 *
 * <p><b>比對一律用 {@code isEqualByComparingTo}。</b>長視窗一旦撈到 12–24 個月前的事件就會走
 * {@code DistributionAdjustedPriceService} 的縮放路徑，{@code scale()} 會把價格由
 * {@code numeric(15,4)} 的 scale 4 變成 scale 8：同一個價格由 {@code 100.5000} 變成
 * {@code 100.50000000}，{@code compareTo == 0} 但 {@code equals == false}。用 {@code equals}
 * 會紅燈在一個不是缺陷的地方。</p>
 *
 * <p>本檔<b>不</b>斷言最終 {@code score}／{@code action}／{@code reasons}／{@code risks}：
 * 擴窗正是週K 因子生效的前提，兩臂的最終輸出<b>必然不同</b>（見
 * {@link #wideningTheWindowIsExactlyWhatMakesWeeklyIndicatorsAvailable}），
 * 把它們寫進逐位不變的斷言等於要求一條不可能綠燈的測試。</p>
 */
class RadarInputAssemblerWindowRegressionTest {

    private static final int DAILY_CONTRACT_ROWS = RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS;
    private static final int LONG_WINDOW_ROWS = 500;
    private static final LocalDate NEWEST = LocalDate.of(2026, 8, 21);   // 週五

    private RadarInputAssembler assembler() {
        return new RadarInputAssembler(
                new TechnicalIndicatorService(
                        mock(StockPriceHistoryRepository.class),
                        mock(PriceQueryService.class),
                        mock(TwseIndexDailyHistoryRepository.class),
                        mock(UsIndexDailyHistoryRepository.class)),
                new DistributionAdjustedPriceService(),
                new TradingRadarRuleEngine());
    }

    // ───────────────────────── 逐位不變（356.4g） ─────────────────────────

    @Test
    void dailyIntermediatesAreUnchangedByTheWideningAfterTheClose() {
        assertDailyPathUnchanged(false, exDividend(300));
    }

    @Test
    void dailyIntermediatesAreUnchangedByTheWideningIntraday() {
        assertDailyPathUnchanged(true, exDividend(300));
    }

    @Test
    void dailyIntermediatesAreUnchangedWhenTheEventSitsInsideTheContractWindow() {
        // 正對照：事件落在 241 列之內時，兩臂都必須是 true（判準不是「一律 false」）。
        Arms arms = run(false, exDividend(100));
        assertThat(arms.narrow().distributionAdjusted()).isTrue();
        assertThat(arms.wide().distributionAdjusted()).isTrue();
        assertDailyEqual(arms);
    }

    @Test
    void anExDividendOutsideTheContractWindowMustNotFlipDistributionAdjusted() {
        Arms arms = run(false, exDividend(300));

        assertThat(arms.narrow().distributionAdjusted())
                .as("241 列的區間裡查不到那筆事件")
                .isFalse();
        assertThat(arms.wide().distributionAdjusted())
                .as("事件日不晚於視窗最舊一列 → 視窗內每一列的 priceScale 恆為 1，"
                        + "宣稱「已使用還原權息價」會是假陳述")
                .isFalse();
        // 但長視窗那一臂的還原<b>確實跑了</b>：事件日之前（更舊）的列被縮放，
        // 契約視窗內的列則逐位相同——這正是「判準必須看事件日、不能看 adjusted()」的原因。
        List<StockPriceHistory> wideRows = arms.wide().adjustedRowsDesc();
        assertThat(wideRows).hasSize(LONG_WINDOW_ROWS);
        assertThat(wideRows.get(LONG_WINDOW_ROWS - 1).getClosePrice())
                .as("事件日之前的列確實被還原縮放")
                .isLessThan(rawCloses().get(LONG_WINDOW_ROWS - 1));
        for (int i = 0; i < DAILY_CONTRACT_ROWS; i++) {
            assertThat(wideRows.get(i).getClosePrice())
                    .isEqualByComparingTo(rawCloses().get(i));
        }
    }

    // ───────────────────────── ma60BiasPercentile 的觀測數（356.4d） ─────────────────────────

    @Test
    void ma60BiasPercentileObservationCountIsAlways182ForBothLiveModes() {
        RadarInputAssembler assembler = assembler();
        // close(i) = 1000 − i（降序索引）。第 i 筆觀測的乖離為 29.5 / (close(i) − 29.5) × 100，
        // 隨 i 單調遞增，故「恰好落在最小與次小之間」的現行乖離會讓 countAtOrBelow = 1，
        // 分位 = 100 / N —— 由回傳值即可反推觀測數 N。
        List<StockPriceHistory> afterClose = linearRowsDesc(DAILY_CONTRACT_ROWS);            // 241
        List<StockPriceHistory> intraday = linearRowsDesc(DAILY_CONTRACT_ROWS + 1);          // 242（含 live）
        List<StockPriceHistory> longIntraday = linearRowsDesc(LONG_WINDOW_ROWS + 1);         // 501（含 live）

        // 收盤後：n=241、firstCompleted=0 → 觀測 = 241 − 60 + 1 = 182 → 100/182 = 0.5
        assertThat(assembler.ma60BiasPercentile(afterClose, 0, new BigDecimal("3.0410")))
                .isEqualByComparingTo("0.5");
        // 盤中：n=242、firstCompleted=1 → 觀測 = 242 − 60 − 1 + 1 = 182 → 同樣 0.5
        assertThat(assembler.ma60BiasPercentile(intraday, 1, new BigDecimal("3.0440")))
                .isEqualByComparingTo("0.5");

        // ⚠ 寫成 min(size, 241) 的錯誤版本：盤中只剩 181 筆觀測 → 100/181 = 0.6，分位靜默改值。
        assertThat(assembler.ma60BiasPercentile(
                intraday.subList(0, DAILY_CONTRACT_ROWS), 1, new BigDecimal("3.0440")))
                .as("241 + live 的視窗少切一列就會掉成 181 筆觀測")
                .isEqualByComparingTo("0.6");
        // ⚠ 完全不釘回契約視窗：501 列 → 441 筆觀測 → 100/441 = 0.2。
        assertThat(assembler.ma60BiasPercentile(longIntraday, 1, new BigDecimal("3.0440")))
                .as("長視窗直接吃整份會變成 441 筆觀測")
                .isEqualByComparingTo("0.2");
    }

    @Test
    void assembledPercentileUsesTheContractWindowNotTheLongOne() {
        Arms arms = run(true, List.of());

        assertThat(arms.wide().ma60BiasPercentile())
                .isEqualByComparingTo(arms.narrow().ma60BiasPercentile());
    }

    /**
     * {@code assemble} 實際餵給 {@code ma60BiasPercentile} 的視窗必須是
     * {@code dailyContractRows + (liveAdded ? 1 : 0)}＝242 列，<b>不是</b> 241、也不是整份 501。
     *
     * <p>資料刻意設計成三種視窗會得到三個不同的分位：501 列（0…441 筆觀測）、241 列（181 筆）
     * 與正確的 242 列（182 筆）各自可分辨，因此少切一列或不切都會紅燈。</p>
     */
    @Test
    void assembleFeedsThePercentileExactly182Observations() {
        RadarInputAssembler assembler = assembler();
        List<StockPriceHistory> combined = new ArrayList<>(linearRowsDesc(LONG_WINDOW_ROWS));
        combined.add(0, linearLiveRow());
        BigDecimal price = new BigDecimal("1004.06");

        RadarInputAssembler.Assembled assembled = assembler.assemble(
                combined, List.of(), true, DAILY_CONTRACT_ROWS, DAILY_CONTRACT_ROWS, price);

        List<StockPriceHistory> adjusted = assembled.adjustedRowsDesc();
        BigDecimal bias = assembled.ma60BiasPercent();
        assertThat(assembled.ma60BiasPercentile())
                .as("182 筆觀測、91 筆不高於現行乖離 → 100 × 91 / 182")
                .isEqualByComparingTo("50.0");
        assertThat(assembler.ma60BiasPercentile(adjusted.subList(0, DAILY_CONTRACT_ROWS + 1), 1, bias))
                .isEqualByComparingTo("50.0");
        assertThat(assembler.ma60BiasPercentile(adjusted.subList(0, DAILY_CONTRACT_ROWS), 1, bias))
                .as("min(size, 241) 的錯誤版本只剩 181 筆觀測")
                .isEqualByComparingTo("50.3");
        assertThat(assembler.ma60BiasPercentile(adjusted, 1, bias))
                .as("整份長序列會變成 441 筆觀測")
                .isEqualByComparingTo("20.6");
    }

    // ───────────────────────── volumeRatio 沒有列數上限（356.4e 第三列） ─────────────────────────

    @Test
    void volumeRatioIsPinnedToTheContractWindowEvenThoughItHasNoRowLimit() {
        Arms arms = run(false, List.of());

        assertThat(arms.narrow().volumeRatio())
                .as("契約視窗內只有第 0 列有量，前 240 列全為 null → 正樣本 0 筆")
                .isNull();
        assertThat(arms.wide().volumeRatio())
                .as("擴窗後仍必須是 null——該函式沒有列數上限，只以 20 筆正樣本為界")
                .isNull();

        // 未釘回視窗的錯誤版本會一路掃到第 241 列之後的正成交量而湊滿 20 筆，回出一個值。
        assertThat(assembler().volumeRatio(arms.wide().adjustedRowsDesc(), 0))
                .as("這就是擴窗會靜默改值的機制本身")
                .isNotNull();
    }

    // ───────────────────────── 擴窗的用途 ─────────────────────────

    @Test
    void wideningTheWindowIsExactlyWhatMakesWeeklyIndicatorsAvailable() {
        Arms arms = run(false, List.of());

        assertThat(arms.narrow().weekly().completedWeeks())
                .as("241 個交易日 ≈ 48 個 ISO 週，永遠達不到 60 根完成週")
                .isLessThan(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(arms.narrow().weekly().ma5()).isNull();

        assertThat(arms.wide().weekly().completedWeeks())
                .isGreaterThanOrEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(arms.wide().weekly().ma5()).isNotNull();
        assertThat(arms.wide().weekly().osc()).isNotNull();
    }

    // ─────────── 兩臂都強制 weekly = null 時最終輸出逐位不變（356.4g 驗證 (i)） ───────────

    /**
     * 356.4g 的另一組：<b>兩臂都把 {@code weekly} 強制為 {@code null}</b> 時，最終
     * {@code score}／{@code action}／{@code reasons}／{@code risks} 三軌逐位相同。
     *
     * <p>這條與上面的日K 中間值不變式互補：中間值相同<b>不蘊涵</b>最終輸出相同——引擎還吃
     * {@code dailyCandle}、{@code volumeRatio}、{@code ma60BiasPercentile} 等由視窗決定的欄位，
     * 任何一個被擴窗改值都會在這裡露餡。把週K 關掉是唯一能讓兩臂可比的方式（擴窗正是週K
     * 因子生效的前提，見 {@link #wideningTheWindowIsExactlyWhatMakesWeeklyIndicatorsAvailable}）。</p>
     */
    @Test
    void finalOutputsAreBitIdenticalWhenBothArmsForceWeeklyToNullAfterTheClose() {
        assertFinalOutputsIdenticalWithoutWeekly(run(false, exDividend(300)));
    }

    @Test
    void finalOutputsAreBitIdenticalWhenBothArmsForceWeeklyToNullIntraday() {
        assertFinalOutputsIdenticalWithoutWeekly(run(true, exDividend(300)));
    }

    private void assertFinalOutputsIdenticalWithoutWeekly(Arms arms) {
        TradingRadarRuleEngine engine = new TradingRadarRuleEngine();
        TradingRadarRuleEngine.StockResult narrow =
                engine.evaluateStock(withoutWeekly(arms.narrow(), arms.price()));
        TradingRadarRuleEngine.StockResult wide =
                engine.evaluateStock(withoutWeekly(arms.wide(), arms.price()));

        assertThat(wide.score()).isEqualTo(narrow.score());
        assertThat(wide.action()).isEqualTo(narrow.action());
        assertThat(wide.reasons()).isEqualTo(narrow.reasons());
        assertThat(wide.risks()).isEqualTo(narrow.risks());

        assertThat(wide.swingScore()).isEqualTo(narrow.swingScore());
        assertThat(wide.swingAction()).isEqualTo(narrow.swingAction());
        assertThat(wide.swingReasons()).isEqualTo(narrow.swingReasons());
        assertThat(wide.swingRisks()).isEqualTo(narrow.swingRisks());

        assertThat(wide.shortScore()).isEqualTo(narrow.shortScore());
        assertThat(wide.shortAction()).isEqualTo(narrow.shortAction());
        assertThat(wide.shortReasons()).isEqualTo(narrow.shortReasons());
        assertThat(wide.shortRisks()).isEqualTo(narrow.shortRisks());

        // 這一組本來就該有內容；三軌 reasons 全空的話上面四組相等會退化成空對空。
        assertThat(narrow.reasons()).isNotEmpty();
        assertThat(narrow.swingReasons()).isNotEmpty();
    }

    /**
     * 比照 {@code TradingRadarService.buildStock} 組出 {@code StockInput}，唯一的差別是
     * {@code weekly} 一律傳 {@code null}——本測試量的正是「把週K 拿掉之後，擴窗還有沒有
     * 從別的欄位偷偷改動最終輸出」。
     */
    private TradingRadarRuleEngine.StockInput withoutWeekly(
            RadarInputAssembler.Assembled technical, BigDecimal price) {
        RadarInputAssembler assembler = assembler();
        TechnicalIndicatorService.FullIndicators ind = technical.indicators();
        return new TradingRadarRuleEngine.StockInput(
                true,
                price,
                technical.ruleChangePercent(),
                technical.completedChangePercent(),
                assembler.indicators(ind),
                ind.previousK(),
                ind.previousD(),
                technical.ma20Confirmation(),
                technical.ma60Confirmation(),
                technical.ma240Confirmation(),
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.RISK_ON,
                false,
                null,
                technical.ma60BiasPercent(),
                technical.ma60BiasPercentile(),
                technical.ma240BiasPercent(),
                technical.week52Position(),
                technical.kdBandWidthPercent(),
                null,
                null,
                ind.weeklyMa(),
                assembler.extendedIndicators(ind.extended()),
                technical.volumeRatio(),
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE,
                technical.dailyCandle(),
                null);
    }

    // ───────────────────────── 兩臂比對 ─────────────────────────

    private record Arms(
            RadarInputAssembler.Assembled narrow,
            RadarInputAssembler.Assembled wide,
            BigDecimal price) {}

    private void assertDailyPathUnchanged(boolean liveAdded, List<StockDividendHistory> events) {
        assertDailyEqual(run(liveAdded, events));
    }

    private Arms run(boolean liveAdded, List<StockDividendHistory> events) {
        List<StockPriceHistory> all = rowsDesc(LONG_WINDOW_ROWS);
        List<StockPriceHistory> narrowRows = new ArrayList<>(all.subList(0, DAILY_CONTRACT_ROWS));
        List<StockPriceHistory> wideRows = new ArrayList<>(all);
        if (liveAdded) {
            narrowRows.add(0, liveRow());
            wideRows.add(0, liveRow());
        }
        RadarInputAssembler assembler = assembler();
        BigDecimal price = narrowRows.get(liveAdded ? 1 : 0).getClosePrice();
        return new Arms(
                assembler.assemble(narrowRows, eventsWithin(events, narrowRows), liveAdded,
                        DAILY_CONTRACT_ROWS, DAILY_CONTRACT_ROWS, price),
                assembler.assemble(wideRows, eventsWithin(events, wideRows), liveAdded,
                        DAILY_CONTRACT_ROWS, DAILY_CONTRACT_ROWS, price),
                price);
    }

    private static void assertDailyEqual(Arms arms) {
        RadarInputAssembler.Assembled narrow = arms.narrow();
        RadarInputAssembler.Assembled wide = arms.wide();

        TechnicalIndicatorService.FullIndicators a = narrow.indicators();
        TechnicalIndicatorService.FullIndicators b = wide.indicators();
        assertThat(b.weeklyMa()).isEqualByComparingTo(a.weeklyMa());
        assertThat(b.ma10()).isEqualByComparingTo(a.ma10());
        assertThat(b.monthlyMa()).isEqualByComparingTo(a.monthlyMa());
        assertThat(b.quarterlyMa()).isEqualByComparingTo(a.quarterlyMa());
        assertThat(b.annualMa()).isEqualByComparingTo(a.annualMa());
        assertThat(b.k()).isEqualByComparingTo(a.k());
        assertThat(b.d()).isEqualByComparingTo(a.d());
        assertThat(b.previousK()).isEqualByComparingTo(a.previousK());
        assertThat(b.previousD()).isEqualByComparingTo(a.previousD());
        assertThat(b.extended().j9()).isEqualByComparingTo(a.extended().j9());
        assertThat(b.extended().rsv()).isEqualByComparingTo(a.extended().rsv());
        assertThat(b.extended().dif()).isEqualByComparingTo(a.extended().dif());
        assertThat(b.extended().macd()).isEqualByComparingTo(a.extended().macd());
        assertThat(b.extended().osc()).isEqualByComparingTo(a.extended().osc());
        assertThat(b.extended().rsi5()).isEqualByComparingTo(a.extended().rsi5());
        assertThat(b.extended().rsi10()).isEqualByComparingTo(a.extended().rsi10());
        assertThat(b.extended().bias10()).isEqualByComparingTo(a.extended().bias10());
        assertThat(b.extended().bias20()).isEqualByComparingTo(a.extended().bias20());
        assertThat(b.extended().wr9()).isEqualByComparingTo(a.extended().wr9());

        assertThat(wide.completedCloses()).hasSameSizeAs(narrow.completedCloses());
        for (int i = 0; i < narrow.completedCloses().size(); i++) {
            assertThat(wide.completedCloses().get(i))
                    .isEqualByComparingTo(narrow.completedCloses().get(i));
        }
        assertThat(wide.week52High()).isEqualByComparingTo(narrow.week52High());
        assertThat(wide.week52Low()).isEqualByComparingTo(narrow.week52Low());
        assertThat(wide.kdBandWidthPercent()).isEqualByComparingTo(narrow.kdBandWidthPercent());
        assertThat(wide.ma20Confirmation()).isEqualTo(narrow.ma20Confirmation());
        assertThat(wide.ma60Confirmation()).isEqualTo(narrow.ma60Confirmation());
        assertThat(wide.ma240Confirmation()).isEqualTo(narrow.ma240Confirmation());
        assertThat(wide.ma60BiasPercent()).isEqualByComparingTo(narrow.ma60BiasPercent());
        assertThat(wide.ma60BiasPercentile()).isEqualByComparingTo(narrow.ma60BiasPercentile());
        assertThat(wide.ma240BiasPercent()).isEqualByComparingTo(narrow.ma240BiasPercent());
        assertThat(wide.week52Position()).isEqualByComparingTo(narrow.week52Position());
        assertThat(wide.ruleChangePercent()).isEqualByComparingTo(narrow.ruleChangePercent());
        assertThat(wide.completedChangePercent()).isEqualByComparingTo(narrow.completedChangePercent());
        assertThat(wide.previousAdjustedClose()).isEqualByComparingTo(narrow.previousAdjustedClose());
        assertThat(wide.volumeRatio()).isEqualTo(narrow.volumeRatio());
        assertThat(wide.distributionAdjusted()).isEqualTo(narrow.distributionAdjusted());
        assertThat(wide.returnStdDev60Ratio()).isEqualByComparingTo(narrow.returnStdDev60Ratio());
        assertThat(wide.dailyCandle().close()).isEqualByComparingTo(narrow.dailyCandle().close());
        assertThat(wide.dailyCandle().high()).isEqualByComparingTo(narrow.dailyCandle().high());
        assertThat(wide.dailyCandle().low()).isEqualByComparingTo(narrow.dailyCandle().low());
        assertThat(wide.dailyCandle().open()).isEqualByComparingTo(narrow.dailyCandle().open());
    }

    // ───────────────────────── 固定測試資料 ─────────────────────────

    /**
     * 500 個交易日（跳過週末）的降序序列。
     *
     * <p><b>成交量刻意這樣配</b>：第 0 列有量、第 1–240 列<b>全部</b>為 null、第 241 列起才恢復正量。
     * {@code volumeRatio} 沒有列數上限（掃到湊滿 20 筆正樣本為止），沒釘回契約視窗的話擴窗後
     * 會由 null 變成有值，量價因子跟著由缺值變有值。</p>
     */
    private static List<StockPriceHistory> rowsDesc(int rows) {
        List<StockPriceHistory> desc = new ArrayList<>(rows);
        LocalDate date = NEWEST;
        for (int i = 0; i < rows; i++) {
            BigDecimal close = closeAt(i);
            // 三元運算子混用 Long 與 long 會自動拆箱成 long，null 會直接 NPE；必須先落到 Long 變數。
            Long volume = null;
            if (i == 0) volume = 1_000L;
            else if (i > 240) volume = 500L;
            desc.add(StockPriceHistory.builder()
                    .stockCode("2330").market("台股")
                    .tradingDate(date)
                    .openPrice(close)
                    .highPrice(close.add(BigDecimal.ONE))
                    .lowPrice(close.subtract(BigDecimal.ONE))
                    .closePrice(close)
                    .volume(volume)
                    .build());
            date = previousWeekday(date);
        }
        return desc;
    }

    /** close(i) = 200.00 − 0.05 × i + 0.70 × (i mod 13)，恆為正且相鄰跳動遠小於分割門檻。 */
    private static BigDecimal closeAt(int i) {
        return BigDecimal.valueOf(20_000L - 5L * i + 70L * (i % 13), 2);
    }

    private static List<BigDecimal> rawCloses() {
        List<BigDecimal> out = new ArrayList<>(LONG_WINDOW_ROWS);
        for (int i = 0; i < LONG_WINDOW_ROWS; i++) out.add(closeAt(i));
        return out;
    }

    private static StockPriceHistory liveRow() {
        BigDecimal price = closeAt(0).add(BigDecimal.valueOf(50, 2));
        return StockPriceHistory.builder()
                .stockCode("2330").market("台股")
                .tradingDate(nextWeekday(NEWEST))
                .openPrice(price).highPrice(price).lowPrice(price).closePrice(price)
                .build();
    }

    /** 第 {@code index} 個交易日（降序索引）除息，用來製造「事件落在契約視窗之外」的情形。 */
    private static List<StockDividendHistory> exDividend(int index) {
        LocalDate date = NEWEST;
        for (int i = 0; i < index; i++) date = previousWeekday(date);
        StockDividendHistory event = new StockDividendHistory();
        event.setStockCode("2330");
        event.setMarket("台股");
        event.setExDividendDate(date);
        event.setCashDividend(new BigDecimal("2.00"));
        return List.of(event);
    }

    /** 比照 {@code TradingRadarService.prepareTechnicalData}：事件只查該序列的日期區間。 */
    private static List<StockDividendHistory> eventsWithin(
            List<StockDividendHistory> events, List<StockPriceHistory> rowsDesc) {
        LocalDate oldest = rowsDesc.get(rowsDesc.size() - 1).getTradingDate();
        LocalDate newest = rowsDesc.get(0).getTradingDate();
        return events.stream()
                .filter(e -> !e.getExDividendDate().isBefore(oldest)
                        && !e.getExDividendDate().isAfter(newest))
                .toList();
    }

    /** close(i) = 1000 − i 的線性序列，專供 {@code ma60BiasPercentile} 的觀測數反推。 */
    private static List<StockPriceHistory> linearRowsDesc(int rows) {
        List<StockPriceHistory> desc = new ArrayList<>(rows);
        LocalDate date = NEWEST;
        for (int i = 0; i < rows; i++) {
            BigDecimal close = BigDecimal.valueOf(1000L - i);
            desc.add(StockPriceHistory.builder()
                    .stockCode("2330").market("台股")
                    .tradingDate(date)
                    .openPrice(close).highPrice(close).lowPrice(close).closePrice(close)
                    .volume(1_000L)
                    .build());
            date = previousWeekday(date);
        }
        return desc;
    }

    /** 線性序列的 live K：收盤 1000.50，使指標視窗前 60 筆的均值恰為 971.49。 */
    private static StockPriceHistory linearLiveRow() {
        BigDecimal price = new BigDecimal("1000.50");
        return StockPriceHistory.builder()
                .stockCode("2330").market("台股")
                .tradingDate(nextWeekday(NEWEST))
                .openPrice(price).highPrice(price).lowPrice(price).closePrice(price)
                .build();
    }

    private static LocalDate previousWeekday(LocalDate date) {
        LocalDate candidate = date.minusDays(1);
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private static LocalDate nextWeekday(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }
}
