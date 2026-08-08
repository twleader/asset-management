package com.steven.assets.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.News;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 個股基本面與產業因子的唯一 as-of resolver（Task 292）。
 *
 * <p>production 與回測都必須傳入顯式 {@code decisionInstant}。production 單次查詢直接限制兩個
 * as-of 時點；回測則只查到該檔最晚決策日，再於記憶體對每個歷史日套用
 * {@code source_available_at <= decisionInstant AND observed_at <= decisionInstant}、選來源順位與
 * 當時最新 revision。多期因子全部來自同一 provider family，禁止把交易所 EPS 與 FinMind 權益
 * 拼成 ROE，也不會讓事後修正版倒灌過去回測。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FundamentalAnalysisService {

    private static final String TW_MARKET = "台股";
    private static final String US_MARKET = "美股";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final int VALUATION_MAX_AGE_DAYS = 10;
    private static final int PE_MIN_SAMPLES = 250;
    // SEC_EDGAR 插在 EXCHANGE 之後、YAHOO 之前：與 EXCHANGE 同屬「官方一手資料」優先序，只是分屬不同市場
    // （EXCHANGE 只會出現在台股列、SEC_EDGAR 只會出現在美股列，兩者不會同時對同一標的出現，插入順序
    // 不影響台股既有行為）。firstProviderValue() 是通用方法，不需要為市場另建第二份清單。
    private static final List<String> PROVIDERS = List.of("EXCHANGE", "SEC_EDGAR", "YAHOO", "WANTGOO", "FINMIND");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PublicInfoEvidenceResolver publicInfo;

    public record Resolved(
            TradingRadarRuleEngine.FundamentalInput input,
            TradingRadarDto.FundamentalSnapshot snapshot) {
        public static Resolved unavailable(boolean applicable) {
            return unavailable(applicable, PublicInfoEvidenceResolver.Evidence.EMPTY);
        }

        public static Resolved unavailable(
                boolean applicable, PublicInfoEvidenceResolver.Evidence evidence) {
            TradingRadarRuleEngine.FundamentalInput input = new TradingRadarRuleEngine.FundamentalInput(
                    applicable, null, null, null, null, null, false);
            TradingRadarDto.FundamentalSnapshot snapshot = new TradingRadarDto.FundamentalSnapshot(
                    applicable, 0, null, null, null, null, null,
                    null, List.of(), null, null, List.of(), null,
                    null, List.of(), null, null, List.of(), null,
                    null, null, null, null, null, List.of(), null,
                    evidence == null ? List.of() : evidence.company(),
                    evidence == null ? List.of() : evidence.industry());
            return new Resolved(input, snapshot);
        }
    }

    public Resolved resolve(String stockCode, String stockName, String market, Instant decisionInstant) {
        if (stockCode == null || decisionInstant == null
                || !Set.of(TW_MARKET, US_MARKET).contains(market)) {
            return Resolved.unavailable(false);
        }
        if (isEtf(stockCode, market)) return Resolved.unavailable(false);

        // public_info_* 是原文證據第一順位；先載入一次，個股與產業比對共用同一快照。
        List<News> evidenceRows;
        PublicInfoEvidenceResolver.Evidence initialEvidence;
        try {
            evidenceRows = publicInfo.loadForEarliestDecision(decisionInstant);
            initialEvidence = publicInfo.resolveFromRows(
                    stockCode, stockName, null, decisionInstant, evidenceRows);
        } catch (Exception e) {
            log.warn("public_info 個股證據讀取失敗（{}）：{}", stockCode, e.getMessage());
            evidenceRows = List.of();
            initialEvidence = PublicInfoEvidenceResolver.Evidence.EMPTY;
        }

        try {
            PreparedData data = loadPreparedData(stockCode, market, decisionInstant);
            return resolvePrepared(stockCode, stockName, decisionInstant, data, evidenceRows);
        } catch (Exception e) {
            // 個別基本面資料失敗不得拖垮技術面；optional 權重會在規則引擎中重分配。
            log.warn("基本面 as-of 解析失敗（{}）：{}", stockCode, e.getMessage());
            return Resolved.unavailable(true, initialEvidence);
        }
    }

    /**
     * 回測專用批次入口：每檔股票只載入四張 observation 表一次，再於記憶體依每個決策日套用
     * {@code source_available_at}/{@code observed_at} 與 revision 選擇。公開資訊只影響揭露、不影響分數，
     * 回測不建立畫面 snapshot，因而不掃多年新聞。這避免原本每股票×每日 4 次 SQL 加新聞查詢的 N+1。
     */
    public Map<Instant, TradingRadarRuleEngine.FundamentalInput> resolveInputsForBacktest(
            String stockCode, String market, List<Instant> decisionInstants) {
        List<Instant> instants = decisionInstants == null ? List.of() : decisionInstants.stream()
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (instants.isEmpty()) return Map.of();
        // 只替換市場判斷子句：stockCode != null 與 !isEtf(...) 兩個子句原樣保留，這是與 resolve() 獨立的
        // 第二道市場閘門，若整句改成只剩市場白名單檢查，會連帶丟掉 null 檢查與 ETF 排除。
        boolean applicable = stockCode != null && Set.of(TW_MARKET, US_MARKET).contains(market)
                && !isEtf(stockCode, market);
        if (!applicable) return unavailableInputs(instants, false);
        try {
            PreparedData data = loadPreparedData(stockCode, market, instants.get(instants.size() - 1));
            Map<Instant, TradingRadarRuleEngine.FundamentalInput> result = new LinkedHashMap<>();
            for (Instant instant : instants) {
                result.put(instant, resolvePrepared(
                        stockCode, stockCode, instant, data, List.of()).input());
            }
            return Map.copyOf(result);
        } catch (Exception e) {
            log.warn("基本面回測批次解析失敗（{}）：{}", stockCode, e.getMessage());
            return unavailableInputs(instants, true);
        }
    }

    private Map<Instant, TradingRadarRuleEngine.FundamentalInput> unavailableInputs(
            List<Instant> instants, boolean applicable) {
        Map<Instant, TradingRadarRuleEngine.FundamentalInput> result = new LinkedHashMap<>();
        TradingRadarRuleEngine.FundamentalInput input = Resolved.unavailable(applicable).input();
        instants.forEach(instant -> result.put(instant, input));
        return Map.copyOf(result);
    }

    private Resolved resolvePrepared(
            String stockCode,
            String stockName,
            Instant decisionInstant,
            PreparedData data,
            List<News> evidenceRows) {
        LocalDate decisionDate = decisionInstant.atZone(TAIPEI).toLocalDate();
        List<FinancialRow> financialRows = latestAsOf(
                data.financials(), decisionInstant,
                row -> row.provider() + '|' + row.year() + '|' + row.quarter());
        Factor eps = firstProviderValue(financialRows, FinancialRow::provider,
                rows -> epsFactor(rows.stream().sorted(FinancialRow.DESC).toList(), decisionDate));
        Factor roe = firstProviderValue(financialRows, FinancialRow::provider,
                rows -> roeFactor(rows.stream().sorted(FinancialRow.DESC).toList(), decisionDate));

        List<RevenueRow> revenueRows = latestAsOf(
                data.revenues(), decisionInstant,
                row -> row.provider() + '|' + row.year() + '|' + row.month());
        Factor revenue = firstProviderValue(revenueRows, RevenueRow::provider,
                rows -> revenueFactor(rows.stream().sorted(RevenueRow.DESC).toList(), decisionDate));
        RevenueRow industryBasis = latestRevenueBasis(revenueRows, decisionDate);

        List<ValuationRow> valuationRows = latestAsOf(
                data.valuations(), decisionInstant,
                row -> row.provider() + '|' + row.date());
        Factor pe = peFactor(valuationRows, decisionDate);
        if (pe != null && pe.contribution() != null && pe.contribution() > 0
                && eps != null && eps.contribution() != null && eps.contribution() <= -1.0) {
            pe = pe.withContribution(null);
        }

        List<IndustryRow> industryRows = latestAsOf(
                data.industries(), decisionInstant,
                row -> row.provider() + '|' + row.industryName() + '|' + row.year() + '|' + row.month());
        Factor industry = industryBasis == null ? null : industryFactor(industryBasis, industryRows);
        String industryName = industryBasis == null ? null : industryBasis.industryName();
        PublicInfoEvidenceResolver.Evidence evidence = evidenceRows == null || evidenceRows.isEmpty()
                ? PublicInfoEvidenceResolver.Evidence.EMPTY
                : publicInfo.resolveFromRows(
                        stockCode, stockName, industryName, decisionInstant, evidenceRows);

        int coverage = count(eps, roe, revenue, pe);
        boolean peLoss = pe != null && pe.loss();
        TradingRadarRuleEngine.FundamentalInput input = new TradingRadarRuleEngine.FundamentalInput(
                true, contribution(eps), contribution(roe), contribution(revenue), contribution(pe),
                contribution(industry), peLoss);
        TradingRadarDto.FundamentalSnapshot snapshot = new TradingRadarDto.FundamentalSnapshot(
                true, coverage,
                value(eps), value(roe), value(revenue), value(pe), pe == null ? null : pe.loss(),
                provider(eps), urls(eps), asOf(eps),
                provider(roe), urls(roe), asOf(roe),
                provider(revenue), urls(revenue), asOf(revenue),
                provider(pe), urls(pe), asOf(pe),
                industryName, value(industry), industry == null ? null : industry.companyCount(),
                industry == null ? null : industry.period(), provider(industry), urls(industry), asOf(industry),
                evidence.company(), evidence.industry());
        return new Resolved(input, snapshot);
    }

    private boolean isEtf(String stockCode, String market) {
        if (stockCode.startsWith("00")) return true;
        try {
            Boolean exists = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM etf_nav_history WHERE stock_code=? AND market=?)",
                    Boolean.class, stockCode, market);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            return false;
        }
    }

    private PreparedData loadPreparedData(String code, String market, Instant latestInstant) {
        List<FinancialRow> financials = jdbc.query("""
                SELECT fiscal_year, fiscal_quarter, eps, net_income_parent, equity_parent,
                       provider, source_urls::text, source_available_at, observed_at
                FROM stock_financial_quarter
                WHERE stock_code=? AND market=?
                  AND source_available_at<=? AND observed_at<=?
                """, (rs, ignored) -> financialRow(rs), code, market,
                timestamp(latestInstant), timestamp(latestInstant));
        List<RevenueRow> revenues = jdbc.query("""
                SELECT revenue_year, revenue_month, industry_name, revenue_yoy_pct,
                       provider, source_urls::text, source_available_at, observed_at
                FROM stock_monthly_revenue
                WHERE stock_code=? AND market=?
                  AND source_available_at<=? AND observed_at<=?
                """, (rs, ignored) -> revenueRow(rs), code, market,
                timestamp(latestInstant), timestamp(latestInstant));
        List<ValuationRow> valuations = jdbc.query("""
                SELECT trading_date, pe_ratio, pe_loss_flag, provider,
                       source_urls::text, source_available_at, observed_at
                FROM stock_valuation_daily
                WHERE stock_code=? AND market=?
                  AND source_available_at<=? AND observed_at<=?
                """, (rs, ignored) -> valuationRow(rs), code, market,
                timestamp(latestInstant), timestamp(latestInstant));
        List<IndustryRow> industries = jdbc.query("""
                SELECT i.industry_name, i.revenue_year, i.revenue_month, i.revenue_yoy_pct,
                       i.company_count, i.provider, i.source_urls::text,
                       i.source_available_at, i.observed_at
                FROM industry_monthly_revenue i
                WHERE i.source_available_at<=? AND i.observed_at<=?
                  AND EXISTS (
                      SELECT 1 FROM stock_monthly_revenue s
                      WHERE s.stock_code=? AND s.market=?
                        AND s.industry_name=i.industry_name
                        AND s.source_available_at<=? AND s.observed_at<=?
                  )
                """, (rs, ignored) -> industryRow(rs),
                timestamp(latestInstant), timestamp(latestInstant), code, market,
                timestamp(latestInstant), timestamp(latestInstant));
        return new PreparedData(financials, revenues, valuations, industries);
    }

    static <T extends SourcedRow> List<T> latestAsOf(
            List<T> rows, Instant decisionInstant, Function<T, String> revisionKey) {
        if (rows == null || decisionInstant == null) return List.of();
        Map<String, T> latest = new LinkedHashMap<>();
        for (T row : rows) {
            if (row == null || row.availableAt() == null || row.observedAt() == null
                    || row.availableAt().isAfter(decisionInstant)
                    || row.observedAt().isAfter(decisionInstant)) continue;
            String key = revisionKey.apply(row);
            if (key == null) continue;
            latest.merge(key, row, (left, right) ->
                    left.observedAt().compareTo(right.observedAt()) >= 0 ? left : right);
        }
        return List.copyOf(latest.values());
    }

    private Factor industryFactor(RevenueRow basis, List<IndustryRow> rows) {
        if (basis.industryName() == null || basis.industryName().isBlank()) return null;
        for (String provider : PROVIDERS) {
            IndustryRow row = rows.stream()
                    .filter(v -> provider.equals(v.provider()))
                    .filter(v -> basis.industryName().equals(v.industryName())
                            && basis.year() == v.year() && basis.month() == v.month())
                    .findFirst().orElse(null);
            if (row == null || row.yoy() == null || row.companyCount() < 3) continue;
            return new Factor(row.yoy(), clampUnit(row.yoy().doubleValue() / 15.0), provider,
                    row.urls(), row.availableAt(), false, row.companyCount(), basis.period());
        }
        return null;
    }

    private record PreparedData(
            List<FinancialRow> financials,
            List<RevenueRow> revenues,
            List<ValuationRow> valuations,
            List<IndustryRow> industries) {}

    /*
     * 下面的 factor 計算只接收已依決策日 collapse 過的 observation；不得在此再查 DB，
     * 才能讓 production 單次解析與回測批次解析共用同一套來源順位及公式。
     */
    Factor epsFactor(List<FinancialRow> rows, LocalDate decisionDate) {
        List<FinancialRow> recent = consecutive(rows, 8, FinancialRow::periodIndex);
        if (recent.size() < 8 || !financialFresh(recent.get(0), decisionDate)) return null;
        List<BigDecimal> standalone = recent.stream().map(r -> standalone(r, rows, FinancialRow::eps)).toList();
        BigDecimal yoy = epsYoyPct(standalone);
        if (yoy == null) return null;
        return factor(yoy, clampUnit(yoy.doubleValue() / 20.0), recent);
    }

    Factor roeFactor(List<FinancialRow> rows, LocalDate decisionDate) {
        List<FinancialRow> recent = consecutive(rows, 4, FinancialRow::periodIndex);
        if (recent.size() < 4 || !financialFresh(recent.get(0), decisionDate)
                || recent.get(0).equity() == null || recent.get(0).equity().signum() <= 0) return null;
        List<BigDecimal> standalone = recent.stream()
                .map(r -> standalone(r, rows, FinancialRow::income)).toList();
        BigDecimal roe = approximateRoePct(standalone, recent.get(0).equity());
        if (roe == null) return null;
        return factor(roe, clampUnit((roe.doubleValue() - 10.0) / 5.0), recent);
    }

    Factor revenueFactor(List<RevenueRow> rows, LocalDate decisionDate) {
        List<RevenueRow> recent = consecutive(rows, 3, RevenueRow::periodIndex);
        if (recent.size() < 3 || !revenueFresh(recent.get(0), decisionDate)
                || recent.stream().anyMatch(r -> r.yoy() == null)) return null;
        BigDecimal average = threeMonthAverage(recent.stream().map(RevenueRow::yoy).toList());
        return new Factor(average, clampUnit(average.doubleValue() / 15.0), recent.get(0).provider(),
                mergedUrls(recent), latestAvailable(recent), false, null, recent.get(0).period());
    }

    Factor peFactor(List<ValuationRow> rows, LocalDate decisionDate) {
        for (String provider : PROVIDERS) {
            List<ValuationRow> providerRows = rows.stream()
                    .filter(r -> provider.equals(r.provider())).sorted(ValuationRow.DESC).toList();
            if (providerRows.isEmpty()) continue;
            ValuationRow latest = providerRows.get(0);
            if (!fresh(latest.date(), decisionDate, VALUATION_MAX_AGE_DAYS)) continue;
            if (Boolean.TRUE.equals(latest.loss())) {
                return new Factor(null, -1.0, provider, latest.urls(), latest.availableAt(), true, null,
                        latest.date().toString());
            }
            if (!Boolean.FALSE.equals(latest.loss()) || latest.pe() == null || latest.pe().signum() <= 0) continue;
            List<BigDecimal> history = providerRows.stream().map(ValuationRow::pe)
                    .filter(v -> v != null && v.signum() > 0).toList();
            if (history.size() < PE_MIN_SAMPLES) continue;
            long atOrBelow = history.stream().filter(v -> v.compareTo(latest.pe()) <= 0).count();
            BigDecimal percentile = BigDecimal.valueOf(100.0 * atOrBelow / history.size())
                    .setScale(1, RoundingMode.HALF_UP);
            double contribution = clampUnit(-(percentile.doubleValue() - 50.0) / 30.0);
            return new Factor(percentile, contribution, provider, latest.urls(), latest.availableAt(),
                    false, null, latest.date().toString());
        }
        return null;
    }

    private RevenueRow latestRevenueBasis(List<RevenueRow> rows, LocalDate decisionDate) {
        for (String provider : PROVIDERS) {
            RevenueRow row = rows.stream().filter(v -> provider.equals(v.provider()))
                    .filter(v -> v.industryName() != null && !v.industryName().isBlank())
                    .max(Comparator.comparingInt(RevenueRow::periodIndex)).orElse(null);
            if (row != null && revenueFresh(row, decisionDate)) return row;
        }
        return null;
    }

    private static boolean financialFresh(FinancialRow row, LocalDate decisionDate) {
        return row != null && row.periodIndex() >= expectedFinancialPeriodIndex(decisionDate);
    }

    private static boolean revenueFresh(RevenueRow row, LocalDate decisionDate) {
        return row != null && row.periodIndex() >= expectedRevenuePeriodIndex(decisionDate);
    }

    /** 必須與 external-materials-service 的 fallback deadline 契約同步。 */
    static int expectedFinancialPeriodIndex(LocalDate decisionDate) {
        int year = decisionDate.getYear();
        if (!decisionDate.isBefore(LocalDate.of(year, 11, 14))) return year * 4 + 3;
        if (!decisionDate.isBefore(LocalDate.of(year, 8, 14))) return year * 4 + 2;
        if (!decisionDate.isBefore(LocalDate.of(year, 5, 15))) return year * 4 + 1;
        if (!decisionDate.isBefore(LocalDate.of(year, 3, 31))) return (year - 1) * 4 + 4;
        return (year - 1) * 4 + 3;
    }

    /** 必須與 external-materials-service 的月營收 fallback deadline 契約同步。 */
    static int expectedRevenuePeriodIndex(LocalDate decisionDate) {
        YearMonth expected = YearMonth.from(decisionDate).minusMonths(
                decisionDate.getDayOfMonth() >= 11 ? 1 : 2);
        return expected.getYear() * 12 + expected.getMonthValue();
    }

    private static boolean fresh(LocalDate period, LocalDate decisionDate, int maxAgeDays) {
        if (period == null || decisionDate == null || period.isAfter(decisionDate)) return false;
        return ChronoUnit.DAYS.between(period, decisionDate) <= maxAgeDays;
    }

    static <T, R> R firstProviderValue(
            List<T> rows, Function<T, String> providerOf, Function<List<T>, R> calculator) {
        for (String provider : PROVIDERS) {
            List<T> sameProvider = rows.stream().filter(r -> provider.equals(providerOf.apply(r))).toList();
            if (sameProvider.isEmpty()) continue;
            R result = calculator.apply(sameProvider);
            if (result != null) return result;
        }
        return null;
    }

    private static <T> List<T> consecutive(List<T> rows, int count, Function<T, Integer> index) {
        if (rows.size() < count) return List.of();
        List<T> recent = rows.subList(0, count);
        for (int i = 1; i < recent.size(); i++) {
            if (index.apply(recent.get(i - 1)) - index.apply(recent.get(i)) != 1) return List.of();
        }
        return recent;
    }

    /** 年度累計值轉單季；Q2–Q4 缺前一季時必須是 null。 */
    private static BigDecimal standalone(
            FinancialRow row, List<FinancialRow> sameProvider, Function<FinancialRow, BigDecimal> value) {
        BigDecimal cumulative = value.apply(row);
        if (cumulative == null) return null;
        if (row.quarter() == 1) return cumulative;
        FinancialRow previous = sameProvider.stream()
                .filter(r -> r.year() == row.year() && r.quarter() == row.quarter() - 1)
                .findFirst().orElse(null);
        BigDecimal previousCumulative = previous == null ? null : value.apply(previous);
        return standaloneValue(row.quarter(), cumulative, previousCumulative);
    }

    static BigDecimal standaloneValue(int quarter, BigDecimal cumulative, BigDecimal previousCumulative) {
        if (cumulative == null) return null;
        if (quarter == 1) return cumulative;
        if (quarter < 1 || quarter > 4 || previousCumulative == null) return null;
        return cumulative.subtract(previousCumulative);
    }

    static BigDecimal epsYoyPct(List<BigDecimal> standaloneDesc) {
        if (standaloneDesc == null || standaloneDesc.size() < 8
                || standaloneDesc.subList(0, 8).stream().anyMatch(Objects::isNull)) return null;
        BigDecimal recentFour = sum(standaloneDesc.subList(0, 4));
        BigDecimal priorFour = sum(standaloneDesc.subList(4, 8));
        if (priorFour.signum() <= 0) return null;
        return recentFour.subtract(priorFour).multiply(BigDecimal.valueOf(100))
                .divide(priorFour, 4, RoundingMode.HALF_UP);
    }

    static BigDecimal approximateRoePct(List<BigDecimal> standaloneDesc, BigDecimal latestEquity) {
        if (standaloneDesc == null || standaloneDesc.size() < 4 || latestEquity == null
                || latestEquity.signum() <= 0
                || standaloneDesc.subList(0, 4).stream().anyMatch(Objects::isNull)) return null;
        return sum(standaloneDesc.subList(0, 4)).multiply(BigDecimal.valueOf(100))
                .divide(latestEquity, 4, RoundingMode.HALF_UP);
    }

    static BigDecimal threeMonthAverage(List<BigDecimal> yoyDesc) {
        if (yoyDesc == null || yoyDesc.size() < 3
                || yoyDesc.subList(0, 3).stream().anyMatch(Objects::isNull)) return null;
        return sum(yoyDesc.subList(0, 3)).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
    }

    private Factor factor(BigDecimal value, double contribution, List<FinancialRow> rows) {
        return new Factor(value, contribution, rows.get(0).provider(), mergedUrls(rows),
                latestAvailable(rows), false, null, rows.get(0).period());
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static double clampUnit(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private FinancialRow financialRow(ResultSet rs) throws SQLException {
        return new FinancialRow(rs.getInt("fiscal_year"), rs.getInt("fiscal_quarter"),
                rs.getBigDecimal("eps"), rs.getBigDecimal("net_income_parent"),
                rs.getBigDecimal("equity_parent"), rs.getString("provider"),
                urls(rs.getString("source_urls")), instant(rs, "source_available_at"),
                instant(rs, "observed_at"));
    }

    private RevenueRow revenueRow(ResultSet rs) throws SQLException {
        return new RevenueRow(rs.getInt("revenue_year"), rs.getInt("revenue_month"),
                rs.getString("industry_name"), rs.getBigDecimal("revenue_yoy_pct"),
                rs.getString("provider"), urls(rs.getString("source_urls")),
                instant(rs, "source_available_at"), instant(rs, "observed_at"));
    }

    private ValuationRow valuationRow(ResultSet rs) throws SQLException {
        return new ValuationRow(rs.getDate("trading_date").toLocalDate(), rs.getBigDecimal("pe_ratio"),
                (Boolean) rs.getObject("pe_loss_flag"), rs.getString("provider"),
                urls(rs.getString("source_urls")), instant(rs, "source_available_at"),
                instant(rs, "observed_at"));
    }

    private IndustryRow industryRow(ResultSet rs) throws SQLException {
        return new IndustryRow(rs.getString("industry_name"), rs.getInt("revenue_year"),
                rs.getInt("revenue_month"), rs.getBigDecimal("revenue_yoy_pct"),
                rs.getInt("company_count"), rs.getString("provider"),
                urls(rs.getString("source_urls")), instant(rs, "source_available_at"),
                instant(rs, "observed_at"));
    }

    private List<String> urls(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(mapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            log.warn("基本面 source_urls 解析失敗：{}", e.getMessage());
            return List.of();
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static int count(Factor... factors) {
        int count = 0;
        for (Factor factor : factors) if (factor != null) count++;
        return count;
    }

    private static BigDecimal value(Factor factor) { return factor == null ? null : factor.value(); }
    private static Double contribution(Factor factor) { return factor == null ? null : factor.contribution(); }
    private static String provider(Factor factor) { return factor == null ? null : factor.provider(); }
    private static List<String> urls(Factor factor) { return factor == null ? List.of() : factor.urls(); }
    private static String asOf(Factor factor) {
        return factor == null || factor.availableAt() == null ? null : factor.availableAt().toString();
    }

    private static <T extends SourcedRow> List<String> mergedUrls(List<T> rows) {
        Set<String> result = new LinkedHashSet<>();
        rows.forEach(row -> result.addAll(row.urls()));
        return List.copyOf(result);
    }

    private static <T extends SourcedRow> Instant latestAvailable(List<T> rows) {
        return rows.stream().map(SourcedRow::availableAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
    }

    interface SourcedRow {
        List<String> urls();
        Instant availableAt();
        Instant observedAt();
    }

    record Factor(
            BigDecimal value,
            Double contribution,
            String provider,
            List<String> urls,
            Instant availableAt,
            boolean loss,
            Integer companyCount,
            String period) {
        Factor withContribution(Double next) {
            return new Factor(value, next, provider, urls, availableAt, loss, companyCount, period);
        }
    }

    record FinancialRow(
            int year, int quarter, BigDecimal eps, BigDecimal income, BigDecimal equity,
            String provider, List<String> urls, Instant availableAt, Instant observedAt) implements SourcedRow {
        static final Comparator<FinancialRow> DESC = Comparator.comparingInt(FinancialRow::periodIndex).reversed();
        int periodIndex() { return year * 4 + quarter; }
        String period() { return year + "Q" + quarter; }
    }

    record RevenueRow(
            int year, int month, String industryName, BigDecimal yoy,
            String provider, List<String> urls, Instant availableAt, Instant observedAt) implements SourcedRow {
        static final Comparator<RevenueRow> DESC = Comparator.comparingInt(RevenueRow::periodIndex).reversed();
        int periodIndex() { return year * 12 + month; }
        String period() { return "%04d-%02d".formatted(year, month); }
    }

    record ValuationRow(
            java.time.LocalDate date, BigDecimal pe, Boolean loss,
            String provider, List<String> urls, Instant availableAt, Instant observedAt) implements SourcedRow {
        static final Comparator<ValuationRow> DESC = Comparator.comparing(ValuationRow::date).reversed();
    }

    record IndustryRow(
            String industryName, int year, int month, BigDecimal yoy, int companyCount,
            String provider, List<String> urls, Instant availableAt, Instant observedAt) implements SourcedRow {}
}
