package com.steven.assets.integration.fubon;

import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** One independently committed transaction for a fully prepared, insert-only Fubon batch. */
@Service
@RequiredArgsConstructor
public class FubonTradeWriter {
    private final AssetTransactionRepository transactions;
    private final FubonSyncOwnerPort ownerPolicy;
    private final BrokerRepository brokers;
    private final FubonSyncFreshness freshness;

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public CommitResult insert(Long ownerId, Long expectedBrokerId, List<PreparedTrade> prepared) {
        // This is deliberately the first DB action in the writer. The locked dedicated owner
        // closes the race between service preflight and the insert-only ledger transaction.
        FubonSyncOwnerPort.LockedOwner lockedOwner;
        try {
            lockedOwner = ownerPolicy.lockAndRevalidate(ownerId);
        } catch (FubonSyncOwnerPort.Rejected rejected) {
            throw new WriteRejected(outcomeFor(rejected.denial()));
        }
        if (lockedOwner == null || ownerId == null || !ownerId.equals(lockedOwner.ownerId())) {
            throw new WriteRejected(FubonTradeOutcome.NO_OWNER);
        }
        BrokerEntity broker = brokers.findByCode("fubon").orElse(null);
        if (broker != null) freshness.refreshBroker(broker);
        if (broker == null || expectedBrokerId == null || !expectedBrokerId.equals(broker.getId())
                || !"fubon".equals(broker.getCode()) || !Boolean.TRUE.equals(broker.getActive())) {
            throw new WriteRejected(FubonTradeOutcome.BROKER_MISSING);
        }

        // Validate the complete immutable list before the first insert, including on direct
        // internal callers. SQL NUMERIC must never silently round a source quantity or price.
        List<PreparedTrade> batch = List.copyOf(prepared);
        for (PreparedTrade trade : batch) {
            if (trade.filledNo() == null || trade.filledNo().isEmpty() || trade.filledNo().length() > 50
                    || !FubonTradeContract.validCode(trade.stockCode()) || trade.tradeDate() == null
                    || !("買".equals(trade.transactionType()) || "賣".equals(trade.transactionType()))
                    || trade.stockName() == null || trade.stockName().isBlank()
                    || !FubonTradeContract.exactPositiveNumeric(trade.shares(), 15, 5)
                    || !FubonTradeContract.exactPositiveNumeric(trade.price(), 17, 6)
                    || trade.amount() == null || trade.amount().scale() != 2
                    || trade.amount().precision() > 20
                    || trade.amount().compareTo(FubonTradeContract.amount(trade.price(), trade.shares())) != 0) {
                throw new WriteRejected(FubonTradeOutcome.TRADE_FAILED);
            }
        }
        int inserted = 0;
        int existing = 0;
        for (PreparedTrade trade : batch) {
            int count = transactions.insertFubonTradeIfAbsent(ownerId, trade.filledNo(), trade.transactionType(),
                    trade.stockName(), trade.stockCode(), trade.tradeDate(), trade.shares(), trade.price(), trade.amount());
            if (count == 1) inserted++;
            else if (count == 0) existing++;
            else throw new IllegalStateException("UNEXPECTED_INSERT_COUNT");
        }
        transactions.flush();
        // No success metric here: deferred constraints may still fail when the proxy commits.
        return new CommitResult(inserted, existing);
    }

    public record PreparedTrade(String filledNo, String transactionType, String stockName,
            String stockCode, LocalDate tradeDate, BigDecimal shares, BigDecimal price, BigDecimal amount) {}

    public record CommitResult(int insertedCount, int skippedExistingCount) {}

    static final class WriteRejected extends RuntimeException {
        private final FubonTradeOutcome outcome;
        WriteRejected(FubonTradeOutcome outcome) { super(outcome.name()); this.outcome = outcome; }
        FubonTradeOutcome outcome() { return outcome; }
    }

    private static FubonTradeOutcome outcomeFor(FubonSyncOwnerPort.Denial denial) {
        return denial == FubonSyncOwnerPort.Denial.SYNC_OWNER_NOT_CONFIGURED
                ? FubonTradeOutcome.SYNC_OWNER_NOT_CONFIGURED : FubonTradeOutcome.NO_OWNER;
    }
}
