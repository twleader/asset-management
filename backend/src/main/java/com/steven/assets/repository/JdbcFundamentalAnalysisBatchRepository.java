package com.steven.assets.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC observation reader; all provider selection and factor calculations stay in the service. */
@Repository
@RequiredArgsConstructor
public class JdbcFundamentalAnalysisBatchRepository implements FundamentalAnalysisBatchRepository {

    private final JdbcTemplate jdbc;

    @Override
    public boolean hasEtfNav(String stockCode, String market) {
        if (stockCode == null || market == null) return false;
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM etf_nav_history WHERE stock_code=? AND market=?)",
                Boolean.class, stockCode, market);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Snapshot findSnapshot(Key key, Instant latestInstant) {
        if (!valid(key) || latestInstant == null) return Snapshot.empty();
        Timestamp instant = Timestamp.from(latestInstant);
        List<FinancialObservation> financials = jdbc.query("""
                SELECT fiscal_year, fiscal_quarter, eps, net_income_parent, equity_parent,
                       provider, source_urls::text, source_available_at, observed_at
                FROM stock_financial_quarter
                WHERE stock_code=? AND market=?
                  AND source_available_at<=? AND observed_at<=?
                """, this::financial, key.stockCode(), key.market(), instant, instant);
        List<RevenueObservation> revenues = jdbc.query("""
                SELECT revenue_year, revenue_month, industry_name, revenue_yoy_pct,
                       provider, source_urls::text, source_available_at, observed_at
                FROM stock_monthly_revenue
                WHERE stock_code=? AND market=?
                  AND source_available_at<=? AND observed_at<=?
                """, this::revenue, key.stockCode(), key.market(), instant, instant);
        List<ValuationObservation> valuations = jdbc.query("""
                SELECT trading_date, pe_ratio, pb_ratio, dividend_yield_pct, pe_loss_flag, provider,
                       source_urls::text, source_available_at, observed_at
                FROM stock_valuation_daily
                WHERE stock_code=? AND market=?
                  AND source_available_at<=? AND observed_at<=?
                """, this::valuation, key.stockCode(), key.market(), instant, instant);
        List<IndustryObservation> industries = jdbc.query("""
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
                """, this::industry, instant, instant, key.stockCode(), key.market(), instant, instant);
        return new Snapshot(financials, revenues, valuations, industries);
    }

    @Override
    public Map<Key, Snapshot> findSnapshots(Collection<Key> rawKeys, Instant latestInstant) {
        List<Key> keys = canonical(rawKeys);
        if (keys.isEmpty() || latestInstant == null) return Map.of();
        String values = values(keys.size());
        List<Object> arguments = pairArguments(keys);
        arguments.add(Timestamp.from(latestInstant));
        arguments.add(Timestamp.from(latestInstant));
        Map<Key, List<FinancialObservation>> financials = emptyRows(keys);
        Map<Key, List<RevenueObservation>> revenues = emptyRows(keys);
        Map<Key, List<ValuationObservation>> valuations = emptyRows(keys);
        Map<Key, List<IndustryObservation>> industries = emptyRows(keys);

        jdbc.query("""
                WITH requested(stock_code, market) AS (VALUES %s)
                SELECT f.stock_code, f.market, f.fiscal_year, f.fiscal_quarter, f.eps,
                       f.net_income_parent, f.equity_parent, f.provider, f.source_urls::text,
                       f.source_available_at, f.observed_at
                FROM stock_financial_quarter f
                JOIN requested r ON r.stock_code=f.stock_code AND r.market=f.market
                WHERE f.source_available_at<=? AND f.observed_at<=?
                """.formatted(values), (rs, ignored) -> new Row<>(key(rs), financial(rs, ignored)), arguments.toArray())
                .forEach(row -> financials.get(row.key()).add(row.value()));
        jdbc.query("""
                WITH requested(stock_code, market) AS (VALUES %s)
                SELECT r.stock_code, r.market, s.revenue_year, s.revenue_month, s.industry_name,
                       s.revenue_yoy_pct, s.provider, s.source_urls::text, s.source_available_at, s.observed_at
                FROM stock_monthly_revenue s
                JOIN requested r ON r.stock_code=s.stock_code AND r.market=s.market
                WHERE s.source_available_at<=? AND s.observed_at<=?
                """.formatted(values), (rs, ignored) -> new Row<>(key(rs), revenue(rs, ignored)), arguments.toArray())
                .forEach(row -> revenues.get(row.key()).add(row.value()));
        jdbc.query("""
                WITH requested(stock_code, market) AS (VALUES %s)
                SELECT v.stock_code, v.market, v.trading_date, v.pe_ratio, v.pb_ratio,
                       v.dividend_yield_pct, v.pe_loss_flag, v.provider, v.source_urls::text,
                       v.source_available_at, v.observed_at
                FROM stock_valuation_daily v
                JOIN requested r ON r.stock_code=v.stock_code AND r.market=v.market
                WHERE v.source_available_at<=? AND v.observed_at<=?
                """.formatted(values), (rs, ignored) -> new Row<>(key(rs), valuation(rs, ignored)), arguments.toArray())
                .forEach(row -> valuations.get(row.key()).add(row.value()));
        jdbc.query("""
                WITH requested(stock_code, market) AS (VALUES %s)
                SELECT r.stock_code, r.market, i.industry_name, i.revenue_year, i.revenue_month,
                       i.revenue_yoy_pct, i.company_count, i.provider, i.source_urls::text,
                       i.source_available_at, i.observed_at
                FROM requested r
                JOIN industry_monthly_revenue i ON EXISTS (
                    SELECT 1 FROM stock_monthly_revenue s
                    WHERE s.stock_code=r.stock_code AND s.market=r.market
                      AND s.industry_name=i.industry_name
                      AND s.source_available_at<=? AND s.observed_at<=?)
                WHERE i.source_available_at<=? AND i.observed_at<=?
                """.formatted(values), (rs, ignored) -> new Row<>(key(rs), industry(rs, ignored)),
                industryArguments(keys, latestInstant).toArray())
                .forEach(row -> industries.get(row.key()).add(row.value()));

        Map<Key, Snapshot> out = new LinkedHashMap<>();
        for (Key key : keys) {
            out.put(key, new Snapshot(financials.get(key), revenues.get(key),
                    valuations.get(key), industries.get(key)));
        }
        return Map.copyOf(out);
    }

    private FinancialObservation financial(ResultSet rs, int ignored) throws SQLException {
        return new FinancialObservation(rs.getInt("fiscal_year"), rs.getInt("fiscal_quarter"),
                rs.getBigDecimal("eps"), rs.getBigDecimal("net_income_parent"), rs.getBigDecimal("equity_parent"),
                rs.getString("provider"), rs.getString("source_urls"), instant(rs, "source_available_at"),
                instant(rs, "observed_at"));
    }

    private RevenueObservation revenue(ResultSet rs, int ignored) throws SQLException {
        return new RevenueObservation(rs.getInt("revenue_year"), rs.getInt("revenue_month"),
                rs.getString("industry_name"), rs.getBigDecimal("revenue_yoy_pct"),
                rs.getString("provider"), rs.getString("source_urls"), instant(rs, "source_available_at"),
                instant(rs, "observed_at"));
    }

    private ValuationObservation valuation(ResultSet rs, int ignored) throws SQLException {
        java.sql.Date date = rs.getDate("trading_date");
        return new ValuationObservation(date == null ? null : date.toLocalDate(), rs.getBigDecimal("pe_ratio"),
                rs.getBigDecimal("pb_ratio"), rs.getBigDecimal("dividend_yield_pct"),
                (Boolean) rs.getObject("pe_loss_flag"), rs.getString("provider"), rs.getString("source_urls"),
                instant(rs, "source_available_at"), instant(rs, "observed_at"));
    }

    private IndustryObservation industry(ResultSet rs, int ignored) throws SQLException {
        return new IndustryObservation(rs.getString("industry_name"), rs.getInt("revenue_year"),
                rs.getInt("revenue_month"), rs.getBigDecimal("revenue_yoy_pct"), rs.getInt("company_count"),
                rs.getString("provider"), rs.getString("source_urls"), instant(rs, "source_available_at"),
                instant(rs, "observed_at"));
    }

    private static Key key(ResultSet rs) throws SQLException {
        return new Key(rs.getString("stock_code"), rs.getString("market"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static List<Key> canonical(Collection<Key> rawKeys) {
        return rawKeys == null ? List.of() : rawKeys.stream().filter(JdbcFundamentalAnalysisBatchRepository::valid)
                .distinct().sorted(Comparator.comparing(Key::market).thenComparing(Key::stockCode)).toList();
    }

    private static boolean valid(Key key) {
        return key != null && key.stockCode() != null && key.market() != null;
    }

    private static String values(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "(?,?)"));
    }

    private static List<Object> pairArguments(List<Key> keys) {
        List<Object> args = new ArrayList<>(keys.size() * 2 + 2);
        for (Key key : keys) {
            args.add(key.stockCode());
            args.add(key.market());
        }
        return args;
    }

    private static List<Object> industryArguments(List<Key> keys, Instant instant) {
        List<Object> out = pairArguments(keys);
        out.add(Timestamp.from(instant));
        out.add(Timestamp.from(instant));
        out.add(Timestamp.from(instant));
        out.add(Timestamp.from(instant));
        return out;
    }

    private static <T> Map<Key, List<T>> emptyRows(List<Key> keys) {
        Map<Key, List<T>> out = new LinkedHashMap<>();
        keys.forEach(key -> out.put(key, new ArrayList<>()));
        return out;
    }

    private record Row<T>(Key key, T value) {}
}
