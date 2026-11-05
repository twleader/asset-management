package com.steven.assets.repository;

import com.steven.assets.model.DailyInventory.Capture;
import com.steven.assets.service.DailyInventoryHistoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;

@Repository
public class JdbcDailyInventoryHistoryRepository implements DailyInventoryHistoryRepository {
    private final JdbcTemplate jdbc;
    public JdbcDailyInventoryHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(Capture c) {
        var ids = jdbc.query("""
                INSERT INTO daily_inventory_snapshot
                  (owner_user_id,market,trading_date,session_close_at,scheduled_at,captured_at,
                   source_snapshot_id,source_snapshot_date,status)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (owner_user_id,market,trading_date) DO NOTHING RETURNING id
                """, (rs, n) -> rs.getLong(1), c.ownerId(), c.session().market(), c.session().tradingDate(),
                Timestamp.from(c.session().close()), Timestamp.from(c.session().scheduledAt()),
                Timestamp.from(c.capturedAt()), c.sourceSnapshotId(), c.sourceSnapshotDate(),
                c.positions().isEmpty() ? "EMPTY" : "SAVED");
        if (ids.isEmpty()) return false;
        long id = ids.getFirst();
        for (var p : c.positions()) jdbc.update("""
                INSERT INTO daily_inventory_position
                  (snapshot_id,source_holding_id,stock_code,broker_id,shares,investment_cost,currency,transaction_exchange_rate)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, p.sourceHoldingId(), p.code(), p.brokerId(), p.shares(), p.investmentCost(),
                p.currency(), p.transactionExchangeRate());
        return true;
    }

    @Override @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpired() {
        // Never accept a caller-provided date: DB guards and selection use authoritative DB time.
        var at = jdbc.queryForObject("SELECT statement_timestamp()", java.time.OffsetDateTime.class);
        var ids = jdbc.query("""
                SELECT id FROM daily_inventory_snapshot
                 WHERE (market='台股' AND trading_date<daily_inventory_retention_cutoff('台股',?))
                    OR (market='美股' AND trading_date<daily_inventory_retention_cutoff('美股',?))
                 ORDER BY id FOR UPDATE
                """, (rs, n) -> rs.getLong(1), at, at);
        if (ids.isEmpty()) return 0;
        // A fixed, already-locked ID set keeps children and EMPTY/SAVED headers in one transaction.
        Long[] values = ids.toArray(Long[]::new);
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var array = connection.createArrayOf("bigint", values);
            try (var child = connection.prepareStatement("DELETE FROM daily_inventory_position WHERE snapshot_id=ANY(?)");
                 var parent = connection.prepareStatement("DELETE FROM daily_inventory_snapshot WHERE id=ANY(?)")) {
                child.setArray(1, array); child.executeUpdate();
                parent.setArray(1, array); parent.executeUpdate();
            } finally { array.free(); }
            return null;
        });
        return ids.size();
    }
}
