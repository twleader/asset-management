package com.steven.assets.integration.fubon;

import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.CurrentUserFilter;
import com.steven.assets.security.TenantFilterAspect;
import com.steven.assets.service.AssetSnapshotMutationLock;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.SnapshotAggregateCalculator;
import com.steven.assets.service.StockMasterService;
import com.steven.assets.service.UserAdminService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** HTTP identity, actual Hibernate aspect and OSIV persistence context meet real PostgreSQL. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.liquibase.enabled=false",
        "app.admin-email=snapshot-test@example.invalid"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaFubonSyncFreshness.class, AssetSnapshotMutationLock.class, SnapshotAggregateCalculator.class, UserAdminService.class,
        CurrentUserContext.class, CurrentUserFilter.class, TenantFilterAspect.class,
        FubonConfiguredOwnerSnapshotPostgresTest.Config.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FubonConfiguredOwnerSnapshotPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fubon_snapshot_identity_isolated").withUsername("assets").withPassword("test-only-password");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean FubonConfigState configState;
    @MockBean FubonBrokerClient brokerClient;
    @MockBean MarketDataService marketData;
    @MockBean StockMasterService stockMaster;
    @SpyBean AssetSnapshotMutationLock mutationLock;
    @Autowired AssetSnapshotRepository snapshots;
    @Autowired AppUserRepository users;
    @Autowired BankRepository banks;
    @Autowired BrokerRepository brokers;
    @Autowired DepositTypeRepository depositTypes;
    @Autowired FubonBankBalanceSyncService bankService;
    @Autowired FubonBankBalanceWriter bankWriter;
    @Autowired FubonInventorySyncService inventoryService;
    @Autowired FubonInventoryWriter inventoryWriter;
    @SpyBean SnapshotAggregateCalculator calculator;
    @Autowired CurrentUserFilter currentUserFilter;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired SqlTrace trace;
    @Autowired MutableClock clock;
    private Long ownerId;
    private Long otherOwnerId;
    private Long snapshotId;
    private Long otherSnapshotId;
    private Bank fubonBank;
    private Bank otherBank;
    private BrokerEntity fubonBroker;
    private BrokerEntity otherBroker;

    @BeforeEach void seed() {
        RequestContextHolder.resetRequestAttributes(); trace.stop(); reset(mutationLock, brokerClient, configState, calculator); clock.set(NOW);
        tx(() -> {
            snapshots.deleteAll(); snapshots.flush(); banks.deleteAll(); brokers.deleteAll(); depositTypes.deleteAll(); users.deleteAll(); em.flush();
            ownerId = users.saveAndFlush(AppUser.builder().email("snapshot-test@example.invalid")
                    .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()).getId();
            otherOwnerId = users.saveAndFlush(AppUser.builder().email("other-snapshot-test@example.invalid")
                    .role(AppUser.ROLE_USER).status(AppUser.STATUS_ACTIVE).build()).getId();
            fubonBank = banks.saveAndFlush(Bank.builder().code("fubon").displayName("台北富邦銀行").active(true).build());
            otherBank = banks.saveAndFlush(Bank.builder().code("other").displayName("其他銀行").active(true).build());
            fubonBroker = brokers.saveAndFlush(BrokerEntity.builder().code("fubon").displayName("富邦證券").active(true).build());
            otherBroker = brokers.saveAndFlush(BrokerEntity.builder().code("other").displayName("其他券商").active(true).build());
            depositTypes.saveAndFlush(DepositTypeEntity.builder().code("證券戶").displayName("證券戶").active(true).build());
            depositTypes.saveAndFlush(DepositTypeEntity.builder().code("活存").displayName("活存").active(true).build());
            AssetSnapshot snapshot = AssetSnapshot.builder().ownerUserId(ownerId).snapshotDate(DATE).usdExchangeRate(BigDecimal.ONE).build();
            snapshot.getDeposits().add(deposit(snapshot, fubonBank, "20", "證券戶"));
            snapshot.getDeposits().add(deposit(snapshot, otherBank, "100", "活存"));
            snapshot.getStocks().add(holding(snapshot, fubonBroker, "2330", "20"));
            snapshot.getStocks().add(holding(snapshot, otherBroker, "0050", "50"));
            calculator.recalculate(snapshot); snapshotId = snapshots.saveAndFlush(snapshot).getId();
            otherSnapshotId = snapshots.saveAndFlush(AssetSnapshot.builder().ownerUserId(otherOwnerId).snapshotDate(DATE).build()).getId();
        });
        when(configState.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "synthetic", "READY"));
        when(marketData.isTwTradingDayKnown(DATE)).thenReturn(Optional.of(true));
        when(brokerClient.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank("1000")));
        when(brokerClient.readPortfolio()).thenReturn(FubonDtos.CallResult.success(new FubonDtos.PortfolioResponse(
                "inventory-batch", DATE, FINGERPRINT, false, List.of(new FubonDtos.Position("2330", 3, decimal("12.345"))), null)));
        var quote = new FubonDtos.Quote("2330", "台積電", "台股", decimal("20"), decimal("19"), decimal("19"), decimal("21"),
                decimal("18"), null, null, 5L, NOW, DATE, "FUBON_INTRADAY", false, "LIVE");
        when(brokerClient.readTwQuotes(List.of("2330"))).thenReturn(FubonDtos.CallResult.success(
                new FubonDtos.QuoteBatchResponse("quote-batch", List.of(new FubonDtos.QuoteItem("2330", "SUCCESS", null, quote)))));
    }

    @Test void internalRequestWithoutTenantHeadersWritesConfiguredOwnerWhileOrdinaryReadsRemainEmpty() {
        assertThat(AopUtils.isAopProxy(bankWriter)).isTrue(); assertThat(AopUtils.isAopProxy(inventoryWriter)).isTrue();
        inRequest(null, () -> {
            tx(() -> assertThat(snapshots.findLatest()).as("real fail-closed aspect is active").isEmpty());
            assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
            assertThat(inventoryService.syncManual(false).outcome()).isEqualTo(FubonOutcome.SUCCESS);
            tx(() -> assertThat(snapshots.findAll()).isEmpty());
        });
        tx(() -> {
            em.clear(); var snapshot = snapshots.findById(snapshotId).orElseThrow();
            assertThat(target(snapshot).getAmount()).isEqualByComparingTo("1000");
            assertThat(snapshot.getStocks().stream().filter(s -> s.getBroker().getCode().equals("fubon")).findFirst().orElseThrow().getShares()).isEqualByComparingTo("3");
            assertThat(snapshots.findById(otherSnapshotId).orElseThrow().getDeposits()).isEmpty();
        });
    }

    @Test void unrelatedTenantHeaderCannotRedirectEitherConfiguredOwnerWriterOrWidenOrdinaryReads() {
        inRequest(otherOwnerId, () -> {
            tx(() -> assertThat(snapshots.findLatest().orElseThrow().getOwnerUserId()).isEqualTo(otherOwnerId));
            assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
            assertThat(inventoryService.syncManual(false).outcome()).isEqualTo(FubonOutcome.SUCCESS);
            tx(() -> {
                assertThat(snapshots.findAll()).extracting(AssetSnapshot::getOwnerUserId).containsExactly(otherOwnerId);
                assertThat(snapshots.findLatestByOwnerUserIdForUpdate(ownerId)).isEmpty();
            });
        });
        tx(() -> { em.clear(); assertThat(target(snapshots.findById(snapshotId).orElseThrow()).getAmount()).isEqualByComparingTo("1000");
            assertThat(snapshots.findById(otherSnapshotId).orElseThrow().getStocks()).isEmpty(); });
    }

    @Test void firstWriterSqlIsStillTheSharedSnapshotRowLockWithHttpContext() {
        inRequest(null, () -> {
            trace.start(); bankWriter.write(ownerId, bank("1000"));
            assertThat(trace.stop().getFirst()).contains("from asset_snapshot").contains("for update").doesNotContain("app_user", "bank_deposit");
        });
    }

    @Test void ownerDisabledDuringAdapterReadIsNotRevalidatedFromOsivCachedEntity() {
        when(brokerClient.readBankBalance()).thenAnswer(call -> {
            mutateElsewhere(() -> { var owner = users.findById(ownerId).orElseThrow(); owner.setStatus(AppUser.STATUS_DISABLED); users.saveAndFlush(owner); });
            return FubonDtos.CallResult.success(bank("1000"));
        });
        inRequest(null, () -> assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.NO_OWNER));
        tx(() -> { em.clear(); assertThat(target(snapshots.findById(snapshotId).orElseThrow()).getAmount()).isEqualByComparingTo("20"); });
    }

    @Test void inventoryOwnerDisabledAfterPreflightIsRecheckedAfterItsSnapshotLock() {
        AssetSnapshotMutationLock target = AopTestUtils.getUltimateTargetObject(mutationLock);
        doAnswer(call -> {
            Object locked = call.callRealMethod();
            mutateElsewhere(() -> { var owner = users.findById(ownerId).orElseThrow(); owner.setStatus(AppUser.STATUS_DISABLED); users.saveAndFlush(owner); });
            return locked;
        }).when(target).lockLatestForFubonConfiguredOwner(ownerId);
        inRequest(null, () -> assertThat(inventoryService.syncManual(false).outcome()).isEqualTo(FubonOutcome.NO_OWNER));
        tx(() -> { em.clear(); assertThat(snapshots.findById(snapshotId).orElseThrow().getStocks().stream()
                .filter(s -> s.getBroker().getCode().equals("fubon")).findFirst().orElseThrow().getShares()).isEqualByComparingTo("1"); });
    }

    @Test void alreadyManagedSnapshotChildrenMustBeFreshAfterAcquiringTheRowLock() {
        inRequest(null, () -> {
            tx(() -> { var cached = snapshots.findById(snapshotId).orElseThrow(); assertThat(cached.getDeposits()).hasSize(2); });
            mutateElsewhere(() -> { var snapshot = snapshots.findById(snapshotId).orElseThrow(); snapshot.getDeposits().stream()
                    .filter(d -> d.getBank().getCode().equals("other")).forEach(d -> d.setAmount(new BigDecimal("400")));
                calculator.recalculate(snapshot); snapshots.saveAndFlush(snapshot); });
            assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
        });
        tx(() -> { em.clear(); var snapshot = snapshots.findById(snapshotId).orElseThrow();
            assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo("1400");
            assertThat(snapshot.getTotalAssets()).isEqualByComparingTo("1470");
            assertThat(snapshot.getDeposits().stream().filter(d -> d.getBank().getCode().equals("other")).findFirst().orElseThrow().getAmount()).isEqualByComparingTo("400"); });
    }

    @ParameterizedTest @ValueSource(strings = {"broker", "bank", "deposit-type"})
    void cachedLookupCannotKeepADeactivatedTargetEligible(String kind) {
        inRequest(null, () -> {
            tx(() -> { switch (kind) {
                case "broker" -> assertThat(brokers.findByCode("fubon").orElseThrow().getActive()).isTrue();
                case "bank" -> assertThat(banks.findByCode("fubon").orElseThrow().getActive()).isTrue();
                default -> assertThat(depositTypes.findByCode("證券戶").orElseThrow().getActive()).isTrue();
            }});
            mutateElsewhere(() -> { switch (kind) {
                case "broker" -> { var b = brokers.findByCode("fubon").orElseThrow(); b.setActive(false); brokers.saveAndFlush(b); }
                case "bank" -> { var b = banks.findByCode("fubon").orElseThrow(); b.setActive(false); banks.saveAndFlush(b); }
                default -> { var t = depositTypes.findByCode("證券戶").orElseThrow(); t.setActive(false); depositTypes.saveAndFlush(t); }
            }});
            assertThat(bankService.syncManual(false).outcome()).isEqualTo(kind.equals("broker")
                    ? FubonBankBalanceOutcome.BROKER_MISSING : FubonBankBalanceOutcome.BANK_MISSING);
        });
        tx(() -> { em.clear(); assertThat(target(snapshots.findById(snapshotId).orElseThrow()).getAmount()).isEqualByComparingTo("20"); });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void depositMembershipChangeIsReloadedOrSafelyRolledBackWithoutWrongTotals(boolean add) {
        var outcome = new java.util.concurrent.atomic.AtomicReference<FubonBankBalanceOutcome>();
        inRequest(null, () -> {
            tx(() -> assertThat(snapshots.findById(snapshotId).orElseThrow().getDeposits()).hasSize(2));
            mutateElsewhere(() -> {
                var snapshot = snapshots.findById(snapshotId).orElseThrow();
                if (add) snapshot.getDeposits().add(deposit(snapshot, otherBank, "300", "定存"));
                else snapshot.getDeposits().removeIf(d -> d.getBank().getCode().equals("other"));
                calculator.recalculate(snapshot); snapshots.saveAndFlush(snapshot);
            });
            var result = bankService.syncManual(false);
            outcome.set(result.outcome());
            assertThat(result.outcome()).isIn(FubonBankBalanceOutcome.SUCCESS, FubonBankBalanceOutcome.ROLLED_BACK);
            if (result.outcome() == FubonBankBalanceOutcome.ROLLED_BACK) assertThat(result.updatedAmount()).isNull();
        });
        tx(() -> {
            em.clear(); var snapshot = snapshots.findById(snapshotId).orElseThrow();
            assertThat(snapshot.getDeposits()).hasSize(add ? 3 : 1);
            BigDecimal targetAmount = new BigDecimal(outcome.get() == FubonBankBalanceOutcome.SUCCESS ? "1000" : "20");
            assertThat(target(snapshot).getAmount()).isEqualByComparingTo(targetAmount);
            BigDecimal expected = targetAmount.add(new BigDecimal(add ? "400" : "0"));
            assertThat(snapshot.getTotalDeposit()).isEqualByComparingTo(expected);
            assertThat(snapshot.getTotalAssets()).isEqualByComparingTo(expected.add(new BigDecimal("70")));
        });
        // If a deleted already-managed child forced a safe rollback, the next clean request recovers.
        assertThat(bankService.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.SUCCESS);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void stockMembershipChangeNeverUsesStaleOwnedCollectionForPartialReplacement(boolean add) {
        var outcome = new java.util.concurrent.atomic.AtomicReference<FubonOutcome>();
        inRequest(null, () -> {
            tx(() -> assertThat(snapshots.findById(snapshotId).orElseThrow().getStocks()).hasSize(2));
            mutateElsewhere(() -> {
                var snapshot = snapshots.findById(snapshotId).orElseThrow();
                if (add) snapshot.getStocks().add(holding(snapshot, otherBroker, "0056", "30"));
                else snapshot.getStocks().removeIf(s -> s.getBroker().getCode().equals("other"));
                calculator.recalculate(snapshot); snapshots.saveAndFlush(snapshot);
            });
            var result = inventoryService.syncManual(false);
            outcome.set(result.outcome());
            assertThat(result.outcome()).isIn(FubonOutcome.SUCCESS, FubonOutcome.ROLLED_BACK);
            if (result.outcome() == FubonOutcome.ROLLED_BACK) assertThat(result.replaceCount()).isZero();
        });
        tx(() -> {
            em.clear(); var snapshot = snapshots.findById(snapshotId).orElseThrow();
            assertThat(snapshot.getStocks()).hasSize(add ? 3 : 1);
            var target = snapshot.getStocks().stream().filter(s -> s.getBroker().getCode().equals("fubon")).findFirst().orElseThrow();
            assertThat(target.getShares()).isEqualByComparingTo(outcome.get() == FubonOutcome.SUCCESS ? "3" : "1");
            BigDecimal value = new BigDecimal(outcome.get() == FubonOutcome.SUCCESS ? "60" : "20");
            BigDecimal expected = value.add(new BigDecimal(add ? "80" : "0"));
            assertThat(snapshot.getTotalStockValue()).isEqualByComparingTo(expected);
            assertThat(snapshot.getTotalAssets()).isEqualByComparingTo(expected.add(new BigDecimal("120")));
        });
        assertThat(inventoryService.syncManual(false).outcome()).isEqualTo(FubonOutcome.SUCCESS);
    }

    @Test void inventoryDateRolloverBeforeCommitRollsBackFlushedChildrenAndTotals() {
        doAnswer(call -> { call.callRealMethod(); clock.set(NOW.plusSeconds(86400)); return null; })
                .when(calculator).recalculate(any());
        assertThat(inventoryService.syncManual(false).outcome()).isEqualTo(FubonOutcome.ROLLED_BACK);
        tx(() -> { em.clear(); var snapshot = snapshots.findById(snapshotId).orElseThrow();
            assertThat(snapshot.getStocks().stream().filter(s -> s.getBroker().getCode().equals("fubon"))
                    .findFirst().orElseThrow().getShares()).isEqualByComparingTo("1");
            assertThat(snapshot.getTotalStockValue()).isEqualByComparingTo("70");
            assertThat(snapshot.getTotalAssets()).isEqualByComparingTo("190"); });
    }

    private void inRequest(Long headerOwner, Runnable body) {
        var request = new MockHttpServletRequest();
        if (headerOwner != null) { request.addHeader(CurrentUserFilter.HDR_USER_ID, headerOwner.toString());
            request.addHeader(CurrentUserFilter.HDR_USER_ROLE, AppUser.ROLE_USER);
            request.addHeader(CurrentUserFilter.HDR_USER_STATUS, AppUser.STATUS_ACTIVE); }
        var attributes = new ServletRequestAttributes(request);
        EntityManager osiv = emf.createEntityManager();
        RequestContextHolder.setRequestAttributes(attributes);
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(osiv));
        try { currentUserFilter.doFilter(request, new MockHttpServletResponse(), (req, res) -> body.run()); }
        catch (java.io.IOException | jakarta.servlet.ServletException failure) { throw new AssertionError(failure); }
        finally { attributes.requestCompleted(); RequestContextHolder.resetRequestAttributes();
            TransactionSynchronizationManager.unbindResource(emf); osiv.close(); }
    }
    private void mutateElsewhere(Runnable body) {
        try { CompletableFuture.runAsync(() -> tx(body)).get(5, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError("isolated concurrent transaction failed", failure); }
    }
    private void tx(Runnable body) { new TransactionTemplate(transactions).executeWithoutResult(status -> body.run()); }
    private static CanonicalFubonDecimal decimal(String value) { return CanonicalFubonDecimal.parsePositive(value); }
    private BankDeposit deposit(AssetSnapshot snapshot, Bank bank, String amount, String type) {
        return BankDeposit.builder().snapshot(snapshot).bank(bank).depositType(type).amount(new BigDecimal(amount)).currency("TWD").build();
    }
    private StockHolding holding(AssetSnapshot snapshot, BrokerEntity broker, String code, String value) {
        return StockHolding.builder().snapshot(snapshot).broker(broker).stockCode(code).market("台股").shares(BigDecimal.ONE)
                .investmentCost(new BigDecimal(value)).currentValue(new BigDecimal(value)).currency("TWD").build();
    }
    private BankDeposit target(AssetSnapshot snapshot) {
        return snapshot.getDeposits().stream().filter(d -> d.getBank().getCode().equals("fubon") && d.getDepositType().equals("證券戶"))
                .findFirst().orElseThrow();
    }
    static class SqlTrace implements org.hibernate.resource.jdbc.spi.StatementInspector {
        private final ThreadLocal<List<String>> statements = new ThreadLocal<>();
        void start() { statements.set(new ArrayList<>()); }
        List<String> stop() { var rows = statements.get(); statements.remove(); return rows == null ? List.of() : List.copyOf(rows); }
        @Override public String inspect(String sql) { if (statements.get() != null) statements.get().add(sql.toLowerCase()); return sql; }
    }
    static class MutableClock extends Clock {
        private final java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
    @TestConfiguration @EnableAspectJAutoProxy static class Config {
        @Bean static BeanFactoryPostProcessor requestScope() {
            return factory -> factory.registerScope("request", new org.springframework.web.context.request.RequestScope());
        }
        @Bean SqlTrace trace() { return new SqlTrace(); }
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean HibernatePropertiesCustomizer inspectSql(SqlTrace trace) { return properties -> properties.put("hibernate.session_factory.statement_inspector", trace); }
        @Bean FubonBankBalanceWriter bankWriter(AssetSnapshotMutationLock lock, UserAdminService users, BrokerRepository brokers,
                BankRepository banks, DepositTypeRepository types, SnapshotAggregateCalculator calculator, AssetSnapshotRepository snapshots, FubonSyncFreshness freshness, MutableClock clock) {
            return new FubonBankBalanceWriter(lock, users, brokers, banks, types, calculator, snapshots, freshness, clock);
        }
        @Bean FubonBankBalanceSyncService bankService(FubonConfigState config, FubonBrokerClient client, UserAdminService users, FubonBankBalanceWriter writer, MutableClock clock) {
            return new FubonBankBalanceSyncService(config, client, users, writer, new FubonBankBalanceOutcomeCounters(), true, clock);
        }
        @Bean FubonInventoryWriter inventoryWriter(AssetSnapshotMutationLock lock, AssetSnapshotRepository snapshots, BrokerRepository brokers,
                SnapshotAggregateCalculator calculator, UserAdminService users, FubonSyncFreshness freshness, MutableClock clock) {
            return new FubonInventoryWriter(lock, snapshots, brokers, calculator, users, freshness, clock);
        }
        @Bean FubonInventorySyncService inventoryService(FubonConfigState config, FubonBrokerClient client, MarketDataService marketData,
                UserAdminService users, AssetSnapshotRepository snapshots, BrokerRepository brokers, StockRepository stocks, FubonInventoryWriter writer, MutableClock clock) {
            return new FubonInventorySyncService(config, client, marketData, users, snapshots, brokers, stocks, writer, new FubonOutcomeCounters(), clock);
        }
    }
}
