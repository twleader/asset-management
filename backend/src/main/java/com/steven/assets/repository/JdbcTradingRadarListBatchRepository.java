package com.steven.assets.repository;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.model.EtfNavObservation;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC implementation of the Radar list's bounded, exact-pair read port. */
@Repository
@RequiredArgsConstructor
public class JdbcTradingRadarListBatchRepository implements TradingRadarListBatchRepository {

    private final JdbcTemplate jdbc;
    private final TreasuryYieldSeriesRepository treasuryYieldSeriesRepository;
    private final ExchangeRateHistoryRepository exchangeRateHistoryRepository;

    @Override
    public Map<Key, Stock> findStocks(Collection<Key> rawKeys) {
        List<Key> keys = canonical(rawKeys);
        if (keys.isEmpty()) return Map.of();
        String values = values(keys.size());
        List<Object> args = pairArguments(keys);
        Map<Key, Stock> out = new LinkedHashMap<>();
        jdbc.query("""
                WITH requested(code, market) AS (VALUES %s)
                SELECT s.code, s.market, s.name, s.asset_class, s.stock_style, s.bond_term,
                       s.underlying_currency
                FROM stock s JOIN requested r ON r.code=s.code AND r.market=s.market
                """.formatted(values), (rs, ignored) -> Stock.builder().code(rs.getString("code"))
                .market(rs.getString("market")).name(rs.getString("name"))
                .assetClass(rs.getString("asset_class")).stockStyle(rs.getString("stock_style"))
                .bondTerm(rs.getString("bond_term")).underlyingCurrency(rs.getString("underlying_currency"))
                .build(), args.toArray()).forEach(stock -> out.put(new Key(stock.getCode(), stock.getMarket()), stock));
        return Map.copyOf(out);
    }

    @Override
    public Map<Key, List<StockPriceHistory>> findRecentPrices(Collection<Key> rawKeys, int limit) {
        List<Key> keys = canonical(rawKeys);
        if (keys.isEmpty() || limit < 1) return Map.of();
        List<Object> args = pairArguments(keys);
        args.add(limit);
        List<StockPriceHistory> rows = jdbc.query("""
                WITH requested(stock_code, market) AS (VALUES %s), ranked AS (
                    SELECT h.*, row_number() OVER (
                        PARTITION BY h.stock_code, h.market ORDER BY h.trading_date DESC
                    ) AS row_rank
                    FROM stock_price_history h
                    JOIN requested r ON r.stock_code=h.stock_code AND r.market=h.market
                )
                SELECT id, stock_code, market, trading_date, open_price, high_price, low_price,
                       close_price, volume, close_source
                FROM ranked WHERE row_rank <= ?
                ORDER BY market ASC, stock_code ASC, trading_date DESC
                """.formatted(values(keys.size())), this::price, args.toArray());
        return groupPrices(keys, rows);
    }

    @Override
    public Map<Key, List<StockPriceHistory>> findAllPrices(Collection<Key> rawKeys) {
        List<Key> keys = canonical(rawKeys);
        if (keys.isEmpty()) return Map.of();
        List<StockPriceHistory> rows = jdbc.query("""
                WITH requested(stock_code, market) AS (VALUES %s)
                SELECT h.id, h.stock_code, h.market, h.trading_date, h.open_price, h.high_price,
                       h.low_price, h.close_price, h.volume, h.close_source
                FROM stock_price_history h
                JOIN requested r ON r.stock_code=h.stock_code AND r.market=h.market
                ORDER BY h.market ASC, h.stock_code ASC, h.trading_date ASC
                """.formatted(values(keys.size())), this::price, pairArguments(keys).toArray());
        return groupPrices(keys, rows);
    }

    @Override
    public Map<Key, List<EtfNavObservation>> findEtfNavObservations(
            Collection<Key> rawKeys, Instant decisionInstant) {
        List<Key> keys = canonical(rawKeys);
        if (keys.isEmpty() || decisionInstant == null) return Map.of();
        List<Object> args = pairArguments(keys);
        args.add(java.sql.Timestamp.from(decisionInstant));
        args.add(java.sql.Timestamp.from(decisionInstant));
        List<EtfNavObservation> rows = jdbc.query("""
                WITH requested(stock_code, market) AS (VALUES %s)
                SELECT e.id, e.stock_code, e.market, e.nav_date, e.nav, e.premium_discount_pct,
                       e.pct_origin, e.source, e.observed_at, e.available_at, e.availability_basis
                FROM etf_nav_observation e
                JOIN requested r ON r.stock_code=e.stock_code AND r.market=e.market
                WHERE e.observed_at<=? AND e.available_at<=?
                ORDER BY e.market ASC, e.stock_code ASC, e.nav_date ASC, e.available_at ASC,
                         e.observed_at ASC, e.id ASC
                """.formatted(values(keys.size())), this::etfNavObservation, args.toArray());
        Map<Key, List<EtfNavObservation>> out = emptyLists(keys);
        for (EtfNavObservation row : rows) {
            out.computeIfAbsent(new Key(row.getStockCode(), row.getMarket()), ignored -> new ArrayList<>()).add(row);
        }
        return immutableLists(out);
    }

    @Override
    public Map<Key, List<StockDividendHistory>> findAdjustmentEvents(
            Collection<Key> rawKeys, Map<Key, List<StockPriceHistory>> pricesByKey) {
        List<Key> keys = canonical(rawKeys);
        if (keys.isEmpty()) return Map.of();
        LocalDate min = null;
        LocalDate max = null;
        Map<Key, List<StockPriceHistory>> safePrices = pricesByKey == null ? Map.of() : pricesByKey;
        for (List<StockPriceHistory> rows : safePrices.values()) {
            for (StockPriceHistory row : rows) {
                if (row == null || row.getTradingDate() == null) continue;
                min = min == null || row.getTradingDate().isBefore(min) ? row.getTradingDate() : min;
                max = max == null || row.getTradingDate().isAfter(max) ? row.getTradingDate() : max;
            }
        }
        if (min == null || max == null) return immutableLists(emptyLists(keys));
        List<Object> args = pairArguments(keys);
        args.add(min);
        args.add(max);
        List<StockDividendHistory> rows = jdbc.query("""
                WITH requested(stock_code, market) AS (VALUES %s)
                SELECT h.id, h.stock_code, h.market, h.year, h.cash_dividend, h.stock_dividend,
                       h.ex_dividend_date, h.ex_rights_date, h.yield_pct, h.cash_payment_date,
                       h.stock_payment_date, h.fill_days, h.previous_close, h.source, h.event_key,
                       h.event_status, h.updated_at
                FROM stock_dividend_history h
                JOIN requested r ON r.stock_code=h.stock_code AND r.market=h.market
                WHERE LEAST(h.ex_dividend_date, h.ex_rights_date) BETWEEN ? AND ?
                  AND h.event_status='ACTIVE'
                  AND (COALESCE(h.cash_dividend, 0)>0 OR COALESCE(h.stock_dividend, 0)>0)
                ORDER BY h.market ASC, h.stock_code ASC,
                         LEAST(h.ex_dividend_date, h.ex_rights_date) ASC, h.id ASC
                """.formatted(values(keys.size())), this::dividend, args.toArray());
        Map<Key, List<StockDividendHistory>> out = emptyLists(keys);
        for (StockDividendHistory row : rows) {
            Key key = new Key(row.getStockCode(), row.getMarket());
            List<StockPriceHistory> prices = safePrices.getOrDefault(key, List.of());
            LocalDate from = prices.isEmpty() ? null : prices.getLast().getTradingDate();
            LocalDate to = prices.isEmpty() ? null : prices.getFirst().getTradingDate();
            LocalDate anchor = row.anchorDate();
            if (from != null && to != null && anchor != null && !anchor.isBefore(from) && !anchor.isAfter(to)) {
                out.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        return immutableLists(out);
    }

    @Override
    public List<TreasuryYieldDto.StoredBatch> findCompleteTreasurySeriesThrough(Instant decisionInstant) {
        return decisionInstant == null ? List.of() : List.copyOf(
                treasuryYieldSeriesRepository.findCompleteSeriesThrough(decisionInstant));
    }

    @Override
    public List<ExchangeRateHistory> findExchangeRatesByCurrency(String currency) {
        return currency == null || currency.isBlank() ? List.of() : List.copyOf(
                exchangeRateHistoryRepository.findByCurrencyOrderByRateDateAsc(currency));
    }

    private Map<Key, List<StockPriceHistory>> groupPrices(List<Key> keys, List<StockPriceHistory> rows) {
        Map<Key, List<StockPriceHistory>> out = emptyLists(keys);
        for (StockPriceHistory row : rows == null ? List.<StockPriceHistory>of() : rows) {
            out.computeIfAbsent(new Key(row.getStockCode(), row.getMarket()), ignored -> new ArrayList<>()).add(row);
        }
        return immutableLists(out);
    }

    private StockPriceHistory price(ResultSet rs, int ignored) throws SQLException {
        return StockPriceHistory.builder().id(rs.getLong("id")).stockCode(rs.getString("stock_code"))
                .market(rs.getString("market")).tradingDate(rs.getObject("trading_date", LocalDate.class))
                .openPrice(rs.getBigDecimal("open_price")).highPrice(rs.getBigDecimal("high_price"))
                .lowPrice(rs.getBigDecimal("low_price")).closePrice(rs.getBigDecimal("close_price"))
                .volume((Long) rs.getObject("volume")).closeSource(rs.getString("close_source")).build();
    }

    private StockDividendHistory dividend(ResultSet rs, int ignored) throws SQLException {
        return StockDividendHistory.builder().id(rs.getLong("id")).stockCode(rs.getString("stock_code"))
                .market(rs.getString("market")).year((Integer) rs.getObject("year"))
                .cashDividend(rs.getBigDecimal("cash_dividend")).stockDividend(rs.getBigDecimal("stock_dividend"))
                .exDividendDate(rs.getObject("ex_dividend_date", LocalDate.class))
                .exRightsDate(rs.getObject("ex_rights_date", LocalDate.class))
                .yieldPct(rs.getBigDecimal("yield_pct")).cashPaymentDate(rs.getObject("cash_payment_date", LocalDate.class))
                .stockPaymentDate(rs.getObject("stock_payment_date", LocalDate.class)).fillDays((Integer) rs.getObject("fill_days"))
                .previousClose(rs.getBigDecimal("previous_close")).source(rs.getString("source"))
                .eventKey(rs.getString("event_key")).eventStatus(rs.getString("event_status"))
                .updatedAt(rs.getObject("updated_at", java.time.LocalDateTime.class)).build();
    }

    private EtfNavObservation etfNavObservation(ResultSet rs, int ignored) throws SQLException {
        java.sql.Timestamp observed = rs.getTimestamp("observed_at");
        java.sql.Timestamp available = rs.getTimestamp("available_at");
        return EtfNavObservation.builder().id(rs.getLong("id")).stockCode(rs.getString("stock_code"))
                .market(rs.getString("market")).navDate(rs.getObject("nav_date", LocalDate.class))
                .nav(rs.getBigDecimal("nav")).premiumDiscountPct(rs.getBigDecimal("premium_discount_pct"))
                .pctOrigin(rs.getString("pct_origin")).source(rs.getString("source"))
                .observedAt(observed == null ? null : observed.toInstant())
                .availableAt(available == null ? null : available.toInstant())
                .availabilityBasis(rs.getString("availability_basis")).build();
    }

    private static List<Key> canonical(Collection<Key> keys) {
        return keys == null ? List.of() : keys.stream()
                .filter(key -> key != null && key.code() != null && key.market() != null)
                .distinct().sorted(Comparator.comparing(Key::market).thenComparing(Key::code)).toList();
    }

    private static String values(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "(?,?)"));
    }

    private static List<Object> pairArguments(List<Key> keys) {
        List<Object> args = new ArrayList<>(keys.size() * 2);
        for (Key key : keys) {
            args.add(key.code());
            args.add(key.market());
        }
        return args;
    }

    private static <T> Map<Key, List<T>> emptyLists(List<Key> keys) {
        Map<Key, List<T>> out = new LinkedHashMap<>();
        keys.forEach(key -> out.put(key, new ArrayList<>()));
        return out;
    }

    private static <T> Map<Key, List<T>> immutableLists(Map<Key, List<T>> source) {
        Map<Key, List<T>> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(key, List.copyOf(value)));
        return Map.copyOf(out);
    }
}
