package com.steven.assets.repository;

import com.steven.assets.model.AnnualDividendData.*;
import com.steven.assets.service.AnnualDividendReadRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

/** Every private query states its owner predicate; no ambient Hibernate filter is required. */
@Repository
public class JdbcAnnualDividendReadRepository implements AnnualDividendReadRepository {
    private final JdbcTemplate jdbc;
    public JdbcAnnualDividendReadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String SCOPE = """
            WITH owned_symbols AS (
                SELECT h.stock_code AS code, h.market
                  FROM stock_holding h JOIN asset_snapshot s ON s.id=h.snapshot_id
                 WHERE s.owner_user_id=? AND s.snapshot_date<=?
                UNION
                SELECT t.asset_code AS code, t.market FROM asset_transaction t
                 WHERE t.owner_user_id=? AND t.trade_date<=? AND t.asset_type='股票'
                UNION
                SELECT p.stock_code AS code, s.market FROM daily_inventory_snapshot s
                  JOIN daily_inventory_position p ON p.snapshot_id=s.id
                 WHERE s.owner_user_id=? AND s.trading_date<=?
                   AND s.trading_date>=daily_inventory_retention_cutoff(s.market,statement_timestamp())
            )
            """;

    @Override
    public Facts load(long ownerId, LocalDate asOfDate) {
        if (ownerId <= 0) throw new IllegalArgumentException("使用者識別無效");
        List<Security> securities = jdbc.query(SCOPE + """
                SELECT o.code, o.market, COALESCE(st.name,
                  (SELECT t.asset_name FROM asset_transaction t
                    WHERE t.owner_user_id=? AND t.asset_type='股票' AND t.asset_code=o.code
                      AND t.market=o.market AND t.trade_date<=?
                    ORDER BY t.trade_date DESC, t.id DESC LIMIT 1), o.code) AS name
                  FROM owned_symbols o LEFT JOIN stock st ON st.code=o.code AND st.market=o.market
                 WHERE o.code IS NOT NULL AND TRIM(o.code)<>'' AND o.market IS NOT NULL
                 ORDER BY o.market, o.code
                """, (rs, n) -> new Security(rs.getString("code"), rs.getString("name"), rs.getString("market")),
                ownerId, asOfDate, ownerId, asOfDate, ownerId, asOfDate, ownerId, asOfDate);
        Map<LocalDate, List<Position>> positions = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.snapshot_date, h.stock_code, h.market, h.shares
                  FROM asset_snapshot s LEFT JOIN stock_holding h ON h.snapshot_id=s.id
                 WHERE s.owner_user_id=? AND s.snapshot_date<=?
                 ORDER BY s.snapshot_date, h.id
                """, rs -> {
                    LocalDate date = rs.getObject("snapshot_date", LocalDate.class);
                    List<Position> items = positions.computeIfAbsent(date, ignored -> new ArrayList<>());
                    if (rs.getString("stock_code") != null)
                        items.add(new Position(rs.getString("stock_code"), rs.getString("market"), rs.getBigDecimal("shares")));
                }, ownerId, asOfDate);
        List<Snapshot> snapshots = new ArrayList<>(positions.entrySet().stream()
                .map(e -> new Snapshot(e.getKey(), e.getValue())).toList());
        // Header LEFT JOIN retains EMPTY as a real zero baseline only for its own market.
        Map<Long, List<Position>> dailyPositions = new LinkedHashMap<>();
        Map<Long, LocalDate> dailyDates = new LinkedHashMap<>();
        Map<Long, String> dailyMarkets = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.id,s.trading_date,s.market,p.stock_code,p.shares
                  FROM daily_inventory_snapshot s LEFT JOIN daily_inventory_position p ON p.snapshot_id=s.id
                 WHERE s.owner_user_id=? AND s.trading_date<=?
                   AND s.trading_date>=daily_inventory_retention_cutoff(s.market,statement_timestamp())
                 ORDER BY s.trading_date,s.market,p.id
                """, rs -> {
                    long id = rs.getLong("id");
                    dailyDates.put(id, rs.getObject("trading_date", LocalDate.class));
                    dailyMarkets.put(id, rs.getString("market"));
                    var items = dailyPositions.computeIfAbsent(id, ignored -> new ArrayList<>());
                    if (rs.getString("stock_code") != null)
                        items.add(new Position(rs.getString("stock_code"), rs.getString("market"), rs.getBigDecimal("shares")));
                }, ownerId, asOfDate);
        dailyPositions.forEach((id, items) -> snapshots.add(new Snapshot(dailyDates.get(id), items, dailyMarkets.get(id))));
        snapshots.sort(Comparator.comparing(Snapshot::date).thenComparing(s -> s.market() != null));
        List<Trade> trades = jdbc.query("""
                SELECT asset_code, market, trade_date, transaction_type, shares
                  FROM asset_transaction
                 WHERE owner_user_id=? AND asset_type='股票' AND trade_date<=?
                 ORDER BY trade_date, id
                """, (rs, n) -> new Trade(rs.getString("asset_code"), rs.getString("market"),
                        rs.getObject("trade_date", LocalDate.class), rs.getString("transaction_type"), rs.getBigDecimal("shares")),
                ownerId, asOfDate);
        List<Event> events = List.of();
        List<Market> markets = jdbc.query("SELECT code, display_name FROM market_type ORDER BY sort_order, code",
                (rs, n) -> new Market(rs.getString("code"), rs.getString("display_name")));
        return new Facts(securities, snapshots, trades, events, markets);
    }
}
