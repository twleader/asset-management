package com.steven.assets.service;

import com.steven.assets.model.*;
import com.steven.assets.repository.BrokerFilledTradeCostProjectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Provider-neutral, buy-only projection. Sell entries are ledger facts but never guessed. */
@Service
public class BrokerFilledTradeCostProjector {
    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";
    public static final String SKIPPED_NO_PRETRADE_BASIS = "SKIPPED_NO_PRETRADE_BASIS";
    private final BrokerFilledTradeCostProjectionRepository projections;
    private final AssetSnapshotMutationLock mutationLock;
    private final SnapshotAggregateCalculator aggregates;

    public BrokerFilledTradeCostProjector(BrokerFilledTradeCostProjectionRepository projections,
                                          AssetSnapshotMutationLock mutationLock,
                                          SnapshotAggregateCalculator aggregates) {
        this.projections = projections; this.mutationLock = mutationLock; this.aggregates = aggregates;
    }

    @Transactional
    public void projectNew(BrokerFilledTradeCostProjection projection) {
        projections.save(projection);
        applyOrPend(projection);
    }

    /** Must be called after the common owner/latest snapshot lock has been obtained. */
    public void applyOrPend(BrokerFilledTradeCostProjection projection) {
        if (!"買".equals(projection.getTransactionType())) {
            projection.setStatus(SKIPPED_NO_PRETRADE_BASIS);
            return;
        }
        AssetSnapshot snapshot = mutationLock.lockLatestForOwner(projection.getOwnerUserId()).orElse(null);
        if (snapshot == null) return;
        List<StockHolding> targets = snapshot.getStocks().stream().filter(s -> projection.getStockCode().equals(s.getStockCode())
                && "台股".equals(s.getMarket()) && "TWD".equals(s.getCurrency()) && s.getBroker() != null
                && projection.getBroker().getId().equals(s.getBroker().getId())).toList();
        if (targets.size() != 1) return;
        StockHolding target = targets.getFirst();
        BigDecimal currentCost = target.getInvestmentCost() == null ? BigDecimal.ZERO : target.getInvestmentCost();
        target.setInvestmentCost(currentCost.add(projection.getBuyCost()).setScale(2, RoundingMode.HALF_UP));
        projection.setStatus(APPLIED);
        aggregates.recalculate(snapshot);
    }

    /** Inventory's already locked replacement uses this after creating first-time zero-cost rows. */
    public void consumePendingBuys(AssetSnapshot snapshot, BrokerEntity broker, StockHolding target) {
        for (BrokerFilledTradeCostProjection p : projections.findPendingBuys(snapshot.getOwnerUserId(), broker.getId(), target.getStockCode())) {
            BigDecimal currentCost = target.getInvestmentCost() == null ? BigDecimal.ZERO : target.getInvestmentCost();
            target.setInvestmentCost(currentCost.add(p.getBuyCost()).setScale(2, RoundingMode.HALF_UP));
            p.setStatus(APPLIED);
        }
    }

    public static BigDecimal buyCost(BigDecimal amount, BigDecimal shares, BigDecimal price, BigDecimal fee, BigDecimal tax) {
        BigDecimal extras = (fee == null ? BigDecimal.ZERO : fee).add(tax == null ? BigDecimal.ZERO : tax);
        return amount.max(shares.multiply(price).add(extras)).setScale(2, RoundingMode.HALF_UP);
    }
}
