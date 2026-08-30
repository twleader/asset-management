package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BankDeposit;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.DepositTypeRepository;
import com.steven.assets.service.AssetSnapshotMutationLock;
import com.steven.assets.service.SnapshotAggregateCalculator;
import com.steven.assets.service.UserAdminService;
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

/** A short, separately proxied transaction for one managed snapshot and all its totals. */
@Service
public class FubonBankBalanceWriter {
    private static final String FUBON = "fubon";
    private static final String DEPOSIT_TYPE = "證券戶";
    private final AssetSnapshotMutationLock mutationLock;
    private final UserAdminService userAdminService;
    private final BrokerRepository brokerRepository;
    private final BankRepository bankRepository;
    private final DepositTypeRepository depositTypeRepository;
    private final SnapshotAggregateCalculator aggregates;
    private final AssetSnapshotRepository snapshotRepository;
    private final Clock clock;

    @Autowired
    public FubonBankBalanceWriter(AssetSnapshotMutationLock mutationLock, UserAdminService userAdminService,
            BrokerRepository brokerRepository, BankRepository bankRepository, DepositTypeRepository depositTypeRepository,
            SnapshotAggregateCalculator aggregates, AssetSnapshotRepository snapshotRepository) {
        this(mutationLock, userAdminService, brokerRepository, bankRepository, depositTypeRepository,
                aggregates, snapshotRepository, Clock.systemUTC());
    }

    FubonBankBalanceWriter(AssetSnapshotMutationLock mutationLock, UserAdminService userAdminService,
            BrokerRepository brokerRepository, BankRepository bankRepository, DepositTypeRepository depositTypeRepository,
            SnapshotAggregateCalculator aggregates, AssetSnapshotRepository snapshotRepository, Clock clock) {
        this.mutationLock = mutationLock;
        this.userAdminService = userAdminService;
        this.brokerRepository = brokerRepository;
        this.bankRepository = bankRepository;
        this.depositTypeRepository = depositTypeRepository;
        this.aggregates = aggregates;
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public BigDecimal write(Long ownerId, FubonDtos.BankBalance observation) {
        // This must precede EVERY database read, including owner and lookup-table checks.
        AssetSnapshot snapshot = mutationLock.lockLatestForOwner(ownerId)
                .orElseThrow(() -> new WriteRejected(FubonBankBalanceOutcome.NO_SNAPSHOT));
        AppUser owner = userAdminService.configuredAdmin().orElse(null);
        if (owner == null || ownerId == null || !ownerId.equals(owner.getId()) || !owner.isActive()
                || !owner.isAdmin() || !ownerId.equals(snapshot.getOwnerUserId())) {
            throw new WriteRejected(FubonBankBalanceOutcome.NO_OWNER);
        }
        if (brokerRepository.findByCode(FUBON).filter(b -> Boolean.TRUE.equals(b.getActive())).isEmpty()) {
            throw new WriteRejected(FubonBankBalanceOutcome.BROKER_MISSING);
        }
        Bank bank = bankRepository.findByCode(FUBON).filter(b -> Boolean.TRUE.equals(b.getActive()))
                .orElseThrow(() -> new WriteRejected(FubonBankBalanceOutcome.BANK_MISSING));
        if (depositTypeRepository.findByCode(DEPOSIT_TYPE).filter(t -> Boolean.TRUE.equals(t.getActive())).isEmpty()) {
            throw new WriteRejected(FubonBankBalanceOutcome.BANK_MISSING);
        }
        FubonAccountingContract.validateBank(observation, clock);
        FubonAccountingContract.fresh(observation.queryDate(), observation.observedAt(), clock);

        List<BankDeposit> targets = snapshot.getDeposits().stream()
                .filter(d -> d.getBank() != null && Objects.equals(d.getBank().getId(), bank.getId())
                        && DEPOSIT_TYPE.equals(d.getDepositType())).toList();
        if (targets.size() > 1 || (!targets.isEmpty() && !"TWD".equals(targets.get(0).getCurrency()))) {
            throw new WriteRejected(FubonBankBalanceOutcome.AMBIGUOUS_TARGET);
        }
        BigDecimal amount = FubonAccountingContract.money(observation.balance().value());
        BankDeposit target;
        if (targets.isEmpty()) {
            target = BankDeposit.builder().bank(bank).depositType(DEPOSIT_TYPE).currency("TWD").build();
            target.setSnapshot(snapshot);
            snapshot.getDeposits().add(target);
        } else {
            target = targets.get(0);
        }
        target.setAmount(amount);
        target.setOriginalAmount(null);
        aggregates.recalculate(snapshot);
        validateTotals(snapshot);
        snapshotRepository.saveAndFlush(snapshot);

        // A long lock/flush must not turn a prior-day or expired observation into a successful commit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                FubonAccountingContract.fresh(observation.queryDate(), observation.observedAt(), clock);
            }
        });
        return amount; // The caller sees this only after the Spring proxy has committed.
    }

    private void validateTotals(AssetSnapshot snapshot) {
        snapshot.setTotalDeposit(FubonAccountingContract.money(snapshot.getTotalDeposit()));
        snapshot.setTotalFundValue(FubonAccountingContract.money(snapshot.getTotalFundValue()));
        snapshot.setTotalFundCost(FubonAccountingContract.money(snapshot.getTotalFundCost()));
        snapshot.setTotalStockValue(FubonAccountingContract.money(snapshot.getTotalStockValue()));
        snapshot.setTotalStockCost(FubonAccountingContract.money(snapshot.getTotalStockCost()));
        snapshot.setTotalAssets(FubonAccountingContract.money(snapshot.getTotalAssets()));
        snapshot.setEstimatedAnnualDividend(FubonAccountingContract.money(snapshot.getEstimatedAnnualDividend()));
        if (snapshot.getRealizedGain() != null) FubonAccountingContract.money(snapshot.getRealizedGain());
    }

    static final class WriteRejected extends RuntimeException {
        private final FubonBankBalanceOutcome outcome;
        WriteRejected(FubonBankBalanceOutcome outcome) { super(outcome.name()); this.outcome = outcome; }
        FubonBankBalanceOutcome outcome() { return outcome; }
    }
}
