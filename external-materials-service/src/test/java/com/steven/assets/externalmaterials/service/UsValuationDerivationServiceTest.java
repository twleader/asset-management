package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.StockFundamentalFetchClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 334.7 美股歷史估值推導的行為守門（純單元測試，不啟 Spring context、不連 DB）。
 *
 * <p>本檔釘住的都是「做錯不會有任何錯誤訊息」的地方：累計→單季還原、point-in-time 的
 * {@code effective_available_at} 單調化、每股基準閘門的單季口徑、股利去重的三種型態，以及重跑冪等。</p>
 */
class UsValuationDerivationServiceTest {

    private static final String COMPANY_FACTS_URL =
            "https://data.sec.gov/api/xbrl/companyfacts/CIK0001018724.json";
    private static final long ONE_BILLION = 1_000_000_000L;
    private static final Long EQUITY = 200_000_000_000L;
    private static final ZoneId US_EXCHANGE_ZONE = ZoneId.of("America/New_York");
    private static final String US_MARKET = "美股";

    // ── (a) 累計→單季還原與 TTM ────────────────────────────────────────────────

    /**
     * 與 backend {@code FundamentalAnalysisService.standaloneValue(int, BigDecimal, BigDecimal)}
     * 逐列相同的契約表。兩個 module 無法互相呼叫，這張表就是把兩份複本釘在一起的唯一手段
     * （本專案已有 {@code expectedFinancialPeriodIndex} 兩份複本走樣的前例）。
     */
    @Test
    void standaloneValueMirrorsBackendContractRowByRow() {
        assertThat(UsValuationDerivationService.standaloneValue(1, bd("3.00"), null))
                .isEqualByComparingTo("3.00");
        // Q1 直接取累計，前季值即使存在也不參與（會計年度重新起算）
        assertThat(UsValuationDerivationService.standaloneValue(1, bd("3.00"), bd("99.00")))
                .isEqualByComparingTo("3.00");
        assertThat(UsValuationDerivationService.standaloneValue(2, bd("5.00"), bd("3.00")))
                .isEqualByComparingTo("2.00");
        assertThat(UsValuationDerivationService.standaloneValue(4, bd("5.00"), bd("9.00")))
                .isEqualByComparingTo("-4.00");
        // Q2–Q4 缺前一季時必須是 null，不得退化成「把累計當單季」
        assertThat(UsValuationDerivationService.standaloneValue(2, bd("5.00"), null)).isNull();
        assertThat(UsValuationDerivationService.standaloneValue(3, null, bd("1.00"))).isNull();
        assertThat(UsValuationDerivationService.standaloneValue(0, bd("1.00"), bd("1.00"))).isNull();
        assertThat(UsValuationDerivationService.standaloneValue(5, bd("1.00"), bd("1.00"))).isNull();
    }

    @Test
    void ttmSumsFourConsecutiveStandaloneQuartersAcrossTheFiscalYearBoundary() {
        List<StockFundamentalFetchClient.Valuation> rows = UsValuationDerivationService.derive(
                "AMZN", growingQuarters(), closes("2025-01-20", "2025-05-09", "100.00"), List.of());

        // 2024Q4 於 2025-01-30 收盤後申報，當日與之前只有三季可見 → 湊不出 TTM → 不落列
        assertThat(on(rows, "2025-01-29")).isNull();
        assertThat(on(rows, "2025-01-30")).isNull();
        // 2024Q1–Q4 單季 EPS 各 1.00 → TTM 4.00 → PE = 100 / 4（申報日的下一個交易日起）
        assertThat(on(rows, "2025-01-31").peRatio()).isEqualByComparingTo("25.0000");
        // 跨會計年度：2025Q1（單季 2.00）＋2024Q4/Q3/Q2 → TTM 5.00
        assertThat(on(rows, "2025-04-25").peRatio()).isEqualByComparingTo("20.0000");
    }

    @Test
    void nonConsecutiveQuartersProduceNoRowUntilAFullyConsecutiveWindowIsVisible() {
        // 整個 2024Q4 缺列（不是欄位為 null）：Q1 不需前季、Q3 有 Q2，故所有單季推導股數仍可算，
        // 閘門不會被觸發；擋下舊日期的必須是「四季連續」這一條。
        List<FundamentalObservationStore.QuarterFact> quarters = new ArrayList<>(twoYearsFlatQuarters());
        quarters.removeIf(q -> q.fiscalYear() == 2024 && q.fiscalQuarter() == 4);

        List<StockFundamentalFetchClient.Valuation> rows = UsValuationDerivationService.derive(
                "MSFT", quarters, closes("2024-11-01", "2026-03-06", "100.00"), List.of());

        // 2025Q1 可見時，最新四季是 2025Q1／2024Q3／Q2／Q1 —— 期別不連續 → 不落列
        assertThat(on(rows, "2025-06-02")).isNull();
        // 2025Q1–Q4 到齊後才連續 → 落列
        assertThat(on(rows, "2026-02-02")).isNotNull();
    }

    // ── (b) 虧損 ────────────────────────────────────────────────────────────────

    /**
     * TTM EPS ≤ 0 時 {@code pe_ratio} 為 null、{@code pe_loss_flag} 為 true，而且<b>該列仍須產出</b>
     * ——否則下游無法區分「虧損」與「未推導出」這兩種行為相反的情形。嚴禁以 0 代替 null
     * （{@code pe_ratio = 0} 在下游會被讀成「本益比極低＝極便宜」，把虧損公司評為最優）。
     *
     * <p><b>注意：本狀態目前無法由 {@link UsValuationDerivationService#derive} 端到端產生。</b>
     * 任務檔的每股基準閘門規定「單季 EPS 或單季淨利不為正一律視為基準不可驗證＝等同變動點
     * （fail closed）」，於是虧損季必然被排除在可用區段之外，四季全為正 EPS 的 TTM 不可能 ≤ 0。
     * 這裡直接測 {@code buildRow} 的語意，是為了讓 334.5（backend 必須把 {@code SEC_DERIVED} 排除在
     * {@code latestLoss} 之外）所依賴的前提是可查證的，且日後若放寬閘門，虧損語意已經是對的。</p>
     */
    @Test
    void lossQuarterStillLandsARowWithNullPeAndExplicitLossFlag() {
        StockFundamentalFetchClient.Valuation loss = UsValuationDerivationService.buildRow(
                "COIN", LocalDate.of(2025, 5, 2), bd("100.00"),
                bd("-2.00"), bd("-2000000000"), EQUITY, null,
                filed("2025-04-24"), List.of(COMPANY_FACTS_URL));

        assertThat(loss).isNotNull();
        assertThat(loss.peRatio()).isNull();
        assertThat(loss.peLossFlag()).isTrue();

        // TTM EPS 恰為 0 同樣算虧損（除以零不可行），不得回 0 或漏列
        StockFundamentalFetchClient.Valuation zero = UsValuationDerivationService.buildRow(
                "COIN", LocalDate.of(2025, 5, 2), bd("100.00"),
                bd("0.00"), bd("0"), EQUITY, null, filed("2025-04-24"), List.of(COMPANY_FACTS_URL));
        assertThat(zero.peRatio()).isNull();
        assertThat(zero.peLossFlag()).isTrue();

        // 有正 TTM EPS 時旗標必須是明確的 FALSE，不能留 null
        StockFundamentalFetchClient.Valuation profit = UsValuationDerivationService.buildRow(
                "COIN", LocalDate.of(2025, 5, 2), bd("100.00"),
                bd("4.00"), bd("4000000000"), EQUITY, null, filed("2025-04-24"), List.of(COMPANY_FACTS_URL));
        assertThat(profit.peLossFlag()).isFalse();
        assertThat(profit.peRatio()).isEqualByComparingTo("25.0000");
    }

    // ── (b2) 每股基準閘門 ───────────────────────────────────────────────────────

    /**
     * 10:1 分割：單季推導股數由 10 億跳到 100 億。跨越該變動點的 TTM 視窗不得落列，落地序列的最早日期
     * 必須晚於變動點，且整條序列不得出現倍數級的相鄰跳階（那正是「PE 看起來便宜 k 倍」的症狀）。
     */
    @Test
    void perShareBasisChangeTruncatesTheUsableSegmentInsteadOfPollutingThePercentile() {
        LocalDate splitDay = LocalDate.of(2025, 2, 14);
        List<StockFundamentalFetchClient.Valuation> rows = UsValuationDerivationService.derive(
                "NVDA", splitQuarters("0.10"), closes("2024-06-03", "2026-08-14", "100.00"), List.of());

        // 對照組：同樣的申報日程但沒有分割時，2024Q4 申報（2025-01-30 收盤後）的次一交易日就開始落列。
        // 兩者相減即為閘門實際砍掉的區段，證明它不是無作用的裝飾。
        List<StockFundamentalFetchClient.Valuation> unsplit = UsValuationDerivationService.derive(
                "NVDA", twoYearsFlatQuarters(), closes("2024-06-03", "2026-08-14", "100.00"), List.of());
        assertThat(unsplit.get(0).tradingDate()).isEqualTo(LocalDate.of(2025, 1, 31));

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).tradingDate()).isAfter(splitDay);
        // 分割後第一個「四季皆為新基準」的視窗要等 2025Q4 申報（2026-01-29 收盤後）才成立
        assertThat(rows.get(0).tradingDate()).isEqualTo(LocalDate.of(2026, 1, 30));
        // 分割日前後的交易日都沒有推導列（閘門把整段舊基準排除），故不可能出現混基準的 PE
        assertThat(on(rows, "2025-02-13")).isNull();
        assertThat(on(rows, "2025-02-18")).isNull();
        // 已落地的相鄰交易日 PE 必須連續、無倍數跳階
        assertThat(maxAdjacentPeRatio(rows)).isLessThan(UsValuationDerivationService.BASIS_CHANGE_MIN_RATIO);
    }

    /**
     * (b2b) 口徑錨點：<b>4:1</b> 分割。單季口徑比值恰為 4.0，一次命中門檻；TTM 口徑會被攤平成四個小台階
     * （最大只有 1.75），完全偵測不到。10:1 的 fixture 在兩種口徑下都會通過，所以缺這一案，
     * 單季／TTM 的歧義會活過測試。
     */
    @Test
    void fourForOneSplitIsDetectedOnStandaloneBasisButWouldBeInvisibleOnTtmBasis() {
        // 單季口徑：分割前後推導股數 10 億 → 40 億，比值 4.0
        BigDecimal before = UsValuationDerivationService.derivedShares(bd("1.00"), bd("1000000000"));
        BigDecimal after = UsValuationDerivationService.derivedShares(bd("0.25"), bd("1000000000"));
        assertThat(before).isEqualByComparingTo("1000000000");
        assertThat(after).isEqualByComparingTo("4000000000");
        assertThat(after.divide(before, 4, RoundingMode.HALF_UP))
                .isGreaterThanOrEqualTo(UsValuationDerivationService.BASIS_CHANGE_MIN_RATIO);

        // TTM 口徑：單季 EPS 1.00×4 → 0.25×4，淨利固定 10 億／季，四步相鄰比值最大 1.75
        List<BigDecimal> standaloneEps = List.of(
                bd("1.00"), bd("1.00"), bd("1.00"), bd("1.00"),
                bd("0.25"), bd("0.25"), bd("0.25"), bd("0.25"));
        List<BigDecimal> ttmShares = new ArrayList<>();
        for (int end = 3; end < standaloneEps.size(); end++) {
            BigDecimal eps = BigDecimal.ZERO;
            for (int i = end - 3; i <= end; i++) eps = eps.add(standaloneEps.get(i));
            ttmShares.add(bd("4000000000").divide(eps, 6, RoundingMode.HALF_UP));
        }
        BigDecimal maxStep = BigDecimal.ZERO;
        for (int i = 1; i < ttmShares.size(); i++) {
            maxStep = maxStep.max(ttmShares.get(i).divide(ttmShares.get(i - 1), 4, RoundingMode.HALF_UP));
        }
        assertThat(maxStep).isEqualByComparingTo("1.7500");
        assertThat(maxStep).isLessThan(UsValuationDerivationService.BASIS_CHANGE_MIN_RATIO);

        // 端到端：4:1 fixture 的落地序列一樣只從分割後開始
        List<StockFundamentalFetchClient.Valuation> rows = UsValuationDerivationService.derive(
                "NVDA", splitQuarters("0.25"), closes("2024-06-03", "2026-08-14", "100.00"), List.of());
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).tradingDate()).isAfter(LocalDate.of(2025, 2, 14));
        assertThat(on(rows, "2025-06-02")).isNull();
    }

    /**
     * (b3) {@code net_income_parent} 為 null 的季度＝基準不可驗證，必須<b>終止</b>可用區段
     * （fail closed），而不是被略過後繼續往更舊的季度延伸。
     */
    @Test
    void quarterWithoutNetIncomeTerminatesTheUsableSegmentInsteadOfBeingSkipped() {
        List<FundamentalObservationStore.QuarterFact> intact = twoYearsFlatQuarters();
        List<FundamentalObservationStore.QuarterFact> holed = intact.stream()
                .map(q -> q.fiscalYear() == 2024 && q.fiscalQuarter() == 4
                        ? new FundamentalObservationStore.QuarterFact(q.fiscalYear(), q.fiscalQuarter(),
                                q.cumulativeEps(), null, q.equityParent(),
                                q.sourceAvailableAt(), q.sourceUrls())
                        : q)
                .toList();

        List<StockSourceQuery.ClosePoint> prices = closes("2024-11-01", "2026-03-06", "100.00");
        List<StockFundamentalFetchClient.Valuation> baseline =
                UsValuationDerivationService.derive("AVGO", intact, prices, List.of());
        List<StockFundamentalFetchClient.Valuation> gated =
                UsValuationDerivationService.derive("AVGO", holed, prices, List.of());

        // 完好時 2024Q4 一申報（次一交易日）就開始落列
        assertThat(baseline.get(0).tradingDate()).isEqualTo(LocalDate.of(2025, 1, 31));
        // 淨利缺一季後，可用區段被截斷在 2025Q1，必須等 2025Q4 到齊才有第一個四季視窗
        assertThat(on(gated, "2025-06-02")).isNull();
        assertThat(gated.get(0).tradingDate()).isEqualTo(LocalDate.of(2026, 1, 30));
    }

    /**
     * (b3 補) 閘門 fail-closed 條款 (iii)：<b>單季 EPS 或單季淨利不為正</b>同樣視同基準變動點。
     *
     * <p>虧損季的推導股數會變成負數，而「比值 ≥ 1.8」對負數<b>永遠為 false</b>——只擋 0（把
     * {@code signum() <= 0} 化簡成 {@code signum() == 0}）會讓整個虧損區段靜默放行，與 fail-closed
     * 的意圖相反，且沒有任何測試會變紅。這一案就是釘住負數那一半。</p>
     */
    @Test
    void nonPositiveStandaloneEpsOrIncomeTerminatesTheUsableSegment() {
        assertThat(UsValuationDerivationService.derivedShares(bd("-0.50"), bd("1000000000"))).isNull();
        assertThat(UsValuationDerivationService.derivedShares(bd("1.00"), bd("-1000000000"))).isNull();
        assertThat(UsValuationDerivationService.derivedShares(bd("0.00"), bd("1000000000"))).isNull();

        List<StockSourceQuery.ClosePoint> prices = closes("2024-11-01", "2026-03-06", "100.00");
        List<StockFundamentalFetchClient.Valuation> baseline =
                UsValuationDerivationService.derive("AMZN", twoYearsFlatQuarters(), prices, List.of());
        List<StockFundamentalFetchClient.Valuation> gated =
                UsValuationDerivationService.derive("AMZN", lossQuarterInTheMiddle(), prices, List.of());

        assertThat(baseline.get(0).tradingDate()).isEqualTo(LocalDate.of(2025, 1, 31));
        // 2024Q3 單季 EPS 為 −0.50（單季淨利仍為正）→ 推導股數不可得 → 可用區段截斷在 2024Q4，
        // 第一個「四季皆可用」的視窗要等 2025Q3 申報（2025-10-23 收盤後）才成立。
        assertThat(gated).isNotEmpty();
        assertThat(gated.get(0).tradingDate()).isEqualTo(LocalDate.of(2025, 10, 24));
        assertThat(on(gated, "2025-06-02")).isNull();
    }

    // ── (c)(c2) point-in-time 與 effective_available_at 單調化 ──────────────────

    /**
     * 某季只能從<b>申報日的下一個交易日</b>起被看見。SEC {@code filed} 只有日期精度，寫入端換算為當日
     * 16:30 ET（收盤後）；若換算成當日中午 UTC（＝08:00 ET，開盤前），申報當日的推導列就會是
     * 「盤前價 ÷ 尚未公開的財報」——每季一天的 look-ahead，且恰好落在財報公布日這種最敏感的一天。
     */
    @Test
    void tradingDaysBeforeAFilingCannotSeeThatQuarterAndNeitherCanTheFilingDayItself() {
        List<StockFundamentalFetchClient.Valuation> rows = UsValuationDerivationService.derive(
                "AMZN", growingQuarters(), closes("2025-01-20", "2025-05-09", "100.00"), List.of());

        // 2025Q1（單季 EPS 2.00）於 2025-04-24 收盤後申報：前一交易日仍只能用到 2024Q4 為止的 TTM 4.00
        assertThat(on(rows, "2025-04-23").peRatio()).isEqualByComparingTo("25.0000");
        // 申報當日本身也還看不到（該日收盤價早於申報時點）
        assertThat(on(rows, "2025-04-24").peRatio()).isEqualByComparingTo("25.0000");
        assertThat(on(rows, "2025-04-25").peRatio()).isEqualByComparingTo("20.0000");
    }

    /**
     * (c2) 舊資料的 {@code source_available_at} 可能是「最後一次提及該期別的申報時點」，直接拿來過濾會做出
     * 鋸齒序列。fixture 取自實測：MSFT 三個 Q4 共用同一個 filed、AMZN 2025Q2 的 filed 反而晚於 2025Q3。
     *
     * <p>(ii) 這一半<b>不能用「相鄰 PE 比值 &lt; 1.8」這種代理指標</b>：EPS 平滑成長時，就算視窗在單一交易日
     * 往前跨 5–6 季，相鄰 PE 比值仍然遠低於門檻，斷言恆真、沒有鑑別力。故這裡直接逐交易日重建「TTM 視窗
     * 頂端期別」，量它的單日推進量。fixture 也刻意多給兩年季別，讓第一個批次事件之前<b>已經有落地列</b>
     * ——否則批次永遠落在序列開頭、根本比較不到。</p>
     */
    @Test
    void effectiveAvailableAtIsMonotonicSoTheTtmWindowNeverTurnsOverInASingleDay() {
        List<FundamentalObservationStore.QuarterFact> quarters = pathologicalFiledQuarters();

        Map<Integer, Instant> effective = effectiveAvailableAt(quarters);
        List<Integer> ascending = effective.keySet().stream().sorted().toList();
        for (int i = 1; i < ascending.size(); i++) {
            // (i) 單調化後，較舊期別的有效可見時點不得晚於較新期別
            assertThat(effective.get(ascending.get(i - 1)))
                    .isBeforeOrEqualTo(effective.get(ascending.get(i)));
        }
        // raw filed 本身是非單調的（2025Q2 = 2026-07-31 晚於 2025Q3 = 2025-10-31），單調化必須把它拉回
        assertThat(effective.get(UsValuationDerivationService.periodIndex(2025, 2)))
                .isEqualTo(filed("2025-10-31"));

        List<StockSourceQuery.ClosePoint> prices = closes("2024-10-01", "2026-08-14", "100.00");
        List<StockFundamentalFetchClient.Valuation> rows =
                UsValuationDerivationService.derive("MSFT", quarters, prices, List.of());

        // 2025-06-02：單調化後 2024Q4 已可見，最新四季 2025Q1/2024Q4/Q3/Q2 → TTM 5.00。
        assertThat(on(rows, "2025-06-02")).isNotNull();
        assertThat(on(rows, "2025-06-02").peRatio()).isEqualByComparingTo("20.0000");

        // (ii) 逐交易日重建視窗頂端期別，直接量單日推進量
        Map<LocalDate, Integer> tops = windowTopByDay(effective, prices);
        int maxAdvance = 0;
        LocalDate firstBatchDay = null;
        List<LocalDate> days = tops.keySet().stream().sorted().toList();
        for (int i = 1; i < days.size(); i++) {
            int advance = tops.get(days.get(i)) - tops.get(days.get(i - 1));
            if (advance >= 2 && firstBatchDay == null) firstBatchDay = days.get(i);
            maxAdvance = Math.max(maxAdvance, advance);
        }
        // 單日不得整組換掉四季視窗（＝跨 4 季跳階），否則 TTM 分母會在一天內完全換人
        assertThat(maxAdvance).isLessThan(4);
        // 而且這條斷言確實被考驗過：fixture 真的有「一天冒出兩季」的批次事件
        assertThat(maxAdvance).isGreaterThanOrEqualTo(2);
        assertThat(firstBatchDay).isNotNull();
        // 批次事件發生前已經有落地列（否則它落在序列開頭、測不到）
        assertThat(rows.get(0).tradingDate()).isBefore(firstBatchDay);

        // 對照組：不做單調化、直接用 raw source_available_at 時，同一組 fixture 會在許多交易日湊不出
        // 連續四季而整列消失。這就是單調化實際在做的事，缺了它這個 fixture 會大量掉列。
        Map<LocalDate, Integer> rawTops = windowTopByDay(rawAvailableAt(quarters), prices);
        assertThat(rawTops.size()).isLessThan(tops.size());
    }

    // ── (d)(d2) 殖利率與股利去重 ────────────────────────────────────────────────

    @Test
    void dividendYieldIsNullWithoutAnyExDividendRecordAndZeroAfterAFullYearWithoutOne() {
        LocalDate day = LocalDate.of(2025, 6, 2);

        UsValuationDerivationService.DividendSeries none =
                UsValuationDerivationService.resolveDividends("AMZN", List.of());
        // 查無任何除息紀錄 → null，不得寫 0 冒充「不配息」
        assertThat(UsValuationDerivationService.dividendYieldPct(day, bd("100.00"), none)).isNull();

        UsValuationDerivationService.DividendSeries stopped =
                UsValuationDerivationService.resolveDividends("XYZ", List.of(dividend("2020-01-15", "1.00")));
        // 有紀錄但近 365 日無除息 → 0（已停配滿一年，與「查無紀錄」是兩回事）
        assertThat(UsValuationDerivationService.dividendYieldPct(day, bd("100.00"), stopped))
                .isEqualByComparingTo("0.0000");
    }

    @Test
    void dividendWindowIncludesTheOldestDayInsideThreeSixtyFiveAndExcludesTheBoundaryItself() {
        LocalDate day = LocalDate.of(2025, 6, 2);
        UsValuationDerivationService.DividendSeries series =
                UsValuationDerivationService.resolveDividends("MSFT", List.of(
                        dividend(day.minusDays(365).toString(), "9.00"),   // 恰在 365 天界線上 → 排除
                        dividend(day.minusDays(364).toString(), "1.00"),   // 界線內 → 納入
                        dividend(day.toString(), "2.00"),                  // 當日除息 → 納入
                        dividend(day.plusDays(1).toString(), "8.00")));    // 未來 → 排除

        assertThat(UsValuationDerivationService.dividendYieldPct(day, bd("100.00"), series))
                .isEqualByComparingTo("3.0000");
    }

    /**
     * (d2) 去重三型態。第三案（比值與該標的還原倍數眾數不一致 → null）是反例錨點：
     * AVGO 僅有的一次分割是 10:1，但 2018-03-21 的兩列比值是 5，只看「取最小值」會採 0.35，
     * 是正確值 0.175 的兩倍且不會報錯。
     */
    @Test
    void sameDayDuplicateDividendsAreResolvedByPrecisionSplitRatioOrFailClosed() {
        // ① 型態一：同一筆、兩個精度（QQQ 從未分割），比值 1.0004 → 只計一次、取有效位數較多者
        UsValuationDerivationService.DividendSeries qqq =
                UsValuationDerivationService.resolveDividends("QQQ", List.of(
                        dividend("2023-03-20", "0.472000"), dividend("2023-03-20", "0.472200")));
        assertThat(qqq.unresolved()).isEmpty();
        assertThat(qqq.resolved().get(LocalDate.of(2023, 3, 20))).isEqualByComparingTo("0.472200");

        List<DividendHistoryQuery.CashDividendEvent> avgo = List.of(
                dividend("2017-12-18", "0.175000"), dividend("2017-12-18", "1.750000"),  // 比值 10
                dividend("2018-03-21", "0.350000"), dividend("2018-03-21", "1.750000"),  // 比值 5（反例）
                dividend("2018-06-19", "0.350000"), dividend("2018-06-19", "1.750000"),  // 比值 5
                dividend("2018-09-18", "0.175000"), dividend("2018-09-18", "1.750000"),  // 比值 10
                dividend("2023-06-21", "0.460000"), dividend("2023-06-21", "4.600000")); // 比值 10
        UsValuationDerivationService.DividendSeries resolved =
                UsValuationDerivationService.resolveDividends("AVGO", avgo);

        // ② 型態二：比值 10 與該標的眾數一致 → 只計較小值（已還原基準）一次
        assertThat(resolved.resolved().get(LocalDate.of(2023, 6, 21))).isEqualByComparingTo("0.460000");
        assertThat(resolved.resolved().get(LocalDate.of(2017, 12, 18))).isEqualByComparingTo("0.175000");
        // ③ 比值 5 與眾數 10 不一致 → fail closed，該除息日不可解
        assertThat(resolved.unresolved()).contains(LocalDate.of(2018, 3, 21), LocalDate.of(2018, 6, 19));
        assertThat(resolved.resolved()).doesNotContainKey(LocalDate.of(2018, 3, 21));

        // 不可解的除息日落在視窗內 → 該交易日的殖利率一律 null（少算，不混基準）
        assertThat(UsValuationDerivationService.dividendYieldPct(
                LocalDate.of(2018, 4, 2), bd("100.00"), resolved)).isNull();
        // 視窗滑過該日之後才恢復計算
        assertThat(UsValuationDerivationService.dividendYieldPct(
                LocalDate.of(2023, 6, 30), bd("100.00"), resolved)).isEqualByComparingTo("0.4600");
    }

    /**
     * (d2 補) 眾數只有<b>一個</b>除息日支持時不得自我驗證。該比值必然等於眾數本身、相對距離恆為 0，
     * 不要求最低支持票數就會無條件通過，於是同日兩筆<b>合法但不同金額</b>的分配（常配 ＋ 同日特別配）
     * 被誤判成「每股基準重複」而只計較小者，殖利率少算特別配且沒有任何告警——與規則 3 白紙黑字的
     * fail-closed 取捨（「寧可缺值也不要混基準」）方向相反。
     */
    @Test
    void aSplitRatioModeBackedByASingleExDateMustNotSelfValidate() {
        UsValuationDerivationService.DividendSeries series =
                UsValuationDerivationService.resolveDividends("MSFT", List.of(
                        dividend("2025-02-20", "0.910000"),   // 常配
                        dividend("2025-02-20", "3.000000"),   // 同日特別配，比值 3.30（全歷史唯一一組）
                        dividend("2025-05-15", "0.910000")));

        assertThat(series.resolved()).doesNotContainKey(LocalDate.of(2025, 2, 20));
        assertThat(series.unresolved()).contains(LocalDate.of(2025, 2, 20));
        // 不可解的除息日落在視窗內 → 該交易日殖利率為 null（少算，不冒險採較小者）
        assertThat(UsValuationDerivationService.dividendYieldPct(
                LocalDate.of(2025, 6, 2), bd("100.00"), series)).isNull();
    }

    /**
     * (d 補) 殖利率的<b>落地接線</b>：{@code derive()} → {@code buildRow()} 這一段若把殖利率寫死成 null，
     * 只測 {@code resolveDividends}／{@code dividendYieldPct} 兩支 helper 的案例全部照樣通過（所有既有
     * 端到端案例的股利清單都是空的，每一天的殖利率必為 null，與寫死不可區分）。這一案餵非空股利清單。
     */
    @Test
    void derivedRowsCarryTheDividendYieldFromTheResolvedSeries() {
        List<StockSourceQuery.ClosePoint> prices = closes("2025-01-20", "2025-05-09", "100.00");
        List<StockFundamentalFetchClient.Valuation> rows = UsValuationDerivationService.derive(
                "MSFT", growingQuarters(), prices, List.of(
                        dividend("2024-05-15", "0.75"), dividend("2024-08-15", "0.75"),
                        dividend("2024-11-21", "0.83"), dividend("2025-02-20", "0.83")));

        // 2025-03-03 回看 365 日涵蓋四筆季配 3.16，收盤 100 → 3.16%
        assertThat(on(rows, "2025-03-03").dividendYieldPct()).isEqualByComparingTo("3.1600");

        // 查無任何除息紀錄的標的：該欄必須是 null，不得寫 0 冒充「不配息」
        List<StockFundamentalFetchClient.Valuation> none =
                UsValuationDerivationService.derive("AMZN", growingQuarters(), prices, List.of());
        assertThat(on(none, "2025-03-03")).isNotNull();
        assertThat(on(none, "2025-03-03").dividendYieldPct()).isNull();

        // 視窗內有不可解的除息日：該列仍落地，但殖利率為 null（fail closed）
        List<StockFundamentalFetchClient.Valuation> unresolved = UsValuationDerivationService.derive(
                "XYZ", growingQuarters(), prices, List.of(
                        dividend("2025-02-20", "0.50"), dividend("2025-02-20", "2.00")));
        assertThat(on(unresolved, "2025-03-03")).isNotNull();
        assertThat(on(unresolved, "2025-03-03").dividendYieldPct()).isNull();
        assertThat(on(unresolved, "2025-03-03").peRatio()).isEqualByComparingTo("25.0000");
    }

    // ── (e) PB ─────────────────────────────────────────────────────────────────

    @Test
    void priceToBookUsesUsdWithoutThousandConversionAndFailsClosedOnUnusableInputs() {
        // 推導股數＝40 億 USD ÷ 4.00 ＝ 10 億股；每股淨值＝2,000 億 ÷ 10 億 ＝ 200；PB ＝ 100 / 200
        StockFundamentalFetchClient.Valuation row = UsValuationDerivationService.buildRow(
                "GOOGL", LocalDate.of(2025, 6, 2), bd("100.00"),
                bd("4.00"), bd("4000000000"), EQUITY, null, filed("2025-04-24"), List.of(COMPANY_FACTS_URL));
        assertThat(row.pbRatio()).isEqualByComparingTo("0.5000");
        // 若誤做千元換算會得到 0.0005 或 500，兩者都不得出現
        assertThat(row.pbRatio()).isNotEqualByComparingTo("0.0005");
        assertThat(row.pbRatio()).isNotEqualByComparingTo("500");

        assertThat(UsValuationDerivationService.buildRow("GOOGL", LocalDate.of(2025, 6, 2), bd("100.00"),
                bd("4.00"), bd("4000000000"), null, null, filed("2025-04-24"), List.of()).pbRatio()).isNull();
        assertThat(UsValuationDerivationService.buildRow("GOOGL", LocalDate.of(2025, 6, 2), bd("100.00"),
                bd("0.00"), bd("4000000000"), EQUITY, null, filed("2025-04-24"), List.of()).pbRatio()).isNull();
        // 母公司權益為負 → 每股淨值 ≤ 0 → null（不得算出負 PB 混進分位）
        assertThat(UsValuationDerivationService.buildRow("GOOGL", LocalDate.of(2025, 6, 2), bd("100.00"),
                bd("4.00"), bd("4000000000"), -EQUITY, null, filed("2025-04-24"), List.of()).pbRatio()).isNull();
        // TTM 淨利不可得 → 推導股數不可得 → null
        assertThat(UsValuationDerivationService.buildRow("GOOGL", LocalDate.of(2025, 6, 2), bd("100.00"),
                bd("4.00"), null, EQUITY, null, filed("2025-04-24"), List.of()).pbRatio()).isNull();
    }

    // ── (f) 落地欄位 ───────────────────────────────────────────────────────────

    @Test
    void landedRowsCarryDerivedProviderBasisAndUncollapsedAvailability() {
        List<StockFundamentalFetchClient.Valuation> rows = UsValuationDerivationService.derive(
                "MSFT", pathologicalFiledQuarters(), closes("2024-10-01", "2026-08-14", "100.00"), List.of());

        StockFundamentalFetchClient.Valuation row = on(rows, "2025-06-02");
        assertThat(row.provider()).isEqualTo(StockFundamentalFetchClient.SEC_DERIVED);
        assertThat(row.availabilityBasis()).isEqualTo(UsValuationDerivationService.AVAILABILITY_BASIS);
        assertThat(row.market()).isEqualTo("美股");
        assertThat(row.sourceUrls())
                .contains(COMPANY_FACTS_URL, UsValuationDerivationService.DERIVED_URL_MARKER);

        // MSFT 三個 Q4 的 raw filed 都是 2026-07-29；落地值不得塌成那一天，
        // 否則任何過去決策時點的 walk-forward／holdout 都會整組取不到這些列。
        Instant collapsed = filed("2026-07-29");
        assertThat(row.sourceAvailableAt()).isBefore(collapsed);
        assertThat(row.sourceAvailableAt()).isEqualTo(
                UsValuationDerivationService.marketCloseInstant(LocalDate.of(2025, 6, 2)));
        assertThat(rows.stream().map(StockFundamentalFetchClient.Valuation::sourceAvailableAt).distinct().count())
                .isEqualTo(rows.size());
    }

    // ── (g) 重跑冪等 ───────────────────────────────────────────────────────────

    /**
     * 同一區間跑兩次，第二次 {@code append} 新增列數為 0。
     * {@code stock_valuation_daily} 沒有任何 unique index，冪等完全靠
     * {@code appendValuation} 的 {@code same(...)} 比對——推導輸出只要有一絲不決定性就會每輪多插一列。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rerunningTheSameDerivationAppendsNothingTheSecondTime() {
        List<StockFundamentalFetchClient.Valuation> first = UsValuationDerivationService.derive(
                "AMZN", growingQuarters(), closes("2025-01-20", "2025-05-09", "100.00"), List.of());
        List<StockFundamentalFetchClient.Valuation> second = UsValuationDerivationService.derive(
                "AMZN", growingQuarters(), closes("2025-01-20", "2025-05-09", "100.00"), List.of());
        assertThat(second).isEqualTo(first);

        StockFundamentalFetchClient.Bundle bundle = new StockFundamentalFetchClient.Bundle(
                List.of(first.get(first.size() - 1)), List.of(), List.of());

        JdbcTemplate empty = mock(JdbcTemplate.class);
        when(empty.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> null);
        when(empty.update(anyString(), any(Object[].class))).thenReturn(1);
        assertThat(new FundamentalObservationStore(empty, new ObjectMapper()).append(bundle).valuations())
                .isOne();

        ArgumentCaptor<Object[]> inserted = ArgumentCaptor.forClass(Object[].class);
        verify(empty).update(anyString(), inserted.capture());

        JdbcTemplate populated = mock(JdbcTemplate.class);
        when(populated.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
                .thenAnswer(invocation -> ((ResultSetExtractor) invocation.getArgument(2))
                        .extractData(landedRow(inserted.getValue())));
        assertThat(new FundamentalObservationStore(populated, new ObjectMapper()).append(bundle).valuations())
                .isZero();
    }

    // ── deriveForCode 落地路徑（刪除重寫、重算窗、缺口過濾）─────────────────────

    /**
     * 334.4(b)「可用區段起點後移 → 刪除重寫」的正向案：新起點晚於現有序列的 {@code min(trading_date)}。
     * 這條是本任務<b>唯一允許 DELETE</b> 的路徑，拿掉它不會有任何測試變紅，故必須有測試釘住。
     */
    @Test
    void deriveForCodeDeletesAndRewritesWhenTheUsableSegmentStartMovesForward() {
        FundamentalObservationStore store = mock(FundamentalObservationStore.class);
        StockSourceQuery stockSource = mock(StockSourceQuery.class);
        DividendHistoryQuery dividendHistory = mock(DividendHistoryQuery.class);
        when(store.secEdgarQuarters("NVDA")).thenReturn(splitQuarters("0.10"));
        when(stockSource.loadAllCloses("NVDA", US_MARKET))
                .thenReturn(closes("2024-06-03", "2026-08-14", "100.00"));
        when(dividendHistory.activeCashDividends("NVDA", US_MARKET)).thenReturn(List.of());
        // 現有序列從分割之前就開始（＝舊基準），本輪新起點是 2026-01-30
        when(store.earliestDerivedValuationDate("NVDA")).thenReturn(LocalDate.of(2024, 6, 3));
        when(store.deleteDerivedValuationSeries("NVDA")).thenReturn(402);
        when(store.append(any())).thenReturn(new FundamentalObservationStore.WriteCount(140, 0, 0, 0));

        UsValuationDerivationService.CodeResult result = new UsValuationDerivationService(
                store, stockSource, dividendHistory).deriveForCode("NVDA");

        verify(store).deleteDerivedValuationSeries("NVDA");
        assertThat(result.deleted()).isEqualTo(402);
        // 刪除後不得再以「已落地日期」過濾，否則整段舊基準區間永遠補不回來
        verify(store, never()).derivedValuationDates(anyString());
        ArgumentCaptor<StockFundamentalFetchClient.Bundle> written =
                ArgumentCaptor.forClass(StockFundamentalFetchClient.Bundle.class);
        verify(store).append(written.capture());
        assertThat(written.getValue().valuations().get(0).tradingDate())
                .isEqualTo(LocalDate.of(2026, 1, 30));
    }

    /**
     * 起點未變時<b>不得</b>刪除；送進 {@code append} 的必須恰為「未落地日 ∪ 最後
     * {@value UsValuationDerivationService#RECOMPUTE_TRADING_DAYS} 個交易日」。
     * 重算窗存在的理由是收盤價會被 18:00 ET 的 FinMind 校正事後覆寫，寫成「只重算一天」收盤校正就補不回來。
     */
    @Test
    void deriveForCodeKeepsExistingRowsAndAlwaysRecomputesTheTrailingWindow() {
        FundamentalObservationStore store = mock(FundamentalObservationStore.class);
        StockSourceQuery stockSource = mock(StockSourceQuery.class);
        DividendHistoryQuery dividendHistory = mock(DividendHistoryQuery.class);
        when(store.secEdgarQuarters("AMZN")).thenReturn(growingQuarters());
        when(stockSource.loadAllCloses("AMZN", US_MARKET))
                .thenReturn(closes("2025-01-20", "2025-05-09", "100.00"));
        when(dividendHistory.activeCashDividends("AMZN", US_MARKET)).thenReturn(List.of());

        List<StockFundamentalFetchClient.Valuation> series = UsValuationDerivationService.derive(
                "AMZN", growingQuarters(), closes("2025-01-20", "2025-05-09", "100.00"), List.of());
        List<LocalDate> allDays = series.stream()
                .map(StockFundamentalFetchClient.Valuation::tradingDate).toList();
        LocalDate hole = allDays.get(3);
        Set<LocalDate> landed = new HashSet<>(allDays);
        landed.remove(hole);

        when(store.earliestDerivedValuationDate("AMZN")).thenReturn(allDays.get(0));
        when(store.derivedValuationDates("AMZN")).thenReturn(landed);
        when(store.append(any())).thenReturn(new FundamentalObservationStore.WriteCount(1, 0, 0, 0));

        new UsValuationDerivationService(store, stockSource, dividendHistory).deriveForCode("AMZN");

        verify(store, never()).deleteDerivedValuationSeries(anyString());
        ArgumentCaptor<StockFundamentalFetchClient.Bundle> written =
                ArgumentCaptor.forClass(StockFundamentalFetchClient.Bundle.class);
        verify(store).append(written.capture());
        List<LocalDate> pending = written.getValue().valuations().stream()
                .map(StockFundamentalFetchClient.Valuation::tradingDate).toList();
        List<LocalDate> expected = new ArrayList<>(
                allDays.subList(allDays.size() - UsValuationDerivationService.RECOMPUTE_TRADING_DAYS,
                        allDays.size()));
        expected.add(0, hole);
        assertThat(pending).containsExactlyElementsOf(expected);
    }

    /**
     * 分割剛發生的那約四個季度：閘門已經偵測到基準變動，但新基準湊不出四季視窗 → 本輪序列為空。
     * 若在「序列為空」就直接 return，已落地的整段<b>舊基準</b>序列會原地留存約四個季度，而它已經是混基準
     * 的（價格已還原、EPS 還沒）——最新端 PE 被壓低 k 倍、分位逼近 0、contribution ≈ +1。
     * 起點比較那條規則永遠到不了（它在 return 之後），故必須在這裡就刪。
     */
    @Test
    void deriveForCodeDeletesTheOldBasisSeriesEvenWhenTheNewBasisIsStillTooShort() {
        List<FundamentalObservationStore.QuarterFact> justSplit = splitQuarters("0.10").stream()
                .filter(q -> q.fiscalYear() == 2024 || q.fiscalQuarter() == 1)
                .toList();
        FundamentalObservationStore store = mock(FundamentalObservationStore.class);
        StockSourceQuery stockSource = mock(StockSourceQuery.class);
        DividendHistoryQuery dividendHistory = mock(DividendHistoryQuery.class);
        when(store.secEdgarQuarters("NVDA")).thenReturn(justSplit);
        when(stockSource.loadAllCloses("NVDA", US_MARKET))
                .thenReturn(closes("2024-06-03", "2025-06-30", "100.00"));
        when(dividendHistory.activeCashDividends("NVDA", US_MARKET)).thenReturn(List.of());
        when(store.deleteDerivedValuationSeries("NVDA")).thenReturn(402);

        UsValuationDerivationService.CodeResult result = new UsValuationDerivationService(
                store, stockSource, dividendHistory).deriveForCode("NVDA");

        assertThat(UsValuationDerivationService.derive(
                "NVDA", justSplit, closes("2024-06-03", "2025-06-30", "100.00"), List.of())).isEmpty();
        verify(store).deleteDerivedValuationSeries("NVDA");
        assertThat(result.deleted()).isEqualTo(402);
        assertThat(result.written()).isZero();
        verify(store, never()).append(any());
    }

    /**
     * 反向案：序列為空但<b>不是</b>基準變動（最新季 {@code net_income_parent} 為 null，＝上游暫時缺欄）。
     * 這種情形必須維持保守不刪，否則一次上游抖動就把整條歷史砍掉。
     */
    @Test
    void deriveForCodeKeepsTheSeriesWhenTheEmptyResultIsMerelyMissingUpstreamData() {
        List<FundamentalObservationStore.QuarterFact> missingNewestIncome = twoYearsFlatQuarters().stream()
                .map(q -> q.fiscalYear() == 2025 && q.fiscalQuarter() == 4
                        ? new FundamentalObservationStore.QuarterFact(q.fiscalYear(), q.fiscalQuarter(),
                                q.cumulativeEps(), null, q.equityParent(),
                                q.sourceAvailableAt(), q.sourceUrls())
                        : q)
                .toList();
        FundamentalObservationStore store = mock(FundamentalObservationStore.class);
        StockSourceQuery stockSource = mock(StockSourceQuery.class);
        DividendHistoryQuery dividendHistory = mock(DividendHistoryQuery.class);
        when(store.secEdgarQuarters("AVGO")).thenReturn(missingNewestIncome);
        when(stockSource.loadAllCloses("AVGO", US_MARKET))
                .thenReturn(closes("2024-11-01", "2026-03-06", "100.00"));
        when(dividendHistory.activeCashDividends("AVGO", US_MARKET)).thenReturn(List.of());

        UsValuationDerivationService.CodeResult result = new UsValuationDerivationService(
                store, stockSource, dividendHistory).deriveForCode("AVGO");

        verify(store, never()).deleteDerivedValuationSeries(anyString());
        verify(store, never()).append(any());
        assertThat(result.deleted()).isZero();
        assertThat(result.skippedReason()).isNotNull();
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    /** 2024Q1–Q4 單季 EPS 各 1.00、2025Q1 單季 2.00；推導股數恆為 10 億股（無基準變動）。 */
    private static List<FundamentalObservationStore.QuarterFact> growingQuarters() {
        return List.of(
                quarter(2024, 1, "1.00", ONE_BILLION, "2024-04-25"),
                quarter(2024, 2, "2.00", 2 * ONE_BILLION, "2024-07-25"),
                quarter(2024, 3, "3.00", 3 * ONE_BILLION, "2024-10-24"),
                quarter(2024, 4, "4.00", 4 * ONE_BILLION, "2025-01-30"),
                quarter(2025, 1, "2.00", 2 * ONE_BILLION, "2025-04-24"));
    }

    /** 2024Q1–2025Q4 單季 EPS 全為 1.00、單季淨利全為 10 億，推導股數恆為 10 億股。 */
    private static List<FundamentalObservationStore.QuarterFact> twoYearsFlatQuarters() {
        return List.of(
                quarter(2024, 1, "1.00", ONE_BILLION, "2024-04-25"),
                quarter(2024, 2, "2.00", 2 * ONE_BILLION, "2024-07-25"),
                quarter(2024, 3, "3.00", 3 * ONE_BILLION, "2024-10-24"),
                quarter(2024, 4, "4.00", 4 * ONE_BILLION, "2025-01-30"),
                quarter(2025, 1, "1.00", ONE_BILLION, "2025-04-24"),
                quarter(2025, 2, "2.00", 2 * ONE_BILLION, "2025-07-24"),
                quarter(2025, 3, "3.00", 3 * ONE_BILLION, "2025-10-23"),
                quarter(2025, 4, "4.00", 4 * ONE_BILLION, "2026-01-29"));
    }

    /**
     * 2025 年初發生一次股票分割：2024 年單季 EPS 1.00、2025 年單季 EPS 為 {@code postSplitEps}，
     * 單季淨利固定 10 億（淨利不隨分割改變、EPS 會，正是單季口徑能一次命中的原因）。
     */
    private static List<FundamentalObservationStore.QuarterFact> splitQuarters(String postSplitEps) {
        BigDecimal eps = bd(postSplitEps);
        return List.of(
                quarter(2024, 1, "1.00", ONE_BILLION, "2024-04-25"),
                quarter(2024, 2, "2.00", 2 * ONE_BILLION, "2024-07-25"),
                quarter(2024, 3, "3.00", 3 * ONE_BILLION, "2024-10-24"),
                quarter(2024, 4, "4.00", 4 * ONE_BILLION, "2025-01-30"),
                quarter(2025, 1, eps.toPlainString(), ONE_BILLION, "2025-04-24"),
                quarter(2025, 2, eps.multiply(bd("2")).toPlainString(), 2 * ONE_BILLION, "2025-07-24"),
                quarter(2025, 3, eps.multiply(bd("3")).toPlainString(), 3 * ONE_BILLION, "2025-10-23"),
                quarter(2025, 4, eps.multiply(bd("4")).toPlainString(), 4 * ONE_BILLION, "2026-01-29"));
    }

    /**
     * 2024Q3 單季 EPS 為 −0.50（單季淨利仍為正）的年度：閘門的 fail-closed 條款 (iii) 必須在此截斷可用
     * 區段。刻意同時調整 2024Q4 的累計值，讓 2024Q4 的單季值回到 1.00／10 億，把「負值那一季」以外的
     * 干擾排除——否則截斷點會變成比值超標（那是另一條條款）而測不到本案。
     */
    private static List<FundamentalObservationStore.QuarterFact> lossQuarterInTheMiddle() {
        return List.of(
                quarter(2024, 1, "1.00", ONE_BILLION, "2024-04-25"),
                quarter(2024, 2, "2.00", 2 * ONE_BILLION, "2024-07-25"),
                quarter(2024, 3, "1.50", 3 * ONE_BILLION, "2024-10-24"),
                quarter(2024, 4, "2.50", 4 * ONE_BILLION, "2025-01-30"),
                quarter(2025, 1, "1.00", ONE_BILLION, "2025-04-24"),
                quarter(2025, 2, "2.00", 2 * ONE_BILLION, "2025-07-24"),
                quarter(2025, 3, "3.00", 3 * ONE_BILLION, "2025-10-23"),
                quarter(2025, 4, "4.00", 4 * ONE_BILLION, "2026-01-29"));
    }

    /**
     * 實測型的病態 filed：2024Q4／2025Q4／2026Q2 共用 {@code 2026-07-29}（同一份含三年比較欄的 10-K），
     * 2025Q2 的 {@code 2026-07-31} 甚至晚於 2025Q3 的 {@code 2025-10-31}（非單調）。
     * 單季 EPS 逐季 +0.10、單季淨利同步放大，推導股數恆為 10 億股。
     *
     * <p>2022–2023 八季的 filed 是乾淨的（各自季末後約一個月），目的是讓病態批次事件發生之前<b>已經有
     * 落地列</b>可以比較——只給 2024 年起的季別時，第一個批次事件必然落在序列開頭，任何「單日推進量」
     * 的斷言都測不到它。</p>
     */
    private static List<FundamentalObservationStore.QuarterFact> pathologicalFiledQuarters() {
        return List.of(
                quarter(2022, 1, "0.20", 200_000_000L, "2022-04-26"),
                quarter(2022, 2, "0.50", 500_000_000L, "2022-07-26"),
                quarter(2022, 3, "0.90", 900_000_000L, "2022-10-25"),
                quarter(2022, 4, "1.40", 1_400_000_000L, "2023-01-31"),
                quarter(2023, 1, "0.60", 600_000_000L, "2023-04-25"),
                quarter(2023, 2, "1.30", 1_300_000_000L, "2023-07-25"),
                quarter(2023, 3, "2.10", 2_100_000_000L, "2023-10-24"),
                quarter(2023, 4, "3.00", 3_000_000_000L, "2024-01-30"),
                quarter(2024, 1, "1.00", 1_000_000_000L, "2024-04-25"),
                quarter(2024, 2, "2.10", 2_100_000_000L, "2024-07-25"),
                quarter(2024, 3, "3.30", 3_300_000_000L, "2024-10-24"),
                quarter(2024, 4, "4.60", 4_600_000_000L, "2026-07-29"),
                quarter(2025, 1, "1.40", 1_400_000_000L, "2025-04-24"),
                quarter(2025, 2, "2.90", 2_900_000_000L, "2026-07-31"),
                quarter(2025, 3, "4.50", 4_500_000_000L, "2025-10-31"),
                quarter(2025, 4, "6.20", 6_200_000_000L, "2026-07-29"),
                quarter(2026, 1, "1.80", 1_800_000_000L, "2026-04-23"),
                quarter(2026, 2, "3.70", 3_700_000_000L, "2026-07-29"));
    }

    private static FundamentalObservationStore.QuarterFact quarter(
            int year, int quarter, String cumulativeEps, Long cumulativeIncome, String filedDate) {
        return new FundamentalObservationStore.QuarterFact(year, quarter,
                cumulativeEps == null ? null : bd(cumulativeEps), cumulativeIncome, EQUITY,
                filed(filedDate), List.of(COMPANY_FACTS_URL));
    }

    private static DividendHistoryQuery.CashDividendEvent dividend(String exDate, String amount) {
        // 實查結果：同一除息日的兩列 event_key 是逐列不同的雜湊、source 兩列完全相同，
        // 兩欄都無法確定性區分重複列，故比值規則是實際採用的路徑。
        return new DividendHistoryQuery.CashDividendEvent(
                LocalDate.parse(exDate), bd(amount), "hash-" + exDate + "-" + amount, "NASDAQ+Yahoo Finance");
    }

    private static List<StockSourceQuery.ClosePoint> closes(String from, String to, String price) {
        List<StockSourceQuery.ClosePoint> rows = new ArrayList<>();
        LocalDate end = LocalDate.parse(to);
        for (LocalDate day = LocalDate.parse(from); !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            rows.add(new StockSourceQuery.ClosePoint(day, bd(price)));
        }
        return rows;
    }

    private static Map<Integer, Instant> effectiveAvailableAt(
            List<FundamentalObservationStore.QuarterFact> quarters) {
        LinkedHashMap<Integer, FundamentalObservationStore.QuarterFact> byPeriod = new LinkedHashMap<>();
        quarters.stream()
                .sorted(Comparator.<FundamentalObservationStore.QuarterFact>comparingInt(
                        q -> UsValuationDerivationService.periodIndex(q.fiscalYear(), q.fiscalQuarter()))
                        .reversed())
                .forEach(q -> byPeriod.putIfAbsent(
                        UsValuationDerivationService.periodIndex(q.fiscalYear(), q.fiscalQuarter()), q));
        return UsValuationDerivationService.effectiveAvailableAt(byPeriod, List.copyOf(byPeriod.keySet()));
    }

    /** 未經單調化的原始可見時點，供對照組使用。 */
    private static Map<Integer, Instant> rawAvailableAt(
            List<FundamentalObservationStore.QuarterFact> quarters) {
        Map<Integer, Instant> raw = new LinkedHashMap<>();
        quarters.forEach(q -> raw.put(
                UsValuationDerivationService.periodIndex(q.fiscalYear(), q.fiscalQuarter()),
                q.sourceAvailableAt()));
        return raw;
    }

    /**
     * 逐交易日重建「TTM 視窗頂端期別」：只收錄當日能湊出四個<b>連續</b>可見季度的交易日。
     * 與 {@code derive} 用同一組判準（可見性以當日美股收盤時刻為界、視窗取最新四季且必須連續）。
     */
    private static Map<LocalDate, Integer> windowTopByDay(
            Map<Integer, Instant> availability, List<StockSourceQuery.ClosePoint> prices) {
        Map<LocalDate, Integer> tops = new LinkedHashMap<>();
        for (StockSourceQuery.ClosePoint point : prices) {
            Instant close = UsValuationDerivationService.marketCloseInstant(point.date());
            List<Integer> visible = availability.entrySet().stream()
                    .filter(entry -> entry.getValue() != null && !entry.getValue().isAfter(close))
                    .map(Map.Entry::getKey)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            if (visible.size() < 4 || visible.get(0) - visible.get(3) != 3) continue;
            tops.put(point.date(), visible.get(0));
        }
        return tops;
    }

    /** 落地序列中相鄰交易日 PE 的最大倍數變化；分割污染的症狀就是這個值出現倍數級跳階。 */
    private static BigDecimal maxAdjacentPeRatio(List<StockFundamentalFetchClient.Valuation> rows) {
        BigDecimal max = BigDecimal.ONE;
        for (int i = 1; i < rows.size(); i++) {
            BigDecimal previous = rows.get(i - 1).peRatio();
            BigDecimal current = rows.get(i).peRatio();
            if (previous == null || current == null
                    || previous.signum() <= 0 || current.signum() <= 0) continue;
            BigDecimal ratio = current.compareTo(previous) >= 0
                    ? current.divide(previous, 6, RoundingMode.HALF_UP)
                    : previous.divide(current, 6, RoundingMode.HALF_UP);
            max = max.max(ratio);
        }
        return max;
    }

    private static StockFundamentalFetchClient.Valuation on(
            List<StockFundamentalFetchClient.Valuation> rows, String date) {
        LocalDate day = LocalDate.parse(date);
        return rows.stream().filter(row -> row.tradingDate().equals(day)).findFirst().orElse(null);
    }

    /**
     * SEC {@code filed} 只有日期精度，寫入端一律換算為<b>申報日 16:30 America/New_York</b>（收盤之後），
     * 故該季從申報日的<b>下一個</b>交易日起才可見。這裡必須與
     * {@code StockFundamentalFetchClient.filedInstant} 用同一個換算，否則測到的是不存在的可見時序。
     */
    private static Instant filed(String isoDate) {
        return LocalDate.parse(isoDate).atTime(LocalTime.of(16, 30)).atZone(US_EXCHANGE_ZONE).toInstant();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** 以第一輪 INSERT 的參數重建「DB 已存在的最新一列」，供第二輪 append 做同值比對。 */
    private static ResultSet landedRow(Object[] insertArguments) {
        try {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getBigDecimal(1)).thenReturn((BigDecimal) insertArguments[3]);
            when(rs.getBigDecimal(2)).thenReturn((BigDecimal) insertArguments[4]);
            when(rs.getBigDecimal(3)).thenReturn((BigDecimal) insertArguments[5]);
            when(rs.getObject(4)).thenReturn(insertArguments[6]);
            when(rs.getString(5)).thenReturn((String) insertArguments[8]);
            when(rs.getTimestamp(6)).thenReturn((Timestamp) insertArguments[9]);
            when(rs.getString(7)).thenReturn((String) insertArguments[10]);
            return rs;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
