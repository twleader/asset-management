package com.steven.assets.integration.fubon;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.service.*;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Real Spring transaction proxies, PostgreSQL locks, deferred commit failures and cleared-context readback. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.liquibase.enabled=false",
        "app.admin-email=bank-test@example.invalid"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaFubonSyncFreshness.class, AssetSnapshotMutationLock.class, SnapshotAggregateCalculator.class, AssetService.class,
        UserAdminService.class, FubonAccountingPostgresTest.Config.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FubonAccountingPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fubon_accounting_isolated").withUsername("assets").withPassword("test-only-password");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean FubonConfigState configState;
    @MockBean FubonBrokerClient brokerClient;
    @MockBean MarketDataService marketDataService;
    @MockBean FundNavService fundNavService;
    @MockBean FundDividendService fundDividendService;
    @MockBean AssetClassifier assetClassifier;
    @MockBean StockMasterService stockMasterService;
    @MockBean com.steven.assets.security.TenantGuard tenantGuard;
    @MockBean SnapshotStockScopeOwnershipPort stockScopeOwnershipPort;
    @SpyBean AssetSnapshotMutationLock mutationLock;
    @SpyBean SnapshotAggregateCalculator aggregateCalculator;
    @Autowired AssetSnapshotRepository snapshots;
    @Autowired AppUserRepository users;
    @Autowired BankRepository banks;
    @Autowired BrokerRepository brokers;
    @Autowired DepositTypeRepository depositTypes;
    @Autowired RealizedGainRepository gains;
    @Autowired AssetTransactionRepository ledger;
    @Autowired UserAdminService userAdminService;
    @Autowired AssetService assetService;
    @Autowired FubonBankBalanceWriter writer;
    @Autowired FubonBankBalanceSyncService bankService;
    @Autowired FubonBankBalanceOutcomeCounters counters;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    @Autowired MutableClock clock;
    @Autowired SqlTrace sqlTrace;
    private Long ownerId;
    private Long otherOwnerId;
    private Long snapshotId;
    private Bank fubon;
    private Bank otherBank;
    private BrokerEntity broker;
    private long priorSuccess;

    @BeforeEach void seed() {
        reset(mutationLock, aggregateCalculator, brokerClient, configState, stockScopeOwnershipPort);
        clock.set(NOW); sqlTrace.stop();
        tx(() -> {
            em.createNativeQuery("DROP TRIGGER IF EXISTS reject_bank_commit ON bank_deposit").executeUpdate();
            em.createNativeQuery("DROP FUNCTION IF EXISTS reject_bank_commit()").executeUpdate();
            snapshots.deleteAll(); snapshots.flush(); gains.deleteAll(); ledger.deleteAll();
            banks.deleteAll(); brokers.deleteAll(); depositTypes.deleteAll(); users.deleteAll(); em.flush();
            ownerId = users.saveAndFlush(AppUser.builder().email("bank-test@example.invalid")
                    .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()).getId();
            otherOwnerId = users.saveAndFlush(AppUser.builder().email("other-test@example.invalid")
                    .role(AppUser.ROLE_USER).status(AppUser.STATUS_ACTIVE).build()).getId();
            fubon = banks.saveAndFlush(Bank.builder().code("fubon").displayName("台北富邦銀行").active(true).build());
            otherBank = banks.saveAndFlush(Bank.builder().code("other").displayName("其他銀行").active(true).build());
            broker = brokers.saveAndFlush(BrokerEntity.builder().code("fubon").displayName("富邦證券").active(true).build());
            depositTypes.saveAndFlush(DepositTypeEntity.builder().code("活存").displayName("活存").active(true).build());
            depositTypes.saveAndFlush(DepositTypeEntity.builder().code("證券戶").displayName("證券戶").active(true).build());
            var snapshot = AssetSnapshot.builder().ownerUserId(ownerId).snapshotDate(DATE.minusDays(1)).usdExchangeRate(BigDecimal.ONE).build();
            snapshot.getDeposits().add(deposit(snapshot, fubon, "活存", "20", "TWD", "2", "demand-note", "20.1234"));
            snapshot.getDeposits().add(deposit(snapshot, fubon, "證券戶", "30", "TWD", "3", "target-note", "30.5678"));
            snapshot.getDeposits().add(deposit(snapshot, otherBank, "活存", "100", "TWD", "1", "other-note"));
            snapshot.getFunds().add(FundHolding.builder().snapshot(snapshot).bank(otherBank).fundName("test-fund")
                    .investmentAmount(bd("35")).currentValue(bd("50")).estimatedDividend(bd("3")).build());
            snapshot.getStocks().add(StockHolding.builder().snapshot(snapshot).stockCode("0050").market("台股").broker(broker)
                    .shares(bd("1")).investmentCost(bd("150")).currentValue(bd("200")).estimatedDividend(bd("4")).currency("TWD").build());
            aggregateCalculator.recalculate(snapshot);
            snapshotId = snapshots.saveAndFlush(snapshot).getId();
        });
        when(configState.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "test-only", "READY"));
        when(stockScopeOwnershipPort.capture(any())).thenReturn(SnapshotStockScopeOwnership.payloadOwned());
        when(brokerClient.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank("1000")));
        when(brokerClient.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlement()));
        when(brokerClient.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(realized()));
        priorSuccess = counters.snapshot().get(FubonBankBalanceOutcome.SUCCESS);
    }

    @Test void realProxySuspendsCallerTransactionAndWriterFirstSqlIsSnapshotLock() {
        assertThat(AopUtils.isAopProxy(bankService)).isTrue(); assertThat(AopUtils.isAopProxy(writer)).isTrue();
        when(brokerClient.readBankBalance()).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sqlTrace.start(); return FubonDtos.CallResult.success(bank("1000"));
        });
        tx(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
        var statements = sqlTrace.stop();
        assertThat(statements).isNotEmpty();
        assertThat(statements.getFirst()).contains("from asset_snapshot").contains("for update");
        assertThat(statements.getFirst()).doesNotContain("app_user", "bank_deposit");
        readSnapshot(s -> {
            assertThat(s.getDeposits()).hasSize(3); assertThat(target(s).getSnapshot().getId()).isEqualTo(snapshotId);
            assertThat(target(s).getAmount()).isEqualByComparingTo("1000.00");
            assertThat(target(s).getNotes()).isEqualTo("target-note"); assertThat(target(s).getAnnualInterestRate()).isEqualByComparingTo("3");
            assertThat(target(s).getOriginalAmount()).isEqualByComparingTo("30.5678"); assertThat(s.getFunds()).hasSize(1); assertThat(s.getStocks()).hasSize(1);
            assertThat(demandDeposit(s).getAmount()).isEqualByComparingTo("20");
            assertThat(s.getTotalDeposit()).isEqualByComparingTo("1120");
            assertThat(s.getTotalAssets()).isEqualByComparingTo("1370");
            assertThat(s.getEstimatedAnnualDividend()).isEqualByComparingTo("38");
            assertThat(s.getSnapshotDate()).isEqualTo(DATE.minusDays(1));
        });
    }

    @Test void updateUsesSameManagedSecuritiesAccountAndPreservesItsMetadataAndUnrelatedChildren() {
        Long originalId = readTargetId(); Long demandId = readDemandDepositId();
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
        readSnapshot(s -> {
            assertThat(target(s).getId()).isEqualTo(originalId); assertThat(target(s).getNotes()).isEqualTo("target-note");
            assertThat(target(s).getAnnualInterestRate()).isEqualByComparingTo("3");
            assertThat(target(s).getOriginalAmount()).isEqualByComparingTo("30.5678");
            assertThat(demandDeposit(s).getId()).isEqualTo(demandId); assertThat(demandDeposit(s).getAmount()).isEqualByComparingTo("20");
            assertThat(demandDeposit(s).getNotes()).isEqualTo("demand-note"); assertThat(demandDeposit(s).getAnnualInterestRate()).isEqualByComparingTo("2");
            assertThat(demandDeposit(s).getOriginalAmount()).isEqualByComparingTo("20.1234");
            assertThat(s.getDeposits()).hasSize(3); assertThat(s.getTotalDeposit()).isEqualByComparingTo("1120");
            assertThat(s.getTotalAssets()).isEqualByComparingTo("1370"); assertThat(s.getEstimatedAnnualDividend()).isEqualByComparingTo("38");
            assertThat(s.getFunds().getFirst().getCurrentValue()).isEqualByComparingTo("50"); assertThat(s.getStocks()).hasSize(1);
        });
    }

    @Test void zeroBalancePersistsExistingSecuritiesAccountAndLeavesDemandDepositUntouched() {
        when(brokerClient.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank("0")));
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
        readSnapshot(s -> { assertThat(s.getDeposits()).hasSize(3); assertThat(target(s).getAmount()).isZero();
            assertThat(demandDeposit(s).getAmount()).isEqualByComparingTo("20");
            assertThat(s.getTotalDeposit()).isEqualByComparingTo("120"); assertThat(s.getTotalAssets()).isEqualByComparingTo("370"); });
    }

    @Test void fullEighteenIntegerDigitsFitWhenAllOtherAssetsAreZero() {
        tx(() -> { var s = snapshots.findById(snapshotId).orElseThrow(); BankDeposit securitiesAccount = target(s);
            s.getDeposits().removeIf(deposit -> deposit != securitiesAccount); s.getFunds().clear();
            s.getStocks().clear(); aggregateCalculator.recalculate(s); snapshots.saveAndFlush(s); });
        when(brokerClient.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank("999999999999999999.99")));
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
        readSnapshot(s -> assertThat(s.getTotalAssets()).isEqualByComparingTo("999999999999999999.99"));
    }

    @ParameterizedTest @ValueSource(strings = {"totalDeposit", "totalAssets", "annualDividend"})
    void aggregateOverflowRollsBackChildrenAndTotals(String field) {
        setTarget("0", "TWD", "100", "retained", "20.1234");
        tx(() -> { var s = snapshots.findById(snapshotId).orElseThrow();
            if (!field.equals("totalDeposit")) s.getDeposits().stream().filter(d -> d != target(s))
                    .forEach(d -> { d.setAmount(BigDecimal.ZERO); d.setAnnualInterestRate(null); });
            if (field.equals("annualDividend")) { s.getFunds().getFirst().setCurrentValue(BigDecimal.ZERO);
                s.getStocks().getFirst().setCurrentValue(BigDecimal.ZERO); }
            aggregateCalculator.recalculate(s); snapshots.saveAndFlush(s); });
        var before = totals();
        when(brokerClient.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank("999999999999999999.99")));
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.ROLLED_BACK);
        assertThat(totals()).containsExactlyElementsOf(before);
        readSnapshot(s -> { assertThat(target(s).getAmount()).isZero(); assertThat(target(s).getNotes()).isEqualTo("retained");
            assertThat(target(s).getOriginalAmount()).isEqualByComparingTo("20.1234"); });
        assertNoSuccess();
    }

    @Test void duplicateSecuritiesAccountTargetsRefuseToPickOne() {
        addSecuritiesAccountTarget("10", "TWD", null, null, null);
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.AMBIGUOUS_TARGET);
        readSnapshot(s -> assertThat(securitiesAccountTargets(s)).extracting(BankDeposit::getAmount)
                .containsExactlyInAnyOrder(bd("10.00"), bd("30.00")));
        assertNoSuccess();
    }
    @Test void singletonForeignCurrencySecuritiesAccountIsNotConvertedToTaiwanDollar() {
        setTarget("10", "USD", null, "usd-securities", "10.0000");
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.AMBIGUOUS_TARGET);
        readSnapshot(s -> { assertThat(target(s).getCurrency()).isEqualTo("USD"); assertThat(target(s).getAmount()).isEqualByComparingTo("10"); });
    }

    @Test void missingSecuritiesAccountRejectsWithoutInsertingOrTouchingDemandDepositOrAggregates() {
        Long demandId = readDemandDepositId();
        tx(() -> { var snapshot = snapshots.findById(snapshotId).orElseThrow();
            snapshot.getDeposits().removeIf(this::isSecuritiesAccountTarget); aggregateCalculator.recalculate(snapshot); snapshots.saveAndFlush(snapshot); });
        var before = totals();
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.TARGET_MISSING);
        readSnapshot(s -> {
            assertThat(s.getDeposits()).hasSize(2); assertThat(demandDeposit(s).getId()).isEqualTo(demandId);
            assertThat(demandDeposit(s).getAmount()).isEqualByComparingTo("20"); assertThat(demandDeposit(s).getNotes()).isEqualTo("demand-note");
        });
        assertThat(totals()).containsExactlyElementsOf(before); assertNoSuccess();
    }

    @ParameterizedTest @ValueSource(strings = {"bank", "type", "broker"})
    void inactiveLookupAfterLockIsTypedAndWritesNothing(String kind) {
        tx(() -> { switch (kind) {
            case "bank" -> { var b = banks.findById(fubon.getId()).orElseThrow(); b.setActive(false); banks.saveAndFlush(b); }
            case "type" -> { var d = depositTypes.findByCode("證券戶").orElseThrow(); d.setActive(false); depositTypes.saveAndFlush(d); }
            case "broker" -> { var b = brokers.findById(broker.getId()).orElseThrow(); b.setActive(false); brokers.saveAndFlush(b); }
        }});
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(kind.equals("broker") ? FubonBankBalanceOutcome.BROKER_MISSING : FubonBankBalanceOutcome.BANK_MISSING);
        readSnapshot(s -> assertThat(s.getDeposits()).hasSize(3)); assertNoSuccess();
    }
    @Test void missingBankIsReportedAndDoesNotCreateLookupOrDeposit() {
        tx(() -> { var snapshot = snapshots.findById(snapshotId).orElseThrow();
            snapshot.getDeposits().removeIf(deposit -> deposit.getBank() != null && "fubon".equals(deposit.getBank().getCode()));
            snapshots.saveAndFlush(snapshot); banks.deleteById(fubon.getId()); banks.flush(); });
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.BANK_MISSING);
        assertThat(banks.findByCode("fubon")).isEmpty(); readSnapshot(s -> assertThat(s.getDeposits()).hasSize(1));
    }
    @Test void noSnapshotDoesNotCreateOne() {
        snapshots.deleteAll();
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.NO_SNAPSHOT);
        assertThat(snapshots.count()).isZero();
    }
    @Test void ownerDeactivatedDuringHttpIsRecheckedAfterLock() {
        when(brokerClient.readBankBalance()).thenAnswer(call -> { tx(() -> {
            var owner = users.findById(ownerId).orElseThrow(); owner.setStatus(AppUser.STATUS_DISABLED); users.saveAndFlush(owner);
        }); return FubonDtos.CallResult.success(bank("1000")); });
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.NO_OWNER);
        readSnapshot(s -> assertThat(s.getDeposits()).hasSize(3)); assertNoSuccess();
    }
    @Test void writerRejectsSnapshotForDifferentOwnerEvenIfItExists() {
        tx(() -> snapshots.saveAndFlush(AssetSnapshot.builder().ownerUserId(otherOwnerId).snapshotDate(DATE).build()));
        assertThatThrownBy(() -> writer.write(otherOwnerId, bank("1000")))
                .isInstanceOf(FubonBankBalanceWriter.WriteRejected.class).hasMessage("NO_OWNER");
        readSnapshot(s -> assertThat(s.getDeposits()).hasSize(3));
    }
    @Test void latestSnapshotCreatedDuringHttpIsTheOnlyTarget() {
        var newestId = new AtomicReference<Long>();
        when(brokerClient.readBankBalance()).thenAnswer(call -> { tx(() -> {
            var newest = AssetSnapshot.builder().ownerUserId(ownerId).snapshotDate(DATE).usdExchangeRate(BigDecimal.ONE).build();
            newest.getDeposits().add(deposit(newest, banks.findById(fubon.getId()).orElseThrow(), "證券戶", "20", "TWD", "2", "newest-target", "20.1234"));
            aggregateCalculator.recalculate(newest); newestId.set(snapshots.saveAndFlush(newest).getId());
        });
            return FubonDtos.CallResult.success(bank("1000")); });
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
        readSnapshot(s -> assertThat(s.getDeposits()).hasSize(3));
        tx(() -> { em.clear(); var newest = snapshots.findById(newestId.get()).orElseThrow();
            assertThat(target(newest).getAmount()).isEqualByComparingTo("1000"); assertThat(newest.getTotalAssets()).isEqualByComparingTo("1000"); });
    }
    @Test void dryRunFromRealProxyHasNoSnapshotSqlOrMutations() {
        sqlTrace.start(); assertThat(bankService.syncManual(true).outcome()).isEqualTo(FubonBankBalanceOutcome.DRY_RUN);
        assertThat(sqlTrace.stop()).noneMatch(sql -> sql.contains("asset_snapshot") || sql.contains("bank_deposit") || sql.contains("for update"));
        readSnapshot(s -> assertThat(s.getDeposits()).hasSize(3)); assertNoSuccess();
    }
    @Test void freshnessIsRecheckedAfterFlushBeforeCommit() {
        doAnswer(call -> { call.callRealMethod(); clock.set(NOW.plusSeconds(32)); return null; })
                .when(aggregateCalculator).recalculate(any());
        var result = bankService.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonBankBalanceOutcome.STALE_QUERY); assertThat(result.updatedAmount()).isNull();
        readSnapshot(s -> { assertThat(target(s).getAmount()).isEqualByComparingTo("30"); assertThat(s.getTotalAssets()).isEqualByComparingTo("400"); });
        assertNoSuccess();
    }
    @Test void crossingTaipeiMidnightDuringWriterRollsBack() {
        Instant beforeMidnight = Instant.parse("2026-08-28T15:59:59Z"); clock.set(beforeMidnight);
        when(brokerClient.readBankBalance()).thenReturn(FubonDtos.CallResult.success(new FubonDtos.BankBalance(
                DATE, beforeMidnight, FINGERPRINT, "TWD", CanonicalFubonDecimal.parseNonNegative("1000"), CanonicalFubonDecimal.parseNonNegative("0"))));
        doAnswer(call -> { call.callRealMethod(); clock.set(beforeMidnight.plusSeconds(2)); return null; })
                .when(aggregateCalculator).recalculate(any());
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.STALE_QUERY);
        readSnapshot(s -> assertThat(s.getDeposits()).hasSize(3)); assertNoSuccess();
    }
    @Test void databaseFlushFailureRollsBackBothChildAndAggregate() {
        doAnswer(call -> { call.callRealMethod(); ((AssetSnapshot) call.getArgument(0)).setNotes("x".repeat(501)); return null; })
                .when(aggregateCalculator).recalculate(any());
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.ROLLED_BACK);
        readSnapshot(s -> { assertThat(target(s).getAmount()).isEqualByComparingTo("30"); assertThat(s.getTotalAssets()).isEqualByComparingTo("400"); assertThat(s.getNotes()).isNull(); });
        assertNoSuccess();
    }
    @Test void actualDeferredCommitFailureCannotBecomeSuccess() {
        tx(() -> { em.createNativeQuery("CREATE FUNCTION reject_bank_commit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test-only deferred rejection''; RETURN NEW; END'").executeUpdate();
            em.createNativeQuery("CREATE CONSTRAINT TRIGGER reject_bank_commit AFTER UPDATE ON bank_deposit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.amount = 9999) EXECUTE FUNCTION reject_bank_commit()").executeUpdate(); });
        when(brokerClient.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank("9999")));
        var result = bankService.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonBankBalanceOutcome.ROLLED_BACK); assertThat(result.updatedAmount()).isNull();
        readSnapshot(s -> { assertThat(target(s).getAmount()).isEqualByComparingTo("30"); assertThat(s.getTotalAssets()).isEqualByComparingTo("400"); });
        assertNoSuccess();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void bankAndFullPutSerializeOnTheSameRealPostgresRow(boolean bankFirst) throws Exception {
        setTarget("20", "TWD", "2", "old-note", "20.1234");
        CountDownLatch locked = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        AssetSnapshotMutationLock lockTarget = AopTestUtils.getUltimateTargetObject(mutationLock);
        if (bankFirst) doAnswer(call -> { Object result = call.callRealMethod(); locked.countDown(); await(release); return result; })
                .when(lockTarget).lockLatestForFubonConfiguredOwner(anyLong());
        else doAnswer(call -> { Object result = call.callRealMethod(); locked.countDown(); await(release); return result; })
                .when(lockTarget).lockById(snapshotId);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Runnable bank = () -> assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
            CompletableFuture<Void> first = CompletableFuture.runAsync(bankFirst ? bank : this::fullPut, executor);
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> second = CompletableFuture.runAsync(bankFirst ? this::fullPut : bank, executor);
            try { assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        readSnapshot(s -> {
            assertThat(s.getDeposits()).hasSize(3); assertThat(target(s).getAmount()).isEqualByComparingTo(bankFirst ? "30" : "1000");
            assertThat(target(s).getNotes()).isEqualTo("manual-security-note"); assertThat(target(s).getOriginalAmount()).isEqualByComparingTo("30.5678");
            assertThat(demandDeposit(s).getAmount()).isEqualByComparingTo("7");
            assertThat(s.getTotalDeposit()).isEqualByComparingTo(bankFirst ? "237" : "1207");
            assertThat(s.getTotalAssets()).isEqualByComparingTo(bankFirst ? "367" : "1337");
            assertThat(s.getEstimatedAnnualDividend()).isEqualByComparingTo(bankFirst ? "19" : "48");
            assertThat(s.getFunds().getFirst().getCurrentValue()).isEqualByComparingTo("40");
            assertThat(s.getStocks().getFirst().getCurrentValue()).isEqualByComparingTo("90");
        });
    }

    private void fullPut() {
        assetService.updateSnapshot(snapshotId, new AssetSnapshotDto.CreateSnapshotRequest(DATE.minusDays(1), BigDecimal.ONE, "full-put",
                List.of(new AssetSnapshotDto.DepositRequest(fubon.getId(), "活存", bd("7"), bd("7.1234"), "TWD", bd("2"), "manual-demand-note"),
                        new AssetSnapshotDto.DepositRequest(fubon.getId(), "證券戶", bd("30"), bd("30.5678"), "TWD", bd("3"), "manual-security-note"),
                        new AssetSnapshotDto.DepositRequest(otherBank.getId(), "活存", bd("200"), null, "TWD", bd("1"), "other-manual")),
                List.of(new AssetSnapshotDto.FundRequest("test-fund", null, otherBank.getId(), bd("35"), bd("40"), null, bd("7"))),
                List.of(new AssetSnapshotDto.StockRequest("0050", "0050", "台股", broker.getId(), bd("1"), bd("80"), bd("90"),
                        bd("9"), bd("0.1"), "TWD", null, null, null, null))));
    }
    private void addSecuritiesAccountTarget(String amount, String currency, String rate, String notes, String originalAmount) {
        tx(() -> { var s = snapshots.findById(snapshotId).orElseThrow(); s.getDeposits().add(deposit(s, fubon, "證券戶", amount, currency, rate, notes, originalAmount));
            aggregateCalculator.recalculate(s); snapshots.saveAndFlush(s); });
    }
    private void setTarget(String amount, String currency, String rate, String notes, String originalAmount) {
        tx(() -> { var target = target(snapshots.findById(snapshotId).orElseThrow()); target.setAmount(bd(amount)); target.setCurrency(currency);
            target.setAnnualInterestRate(rate == null ? null : bd(rate)); target.setNotes(notes); target.setOriginalAmount(originalAmount == null ? null : bd(originalAmount));
            aggregateCalculator.recalculate(target.getSnapshot()); snapshots.saveAndFlush(target.getSnapshot()); });
    }
    private BankDeposit deposit(AssetSnapshot s, Bank bank, String type, String amount, String currency, String rate, String notes) {
        return deposit(s, bank, type, amount, currency, rate, notes, null);
    }
    private BankDeposit deposit(AssetSnapshot s, Bank bank, String type, String amount, String currency, String rate, String notes, String originalAmount) {
        return BankDeposit.builder().snapshot(s).bank(bank).depositType(type).amount(bd(amount)).currency(currency)
                .annualInterestRate(rate == null ? null : bd(rate)).notes(notes)
                .originalAmount(originalAmount == null ? null : bd(originalAmount)).build();
    }
    private BankDeposit target(AssetSnapshot s) {
        return securitiesAccountTargets(s).stream().findFirst().orElseThrow();
    }
    private List<BankDeposit> securitiesAccountTargets(AssetSnapshot s) { return s.getDeposits().stream().filter(this::isSecuritiesAccountTarget).toList(); }
    private boolean isSecuritiesAccountTarget(BankDeposit deposit) {
        return deposit.getBank() != null && "fubon".equals(deposit.getBank().getCode()) && "證券戶".equals(deposit.getDepositType());
    }
    private BankDeposit demandDeposit(AssetSnapshot s) {
        return s.getDeposits().stream().filter(d -> d.getBank() != null && "fubon".equals(d.getBank().getCode())
                && "活存".equals(d.getDepositType())).findFirst().orElseThrow();
    }
    private Long readTargetId() { AtomicReference<Long> id = new AtomicReference<>(); readSnapshot(s -> id.set(target(s).getId())); return id.get(); }
    private Long readDemandDepositId() { AtomicReference<Long> id = new AtomicReference<>(); readSnapshot(s -> id.set(demandDeposit(s).getId())); return id.get(); }
    private List<BigDecimal> totals() { List<BigDecimal> values = new ArrayList<>(); readSnapshot(s -> { values.add(s.getTotalDeposit()); values.add(s.getTotalAssets()); values.add(s.getEstimatedAnnualDividend()); }); return values; }
    private void readSnapshot(Consumer<AssetSnapshot> assertion) { tx(() -> { em.clear(); assertion.accept(snapshots.findById(snapshotId).orElseThrow()); }); }
    private void tx(Runnable action) { new TransactionTemplate(transactions).executeWithoutResult(status -> action.run()); }
    private void assertNoSuccess() { assertThat(counters.snapshot().get(FubonBankBalanceOutcome.SUCCESS)).isEqualTo(priorSuccess); }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static void await(CountDownLatch latch) { try { if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("test barrier timed out"); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); } }

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
        List<String> stop() { var rows = active.get(); active.remove(); return rows == null ? List.of() : List.copyOf(rows); }
        @Override public String inspect(String sql) { if (active.get() != null) active.get().add(sql.toLowerCase()); return sql; }
    }
    @TestConfiguration static class Config {
        @Bean MutableClock accountingClock() { return new MutableClock(); }
        @Bean SqlTrace sqlTrace() { return new SqlTrace(); }
        @Bean HibernatePropertiesCustomizer tracing(SqlTrace trace) { return properties -> properties.put("hibernate.session_factory.statement_inspector", trace); }
        @Bean FubonBankBalanceOutcomeCounters bankCounters() { return new FubonBankBalanceOutcomeCounters(); }
        @Bean FubonBankBalanceWriter bankWriter(AssetSnapshotMutationLock lock, UserAdminService users, BrokerRepository brokers,
                BankRepository banks, DepositTypeRepository types, SnapshotAggregateCalculator calculator, AssetSnapshotRepository snapshots, FubonSyncFreshness freshness, MutableClock clock) {
            return new FubonBankBalanceWriter(lock, users, brokers, banks, types, calculator, snapshots, freshness, clock);
        }
        @Bean FubonBankBalanceSyncService bankService(FubonConfigState config, FubonBrokerClient client, UserAdminService users,
                FubonBankBalanceWriter writer, FubonBankBalanceOutcomeCounters counters, MutableClock clock) {
            return new FubonBankBalanceSyncService(config, client, users, writer, counters, true, clock);
        }
    }
}
