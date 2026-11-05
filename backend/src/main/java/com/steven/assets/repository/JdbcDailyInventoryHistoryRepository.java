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
}
