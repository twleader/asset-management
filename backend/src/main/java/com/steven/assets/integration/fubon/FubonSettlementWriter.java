package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BankDeposit;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.TransitFundType;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.TransitFundTypeRepository;
import com.steven.assets.service.AssetSnapshotMutationLock;
import com.steven.assets.service.SnapshotAggregateCalculator;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

@Service
public class FubonSettlementWriter {
    private static final String FUBON = "fubon";
    private static final String TRANSIT_TWD = "TRANSIT_TWD";
    private static final String PAYABLE = "買股待付款";
    private static final String RECEIVABLE = "賣股待收款";

    private final AssetSnapshotMutationLock mutationLock;
    private final FubonSyncOwnerPort ownerPolicy;
    private final BrokerRepository brokers;
    private final BankRepository banks;
    private final TransitFundTypeRepository transitTypes;
    private final SnapshotAggregateCalculator aggregates;
    private final AssetSnapshotRepository snapshots;
    private final Clock clock;

    @Autowired
    public FubonSettlementWriter(AssetSnapshotMutationLock mutationLock, FubonSyncOwnerPort ownerPolicy,
            BrokerRepository brokers, BankRepository banks, TransitFundTypeRepository transitTypes,
            SnapshotAggregateCalculator aggregates, AssetSnapshotRepository snapshots) {
        this(mutationLock, ownerPolicy, brokers, banks, transitTypes, aggregates, snapshots, Clock.systemUTC());
    }

    FubonSettlementWriter(AssetSnapshotMutationLock mutationLock, FubonSyncOwnerPort ownerPolicy,
            BrokerRepository brokers, BankRepository banks, TransitFundTypeRepository transitTypes,
            SnapshotAggregateCalculator aggregates, AssetSnapshotRepository snapshots, Clock clock) {
        this.mutationLock = mutationLock;
        this.ownerPolicy = ownerPolicy;
        this.brokers = brokers;
        this.banks = banks;
        this.transitTypes = transitTypes;
        this.aggregates = aggregates;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    CommitResult write(Long expectedOwnerId, FubonDtos.SettlementBatch observation,
            FubonAccountingContract.SettlementProjection projection) {
        FubonAccountingContract.validateSettlement(observation, clock);
        FubonAccountingContract.fresh(observation.queryDate(), observation.observedAt(), clock);
        if (projection == null) throw new WriteRejected(FubonSettlementOutcome.SETTLEMENT_FAILED);

        AssetSnapshot snapshot = mutationLock.lockLatestForFubonConfiguredOwner(expectedOwnerId)
                .orElseThrow(() -> new WriteRejected(FubonSettlementOutcome.NO_SNAPSHOT));
        FubonSyncOwnerPort.LockedOwner lockedOwner;
        try {
            lockedOwner = ownerPolicy.lockAndRevalidate(expectedOwnerId);
        } catch (FubonSyncOwnerPort.Rejected rejected) {
            throw new WriteRejected(outcomeFor(rejected.denial()));
        }
        if (lockedOwner == null || !Objects.equals(expectedOwnerId, lockedOwner.ownerId())
                || !Objects.equals(expectedOwnerId, snapshot.getOwnerUserId())) {
            throw new WriteRejected(FubonSettlementOutcome.NO_OWNER);
        }

        BrokerEntity broker = brokers.findByCode(FUBON).orElse(null);
        if (broker == null || !FUBON.equals(broker.getCode()) || !Boolean.TRUE.equals(broker.getActive())) {
            throw new WriteRejected(FubonSettlementOutcome.BROKER_MISSING);
        }
        Bank bank = banks.findByCode(FUBON).orElse(null);
        if (bank == null || !FUBON.equals(bank.getCode()) || !Boolean.TRUE.equals(bank.getActive())) {
            throw new WriteRejected(FubonSettlementOutcome.BANK_MISSING);
        }
        boolean changed = false;
        if (projection.payableAmount().signum() != 0) {
            requireTransitType(PAYABLE, true);
            changed |= projectDirection(snapshot, bank, PAYABLE, projection.payableAmount(), true);
        }
        if (projection.receivableAmount().signum() != 0) {
            requireTransitType(RECEIVABLE, false);
            changed |= projectDirection(snapshot, bank, RECEIVABLE, projection.receivableAmount(), false);
        }
        if (!changed) {
            return new CommitResult(false, projection.payableAmount(), projection.receivableAmount());
        }

        aggregates.recalculate(snapshot);
        snapshots.saveAndFlush(snapshot);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    FubonAccountingContract.fresh(observation.queryDate(), observation.observedAt(), clock);
                }
            });
        }
        return new CommitResult(true, projection.payableAmount(), projection.receivableAmount());
    }

    private void requireTransitType(String code, boolean payable) {
        TransitFundType type = transitTypes.findByCode(code).orElse(null);
        if (type == null || !code.equals(type.getCode()) || !Boolean.TRUE.equals(type.getActive())
                || !Boolean.valueOf(payable).equals(type.getPayable())) {
            throw new WriteRejected(FubonSettlementOutcome.BANK_MISSING);
        }
    }

    private boolean projectDirection(AssetSnapshot snapshot, Bank bank, String type, BigDecimal sourceAmount,
            boolean payable) {
        BigDecimal amount = FubonAccountingContract.money(sourceAmount);
        if ((payable && amount.signum() >= 0) || (!payable && amount.signum() <= 0)) {
            throw new WriteRejected(FubonSettlementOutcome.SETTLEMENT_FAILED);
        }
        List<BankDeposit> candidates = snapshot.getDeposits().stream()
                .filter(deposit -> deposit.getBank() != null
                        && Objects.equals(deposit.getBank().getId(), bank.getId())
                        && type.equals(deposit.getDepositType()))
                .toList();
        if (candidates.size() > 1 || (!candidates.isEmpty()
                && !TRANSIT_TWD.equals(candidates.getFirst().getCurrency()))) {
            throw new WriteRejected(FubonSettlementOutcome.AMBIGUOUS_TARGET);
        }
        if (candidates.isEmpty()) {
            BankDeposit created = BankDeposit.builder()
                    .snapshot(snapshot).bank(bank).depositType(type).currency(TRANSIT_TWD)
                    .amount(amount).originalAmount(null).annualInterestRate(null).build();
            snapshot.getDeposits().add(created);
            return true;
        }
        BankDeposit target = candidates.getFirst();
        BigDecimal existing = target.getAmount() == null ? null : FubonAccountingContract.money(target.getAmount());
        if (existing != null && existing.compareTo(amount) == 0) return false;
        target.setAmount(amount);
        target.setOriginalAmount(null);
        target.setAnnualInterestRate(null);
        return true;
    }

    private static FubonSettlementOutcome outcomeFor(FubonSyncOwnerPort.Denial denial) {
        return denial == FubonSyncOwnerPort.Denial.SYNC_OWNER_NOT_CONFIGURED
                ? FubonSettlementOutcome.SYNC_OWNER_NOT_CONFIGURED : FubonSettlementOutcome.NO_OWNER;
    }

    record CommitResult(boolean changed, BigDecimal payableAmount, BigDecimal receivableAmount) {}

    static final class WriteRejected extends RuntimeException {
        private final FubonSettlementOutcome outcome;

        WriteRejected(FubonSettlementOutcome outcome) {
            super(outcome.name());
            this.outcome = outcome;
        }

        FubonSettlementOutcome outcome() { return outcome; }
    }
}
