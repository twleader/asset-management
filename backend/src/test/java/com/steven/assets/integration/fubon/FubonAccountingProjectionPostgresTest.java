package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BankDeposit;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.RealizedGain;
import com.steven.assets.model.TransitFundType;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.RealizedGainRepository;
import com.steven.assets.repository.TransitFundTypeRepository;
import com.steven.assets.service.AssetSnapshotMutationLock;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.AssetClassifier;
import com.steven.assets.service.FundDividendService;
import com.steven.assets.service.FundNavService;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.SnapshotStockScopeOwnershipPort;
import com.steven.assets.service.SnapshotAggregateCalculator;
import com.steven.assets.service.StockMasterService;
import com.steven.assets.service.UserAdminService;
import com.steven.assets.service.fubon.FubonLocalNamePort;
import com.steven.assets.service.fubon.FubonSyncOwnerPolicy;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Real PostgreSQL coverage for the two accounting writers. The adapter is always a Mockito fake;
 * this class never loads a broker SDK or a real Fubon credential.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.liquibase.enabled=false",
        "app.admin-email=sync-owner@example.invalid"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AssetSnapshotMutationLock.class, SnapshotAggregateCalculator.class, UserAdminService.class, AssetService.class,
        FubonAccountingProjectionPostgresTest.Config.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FubonAccountingProjectionPostgresTest {
    private static final String OWNER_EMAIL = "sync-owner@example.invalid";
    private static final String FUBON = "fubon";
    private static final String PAYABLE = "買股待付款";
    private static final String RECEIVABLE = "賣股待收款";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fubon_accounting_projection")
            .withUsername("assets")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean com.steven.assets.service.HoldingCostCachePort holdingCostCache;
    @MockitoBean FubonConfigState configState;
    @MockitoBean FubonBrokerClient brokerClient;
    @MockitoBean StockMasterService stockNames;
    @MockitoBean MarketDataService marketDataService;
    @MockitoBean FundNavService fundNavService;
    @MockitoBean FundDividendService fundDividendService;
    @MockitoBean AssetClassifier assetClassifier;
    @MockitoBean SnapshotStockScopeOwnershipPort stockScopeOwnershipPort;
    @MockitoBean com.steven.assets.security.TenantGuard tenantGuard;
    @MockitoSpyBean SnapshotAggregateCalculator aggregates;

    @Autowired AssetSnapshotRepository snapshots;
    @Autowired AssetService assetService;
    @Autowired AssetTransactionRepository assetTransactions;
    @Autowired AppUserRepository users;
    @Autowired BankRepository banks;
    @Autowired BrokerRepository brokers;
    @Autowired TransitFundTypeRepository transitTypes;
    @Autowired RealizedGainRepository gains;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired FubonSettlementSyncService settlementService;
    @Autowired FubonRealizedGainSyncService realizedService;
    @Autowired FubonSettlementWriter settlementWriter;
    @Autowired FubonRealizedGainWriter realizedWriter;
    @Autowired MutableClock clock;
    @Autowired SqlTrace sqlTrace;

    private Long ownerId;
    private Long snapshotId;
    private Bank fubonBank;
    private Bank otherBank;

    @BeforeEach
    void seed() {
        reset(configState, brokerClient, stockNames);
        clearInvocations(aggregates);
        clock.set(NOW);
        sqlTrace.stop();
        tx(() -> {
            entityManager.createNativeQuery("DROP TRIGGER IF EXISTS reject_settlement_commit ON bank_deposit")
                    .executeUpdate();
            entityManager.createNativeQuery("DROP FUNCTION IF EXISTS reject_settlement_commit()").executeUpdate();
            entityManager.createNativeQuery("DROP TRIGGER IF EXISTS reject_realized_gain_commit ON realized_gain")
                    .executeUpdate();
            entityManager.createNativeQuery("DROP FUNCTION IF EXISTS reject_realized_gain_commit()").executeUpdate();
            gains.deleteAll();
            gains.flush();
            assetTransactions.deleteAll();
            assetTransactions.flush();
            snapshots.deleteAll();
            snapshots.flush();
            transitTypes.deleteAll();
            banks.deleteAll();
            brokers.deleteAll();
            users.deleteAll();
            entityManager.flush();

            ownerId = users.saveAndFlush(AppUser.builder().email(OWNER_EMAIL)
                    .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()).getId();
            users.saveAndFlush(AppUser.builder().email("other-admin@example.invalid")
                    .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build());
            fubonBank = banks.saveAndFlush(Bank.builder().code(FUBON).displayName("富邦銀行").active(true).build());
            otherBank = banks.saveAndFlush(Bank.builder().code("other").displayName("其他銀行").active(true).build());
            brokers.saveAndFlush(BrokerEntity.builder().code(FUBON).displayName("富邦證券").active(true).build());
            transitTypes.saveAndFlush(TransitFundType.builder().code(PAYABLE).displayName(PAYABLE)
                    .payable(true).sortOrder(1).active(true).build());
            transitTypes.saveAndFlush(TransitFundType.builder().code(RECEIVABLE).displayName(RECEIVABLE)
                    .payable(false).sortOrder(2).active(true).build());

            AssetSnapshot snapshot = AssetSnapshot.builder().ownerUserId(ownerId).snapshotDate(DATE.minusDays(1))
                    .usdExchangeRate(BigDecimal.ONE).build();
            snapshot.getDeposits().add(BankDeposit.builder().snapshot(snapshot).bank(otherBank).depositType("活存")
                    .currency("TWD").amount(new BigDecimal("100.00")).build());
            aggregates.recalculate(snapshot);
            snapshotId = snapshots.saveAndFlush(snapshot).getId();
            entityManager.createNativeQuery("CREATE UNIQUE INDEX IF NOT EXISTS uq_realized_gain_fubon_sync_occurrence "
                    + "ON realized_gain(owner_user_id, sync_source, sync_fingerprint, sync_occurrence) "
                    + "WHERE sync_source IS NOT NULL").executeUpdate();
        });
        when(configState.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.READY, "test-only", "READY"));
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlement()));
        when(brokerClient.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(realized()));
        when(stockNames.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        clearInvocations(aggregates);
    }

    @Test
    void servicesAndWritersAreTransactionalProxies() {
        assertThat(AopUtils.isAopProxy(settlementService)).isTrue();
        assertThat(AopUtils.isAopProxy(realizedService)).isTrue();
        assertThat(AopUtils.isAopProxy(settlementWriter)).isTrue();
        assertThat(AopUtils.isAopProxy(realizedWriter)).isTrue();
    }

    @Test
    void settlementWriterFirstSqlIsOwnerScopedSnapshotLockThenFreshOwnerLock() {
        when(brokerClient.readSettlement()).thenAnswer(call -> {
            sqlTrace.start();
            return FubonDtos.CallResult.success(settlement());
        });

        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);

        List<String> statements = sqlTrace.stop();
        assertThat(statements).isNotEmpty();
        assertThat(statements.getFirst()).contains("from asset_snapshot").contains("for update")
                .doesNotContain("app_user", "bank_deposit");
        assertThat(statements).anyMatch(sql -> sql.contains("from app_user") && sql.contains("for update"));
    }

    @Test
    void settlementDryRunTakesNoWriteLockAndDoesNotMutateSnapshot() {
        sqlTrace.start();

        FubonSettlementSyncService.SyncResult result = settlementService.syncManual(true);

        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.DRY_RUN);
        assertThat(sqlTrace.stop()).noneMatch(sql -> sql.contains("for update") || sql.contains("lock table")
                || sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete"));
        readSnapshot(snapshot -> assertThat(snapshot.getDeposits()).hasSize(1));
    }

    @Test
    void settlementCreatesOnlyTheNonzeroPayableTransitTarget() {
        FubonSettlementSyncService.SyncResult result = settlementService.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        assertThat(result.payableAmount()).isEqualByComparingTo("-1002.00");
        assertThat(result.receivableAmount()).isZero();
        readSnapshot(snapshot -> {
            BankDeposit target = transit(snapshot, PAYABLE).getFirst();
            assertThat(target.getAmount()).isEqualByComparingTo("-1002.00");
            assertThat(target.getCurrency()).isEqualTo("TRANSIT_TWD");
            assertThat(target.getOriginalAmount()).isNull();
            assertThat(target.getAnnualInterestRate()).isNull();
            assertThat(target.getSource()).isEqualTo("FUBON_SYNC");
            assertThat(target.getProcessingDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(target.getNotes()).isEqualTo("富邦證券淨交割款；交易日 2026-08-28；交割日 2026-09-01");
            assertThat(snapshot.getDeposits()).hasSize(2);
            assertThat(transit(snapshot, RECEIVABLE)).isEmpty();
            assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("-902.00");
        });
    }

    @Test
    void settlementUpdatePreservesExistingNoteWithoutNewVerifiedCodesAndClearsOnlyDerivedRateFields() {
        addTransit(PAYABLE, "-5.00", "TRANSIT_TWD", "10.00", "keep-note", "5.0000");

        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);

        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).hasSize(1);
            BankDeposit target = transit(snapshot, PAYABLE).getFirst();
            assertThat(target.getAmount()).isEqualByComparingTo("-1002.00");
            assertThat(target.getNotes()).isEqualTo("keep-note");
            assertThat(target.getAnnualInterestRate()).isNull();
            assertThat(target.getOriginalAmount()).isNull();
        });
    }

    @Test
    void settlementNeverMutatesAUniqueManualTarget() {
        addTransit(PAYABLE, "-5.00", "TRANSIT_TWD", null, "manual-note", null, "MANUAL");

        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.UNMANAGED_TARGET);

        readSnapshot(snapshot -> {
            BankDeposit target = transit(snapshot, PAYABLE).getFirst();
            assertThat(target.getAmount()).isEqualByComparingTo("-5.00");
            assertThat(target.getNotes()).isEqualTo("manual-note");
            assertThat(target.getSource()).isEqualTo("MANUAL");
        });
    }

    @Test
    void completeBuyLedgerAtomicallyReplacesManual00881WithOneOfficialPayableAndRepeatsOnce() {
        prepareRecovery();
        Long manualId = addRecoveryTransit("-46486", "00881");
        var result = settlementService.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        AtomicReference<Long> officialId = new AtomicReference<>();
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).hasSize(1);
            var row = transit(snapshot, PAYABLE).getFirst();
            officialId.set(row.getId());
            assertThat(row.getId()).isNotEqualTo(manualId);
            assertThat(row.getAmount()).isEqualByComparingTo("-56586");
            assertThat(row.getSource()).isEqualTo("FUBON_SYNC");
            assertThat(row.getNotes()).isEqualTo("富邦證券已記錄成交：買 00881、009826；淨付 56586");
            assertThat(row.getProcessingDate()).isEqualTo(LocalDate.of(2026, 11, 4));
            assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("-56486");
        });
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).hasSize(1);
            assertThat(transit(snapshot, PAYABLE).getFirst().getId()).isEqualTo(officialId.get());
            assertThat(transit(snapshot, PAYABLE).getFirst().getNotes()).contains("00881", "009826");
        });
    }

    @Test
    void crossDayWithoutNewCompleteLedgerKeepsBothCodesAndOfficialProcessingDate() {
        prepareRecovery();
        addRecoveryTransit("-46486", "00881");
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        tx(() -> assetTransactions.deleteAllInBatch());
        clock.set(Instant.parse("2026-11-03T00:00:30Z"));
        var yesterday = recoverySettlement();
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(new FubonDtos.SettlementBatch(
                LocalDate.of(2026, 11, 3), clock.instant().minusSeconds(1), FINGERPRINT, true,
                yesterday.coverageStatus(), null, yesterday.details())));
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        readSnapshot(snapshot -> {
            var row = transit(snapshot, PAYABLE).getFirst();
            assertThat(row.getNotes()).contains("00881", "009826");
            assertThat(row.getProcessingDate()).isEqualTo(LocalDate.of(2026, 11, 4));
        });
    }

    @Test
    void incompleteLedgerAndDuplicateOrMismatchedManualRowsRemainUnmanagedWithoutMutation() {
        prepareRecovery();
        addRecoveryTransit("-46486", "00881");
        tx(() -> assetTransactions.deleteAllInBatch());
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.UNMANAGED_TARGET);
        readSnapshot(snapshot -> assertThat(transit(snapshot, PAYABLE).getFirst().getSource()).isEqualTo("MANUAL"));
        seedRecoveryLedger();
        addRecoveryTransit("-46486", "00881");
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.UNMANAGED_TARGET);
        readSnapshot(snapshot -> assertThat(transit(snapshot, PAYABLE)).hasSize(2));
        tx(() -> {
            var snapshot = snapshots.findById(snapshotId).orElseThrow();
            snapshot.getDeposits().removeIf(row -> PAYABLE.equals(row.getDepositType()));
            snapshots.saveAndFlush(snapshot);
        });
        addRecoveryTransit("-46420", "00881");
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.UNMANAGED_TARGET);
        readSnapshot(snapshot -> assertThat(transit(snapshot, PAYABLE).getFirst().getAmount())
                .isEqualByComparingTo("-46420"));
    }

    @Test
    void deferredSettlementCommitFailureRestoresRemovedManualRowAndAllAggregates() {
        prepareRecovery();
        Long manualId = addRecoveryTransit("-46486", "00881");
        tx(() -> {
            entityManager.createNativeQuery("CREATE FUNCTION reject_settlement_commit() RETURNS trigger LANGUAGE plpgsql "
                    + "AS 'BEGIN RAISE EXCEPTION ''test-only deferred rejection''; RETURN NEW; END'").executeUpdate();
            entityManager.createNativeQuery("CREATE CONSTRAINT TRIGGER reject_settlement_commit AFTER INSERT ON bank_deposit "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_settlement_commit()")
                    .executeUpdate();
        });
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.ROLLED_BACK);
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).hasSize(1);
            var row = transit(snapshot, PAYABLE).getFirst();
            assertThat(row.getId()).isEqualTo(manualId);
            assertThat(row.getSource()).isEqualTo("MANUAL");
            assertThat(row.getAmount()).isEqualByComparingTo("-46486");
            assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("-46386");
        });
    }

    @Test
    void incompleteOrMismatchedLedgerCannotClaimSettlementNoteEvidence() {
        tx(() -> assetTransactions.saveAllAndFlush(List.of(
                ledger(ownerId, "買", "00719B", "元大美債1-3", "1000", "FUBON_SYNC"),
                ledger(ownerId, "賣", "2885", "元大金", "1000", "FUBON_SYNC"),
                ledger(ownerId, "買", "2330", "台積電", "1000", "MANUAL"))));

        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);

        readSnapshot(snapshot -> assertThat(transit(snapshot, PAYABLE).getFirst().getNotes())
                .isEqualTo("富邦證券淨交割款；交易日 2026-08-28；交割日 2026-09-01"));
    }

    @Test
    void settlementCanonicalSameDataDoesNotRecalculateOrEmitDml() {
        addTransit(PAYABLE, "-1002.000", "TRANSIT_TWD", null, "富邦證券交割款；當日無已同步成交明細", null);
        clearInvocations(aggregates);
        when(brokerClient.readSettlement()).thenAnswer(call -> {
            sqlTrace.start();
            return FubonDtos.CallResult.success(settlement());
        });

        FubonSettlementSyncService.SyncResult result = settlementService.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        verify(aggregates, never()).recalculate(any());
        assertThat(sqlTrace.stop()).noneMatch(sql -> sql.startsWith("insert") || sql.startsWith("update"));
        readSnapshot(snapshot -> assertThat(transit(snapshot, PAYABLE).getFirst().getNotes())
                .isEqualTo("富邦證券交割款；當日無已同步成交明細"));
    }

    @Test
    void officialMixedNetReplacesGrossRowsKeepsOtherDayAndRefreshesManualLedgerNoteIdempotently() {
        String row = netRow("-1002", "500", "-502");
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                read(settlementJson(row), FubonDtos.SettlementBatch.class)));
        Long payableId = addTransit(PAYABLE, "-1002", "TRANSIT_TWD", null, "generic", null);
        addTransit(RECEIVABLE, "500", "TRANSIT_TWD", null, "generic", null);
        tx(() -> {
            var snapshot = snapshots.findById(snapshotId).orElseThrow();
            snapshot.getDeposits().add(BankDeposit.builder().snapshot(snapshot).bank(fubonBank)
                    .depositType(PAYABLE).currency("TRANSIT_TWD").amount(new BigDecimal("-43181"))
                    .processingDate(DATE.plusDays(6)).notes("prior day").source("FUBON_SYNC").build());
            assetTransactions.saveAllAndFlush(List.of(noteLedger("買", "00881", "1000", "2", "0"),
                    noteLedger("賣", "00881", "500", "0", "0")));
            snapshots.saveAndFlush(snapshot);
        });
        var result = settlementService.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        assertThat(result.payableAmount()).isEqualByComparingTo("-502");
        assertThat(result.receivableAmount()).isZero();
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, RECEIVABLE)).isEmpty();
            assertThat(transit(snapshot, PAYABLE)).hasSize(2);
            var net = transit(snapshot, PAYABLE).stream().filter(r -> r.getId().equals(payableId)).findFirst().orElseThrow();
            assertThat(net.getAmount()).isEqualByComparingTo("-502");
            assertThat(net.getNotes()).isEqualTo("富邦證券已記錄成交：買 00881；賣 00881；淨付 502");
            assertThat(transit(snapshot, PAYABLE).stream().filter(r -> r.getNotes().equals("prior day")))
                    .singleElement().satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("-43181"));
        });
        clearInvocations(aggregates);
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        verify(aggregates, never()).recalculate(any());
    }

    @Test
    void mixedNetReceivableAndNetZeroReplaceOnlySameDateSourceOwnedRows() {
        addTransit(PAYABLE, "-1002", "TRANSIT_TWD", null, "old", null);
        addTransit(RECEIVABLE, "1500", "TRANSIT_TWD", null, "old", null);
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                read(settlementJson(netRow("-1002", "1500", "498")), FubonDtos.SettlementBatch.class)));
        var result = settlementService.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        assertThat(result.payableAmount()).isZero();
        assertThat(result.receivableAmount()).isEqualByComparingTo("498");
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).isEmpty();
            assertThat(transit(snapshot, RECEIVABLE)).singleElement()
                    .satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("498"));
        });
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                read(settlementJson(netRow("-1002", "1002", "0")), FubonDtos.SettlementBatch.class)));
        result = settlementService.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        assertThat(result.payableAmount()).isZero();
        assertThat(result.receivableAmount()).isZero();
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).isEmpty();
            assertThat(transit(snapshot, RECEIVABLE)).isEmpty();
            assertThat(snapshot.getDeposits()).hasSize(1);
            assertThat(snapshot.getDeposits().getFirst().getAmount()).isEqualByComparingTo("100");
        });
    }

    @Test
    void mixedNetCanHandoverGrossManualBuyOnlyAfterBothDirectionGuardsPass() {
        tx(() -> assetTransactions.saveAndFlush(noteLedger("買", "00881", "1000", "2", "0")));
        Long manual = addTransit(PAYABLE, "-1002", "TRANSIT_TWD", null, "00881", null, "MANUAL");
        Long conflicting = addTransit(RECEIVABLE, "500", "TRANSIT_TWD", null, "manual", null, "MANUAL");
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlementWithBothDirections()));
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.UNMANAGED_TARGET);
        readSnapshot(snapshot -> assertThat(transit(snapshot, PAYABLE)).singleElement()
                .satisfies(r -> assertThat(r.getId()).isEqualTo(manual)));
        tx(() -> {
            var snapshot = snapshots.findById(snapshotId).orElseThrow();
            snapshot.getDeposits().removeIf(r -> conflicting.equals(r.getId()));
            snapshots.saveAndFlush(snapshot);
        });
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        readSnapshot(snapshot -> assertThat(transit(snapshot, PAYABLE)).singleElement().satisfies(r -> {
            assertThat(r.getId()).isNotEqualTo(manual);
            assertThat(r.getAmount()).isEqualByComparingTo("-502");
            assertThat(r.getSource()).isEqualTo("FUBON_SYNC");
        }));
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        readSnapshot(snapshot -> assertThat(transit(snapshot, PAYABLE)).hasSize(1));
    }

    @Test
    void matchedManualIsNotDeletedWhenOppositeDirectionHasInvalidCurrencyNullDateOrDuplicate() {
        tx(() -> assetTransactions.saveAndFlush(noteLedger("買", "00881", "1000", "2", "0")));
        Long manual = addTransit(PAYABLE, "-1002", "TRANSIT_TWD", null, "00881", null, "MANUAL");
        addTransit(RECEIVABLE, "500", "USD", null, "bad", null);
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlementWithBothDirections()));
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_TARGET);
        tx(() -> {
            var row = transit(snapshots.findById(snapshotId).orElseThrow(), RECEIVABLE).getFirst();
            row.setCurrency("TRANSIT_TWD");
            row.setProcessingDate(null);
        });
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_TARGET);
        tx(() -> transit(snapshots.findById(snapshotId).orElseThrow(), RECEIVABLE).getFirst()
                .setProcessingDate(LocalDate.of(2026, 9, 1)));
        addTransit(RECEIVABLE, "1", "TRANSIT_TWD", null, "duplicate", null);
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_TARGET);
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).singleElement().satisfies(r -> {
                assertThat(r.getId()).isEqualTo(manual);
                assertThat(r.getAmount()).isEqualByComparingTo("-1002");
                assertThat(r.getSource()).isEqualTo("MANUAL");
            });
            assertThat(transit(snapshot, RECEIVABLE)).hasSize(2);
        });
    }

    @Test
    void netRowDeletionRollsBackWhenAggregateWriteFails() {
        addTransit(PAYABLE, "-1002", "TRANSIT_TWD", null, "buy", null);
        addTransit(RECEIVABLE, "500", "TRANSIT_TWD", null, "sell", null);
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlementWithBothDirections()));
        doThrow(new IllegalStateException("test-only aggregate failure")).when(aggregates).recalculate(any());
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.ROLLED_BACK);
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).singleElement()
                    .satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("-1002"));
            assertThat(transit(snapshot, RECEIVABLE)).singleElement()
                    .satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("500"));
        });
    }

    @Test
    void sellOnlyOfficialRowProducesSingleReceivable() {
        String row = netRow("0", "500", "500")
                .replace("\"buyValue\":\"1000\"", "\"buyValue\":\"0\"")
                .replace("\"buyFee\":\"2\"", "\"buyFee\":\"0\"")
                .replace("\"totalFee\":\"2\"", "\"totalFee\":\"0\"")
                .replace("\"totalBsValue\":\"1500\"", "\"totalBsValue\":\"500\"");
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                read(settlementJson(row), FubonDtos.SettlementBatch.class)));
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).isEmpty();
            assertThat(transit(snapshot, RECEIVABLE)).singleElement()
                    .satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("500"));
        });
    }

    private com.steven.assets.model.AssetTransaction noteLedger(String direction, String code,
            String amount, String fee, String tax) {
        var row = ledger(ownerId, direction, code, code, amount, "MANUAL");
        row.setFee(new BigDecimal(fee));
        row.setTransactionTax(new BigDecimal(tax));
        return row;
    }

    private static String netRow(String buy, String sell, String total) {
        return SETTLEMENT_ROW.replace("\"buySettlement\":\"-1002\"", "\"buySettlement\":\"" + buy + "\"")
                .replace("\"sellValue\":\"0\"", "\"sellValue\":\"" + sell + "\"")
                .replace("\"sellSettlement\":\"0\"", "\"sellSettlement\":\"" + sell + "\"")
                .replace("\"totalBsValue\":\"1000\"", "\"totalBsValue\":\"" + new BigDecimal("1000").add(new BigDecimal(sell)) + "\"")
                .replace("\"totalSettlementAmount\":\"-1002\"", "\"totalSettlementAmount\":\"" + total + "\"");
    }

    @Test
    void settlementZeroDirectionsNeverClearExistingTransitTarget() {
        addTransit(PAYABLE, "-77.00", "TRANSIT_TWD", "4.00", "retain", "77.0000");
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(zeroSettlement()));

        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);

        readSnapshot(snapshot -> {
            BankDeposit target = transit(snapshot, PAYABLE).getFirst();
            assertThat(target.getAmount()).isEqualByComparingTo("-77.00");
            assertThat(target.getNotes()).isEqualTo("retain");
            assertThat(target.getAnnualInterestRate()).isEqualByComparingTo("4.00");
            assertThat(target.getOriginalAmount()).isEqualByComparingTo("77.0000");
        });
    }

    @Test
    void settlementKeepsSeparateOfficialDaysAndMissingDayNeverClearsFutureRows() {
        // Stock settlement requires one same-bank manual securities-account target with sufficient balance.
        tx(() -> {
            AssetSnapshot snapshot = snapshots.findById(snapshotId).orElseThrow();
            snapshot.getDeposits().add(BankDeposit.builder().snapshot(snapshot).bank(fubonBank)
                    .depositType("證券戶").currency("TWD").amount(new BigDecimal("2000.00")).build());
            aggregates.recalculate(snapshot);
            snapshots.saveAndFlush(snapshot);
        });
        String secondDay = SETTLEMENT_ROW.replace("2026-09-01", "2026-09-02")
                .replace("1000", "2000").replace("-1002", "-2002");
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                read(settlementJson(SETTLEMENT_ROW + "," + secondDay), FubonDtos.SettlementBatch.class)));
        var result = settlementService.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        assertThat(result.payableAmount()).isEqualByComparingTo("-3004");
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).extracting(BankDeposit::getProcessingDate)
                    .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));
            assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("-904");
        });
        clearInvocations(aggregates);
        sqlTrace.start();
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        assertThat(sqlTrace.stop()).noneMatch(sql -> sql.startsWith("insert") || sql.startsWith("update")
                || sql.startsWith("delete"));
        verify(aggregates, never()).recalculate(any());
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlement()));
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                read(settlementJson(NO_DATA_ROW), FubonDtos.SettlementBatch.class)));
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE).stream().map(BankDeposit::getProcessingDate).toList()).hasSize(2);
            assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("-904");
        });
        // Same production lifecycle method used by the daily and startup jobs, independent of vendor success.
        assertThat(assetService.rollLatestSnapshotToTodayForOwner(ownerId, LocalDate.of(2026, 9, 1))).isTrue();
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE).stream().map(BankDeposit::getProcessingDate).toList())
                    .containsExactly(LocalDate.of(2026, 9, 2));
            assertThat(transit(snapshot, PAYABLE).getFirst().getAmount()).isEqualByComparingTo("-2002");
            assertThat(snapshot.getDeposits().stream()
                    .filter(row -> "TWD".equals(row.getCurrency()) && "證券戶".equals(row.getDepositType())
                            && fubonBank.getId().equals(row.getBank().getId()))
                    .map(BankDeposit::getAmount).toList()).singleElement()
                    .satisfies(amount -> assertThat(amount).isEqualByComparingTo("998"));
            assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("-904");
            assertThat(snapshot.getTotalAssets()).isEqualByComparingTo("-904");
        });
    }

    @Test
    void unknownDirectionTaxesKeepTwoOfficialPayablesAndExistingNotesIdempotently() {
        addTransit(PAYABLE, "-10040.00", "TRANSIT_TWD", null,
                "富邦證券交割款：009826", null);
        AtomicReference<List<Long>> originalIds = new AtomicReference<>();
        tx(() -> {
            var snapshot = snapshots.findById(snapshotId).orElseThrow();
            var second = BankDeposit.builder().snapshot(snapshot).bank(fubonBank).depositType(PAYABLE)
                    .currency("TRANSIT_TWD").amount(new BigDecimal("-43181.00"))
                    .notes("富邦證券交割款：00881").source("FUBON_SYNC")
                    .processingDate(LocalDate.of(2026, 9, 2)).build();
            snapshot.getDeposits().add(second);
            aggregates.recalculate(snapshot);
            snapshots.saveAndFlush(snapshot);
        });
        readSnapshot(snapshot -> originalIds.set(transit(snapshot, PAYABLE).stream()
                .map(BankDeposit::getId).toList()));
        String first = SETTLEMENT_ROW.replace("1000", "10020").replace("\"buyFee\":\"2\"", "\"buyFee\":\"20\"")
                .replace("\"totalFee\":\"2\"", "\"totalFee\":\"20\"").replace("-1002", "-10040")
                .replace("\"buyTax\":\"0\"", "\"buyTax\":null")
                .replace("\"sellTax\":\"0\"", "\"sellTax\":null")
                .replace("sourceQueryDate\":\"2026-08-28", "sourceQueryDate\":\"2026-08-27");
        String second = SETTLEMENT_ROW.replace("2026-09-01", "2026-09-02")
                .replace("1000", "43120").replace("\"buyFee\":\"2\"", "\"buyFee\":\"61\"")
                .replace("\"totalFee\":\"2\"", "\"totalFee\":\"61\"").replace("-1002", "-43181");
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                read(settlementJson(first + "," + second), FubonDtos.SettlementBatch.class)));
        clearInvocations(aggregates);
        for (int attempt = 0; attempt < 2; attempt++) {
            sqlTrace.start();
            var result = settlementService.syncManual(false);
            assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
            assertThat(result.payableAmount()).isEqualByComparingTo("-53221");
            assertThat(result.receivableAmount()).isEqualByComparingTo("0");
            assertThat(result.rowCount()).isEqualTo(2);
            assertThat(sqlTrace.stop()).noneMatch(sql -> sql.startsWith("insert") || sql.startsWith("update")
                    || sql.startsWith("delete"));
        }
        verify(aggregates, never()).recalculate(any());
        readSnapshot(snapshot -> {
            assertThat(snapshot.getDeposits()).hasSize(3);
            var payables = transit(snapshot, PAYABLE);
            assertThat(payables).extracting(BankDeposit::getId).containsExactlyInAnyOrderElementsOf(originalIds.get());
            assertThat(payables).extracting(BankDeposit::getNotes)
                    .containsExactlyInAnyOrder("富邦證券交割款：009826", "富邦證券交割款：00881");
            assertThat(payables).extracting(BankDeposit::getProcessingDate)
                    .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));
            assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("-53121");
        });
    }

    @Test
    void settlementLegacyNullDateFailsClosedEvenWhenAmountsMatch() {
        addTransit(PAYABLE, "-1002.00", "TRANSIT_TWD", null, "legacy", null);
        tx(() -> transit(snapshots.findById(snapshotId).orElseThrow(), PAYABLE).getFirst().setProcessingDate(null));
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_TARGET);
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE)).hasSize(1);
            assertThat(transit(snapshot, PAYABLE).getFirst().getProcessingDate()).isNull();
            assertThat(transit(snapshot, PAYABLE).getFirst().getNotes()).isEqualTo("legacy");
        });
    }

    @Test
    void legacyNullDateOnSecondDirectionRollsBackEarlierDirectionUpdate() {
        addTransit(PAYABLE, "-5.00", "TRANSIT_TWD", null, "payable", null);
        addTransit(RECEIVABLE, "7.00", "TRANSIT_TWD", null, "legacy", null);
        tx(() -> transit(snapshots.findById(snapshotId).orElseThrow(), RECEIVABLE).getFirst().setProcessingDate(null));
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlementWithBothDirections()));
        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_TARGET);
        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE).getFirst().getAmount()).isEqualByComparingTo("-5");
            assertThat(transit(snapshot, PAYABLE).getFirst().getNotes()).isEqualTo("payable");
            assertThat(transit(snapshot, RECEIVABLE).getFirst().getProcessingDate()).isNull();
            assertThat(transit(snapshot, RECEIVABLE).getFirst().getAmount()).isEqualByComparingTo("7");
            assertThat(snapshot.getTotalAssets()).isEqualByComparingTo("102");
        });
    }

    @Test
    void sameDayAndPastNonzeroSettlementRemainRejectedWithoutChangingPersistedFuture() {
        addTransit(PAYABLE, "-77.00", "TRANSIT_TWD", null, "keep", null);
        for (String invalidDay : List.of("2026-08-28", "2026-08-27")) {
            String row = SETTLEMENT_ROW.replace("2026-09-01", invalidDay)
                    .replace("\"sourceQueryDate\":\"2026-08-28\"", "\"sourceQueryDate\":\"2026-08-27\"");
            when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                    read(settlementJson(row), FubonDtos.SettlementBatch.class)));
            assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_SETTLEMENT);
        }
        readSnapshot(snapshot -> assertThat(transit(snapshot, PAYABLE).getFirst().getAmount())
                .isEqualByComparingTo("-77"));
    }

    @Test
    void settlementSecondDirectionConflictRollsBackFirstDirectionMutation() {
        addTransit(PAYABLE, "-5.00", "TRANSIT_TWD", null, "payable", null);
        addTransit(RECEIVABLE, "1.00", "TRANSIT_TWD", null, "one", null);
        addTransit(RECEIVABLE, "2.00", "TRANSIT_TWD", null, "two", null);
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlementWithBothDirections()));

        assertThat(settlementService.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_TARGET);

        readSnapshot(snapshot -> {
            assertThat(transit(snapshot, PAYABLE).getFirst().getAmount()).isEqualByComparingTo("-5.00");
            assertThat(transit(snapshot, RECEIVABLE)).extracting(BankDeposit::getAmount)
                    .extracting(BigDecimal::toPlainString).containsExactlyInAnyOrder("1.00", "2.00");
        });
    }

    @Test
    void realizedWriterFirstLocksFreshOwnerThenLocksTableAndMapsFormula() {
        when(brokerClient.readRealizedGains()).thenAnswer(call -> {
            sqlTrace.start();
            return FubonDtos.CallResult.success(realized());
        });

        FubonRealizedGainSyncService.RealizedGainSyncResult result = realizedService.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.SUCCESS);
        assertThat(result.insertedCount()).isEqualTo(1);
        List<String> statements = sqlTrace.stop();
        assertThat(statements.getFirst()).contains("from app_user").contains("for update");
        assertThat(statements.get(1)).contains("lock table realized_gain");
        tx(() -> {
            RealizedGain gain = gains.findAllForFubonSyncOwner(ownerId).getFirst();
            assertThat(gain.getAssetName()).isEqualTo("台積電");
            assertThat(gain.getAssetCode()).isEqualTo("2330");
            assertThat(gain.getMarket()).isEqualTo("台股");
            assertThat(gain.getCurrency()).isEqualTo("TWD");
            assertThat(gain.getBroker()).isEqualTo("富邦證券");
            assertThat(gain.getTradeDate()).isEqualTo(DATE.minusDays(1));
            assertThat(gain.getShares()).isEqualByComparingTo("1000");
            assertThat(gain.getSalePrice()).isEqualByComparingTo("123.5000");
            assertThat(gain.getProceeds()).isEqualByComparingTo("123500.00");
            assertThat(gain.getInvestmentCost()).isEqualByComparingTo("123520.00");
            assertThat(gain.getExchangeRate()).isNull();
            assertThat(gain.getSyncSource()).isEqualTo(FubonRealizedGainFingerprint.SOURCE);
            assertThat(gain.getSyncOccurrence()).isEqualTo(1);
        });
    }

    @Test
    void realizedDryRunTakesNoWriteLockAndCreatesNothing() {
        sqlTrace.start();

        FubonRealizedGainSyncService.RealizedGainSyncResult result = realizedService.syncManual(true);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.DRY_RUN);
        assertThat(sqlTrace.stop()).noneMatch(sql -> sql.contains("for update") || sql.contains("lock table")
                || sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete"));
        assertThat(gains.findAllForFubonSyncOwner(ownerId)).isEmpty();
    }

    @Test
    void manualEquivalentWithTrailingZerosCountsAsAlreadyRepresentedWithoutDml() {
        addManualEquivalent("1000.00000", "123.5000", "old-local-name");
        when(brokerClient.readRealizedGains()).thenAnswer(call -> {
            sqlTrace.start();
            return FubonDtos.CallResult.success(realized());
        });

        FubonRealizedGainSyncService.RealizedGainSyncResult result = realizedService.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.SUCCESS);
        assertThat(result.insertedCount()).isZero();
        assertThat(result.alreadyRepresentedCount()).isEqualTo(1);
        assertThat(sqlTrace.stop()).noneMatch(sql -> sql.startsWith("insert") || sql.startsWith("update"));
        tx(() -> {
            RealizedGain manual = gains.findAllForFubonSyncOwner(ownerId).getFirst();
            assertThat(manual.getAssetName()).isEqualTo("old-local-name");
            assertThat(manual.getSyncSource()).isNull();
            assertThat(manual.getSyncFingerprint()).isNull();
            assertThat(manual.getSyncOccurrence()).isNull();
        });
    }

    @Test
    void validSyncEquivalentIsSharedAcrossFingerprintsWithoutAnotherInsertOrUpdate() {
        FubonDtos.RealizedGainRow row = realized().rows().getFirst();
        FubonRealizedGainWriter.PreparedGain mapped = FubonRealizedGainWriter.prepare(row, "old-sync-name");
        tx(() -> gains.saveAndFlush(RealizedGain.builder().ownerUserId(ownerId).assetName(mapped.assetName())
                .assetCode(row.stockNo()).market("台股").currency("TWD").broker("富邦證券")
                .tradeDate(row.sourceDate()).shares(new BigDecimal("1000.00000"))
                .salePrice(new BigDecimal("123.5000")).proceeds(mapped.proceeds())
                .investmentCost(mapped.investmentCost()).exchangeRate(null)
                .syncSource(FubonRealizedGainFingerprint.SOURCE)
                .syncFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .syncOccurrence(1).build()));
        when(brokerClient.readRealizedGains()).thenAnswer(call -> {
            sqlTrace.start();
            return FubonDtos.CallResult.success(realized());
        });

        FubonRealizedGainSyncService.RealizedGainSyncResult result = realizedService.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.SUCCESS);
        assertThat(result.insertedCount()).isZero();
        assertThat(result.alreadyRepresentedCount()).isEqualTo(1);
        assertThat(sqlTrace.stop()).noneMatch(sql -> sql.startsWith("insert") || sql.startsWith("update"));
        tx(() -> assertThat(gains.findAllForFubonSyncOwner(ownerId)).hasSize(1));
    }

    @Test
    void duplicateSourceMultiplicityUsesOccurrencesAndRerunDoesNotWrite() {
        FubonDtos.RealizedGainBatch duplicates = read(realizedJson(REALIZED_ROW + "," + REALIZED_ROW),
                FubonDtos.RealizedGainBatch.class);
        when(brokerClient.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(duplicates));

        FubonRealizedGainSyncService.RealizedGainSyncResult first = realizedService.syncManual(false);
        FubonRealizedGainSyncService.RealizedGainSyncResult second = realizedService.syncManual(false);

        assertThat(first.outcome()).isEqualTo(FubonRealizedGainOutcome.SUCCESS);
        assertThat(first.insertedCount()).isEqualTo(2);
        assertThat(second.outcome()).isEqualTo(FubonRealizedGainOutcome.SUCCESS);
        assertThat(second.insertedCount()).isZero();
        assertThat(second.alreadyRepresentedCount()).isEqualTo(2);
        tx(() -> assertThat(gains.findAllForFubonSyncOwner(ownerId)).extracting(RealizedGain::getSyncOccurrence)
                .containsExactly(1, 2));
    }

    @Test
    void mismatchedExistingSyncRowRejectsTheEntireRealizedBatchWithoutOverwrite() {
        FubonDtos.RealizedGainRow row = realized().rows().getFirst();
        tx(() -> gains.saveAndFlush(RealizedGain.builder().ownerUserId(ownerId).assetName("old")
                .assetCode("2330").market("台股").currency("TWD").broker("富邦證券")
                .tradeDate(row.sourceDate()).shares(new BigDecimal("1000"))
                .salePrice(new BigDecimal("123.5000")).proceeds(new BigDecimal("1.00"))
                .investmentCost(new BigDecimal("123520.00")).exchangeRate(null)
                .syncSource(FubonRealizedGainFingerprint.SOURCE)
                .syncFingerprint(FubonRealizedGainFingerprint.of(row)).syncOccurrence(1).build()));

        FubonRealizedGainSyncService.RealizedGainSyncResult result = realizedService.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.EXISTING_DATA_CONFLICT);
        assertThat(result.insertedCount()).isZero();
        tx(() -> {
            RealizedGain existing = gains.findAllForFubonSyncOwner(ownerId).getFirst();
            assertThat(existing.getProceeds()).isEqualByComparingTo("1.00");
            assertThat(existing.getAssetName()).isEqualTo("old");
        });
    }

    @Test
    void twoConcurrentRealizedWritersAllocateOnlyOneOccurrence() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<FubonRealizedGainSyncService.RealizedGainSyncResult> first =
                    CompletableFuture.supplyAsync(() -> realizedService.syncManual(false), executor);
            CompletableFuture<FubonRealizedGainSyncService.RealizedGainSyncResult> second =
                    CompletableFuture.supplyAsync(() -> realizedService.syncManual(false), executor);
            FubonRealizedGainSyncService.RealizedGainSyncResult firstResult = first.get(15, TimeUnit.SECONDS);
            FubonRealizedGainSyncService.RealizedGainSyncResult secondResult = second.get(15, TimeUnit.SECONDS);

            assertThat(List.of(firstResult.outcome(), secondResult.outcome()))
                    .containsOnly(FubonRealizedGainOutcome.SUCCESS);
            assertThat(firstResult.insertedCount() + secondResult.insertedCount()).isEqualTo(1);
            assertThat(firstResult.alreadyRepresentedCount() + secondResult.alreadyRepresentedCount()).isEqualTo(1);
        }
        tx(() -> assertThat(gains.findAllForFubonSyncOwner(ownerId)).hasSize(1));
    }

    @Test
    void ownerChangeBetweenPreflightAndWriterLockRollsBackWithoutGain() {
        when(brokerClient.readRealizedGains()).thenAnswer(call -> {
            tx(() -> {
                AppUser owner = users.findById(ownerId).orElseThrow();
                owner.setStatus(AppUser.STATUS_DISABLED);
                users.saveAndFlush(owner);
            });
            return FubonDtos.CallResult.success(realized());
        });

        FubonRealizedGainSyncService.RealizedGainSyncResult result = realizedService.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.NO_OWNER);
        tx(() -> assertThat(gains.findAllForFubonSyncOwner(ownerId)).isEmpty());
    }

    @Test
    void deferredCommitFailureRollsBackAllRealizedAdds() {
        tx(() -> {
            entityManager.createNativeQuery("CREATE FUNCTION reject_realized_gain_commit() RETURNS trigger LANGUAGE plpgsql "
                    + "AS 'BEGIN RAISE EXCEPTION ''test-only deferred rejection''; RETURN NEW; END'").executeUpdate();
            entityManager.createNativeQuery("CREATE CONSTRAINT TRIGGER reject_realized_gain_commit AFTER INSERT ON realized_gain "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_realized_gain_commit()")
                    .executeUpdate();
        });

        FubonRealizedGainSyncService.RealizedGainSyncResult result = realizedService.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.ROLLED_BACK);
        tx(() -> assertThat(gains.findAllForFubonSyncOwner(ownerId)).isEmpty());
    }

    private FubonDtos.SettlementBatch zeroSettlement() {
        String zero = SETTLEMENT_ROW
                .replace("\"buyValue\":\"1000\"", "\"buyValue\":\"0\"")
                .replace("\"buyFee\":\"2\"", "\"buyFee\":\"0\"")
                .replace("\"buySettlement\":\"-1002\"", "\"buySettlement\":\"0\"")
                .replace("\"totalBsValue\":\"1000\"", "\"totalBsValue\":\"0\"")
                .replace("\"totalFee\":\"2\"", "\"totalFee\":\"0\"")
                .replace("\"totalSettlementAmount\":\"-1002\"", "\"totalSettlementAmount\":\"0\"");
        return read(settlementJson(zero), FubonDtos.SettlementBatch.class);
    }

    private void prepareRecovery() {
        clock.set(Instant.parse("2026-11-02T00:00:30Z"));
        seedRecoveryLedger();
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(recoverySettlement()));
    }

    private void seedRecoveryLedger() {
        tx(() -> assetTransactions.saveAllAndFlush(List.of(
                recoveryBuy("00881", "46.42", "46420", "66"),
                recoveryBuy("009826", "10.08", "10080", "20"))));
    }

    private com.steven.assets.model.AssetTransaction recoveryBuy(String code, String price, String amount, String fee) {
        var row = ledger(ownerId, "買", code, code, "1000", "MANUAL");
        row.setTradeDate(LocalDate.of(2026, 11, 2));
        row.setPrice(new BigDecimal(price));
        row.setAmount(new BigDecimal(amount));
        row.setFee(new BigDecimal(fee));
        row.setTransactionTax(BigDecimal.ZERO);
        return row;
    }

    private static FubonDtos.SettlementBatch recoverySettlement() {
        String row = SETTLEMENT_ROW.replace("2026-08-28", "2026-11-02")
                .replace("2026-09-01", "2026-11-04")
                .replace("\"buyValue\":\"1000\"", "\"buyValue\":\"56500\"")
                .replace("\"buyFee\":\"2\"", "\"buyFee\":\"86\"")
                .replace("\"buySettlement\":\"-1002\"", "\"buySettlement\":\"-56586\"")
                .replace("\"totalBsValue\":\"1000\"", "\"totalBsValue\":\"56500\"")
                .replace("\"totalFee\":\"2\"", "\"totalFee\":\"86\"")
                .replace("\"totalSettlementAmount\":\"-1002\"", "\"totalSettlementAmount\":\"-56586\"");
        return read(settlementJson(row).replace("2026-08-28", "2026-11-02"), FubonDtos.SettlementBatch.class);
    }

    private Long addRecoveryTransit(String amount, String code) {
        AtomicReference<Long> id = new AtomicReference<>();
        tx(() -> {
            var snapshot = snapshots.findById(snapshotId).orElseThrow();
            BankDeposit row = BankDeposit.builder().snapshot(snapshot).bank(fubonBank).depositType(PAYABLE)
                    .currency("TRANSIT_TWD").amount(new BigDecimal(amount)).notes(code).source("MANUAL")
                    .processingDate(LocalDate.of(2026, 11, 4)).build();
            snapshot.getDeposits().add(row);
            entityManager.persist(row);
            aggregates.recalculate(snapshot);
            snapshots.saveAndFlush(snapshot);
            id.set(row.getId());
        });
        return id.get();
    }

    private FubonDtos.SettlementBatch settlementWithBothDirections() {
        String both = SETTLEMENT_ROW
                .replace("\"sellValue\":\"0\"", "\"sellValue\":\"500\"")
                .replace("\"sellSettlement\":\"0\"", "\"sellSettlement\":\"500\"")
                .replace("\"totalBsValue\":\"1000\"", "\"totalBsValue\":\"1500\"")
                .replace("\"totalSettlementAmount\":\"-1002\"", "\"totalSettlementAmount\":\"-502\"");
        return read(settlementJson(both), FubonDtos.SettlementBatch.class);
    }

    private Long addTransit(String type, String amount, String currency, String rate, String notes, String original) {
        return addTransit(type, amount, currency, rate, notes, original, "FUBON_SYNC");
    }

    private Long addTransit(String type, String amount, String currency, String rate, String notes, String original,
            String source) {
        AtomicReference<Long> id = new AtomicReference<>();
        tx(() -> {
            AssetSnapshot snapshot = snapshots.findById(snapshotId).orElseThrow();
            BankDeposit deposit = BankDeposit.builder().snapshot(snapshot).bank(fubonBank).depositType(type)
                    .currency(currency).amount(new BigDecimal(amount)).notes(notes)
                    .processingDate(LocalDate.of(2026, 9, 1))
                    .annualInterestRate(rate == null ? null : new BigDecimal(rate))
                    .originalAmount(original == null ? null : new BigDecimal(original)).source(source).build();
            snapshot.getDeposits().add(deposit);
            entityManager.persist(deposit);
            aggregates.recalculate(snapshot);
            snapshots.saveAndFlush(snapshot);
            id.set(deposit.getId());
        });
        clearInvocations(aggregates);
        return id.get();
    }

    private static com.steven.assets.model.AssetTransaction ledger(Long ownerId, String direction, String code,
            String name, String shares, String source) {
        return com.steven.assets.model.AssetTransaction.builder().ownerUserId(ownerId).transactionType(direction)
                .assetType("股票").assetName(name).assetCode(code).market("台股").currency("TWD")
                .channel("富邦證券").tradeDate(DATE).shares(new BigDecimal(shares)).price(BigDecimal.ONE)
                .amount(new BigDecimal(shares)).source(source).build();
    }

    private void addManualEquivalent(String shares, String price, String name) {
        tx(() -> gains.saveAndFlush(RealizedGain.builder().ownerUserId(ownerId).assetName(name)
                .assetCode("2330").market("台股").currency("TWD").broker("富邦證券")
                .tradeDate(DATE.minusDays(1)).shares(new BigDecimal(shares)).salePrice(new BigDecimal(price))
                .proceeds(new BigDecimal("123500.00")).investmentCost(new BigDecimal("123520.00"))
                .exchangeRate(null).build()));
    }

    private static List<BankDeposit> transit(AssetSnapshot snapshot, String type) {
        return snapshot.getDeposits().stream().filter(deposit -> deposit.getBank() != null
                && FUBON.equals(deposit.getBank().getCode()) && type.equals(deposit.getDepositType()))
                .toList();
    }

    private void readSnapshot(Consumer<AssetSnapshot> assertion) {
        tx(() -> {
            entityManager.clear();
            assertion.accept(snapshots.findById(snapshotId).orElseThrow());
        });
    }

    private void tx(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> action.run());
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);

        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    static class SqlTrace implements StatementInspector {
        private final ThreadLocal<List<String>> active = new ThreadLocal<>();

        void start() { active.set(new ArrayList<>()); }
        List<String> stop() {
            List<String> rows = active.get();
            active.remove();
            return rows == null ? List.of() : List.copyOf(rows);
        }
        @Override public String inspect(String sql) {
            if (active.get() != null) active.get().add(sql.toLowerCase());
            return sql;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean MutableClock accountingClock() { return new MutableClock(); }
        @Bean SqlTrace sqlTrace() { return new SqlTrace(); }
        @Bean HibernatePropertiesCustomizer tracing(SqlTrace trace) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", trace);
        }
        @Bean FubonSyncOwnerDirectoryAdapter fubonSyncOwnerDirectoryAdapter(AppUserRepository users,
                UserAdminService configuredAdmin) {
            return new FubonSyncOwnerDirectoryAdapter(users, configuredAdmin, OWNER_EMAIL);
        }
        @Bean FubonSyncOwnerPolicy fubonSyncOwnerPolicy(FubonSyncOwnerDirectoryAdapter ownerDirectory) {
            return new FubonSyncOwnerPolicy(ownerDirectory, ownerDirectory);
        }
        @Bean FubonLocalNameAdapter fubonLocalNameAdapter(StockMasterService stockNames) {
            return new FubonLocalNameAdapter(stockNames);
        }
        @Bean FubonSettlementWriter fubonSettlementWriter(AssetSnapshotMutationLock locks,
                FubonSyncOwnerPort ownerPolicy, BrokerRepository brokers, BankRepository banks,
                TransitFundTypeRepository transitTypes, SnapshotAggregateCalculator aggregates,
                AssetSnapshotRepository snapshots, AssetTransactionRepository assetTransactions, MutableClock clock) {
            return new FubonSettlementWriter(locks, ownerPolicy, brokers, banks, transitTypes, aggregates,
                    snapshots, assetTransactions, clock);
        }
        @Bean FubonRealizedGainWriter fubonRealizedGainWriter(FubonSyncOwnerPort ownerPolicy,
                RealizedGainRepository gains, EntityManager entityManager) {
            return new FubonRealizedGainWriter(ownerPolicy, gains, entityManager);
        }
        @Bean FubonSettlementOutcomeCounters settlementCounters() { return new FubonSettlementOutcomeCounters(); }
        @Bean FubonRealizedGainOutcomeCounters realizedCounters() { return new FubonRealizedGainOutcomeCounters(); }
        @Bean FubonSettlementSyncService settlementService(FubonConfigState config, FubonBrokerClient client,
                FubonSyncOwnerPort ownerPolicy, BrokerRepository brokers, FubonSettlementWriter writer,
                FubonSettlementOutcomeCounters counters, MutableClock clock) {
            return new FubonSettlementSyncService(config, client, ownerPolicy, brokers, writer, counters, true, clock);
        }
        @Bean FubonRealizedGainSyncService realizedService(FubonConfigState config, FubonBrokerClient client,
                FubonSyncOwnerPort ownerPolicy, BrokerRepository brokers, FubonLocalNamePort localNames,
                FubonRealizedGainWriter writer, FubonRealizedGainOutcomeCounters counters, MutableClock clock) {
            return new FubonRealizedGainSyncService(config, client, ownerPolicy, brokers, localNames, writer,
                    counters, true, clock);
        }
    }
}
