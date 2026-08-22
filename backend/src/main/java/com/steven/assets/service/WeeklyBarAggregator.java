package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 日 K 序列 → ISO 週 K 聚合（Task 356.2）。
 *
 * <p><b>純計算</b>：{@code final class} ＋ private 建構式 ＋ static 方法，不注入任何 repository、
 * 不讀系統時間、不碰網路（與 {@link RadarObservationResolver} 同一風格），因此可以在沒有
 * Spring context 的單元測試中直接驗證，回測與 production 也共用同一份。</p>
 *
 * <p><b>週的單位是 ISO-8601 週（週一為週首）</b>，key 為 {@link WeekFields#ISO} 的
 * {@code weekBasedYear} ＋ {@code weekOfWeekBasedYear}。<b>必須用 week-based-year、不得用
 * {@code getYear()}</b>——否則 2026-12-28（屬 ISO 2027-W01）會被歸進 2026 年的桶，跨年那一週
 * 被切成兩根。與市場時區無關：{@code trading_date} 本來就是該市場的當地交易日期，故同一段
 * 程式碼同時適用台股與美股，大盤序列先映射成中性的 OHLCV 形狀後也走這一支。</p>
 *
 * <p><b>對應實作（必須同步）：{@code com.steven.assets.bff.stockanalysis.ChartSeriesAligner#weekly}</b>。
 * 兩者的<b>分桶規則逐字一致</b>（同一組 ISO key、{@code open} 取該週最早交易日、{@code high}／
 * {@code low} 取極值、{@code close} 取最晚交易日、{@code weekEndDate} 取該週最晚交易日）——同一檔
 * 股票在「股票分析走勢圖的週K」與「交易雷達的週K」若連哪幾天算同一週、哪一天是週收盤都不一樣，
 * 使用者無從解釋。<b>但兩者刻意在五處不同</b>，不得當成同一個量：</p>
 * <ol>
 *   <li>BFF 的週 KD／MACD／RSI 是「該週最後一個交易日的<b>日K</b>指標值」；本側的週指標是在週K
 *       序列上重算（{@code TechnicalIndicatorService.computeFromSeries(週K)}）。</li>
 *   <li>價基不同：BFF 吃走勢圖的原始價基，本側一律吃還原權息／分割後的日K。</li>
 *   <li>進行中週：BFF 會畫出來（圖本來就該畫到今天），本側一律排除（見 {@link #aggregate}）。</li>
 *   <li>{@code requestedStart} 起始週丟棄：BFF 在 {@code start} 不是週一時丟掉整週，避免圖表首週
 *       被截斷成半根；business 這一側沒有 {@code requestedStart} 概念，<b>不適用</b>。</li>
 *   <li>壞資料週：BFF 只要該週任一日 candle 不合法就丟掉<b>整週</b>；本側採<b>逐欄</b> null
 *       （見 {@link #aggregate} 的欄位規則），只有 {@code close} 缺值才丟整根。</li>
 * </ol>
 * <p>前三處週界完全相同，只在「壞資料週是否呈現」上不同；此分歧在
 * {@code WeeklyBarAggregatorTest} 與 {@code ChartSeriesAlignerTest} 雙邊都有明確斷言，不得留白。</p>
 */
public final class WeeklyBarAggregator {

    private WeeklyBarAggregator() {}

    /**
     * 一根週 K。缺值為 {@code null}（不得以 0 或 {@code close} 冒充）。
     *
     * @param weekEndDate 該週最晚交易日的 {@code trading_date}。
     * @param open        該週最早交易日的開盤；該週任一日缺 {@code openPrice} 即為 null。
     * @param high        該週最高價；該週任一日缺 {@code highPrice} 即為 null。
     * @param low         該週最低價；該週任一日缺 {@code lowPrice} 即為 null。
     * @param close       該週最晚交易日的收盤；缺值時整根週 K 無效並丟棄，故本欄恆非 null。
     * @param volume      該週成交量之和；該週任一日 {@code volume} 為 null 時整根為 null。
     */
    public record WeeklyBar(
            LocalDate weekEndDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            Long volume
    ) {}

    /**
     * 聚合結果。
     *
     * @param completedDesc 完成週，<b>降序</b>（新到舊），第 0 筆為最新完成週。
     * @param currentPartial 進行中週（序列中 ISO 週最晚的那一根）；可為 {@code null}。
     *                       <b>本任務不揭露到任何 DTO、不進 {@code StockInput}／{@code MarketInput}</b>，
     *                       只作為聚合結果的完整表示與測試斷言對象（「最晚 ISO 週確實被排除」要有
     *                       東西可斷言）。
     */
    public record Aggregation(List<WeeklyBar> completedDesc, WeeklyBar currentPartial) {
        public static final Aggregation EMPTY = new Aggregation(List.of(), null);
    }

    /**
     * 把任意順序的日 K 列聚合成 ISO 週 K。
     *
     * <p><b>序列中 ISO 週最晚的那一根一律視為「進行中週」，不進入 {@code completedDesc}</b>，
     * 因此不會進入任何指標與評分。完成週的判定<b>完全由序列自身決定</b>：不查交易日曆、
     * 不讀系統時間、不做「今天是不是週五」的推論。理由：</p>
     * <ol>
     *   <li><b>回測可重現</b>——同一段序列恆得同一組完成週，不受執行時刻影響。</li>
     *   <li><b>同一週之內週K 因子不會逐日抖動</b>，符合「週K 本來就是慢變數」。</li>
     *   <li><b>半天交易日、臨時休市與颱風假都不需要特例</b>。</li>
     * </ol>
     * <p>代價是最新一週的資訊延遲最多五個交易日，這是刻意接受的取捨。</p>
     *
     * @param rows 日 K 列（任意順序）；{@code tradingDate} 為 null 的列一律略過。
     */
    public static Aggregation aggregate(List<StockPriceHistory> rows) {
        if (rows == null || rows.isEmpty()) return Aggregation.EMPTY;

        Map<WeekKey, List<StockPriceHistory>> groups = new TreeMap<>();
        for (StockPriceHistory row : rows) {
            if (row == null || row.getTradingDate() == null) continue;
            groups.computeIfAbsent(WeekKey.of(row.getTradingDate()), k -> new ArrayList<>()).add(row);
        }
        if (groups.isEmpty()) return Aggregation.EMPTY;

        List<WeeklyBar> ascending = new ArrayList<>(groups.size());
        for (List<StockPriceHistory> group : groups.values()) {
            group.sort(Comparator.comparing(StockPriceHistory::getTradingDate));
            ascending.add(bar(group));
        }
        // 最晚 ISO 週恆為進行中週，即使它因 close 缺值而無效（此時 currentPartial 為 null，
        // 但那一週仍不得回補進 completedDesc）。
        WeeklyBar currentPartial = ascending.get(ascending.size() - 1);
        List<WeeklyBar> completedDesc = new ArrayList<>(ascending.size());
        for (int i = ascending.size() - 2; i >= 0; i--) {
            if (ascending.get(i) != null) completedDesc.add(ascending.get(i));
        }
        return new Aggregation(List.copyOf(completedDesc), currentPartial);
    }

    /** 單一週的聚合；{@code close} 缺值時回 null（整根丟棄）。 */
    private static WeeklyBar bar(List<StockPriceHistory> ascendingGroup) {
        StockPriceHistory last = ascendingGroup.get(ascendingGroup.size() - 1);
        BigDecimal close = last.getClosePrice();
        if (close == null) return null;

        BigDecimal open = ascendingGroup.get(0).getOpenPrice();
        BigDecimal high = null;
        BigDecimal low = null;
        long volumeSum = 0L;
        boolean volumeComplete = true;
        boolean highComplete = true;
        boolean lowComplete = true;
        for (StockPriceHistory row : ascendingGroup) {
            if (row.getOpenPrice() == null) open = null;
            if (row.getHighPrice() == null) {
                highComplete = false;
            } else if (high == null || row.getHighPrice().compareTo(high) > 0) {
                high = row.getHighPrice();
            }
            if (row.getLowPrice() == null) {
                lowComplete = false;
            } else if (low == null || row.getLowPrice().compareTo(low) < 0) {
                low = row.getLowPrice();
            }
            if (row.getVolume() == null) {
                volumeComplete = false;
            } else {
                volumeSum += row.getVolume();
            }
        }
        return new WeeklyBar(
                last.getTradingDate(),
                open,
                highComplete ? high : null,
                lowComplete ? low : null,
                close,
                volumeComplete ? volumeSum : null);
    }

    /**
     * ISO 週 key。{@code weekBasedYear} 而非 {@code getYear()}——跨年週必須是同一個桶
     * （2026-12-28 屬 ISO 2027-W01）。
     */
    private record WeekKey(int weekBasedYear, int week) implements Comparable<WeekKey> {
        static WeekKey of(LocalDate date) {
            return new WeekKey(
                    date.get(WeekFields.ISO.weekBasedYear()),
                    date.get(WeekFields.ISO.weekOfWeekBasedYear()));
        }

        @Override
        public int compareTo(WeekKey other) {
            return weekBasedYear == other.weekBasedYear
                    ? Integer.compare(week, other.week)
                    : Integer.compare(weekBasedYear, other.weekBasedYear);
        }
    }
}
