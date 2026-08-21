package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.AssetSnapshotMutationLock;
import com.steven.assets.service.SnapshotAggregateCalculator;
import com.steven.assets.service.StockMasterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The only DB-writing Fubon boundary; adapter I/O is completed before entry. */
@Service
@RequiredArgsConstructor
public class FubonInventoryWriter {
    private static final String MARKET_TW = "台股";
    private static final String BROKER_CODE = "fubon";

    private final AssetSnapshotMutationLock mutationLock;
    private final AssetSnapshotRepository snapshotRepository;
    private final BrokerRepository brokerRepository;
    private final StockMasterService stockMasterService;
    private final SnapshotAggregateCalculator aggregateCalculator;

    @Transactional
    public CommitResult replace(
            Long ownerUserId,
            Long expectedSnapshotId,
            LocalDate queryDate,
            List<PreparedPosition> positions,
            boolean emptyConfirmed) {
        // First DB operation in this transaction: owner-latest PESSIMISTIC_WRITE row lock.
        AssetSnapshot snapshot = mutationLock.lockLatestForOwner(ownerUserId)
                .orElseThrow(() -> new CommitRejected("NO_TODAY_SNAPSHOT"));
        if (!snapshot.getId().equals(expectedSnapshotId) || !queryDate.equals(snapshot.getSnapshotDate())) {
            throw new CommitRejected("LATEST_SNAPSHOT_CHANGED");
        }
        if (emptyConfirmed != positions.isEmpty()) {
            throw new CommitRejected("EMPTY_SEMANTICS_MISMATCH");
        }
        BrokerEntity broker = brokerRepository.findByCode(BROKER_CODE)
                .filter(candidate -> Boolean.TRUE.equals(candidate.getActive()))
                .orElseThrow(() -> new CommitRejected("BROKER_MISSING"));

        Map<String, List<StockHolding>> oldScope = new HashMap<>();
        for (StockHolding stock : snapshot.getStocks()) {
            if (isFubonTw(stock)) {
                oldScope.computeIfAbsent(stock.getStockCode(), ignored -> new ArrayList<>()).add(stock);
            }
        }
        int nextDisplayOrder = snapshot.getStocks().stream()
                .filter(stock -> MARKET_TW.equals(stock.getMarket()))
                .map(StockHolding::getDisplayOrder)
                .filter(order -> order != null)
                .max(Integer::compareTo)
                .orElse(-1) + 1;
        snapshot.getStocks().removeIf(this::isFubonTw);

        List<PreparedPosition> sorted = positions.stream()
                .sorted(Comparator.comparing(PreparedPosition::stockCode))
                .toList();
        for (PreparedPosition position : sorted) {
            validatePrepared(position);
            BigDecimal shares = BigDecimal.valueOf(position.shares());
            BigDecimal investmentCost = checkedMoney(position.costPrice().multiply(shares));
            BigDecimal currentValue = checkedMoney(position.actualPrice().multiply(shares));
            List<StockHolding> old = oldScope.getOrDefault(position.stockCode(), List.of());

            BigDecimal dividendRate = null;
            BigDecimal estimatedDividend = null;
            Integer displayOrder = null;
            if (old.size() == 1) {
                StockHolding previous = old.getFirst();
                displayOrder = previous.getDisplayOrder();
                if (isPositiveRate(previous.getDividendRate())) {
                    dividendRate = previous.getDividendRate();
                    estimatedDividend = currentValue.multiply(dividendRate)
                            .setScale(0, RoundingMode.HALF_UP);
                } else if (numericEquals(previous.getShares(), shares)
                        && numericEquals(previous.getCurrentValue(), currentValue)) {
                    estimatedDividend = previous.getEstimatedDividend();
                }
            }
            if (displayOrder == null) {
                Integer matchingOrder = snapshot.getStocks().stream()
                        .filter(stock -> MARKET_TW.equals(stock.getMarket()))
                        .filter(stock -> position.stockCode().equals(stock.getStockCode()))
                        .map(StockHolding::getDisplayOrder)
                        .filter(order -> order != null)
                        .findFirst()
                        .orElse(null);
                displayOrder = matchingOrder != null ? matchingOrder : nextDisplayOrder++;
            }

            stockMasterService.upsert(position.stockCode(), MARKET_TW, position.stockName());
            snapshot.getStocks().add(StockHolding.builder()
                    .snapshot(snapshot)
                    .stockCode(position.stockCode())
                    .market(MARKET_TW)
                    .broker(broker)
                    .shares(shares)
                    .investmentCost(investmentCost)
                    .currentValue(currentValue)
                    .estimatedDividend(estimatedDividend)
                    .dividendRate(dividendRate)
                    .currency("TWD")
                    .originalCurrencyValue(null)
                    .transactionType(null)
                    .transactionDate(null)
                    .transactionExchangeRate(null)
                    .displayOrder(displayOrder)
                    .build());
        }

        aggregateCalculator.recalculate(snapshot);
        snapshotRepository.saveAndFlush(snapshot);
        return new CommitResult(snapshot.getId(), sorted.size(), emptyConfirmed);
    }

    static BigDecimal checkedMoney(BigDecimal unrounded) {
        BigDecimal value = unrounded.setScale(2, RoundingMode.HALF_UP);
        if (value.precision() > 20) throw new CommitRejected("MONEY_PRECISION_EXCEEDED");
        return value;
    }

    private void validatePrepared(PreparedPosition position) {
        if (position == null || position.stockCode() == null || position.stockCode().isBlank()
                || position.stockName() == null || position.stockName().isBlank()
                || position.stockName().length() > 100
                || position.stockName().equalsIgnoreCase(position.stockCode())
                || position.shares() < 1 || position.shares() > ExactSharesDeserializer.MAX_SHARES
                || !validWireDecimal(position.costPrice())
                || !validWireDecimal(position.actualPrice())) {
            throw new CommitRejected("INVALID_PREPARED_POSITION");
        }
    }

    private boolean validWireDecimal(BigDecimal value) {
        return value != null && value.signum() > 0 && value.precision() <= 20
                && value.scale() >= 0 && value.scale() <= 10;
    }

    private boolean isFubonTw(StockHolding stock) {
        return MARKET_TW.equals(stock.getMarket())
                && stock.getBroker() != null
                && BROKER_CODE.equals(stock.getBroker().getCode());
    }

    private boolean isPositiveRate(BigDecimal value) {
        return value != null && value.signum() > 0 && value.precision() <= 10
                && value.scale() >= 0 && value.scale() <= 6;
    }

    private boolean numericEquals(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    public record PreparedPosition(
            String stockCode,
            long shares,
            BigDecimal costPrice,
            BigDecimal actualPrice,
            String stockName) {}

    public record CommitResult(Long snapshotId, int replaceCount, boolean emptyCleared) {}

    public static class CommitRejected extends RuntimeException {
        private final String reason;

        CommitRejected(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
