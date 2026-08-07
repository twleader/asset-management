package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.StockFundamentalFetchClient;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Task 292 基本面 observation store。
 *
 * <p>四張表皆採 append-on-change：同 provider 的最新 observation 逐欄相同就 no-op，值變動才新增。
 * 禁止 {@code UPDATE} 過去列，否則來源修正版會在回測中被視為當時早已可見。</p>
 */
@Component
@RequiredArgsConstructor
public class FundamentalObservationStore {

    private static final String TW_MARKET = "台股";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final int VALUATION_MAX_AGE_DAYS = 10;
    private static final int PE_MIN_SAMPLES = 250;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record WriteCount(int valuations, int financials, int revenues, int industries) {
        public WriteCount plus(WriteCount other) {
            return new WriteCount(valuations + other.valuations, financials + other.financials,
                    revenues + other.revenues, industries + other.industries);
        }
    }

    /**
     * 各結構化因子是否仍需 fallback。
     *
     * <p>不能只算資料列數：同一 provider 即使有很多舊列，也可能最新季已過期、中間斷期或必要欄位為
     * {@code null}。這裡使用與 V11 resolver 相同的 as-of／revision 規則，逐因子檢查可形成性；因此
     * Yahoo 只補估值，FinMind 才會在 EPS／ROE／月營收仍不足時接手。</p>
     */
    public FallbackNeed fallbackNeed(String code, Instant decisionInstant) {
        if (code == null || code.isBlank() || decisionInstant == null) return FallbackNeed.ALL;
        List<FinancialCoverage> financials = jdbc.query("""
                SELECT fiscal_year, fiscal_quarter, eps, net_income_parent, equity_parent, provider
                FROM (
                    SELECT DISTINCT ON (fiscal_year, fiscal_quarter, provider)
                           fiscal_year, fiscal_quarter, eps, net_income_parent, equity_parent,
                           provider, observed_at
                    FROM stock_financial_quarter
                    WHERE stock_code=? AND market=?
                      AND source_available_at<=? AND observed_at<=?
                    ORDER BY fiscal_year DESC, fiscal_quarter DESC, provider, observed_at DESC
                ) q
                ORDER BY fiscal_year DESC, fiscal_quarter DESC
                """, (rs, ignored) -> new FinancialCoverage(
                rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5), rs.getString(6)),
                code, TW_MARKET, java.sql.Timestamp.from(decisionInstant),
                java.sql.Timestamp.from(decisionInstant));
        List<RevenueCoverage> revenues = jdbc.query("""
                SELECT revenue_year, revenue_month, revenue_yoy_pct, provider
                FROM (
                    SELECT DISTINCT ON (revenue_year, revenue_month, provider)
                           revenue_year, revenue_month, revenue_yoy_pct, provider, observed_at
                    FROM stock_monthly_revenue
                    WHERE stock_code=? AND market=?
                      AND source_available_at<=? AND observed_at<=?
                    ORDER BY revenue_year DESC, revenue_month DESC, provider, observed_at DESC
                ) q
                ORDER BY revenue_year DESC, revenue_month DESC
                """, (rs, ignored) -> new RevenueCoverage(
                rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3), rs.getString(4)),
                code, TW_MARKET, java.sql.Timestamp.from(decisionInstant),
                java.sql.Timestamp.from(decisionInstant));
        List<ValuationCoverage> valuations = jdbc.query("""
                SELECT trading_date, pe_ratio, pe_loss_flag, provider
                FROM (
                    SELECT DISTINCT ON (trading_date, provider)
                           trading_date, pe_ratio, pe_loss_flag, provider, observed_at
                    FROM stock_valuation_daily
                    WHERE stock_code=? AND market=?
                      AND source_available_at<=? AND observed_at<=?
                    ORDER BY trading_date DESC, provider, observed_at DESC
                ) q
                ORDER BY trading_date DESC
                """, (rs, ignored) -> new ValuationCoverage(
                rs.getDate(1).toLocalDate(), rs.getBigDecimal(2),
                (Boolean) rs.getObject(3), rs.getString(4)),
                code, TW_MARKET, java.sql.Timestamp.from(decisionInstant),
                java.sql.Timestamp.from(decisionInstant));
        return coverageNeed(financials, revenues, valuations, decisionInstant);
    }

    /** 欄位為 {@code true} 代表該因子仍缺資料、必須繼續下一順位來源。 */
    public record FallbackNeed(boolean eps, boolean roe, boolean revenue, boolean valuation) {
        static final FallbackNeed ALL = new FallbackNeed(true, true, true, true);
        public boolean any() { return eps || roe || revenue || valuation; }
    }

    record FinancialCoverage(
            int year, int quarter, BigDecimal eps, BigDecimal income, BigDecimal equity, String provider) {
        int periodIndex() { return year * 4 + quarter; }
        LocalDate periodEnd() { return YearMonth.of(year, quarter * 3).atEndOfMonth(); }
    }

    record RevenueCoverage(int year, int month, BigDecimal yoy, String provider) {
        int periodIndex() { return year * 12 + month; }
        LocalDate periodEnd() { return YearMonth.of(year, month).atEndOfMonth(); }
    }

    record ValuationCoverage(LocalDate date, BigDecimal pe, Boolean loss, String provider) {}

    static FallbackNeed coverageNeed(
            List<FinancialCoverage> financials,
            List<RevenueCoverage> revenues,
            List<ValuationCoverage> valuations,
            Instant decisionInstant) {
        LocalDate decisionDate = decisionInstant.atZone(TAIPEI).toLocalDate();
        Map<String, List<FinancialCoverage>> financialByProvider = byProvider(financials,
                FinancialCoverage::provider);
        boolean eps = financialByProvider.values().stream()
                .noneMatch(rows -> hasFinancialCoverage(rows, 8, true, decisionDate));
        boolean roe = financialByProvider.values().stream()
                .noneMatch(rows -> hasFinancialCoverage(rows, 4, false, decisionDate));

        Map<String, List<RevenueCoverage>> revenueByProvider = byProvider(revenues, RevenueCoverage::provider);
        boolean revenue = revenueByProvider.values().stream().noneMatch(rows -> {
            List<RevenueCoverage> sorted = rows.stream()
                    .sorted(Comparator.comparingInt(RevenueCoverage::periodIndex).reversed()).toList();
            List<RevenueCoverage> recent = consecutive(sorted, 3, RevenueCoverage::periodIndex);
            return recent.size() == 3
                    && recent.get(0).periodIndex() >= expectedRevenuePeriodIndex(decisionDate)
                    && recent.stream().noneMatch(row -> row.yoy() == null);
        });

        Map<String, List<ValuationCoverage>> valuationByProvider = byProvider(
                valuations, ValuationCoverage::provider);
        boolean valuation = valuationByProvider.values().stream().noneMatch(rows -> {
            List<ValuationCoverage> sorted = rows.stream()
                    .sorted(Comparator.comparing(ValuationCoverage::date).reversed()).toList();
            if (sorted.isEmpty() || !fresh(sorted.get(0).date(), decisionDate, VALUATION_MAX_AGE_DAYS)) {
                return false;
            }
            ValuationCoverage latest = sorted.get(0);
            if (Boolean.TRUE.equals(latest.loss())) return true;
            if (!Boolean.FALSE.equals(latest.loss()) || latest.pe() == null || latest.pe().signum() <= 0) {
                return false;
            }
            return sorted.stream().filter(row -> row.pe() != null && row.pe().signum() > 0).count()
                    >= PE_MIN_SAMPLES;
        });
        return new FallbackNeed(eps, roe, revenue, valuation);
    }

    private static boolean hasFinancialCoverage(
            List<FinancialCoverage> rows, int periods, boolean useEps, LocalDate decisionDate) {
        List<FinancialCoverage> sorted = rows.stream()
                .sorted(Comparator.comparingInt(FinancialCoverage::periodIndex).reversed()).toList();
        List<FinancialCoverage> recent = consecutive(sorted, periods, FinancialCoverage::periodIndex);
        if (recent.size() != periods
                || recent.get(0).periodIndex() < expectedFinancialPeriodIndex(decisionDate)) return false;
        if (!useEps && recent.get(0).equity() == null) return false;
        for (FinancialCoverage row : recent) {
            BigDecimal value = useEps ? row.eps() : row.income();
            if (value == null) return false;
            if (row.quarter() > 1) {
                FinancialCoverage previous = sorted.stream()
                        .filter(candidate -> candidate.year() == row.year()
                                && candidate.quarter() == row.quarter() - 1)
                        .findFirst().orElse(null);
                BigDecimal previousValue = previous == null ? null
                        : (useEps ? previous.eps() : previous.income());
                if (previousValue == null) return false;
            }
        }
        return true;
    }

    private static <T> Map<String, List<T>> byProvider(List<T> rows, Function<T, String> provider) {
        return (rows == null ? List.<T>of() : rows).stream()
                .filter(Objects::nonNull)
                .filter(row -> provider.apply(row) != null)
                .collect(Collectors.groupingBy(provider));
    }

    private static <T> List<T> consecutive(List<T> rows, int count, Function<T, Integer> index) {
        if (rows.size() < count) return List.of();
        List<T> recent = rows.subList(0, count);
        for (int i = 1; i < recent.size(); i++) {
            if (index.apply(recent.get(i - 1)) - index.apply(recent.get(i)) != 1) return List.of();
        }
        return recent;
    }

    private static boolean fresh(LocalDate period, LocalDate decisionDate, int maxAgeDays) {
        if (period == null || decisionDate == null || period.isAfter(decisionDate)) return false;
        return ChronoUnit.DAYS.between(period, decisionDate) <= maxAgeDays;
    }

    /**
     * 依台灣季報法定常用截止日推得「現在至少應看到哪一季」：Q1 5/15、Q2 8/14、Q3 11/14、
     * 年報次年 3/31。比固定天數更能抓出「筆數很多但最新季漏抓」的情況。
     */
    static int expectedFinancialPeriodIndex(LocalDate decisionDate) {
        int year = decisionDate.getYear();
        if (!decisionDate.isBefore(LocalDate.of(year, 11, 14))) return year * 4 + 3;
        if (!decisionDate.isBefore(LocalDate.of(year, 8, 14))) return year * 4 + 2;
        if (!decisionDate.isBefore(LocalDate.of(year, 5, 15))) return year * 4 + 1;
        if (!decisionDate.isBefore(LocalDate.of(year, 3, 31))) return (year - 1) * 4 + 4;
        return (year - 1) * 4 + 3;
    }

    /** 月營收通常在次月 10 日前公告；11 日起若仍只有前前月，就必須繼續 fallback。 */
    static int expectedRevenuePeriodIndex(LocalDate decisionDate) {
        YearMonth expected = YearMonth.from(decisionDate).minusMonths(decisionDate.getDayOfMonth() >= 11 ? 1 : 2);
        return expected.getYear() * 12 + expected.getMonthValue();
    }

    public boolean hasObservedToday(LocalDate date) {
        Boolean present = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM stock_valuation_daily WHERE observed_at::date=?)
                    OR EXISTS(SELECT 1 FROM stock_financial_quarter WHERE observed_at::date=?)
                    OR EXISTS(SELECT 1 FROM stock_monthly_revenue WHERE observed_at::date=?)
                """, Boolean.class, date, date, date);
        return Boolean.TRUE.equals(present);
    }

    public boolean isEtf(String code) {
        if (code == null) return false;
        if (code.startsWith("00")) return true;
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM etf_nav_history WHERE stock_code=? AND market=?",
                Integer.class, code, TW_MARKET);
        return nz(count) > 0;
    }

    public WriteCount append(StockFundamentalFetchClient.Bundle bundle) {
        int valuations = 0;
        int financials = 0;
        int revenues = 0;
        for (StockFundamentalFetchClient.Valuation row : safe(bundle.valuations())) {
            valuations += appendValuation(row);
        }
        for (StockFundamentalFetchClient.Financial row : safe(bundle.financials())) {
            financials += appendFinancial(row);
        }
        for (StockFundamentalFetchClient.Revenue row : safe(bundle.revenues())) {
            revenues += appendRevenue(row);
        }
        return new WriteCount(valuations, financials, revenues, 0);
    }

    public int appendIndustry(
            String industry,
            int year,
            int month,
            BigDecimal revenue,
            BigDecimal priorRevenue,
            BigDecimal yoy,
            int companyCount,
            String provider,
            List<String> sourceUrls,
            Instant sourceAvailableAt,
            String basis) {
        if (industry == null || industry.isBlank() || year <= 0 || month < 1 || month > 12
                || sourceAvailableAt == null) return 0;
        IndustryLatest latest = jdbc.query("""
                        SELECT revenue, prior_year_revenue, revenue_yoy_pct, company_count,
                               source_urls::text, source_available_at, availability_basis
                        FROM industry_monthly_revenue
                        WHERE industry_name=? AND revenue_year=? AND revenue_month=? AND provider=?
                        ORDER BY observed_at DESC LIMIT 1
                        """, ps -> {
                    ps.setString(1, industry);
                    ps.setInt(2, year);
                    ps.setInt(3, month);
                    ps.setString(4, provider);
                }, rs -> rs.next() ? industryLatest(rs) : null);
        String urls = json(sourceUrls);
        if (latest != null && latest.same(revenue, priorRevenue, yoy, companyCount, urls, sourceAvailableAt, basis)) {
            return 0;
        }
        return jdbc.update("""
                INSERT INTO industry_monthly_revenue
                  (industry_name, revenue_year, revenue_month, revenue, prior_year_revenue,
                   revenue_yoy_pct, company_count, provider, source_urls, source_available_at,
                   availability_basis, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now())
                """, industry, year, month, revenue, priorRevenue, yoy, companyCount,
                provider, urls, Timestamp.from(sourceAvailableAt), basis);
    }

    private int appendValuation(StockFundamentalFetchClient.Valuation row) {
        if (row.stockCode() == null || row.tradingDate() == null || row.sourceAvailableAt() == null) return 0;
        ValuationLatest latest = jdbc.query("""
                        SELECT pe_ratio, pb_ratio, dividend_yield_pct, pe_loss_flag,
                               source_urls::text, source_available_at, availability_basis
                        FROM stock_valuation_daily
                        WHERE stock_code=? AND market=? AND trading_date=? AND provider=?
                        ORDER BY observed_at DESC LIMIT 1
                        """, ps -> {
                    ps.setString(1, row.stockCode());
                    ps.setString(2, TW_MARKET);
                    ps.setObject(3, row.tradingDate());
                    ps.setString(4, row.provider());
                }, rs -> rs.next() ? valuationLatest(rs) : null);
        String urls = json(row.sourceUrls());
        if (latest != null && latest.same(row, urls)) return 0;
        return jdbc.update("""
                INSERT INTO stock_valuation_daily
                  (stock_code, market, trading_date, pe_ratio, pb_ratio, dividend_yield_pct,
                   pe_loss_flag, provider, source_urls, source_available_at, availability_basis, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now())
                """, row.stockCode(), TW_MARKET, row.tradingDate(), row.peRatio(), row.pbRatio(),
                row.dividendYieldPct(), row.peLossFlag(), row.provider(), urls,
                Timestamp.from(row.sourceAvailableAt()), row.availabilityBasis());
    }

    private int appendFinancial(StockFundamentalFetchClient.Financial row) {
        if (row.stockCode() == null || row.fiscalYear() <= 0 || row.fiscalQuarter() < 1
                || row.fiscalQuarter() > 4 || row.sourceAvailableAt() == null) return 0;
        FinancialLatest latest = jdbc.query("""
                        SELECT eps, net_income_parent, equity_parent, source_urls::text,
                               source_available_at, availability_basis
                        FROM stock_financial_quarter
                        WHERE stock_code=? AND market=? AND fiscal_year=? AND fiscal_quarter=? AND provider=?
                        ORDER BY observed_at DESC LIMIT 1
                        """, ps -> {
                    ps.setString(1, row.stockCode());
                    ps.setString(2, TW_MARKET);
                    ps.setInt(3, row.fiscalYear());
                    ps.setInt(4, row.fiscalQuarter());
                    ps.setString(5, row.provider());
                }, rs -> rs.next() ? financialLatest(rs) : null);
        String urls = json(row.sourceUrls());
        if (latest != null && latest.same(row, urls)) return 0;
        return jdbc.update("""
                INSERT INTO stock_financial_quarter
                  (stock_code, market, fiscal_year, fiscal_quarter, eps, net_income_parent, equity_parent,
                   provider, source_urls, source_available_at, availability_basis, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now())
                """, row.stockCode(), TW_MARKET, row.fiscalYear(), row.fiscalQuarter(), row.cumulativeEps(),
                row.cumulativeNetIncomeParent(), row.equityParent(), row.provider(), urls,
                Timestamp.from(row.sourceAvailableAt()), row.availabilityBasis());
    }

    private int appendRevenue(StockFundamentalFetchClient.Revenue row) {
        if (row.stockCode() == null || row.revenueYear() <= 0 || row.revenueMonth() < 1
                || row.revenueMonth() > 12 || row.sourceAvailableAt() == null) return 0;
        RevenueLatest latest = jdbc.query("""
                        SELECT industry_name, revenue, prior_year_revenue, revenue_yoy_pct,
                               source_urls::text, source_available_at, availability_basis
                        FROM stock_monthly_revenue
                        WHERE stock_code=? AND market=? AND revenue_year=? AND revenue_month=? AND provider=?
                        ORDER BY observed_at DESC LIMIT 1
                        """, ps -> {
                    ps.setString(1, row.stockCode());
                    ps.setString(2, TW_MARKET);
                    ps.setInt(3, row.revenueYear());
                    ps.setInt(4, row.revenueMonth());
                    ps.setString(5, row.provider());
                }, rs -> rs.next() ? revenueLatest(rs) : null);
        String urls = json(row.sourceUrls());
        if (latest != null && latest.same(row, urls)) return 0;
        return jdbc.update("""
                INSERT INTO stock_monthly_revenue
                  (stock_code, market, revenue_year, revenue_month, industry_name, revenue,
                   prior_year_revenue, revenue_yoy_pct, provider, source_urls,
                   source_available_at, availability_basis, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, now())
                """, row.stockCode(), TW_MARKET, row.revenueYear(), row.revenueMonth(), row.industryName(),
                row.revenue(), row.priorYearRevenue(), row.revenueYoyPct(), row.provider(), urls,
                Timestamp.from(row.sourceAvailableAt()), row.availabilityBasis());
    }

    private String json(List<String> urls) {
        try { return mapper.writeValueAsString(urls == null ? List.of() : urls); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("invalid source URLs", e); }
    }

    private static int nz(Integer value) { return value == null ? 0 : value; }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    private static ValuationLatest valuationLatest(ResultSet rs) throws SQLException {
        return new ValuationLatest(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                (Boolean) rs.getObject(4), rs.getString(5), instant(rs, 6), rs.getString(7));
    }

    private static FinancialLatest financialLatest(ResultSet rs) throws SQLException {
        return new FinancialLatest(rs.getBigDecimal(1), (Long) rs.getObject(2), (Long) rs.getObject(3),
                rs.getString(4), instant(rs, 5), rs.getString(6));
    }

    private static RevenueLatest revenueLatest(ResultSet rs) throws SQLException {
        return new RevenueLatest(rs.getString(1), (Long) rs.getObject(2), (Long) rs.getObject(3),
                rs.getBigDecimal(4), rs.getString(5), instant(rs, 6), rs.getString(7));
    }

    private static IndustryLatest industryLatest(ResultSet rs) throws SQLException {
        return new IndustryLatest(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                rs.getInt(4), rs.getString(5), instant(rs, 6), rs.getString(7));
    }

    private record ValuationLatest(BigDecimal pe, BigDecimal pb, BigDecimal yield, Boolean loss,
                                   String urls, Instant available, String basis) {
        boolean same(StockFundamentalFetchClient.Valuation row, String json) {
            return decimalEquals(pe, row.peRatio()) && decimalEquals(pb, row.pbRatio())
                    && decimalEquals(yield, row.dividendYieldPct()) && Objects.equals(loss, row.peLossFlag())
                    && jsonEquals(urls, json) && availabilityEquals(available, row.sourceAvailableAt(), basis)
                    && Objects.equals(basis, row.availabilityBasis());
        }
    }

    private record FinancialLatest(BigDecimal eps, Long income, Long equity, String urls,
                                   Instant available, String basis) {
        boolean same(StockFundamentalFetchClient.Financial row, String json) {
            return decimalEquals(eps, row.cumulativeEps())
                    && Objects.equals(income, row.cumulativeNetIncomeParent())
                    && Objects.equals(equity, row.equityParent()) && jsonEquals(urls, json)
                    && availabilityEquals(available, row.sourceAvailableAt(), basis)
                    && Objects.equals(basis, row.availabilityBasis());
        }
    }

    private record RevenueLatest(String industry, Long revenue, Long prior, BigDecimal yoy, String urls,
                                 Instant available, String basis) {
        boolean same(StockFundamentalFetchClient.Revenue row, String json) {
            return Objects.equals(industry, row.industryName()) && Objects.equals(revenue, row.revenue())
                    && Objects.equals(prior, row.priorYearRevenue()) && decimalEquals(yoy, row.revenueYoyPct())
                    && jsonEquals(urls, json) && availabilityEquals(available, row.sourceAvailableAt(), basis)
                    && Objects.equals(basis, row.availabilityBasis());
        }
    }

    private record IndustryLatest(BigDecimal revenue, BigDecimal prior, BigDecimal yoy, int count,
                                  String urls, Instant available, String basis) {
        boolean same(BigDecimal r, BigDecimal p, BigDecimal y, int c, String json, Instant at, String b) {
            return decimalEquals(revenue, r) && decimalEquals(prior, p) && decimalEquals(yoy, y)
                    && count == c && jsonEquals(urls, json) && availabilityEquals(available, at, basis)
                    && Objects.equals(basis, b);
        }
    }

    /** PostgreSQL jsonb 會正規化空白；以 JSON tree 比較，避免格式差異製造假 observation。 */
    private static boolean jsonEquals(String left, String right) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return Objects.equals(mapper.readTree(left), mapper.readTree(right));
        } catch (Exception e) {
            return Objects.equals(left, right);
        }
    }

    private static Instant instant(ResultSet rs, int index) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(index);
        return value == null ? null : value.toInstant();
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    /** OBSERVED 的可用時點定義為第一次成功觀測；同值重跑不得把它往後推。 */
    private static boolean availabilityEquals(Instant left, Instant right, String basis) {
        return "OBSERVED".equals(basis) || Objects.equals(left, right);
    }
}
