package com.steven.assets.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 交易雷達規則回測的請求／回應契約（Task 273／Requirement 56）。
 *
 * <p><b>本檔即 273.4b.3 所要求的「輸入／輸出契約」單一事實來源</b>——t274／t275／t276／t277
 * 的驗收指令依此撰寫。欄位增刪須同步更新那四份任務檔的驗收段。</p>
 *
 * <p><b>輸出一律表述為「歷史上此條件成立後的報酬分布」</b>。不得出現「預測」「將會」「機率為」
 * 「能降低風險」等語句（Task 273 的 273.8.1，沿用 {@code KD_OVERHEAT_K} Javadoc 已建立的紀律）。
 * 亦刻意<b>不輸出 p 值與信賴區間</b>：單一市場、單一十年期、標的高度重疊，且連續多日同一訊號成立
 * 造成嚴重自相關，古典檢定的前提不成立。</p>
 */
public final class BacktestDto {

    private BacktestDto() {}

    /**
     * @param codes      標的代號清單；null／空＝全部台股（不含 {@code 0000}）。
     * @param from       起始交易日（含）；null＝不設限。
     * @param to         結束交易日（含）；null＝不設限。
     * @param horizons   前瞻交易日數；null／空＝ {@code [5, 20, 60, 240]}。
     * @param thresholds 門檻掃描（273.4b.1）。key 為可調量名稱、value 為候選值清單。
     *                   支援的 key：{@code biasSigma}（季線乖離的 σ 倍數）、{@code sigmaFloor}
     *                   （σ 絕對下限）、{@code kdOverheat}、{@code kdOversold}、{@code volumeRatio}。
     *                   每個候選值各產生一條述詞、各自完整統計。
     * @param predicates 額外的組合述詞（273.4b.2），語法為 {@code 基礎條件+附加條件}，
     *                   例如 {@code EXTREME_OVERSOLD+SCORE_LT_40}。null／空＝只跑內建述詞。
     */
    public record Request(
            List<String> codes,
            String from,
            String to,
            List<Integer> horizons,
            Map<String, List<BigDecimal>> thresholds,
            List<String> predicates
    ) {}

    /**
     * @param ruleVersion    產出這份統計的規則版本（本任務不升版，應為既有值）。
     * @param dataFrom       實際涵蓋的最早交易日。
     * @param dataTo         實際涵蓋的最晚交易日。
     * @param codeCount      納入統計的標的數。
     * @param horizons       實際使用的 horizon。
     * @param perCode        逐標的的樣本揭露（273.7.1）。
     * @param results        每個（述詞 × held × horizon）的統計。
     * @param pairedByCode   逐標的配對比較的彙總（273.6.2）。
     * @param failedCodes    因讀取或還原失敗而未納入統計的標的。**必須可見**——否則「N 檔的統計」
     *                       與「本來就只有 N 檔」無法區分，結果不可獨立複驗。
     * @param notes          必須讓讀者看見的限制與揭露。
     */
    public record Response(
            String ruleVersion,
            String dataFrom,
            String dataTo,
            int codeCount,
            List<Integer> horizons,
            List<CodeCoverage> perCode,
            List<PredicateStat> results,
            List<PairedStat> pairedByCode,
            List<String> failedCodes,
            List<String> notes
    ) {}

    /**
     * @param rows            該標的的原始交易日數。
     * @param nonPositiveRows 被剔除的 {@code close_price <= 0} 髒列數（另立任務處理，此處只揭露）。
     * @param warmupExcluded  因暖機不足 240 筆而排除的天數。
     * @param evaluated       實際進入統計的天數。
     * @param etfPremiumDays  {@code etf_nav_history} 在該標的上實際有值的天數。
     *                        <b>回測期間結構性缺值</b>（全庫僅 11 個交易日），故 ETF 的 buyGate 與
     *                        OVERBOUGHT 統計未含折溢價否決，不得直接當成 production 行為的證據。
     */
    public record CodeCoverage(
            String code,
            String market,
            String instrumentType,
            String dataFrom,
            String dataTo,
            int rows,
            int nonPositiveRows,
            int warmupExcluded,
            int evaluated,
            int etfPremiumDays
    ) {}

    /**
     * 單一（述詞 × held × horizon）的統計。全部欄位皆為「歷史分布」的描述。
     *
     * @param n                    訊號組樣本數。
     * @param sampleInsufficient   {@code n < 30}；該格的數字不可用於決策。
     * @param insufficientForward  因 {@code t+h} 越界而未計入此 horizon 的訊號日數。
     * @param downsideRisk         報酬 {@code <= -10%} 的比例。
     * @param baselineN            同標的同期間所有具備完整前瞻報酬的交易日數。
     * @param diffMean             訊號組平均 − 基準平均。
     * @param caveat               該格特有的揭露（例如 ETF 未含折溢價否決）。
     */
    public record PredicateStat(
            String predicate,
            boolean held,
            int horizon,
            int n,
            boolean sampleInsufficient,
            int insufficientForward,
            BigDecimal mean,
            BigDecimal median,
            BigDecimal winRate,
            BigDecimal downsideRisk,
            BigDecimal p5,
            BigDecimal p25,
            BigDecimal p75,
            BigDecimal p95,
            int baselineN,
            BigDecimal baselineMean,
            BigDecimal baselineMedian,
            BigDecimal baselineWinRate,
            BigDecimal baselineDownsideRisk,
            BigDecimal diffMean,
            String caveat
    ) {}

    /**
     * 逐標的配對比較的彙總（273.6.2）：每檔各自算「訊號組 − 基準」，再對各檔的差額取統計量。
     *
     * <p>與 {@link PredicateStat}（全樣本合併口徑）**必須並列呈現**——合併口徑會被樣本數多的
     * 高波動標的主導，兩者不一致是預期的，必須讓讀者看見。</p>
     *
     * @param codesWithSignal 有訊號的標的數。
     * @param meanOfDiffs     各標的「訊號組平均 − 基準平均」的平均。
     * @param medianOfDiffs   同上的中位數。
     * @param codesPositive   差額為正的標的數。
     */
    public record PairedStat(
            String predicate,
            boolean held,
            int horizon,
            int codesWithSignal,
            BigDecimal meanOfDiffs,
            BigDecimal medianOfDiffs,
            int codesPositive
    ) {}
}
