package com.steven.assets.service;

import com.steven.assets.model.DailyInventory.*;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.AssetSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
public class DailyInventoryCaptureService {
    private static final ZoneId TPE = ZoneId.of("Asia/Taipei");
    private final AssetSnapshotMutationLock locks;
    private final AssetSnapshotRepository snapshots;
    private final DailyInventoryHistoryRepository history;
    private final Clock clock;
    @Autowired
    public DailyInventoryCaptureService(AssetSnapshotMutationLock locks, AssetSnapshotRepository snapshots,
                                       DailyInventoryHistoryRepository history) {
        this(locks, snapshots, history, Clock.systemUTC());
    }
    DailyInventoryCaptureService(AssetSnapshotMutationLock locks, AssetSnapshotRepository snapshots,
                                DailyInventoryHistoryRepository history, Clock clock) {
        this.locks = locks; this.snapshots = snapshots; this.history = history; this.clock = clock;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean capture(long ownerId, Session session) {
        if (ownerId <= 0) throw new IllegalArgumentException("INVENTORY_OWNER_INVALID");
        // First DB operation: share the source root lock with every existing inventory mutation.
        var source = locks.lockLatestForOwner(ownerId).orElse(null);
        if (source == null) return false;
        Instant capturedAt = clock.instant();
        if (!session.captureWindow(capturedAt)) return false;
        // A newer root may have committed while our SELECT FOR UPDATE was waiting.
        var latest = snapshots.findFirstByOwnerUserIdOrderBySnapshotDateDesc(ownerId).orElse(null);
        if (latest == null || !Objects.equals(source.getId(), latest.getId())) return false;
        if (!Objects.equals(source.getOwnerUserId(), ownerId) || source.getId() == null
                || source.getSnapshotDate() == null || source.getSnapshotDate().isAfter(capturedAt.atZone(TPE).toLocalDate()))
            throw new IllegalStateException("INVENTORY_SOURCE_UNAVAILABLE");
        List<Position> positions = new ArrayList<>();
        for (StockHolding h : source.getStocks()) {
            if (!session.market().equals(h.getMarket())) continue;
            if (h.getId() == null || h.getId() <= 0 || h.getStockCode() == null || h.getStockCode().isBlank()
                    || h.getStockCode().length() > 20 || (h.getCurrency() != null && h.getCurrency().length() > 3))
                throw new IllegalStateException("INVENTORY_POSITION_INVALID");
            decimal(h.getShares(), 15, 5, true);
            decimal(h.getInvestmentCost(), 20, 2, true);
            if (h.getTransactionExchangeRate() != null) decimal(h.getTransactionExchangeRate(), 10, 4, false);
            positions.add(new Position(h.getId(), h.getStockCode(), h.getBroker() == null ? null : h.getBroker().getId(),
                    h.getShares(), h.getInvestmentCost(), h.getCurrency(), h.getTransactionExchangeRate()));
        }
        return history.insert(new Capture(ownerId, session, capturedAt, source.getId(), source.getSnapshotDate(), positions));
    }
    private static void decimal(BigDecimal value, int precision, int scale, boolean nonnegative) {
        try {
            if (value == null || (nonnegative && value.signum() < 0) || value.setScale(scale, RoundingMode.UNNECESSARY).precision() > precision)
                throw new IllegalStateException("INVENTORY_DECIMAL_INVALID");
        } catch (ArithmeticException invalid) { throw new IllegalStateException("INVENTORY_DECIMAL_INVALID", invalid); }
    }
}
