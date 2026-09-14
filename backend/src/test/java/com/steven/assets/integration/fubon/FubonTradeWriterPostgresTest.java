package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.JpaFubonSyncFreshness;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.StockMasterService;
import com.steven.assets.service.UserAdminService;
import com.steven.assets.service.fubon.FubonSyncOwnerPolicy;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.TenantFilterAspect;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.mock.web.MockHttpServletRequest;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** Dedicated PostgreSQL: actual transactional writer, conflict target, and deferred commit. */
@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.liquibase.enabled=false",
        "app.admin-email=trade-test@example.invalid", "fubon.sync-owner-email=trade-test@example.invalid"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaFubonSyncFreshness.class, FubonTradeWriter.class, UserAdminService.class, CurrentUserContext.class,
        TenantFilterAspect.class, FubonTradeWriterPostgresTest.Config.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FubonTradeWriterPostgresTest {
    static final LocalDate DATE = LocalDate.of(2026, 8, 28);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T02:00:00Z"), ZoneOffset.UTC);
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fubon_trade_writer_isolated").withUsername("assets").withPassword("test-only-password");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean FubonConfigState config;
    @MockBean FubonBrokerClient client;
    @MockBean MarketDataService marketData;
    @MockBean StockMasterService stockMaster;
    @SpyBean FubonTradeWriter writer;
    @Autowired FubonTradeSyncService service;
    @Autowired FubonTradeOutcomeCounters counters;
    @Autowired AssetTransactionRepository ledger;
    @Autowired AppUserRepository users;
    @Autowired BrokerRepository brokers;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactions;
    private Long ownerId;
    private Long otherOwnerId;
    private Long brokerId;
    private long successBefore;
    private long rolledBackBefore;

    @BeforeEach void seedIsolatedDatabase() {
        reset(writer, config, client, marketData, stockMaster);
        tx(() -> {
            em.createNativeQuery("DROP TRIGGER IF EXISTS reject_trade_commit ON asset_transaction").executeUpdate();
            em.createNativeQuery("DROP FUNCTION IF EXISTS reject_trade_commit()").executeUpdate();
            em.createNativeQuery("ALTER TABLE asset_transaction DROP CONSTRAINT IF EXISTS reject_trade_row").executeUpdate();
            em.createNativeQuery("CREATE UNIQUE INDEX IF NOT EXISTS ux_asset_transaction_owner_broker_filled_no "
                    + "ON asset_transaction (owner_user_id, broker_filled_no) WHERE broker_filled_no IS NOT NULL").executeUpdate();
            ledger.deleteAll(); users.deleteAll(); brokers.deleteAll(); em.flush();
            ownerId = users.saveAndFlush(AppUser.builder().email("trade-test@example.invalid")
                    .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()).getId();
            otherOwnerId = users.saveAndFlush(AppUser.builder().email("other-trade-test@example.invalid")
                    .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()).getId();
            brokerId = brokers.saveAndFlush(BrokerEntity.builder().code("fubon").displayName("富邦證券").active(true).build()).getId();
        });
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "synthetic", "READY"));
        when(marketData.isTwTradingDayKnown(DATE)).thenReturn(Optional.of(true));
        when(stockMaster.resolveNameLocalOnly(any(), any())).thenReturn("測試股票");
        successBefore = counters.snapshot().get(FubonTradeOutcome.SUCCESS);
        rolledBackBefore = counters.snapshot().get(FubonTradeOutcome.ROLLED_BACK);
    }

    @Test void serviceAndWriterUseRealProxiesAndAdapterNeverRunsInsideCallerTransaction() {
        assertThat(AopUtils.isAopProxy(service)).isTrue(); assertThat(AopUtils.isAopProxy(writer)).isTrue();
        when(client.readFilledTrades(DATE, DATE)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return FubonDtos.CallResult.success(batch("F-1", "F-2"));
        });
        tx(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var result = service.syncManual(false);
            assertThat(result.outcome()).isEqualTo(FubonTradeOutcome.SUCCESS);
            assertThat(result.insertedCount()).isEqualTo(2);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });
        List<AssetTransaction> rows = ledger.findAll();
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            assertThat(row.getOwnerUserId()).isEqualTo(ownerId);
            assertThat(row.getTransactionType()).isEqualTo("賣"); assertThat(row.getAssetType()).isEqualTo("股票");
            assertThat(row.getAssetName()).isEqualTo("測試股票"); assertThat(row.getAssetCode()).isEqualTo("2330");
            assertThat(row.getMarket()).isEqualTo("台股"); assertThat(row.getCurrency()).isEqualTo("TWD");
            assertThat(row.getChannel()).isEqualTo("富邦證券"); assertThat(row.getTradeDate()).isEqualTo(DATE);
            assertThat(row.getShares()).isEqualByComparingTo("3"); assertThat(row.getPrice()).isEqualByComparingTo("12.345");
            assertThat(row.getAmount()).isEqualByComparingTo("37.04"); assertThat(row.getFee()).isNull();
            assertThat(row.getTransactionTax()).isNull(); assertThat(row.getExchangeRate()).isNull();
            assertThat(row.getNotes()).isNull(); assertThat(row.getSource()).isEqualTo("FUBON_SYNC");
        }
    }

    @Test void laterNonDuplicateConstraintFailureRollsBackTheEarlierInsert() {
        tx(() -> em.createNativeQuery("ALTER TABLE asset_transaction ADD CONSTRAINT reject_trade_row "
                + "CHECK (broker_filled_no <> 'F-REJECT')").executeUpdate());
        when(client.readFilledTrades(DATE, DATE)).thenReturn(FubonDtos.CallResult.success(batch("F-FIRST", "F-REJECT")));
        assertThatThrownBy(() -> service.syncManual(false)).isInstanceOf(IllegalStateException.class)
                .hasMessage("TRANSACTION_ROLLED_BACK").hasNoCause();
        assertThat(ledger.findAll()).isEmpty(); assertNoSuccess();
        assertThat(counters.snapshot().get(FubonTradeOutcome.ROLLED_BACK)).isEqualTo(rolledBackBefore + 1);
    }

    @Test void databaseDeferredCommitFailureCannotReturnOrIncrementSuccess() {
        tx(() -> {
            em.createNativeQuery("CREATE FUNCTION reject_trade_commit() RETURNS trigger LANGUAGE plpgsql AS "
                    + "'BEGIN RAISE EXCEPTION ''synthetic deferred rejection''; RETURN NEW; END'").executeUpdate();
            em.createNativeQuery("CREATE CONSTRAINT TRIGGER reject_trade_commit AFTER INSERT ON asset_transaction "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.broker_filled_no = 'F-COMMIT') "
                    + "EXECUTE FUNCTION reject_trade_commit()").executeUpdate();
        });
        when(client.readFilledTrades(DATE, DATE)).thenReturn(FubonDtos.CallResult.success(batch("F-FIRST", "F-COMMIT")));
        assertThatThrownBy(() -> service.syncManual(false)).isInstanceOf(IllegalStateException.class)
                .hasMessage("TRANSACTION_ROLLED_BACK").hasNoCause();
        assertThat(ledger.findAll()).isEmpty(); assertNoSuccess();
    }

    @Test void twoConcurrentWritersCommitExactlyOneSameOwnerFill() throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        FubonTradeWriter target = AopTestUtils.getUltimateTargetObject(writer);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (first.compareAndSet(true, false)) { firstInserted.countDown(); await(releaseFirst); }
            return result;
        }).when(target).insert(any(), any(), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = CompletableFuture.supplyAsync(() -> writer.insert(ownerId, brokerId, List.of(prepared("F-SAME"))), executor);
            assertThat(firstInserted.await(5, TimeUnit.SECONDS)).isTrue();
            var b = CompletableFuture.supplyAsync(() -> writer.insert(ownerId, brokerId, List.of(prepared("F-SAME"))), executor);
            try { assertThatThrownBy(() -> b.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { releaseFirst.countDown(); }
            assertThat(a.get(10, TimeUnit.SECONDS).insertedCount()).isEqualTo(1);
            var duplicate = b.get(10, TimeUnit.SECONDS);
            assertThat(duplicate.insertedCount()).isZero(); assertThat(duplicate.skippedExistingCount()).isEqualTo(1);
        } finally { releaseFirst.countDown(); }
        assertThat(ledger.findAll()).hasSize(1);
    }

    @Test void duplicatePreservesEveryManualEditAndLaterNewFillStillInserts() {
        writer.insert(ownerId, brokerId, List.of(prepared("F-EDITED")));
        tx(() -> { var row = ledger.findAll().getFirst(); row.setAssetName("manual name"); row.setAmount(bd("7"));
            row.setFee(bd("2")); row.setTransactionTax(bd("3")); row.setNotes("manual note"); ledger.saveAndFlush(row); });
        var result = writer.insert(ownerId, brokerId, List.of(prepared("F-EDITED"), prepared("F-NEW")));
        assertThat(result.insertedCount()).isEqualTo(1); assertThat(result.skippedExistingCount()).isEqualTo(1);
        var edited = ledger.findAll().stream().filter(row -> row.getBrokerFilledNo().equals("F-EDITED")).findFirst().orElseThrow();
        assertThat(edited.getAssetName()).isEqualTo("manual name"); assertThat(edited.getAmount()).isEqualByComparingTo("7");
        assertThat(edited.getFee()).isEqualByComparingTo("2"); assertThat(edited.getTransactionTax()).isEqualByComparingTo("3");
        assertThat(edited.getNotes()).isEqualTo("manual note");
    }

    @Test void anotherOwnersSameFilledNoDoesNotSuppressConfiguredOwnerInsert() {
        ledger.saveAndFlush(AssetTransaction.builder().ownerUserId(otherOwnerId).brokerFilledNo("F-SAME")
                .transactionType("買").assetType("股票").assetName("other retained").tradeDate(DATE).amount(bd("1")).build());
        assertThat(writer.insert(ownerId, brokerId, List.of(prepared("F-SAME"))).insertedCount()).isEqualTo(1);
        assertThat(ledger.findAll()).extracting(AssetTransaction::getOwnerUserId).containsExactlyInAnyOrder(ownerId, otherOwnerId);
    }

    @ParameterizedTest @ValueSource(strings = {"owner", "broker", "owner-osiv", "broker-osiv"})
    void accountOrBrokerDeactivatedDuringAdapterReadPreventsEveryWrite(String kind) {
        when(client.readFilledTrades(DATE, DATE)).thenAnswer(call -> {
            CompletableFuture.runAsync(() -> tx(() -> { if (kind.startsWith("owner")) { var owner = users.findById(ownerId).orElseThrow();
                    owner.setStatus(AppUser.STATUS_DISABLED); users.saveAndFlush(owner); }
                else { var broker = brokers.findById(brokerId).orElseThrow(); broker.setActive(false); brokers.saveAndFlush(broker); } }))
                    .get(5, TimeUnit.SECONDS);
            return FubonDtos.CallResult.success(batch("F-1"));
        });
        Runnable invoke = () -> assertThat(service.syncManual(false).outcome())
                .isEqualTo(kind.startsWith("owner") ? FubonTradeOutcome.NO_OWNER : FubonTradeOutcome.BROKER_MISSING);
        if (kind.endsWith("osiv")) inOsivRequest(invoke); else invoke.run();
        assertThat(ledger.findAll()).isEmpty(); assertNoSuccess();
    }

    @Test void callerCannotChooseAnotherAdminOrStaleBrokerIdentity() {
        assertThatThrownBy(() -> writer.insert(otherOwnerId, brokerId, List.of(prepared("F-OTHER"))))
                .isInstanceOf(FubonTradeWriter.WriteRejected.class).hasMessage("NO_OWNER");
        assertThatThrownBy(() -> writer.insert(ownerId, brokerId + 100, List.of(prepared("F-OTHER"))))
                .isInstanceOf(FubonTradeWriter.WriteRejected.class).hasMessage("BROKER_MISSING");
        assertThat(ledger.findAll()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"12.3450000000", "99999999999.999999"})
    void exactPriceBoundaryPersistsWithoutImplicitRounding(String price) {
        var row = new FubonTradeWriter.PreparedTrade("F-PRECISION", "買", "exact", "2330", DATE,
                BigDecimal.ONE, bd(price), bd(price).setScale(2, java.math.RoundingMode.HALF_UP));
        writer.insert(ownerId, brokerId, List.of(row));
        assertThat(ledger.findAll().getFirst().getPrice()).isEqualByComparingTo(price);
    }

    @ParameterizedTest @ValueSource(strings = {"12.3450001", "100000000000"})
    void invalidLaterPricePreventsEvenTheFirstInsert(String price) {
        var row = new FubonTradeWriter.PreparedTrade("F-PRECISION", "買", "invalid", "2330", DATE,
                BigDecimal.ONE, bd(price), bd(price).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThatThrownBy(() -> writer.insert(ownerId, brokerId, List.of(prepared("F-FIRST"), row)))
                .isInstanceOf(FubonTradeWriter.WriteRejected.class).hasMessage("TRADE_FAILED");
        assertThat(ledger.findAll()).isEmpty();
    }

    @Test void finalAmountOverflowRejectsTheWholePreparedBatch() {
        var row = new FubonTradeWriter.PreparedTrade("F-OVERFLOW", "買", "invalid", "2330", DATE,
                bd("9999999999"), bd("1000000000"), bd("9999999999000000000.00"));
        assertThatThrownBy(() -> writer.insert(ownerId, brokerId, List.of(prepared("F-FIRST"), row)))
                .isInstanceOf(FubonTradeWriter.WriteRejected.class).hasMessage("TRADE_FAILED");
        assertThat(ledger.findAll()).isEmpty();
    }

    private FubonDtos.TradeBatchResponse batch(String... ids) {
        var price = CanonicalFubonDecimal.parsePositive("12.345");
        return new FubonDtos.TradeBatchResponse("batch", DATE, DATE, "0123456789abcdef01234567", false,
                java.util.Arrays.stream(ids).map(id -> new FubonDtos.FilledTrade("2330", "Sell", 3, price, price, DATE, "09:00", id)).toList());
    }
    private FubonTradeWriter.PreparedTrade prepared(String id) {
        return new FubonTradeWriter.PreparedTrade(id, "買", "test", "2330", DATE, bd("3"), bd("12.345"), bd("37.04"));
    }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private void tx(Runnable body) { new TransactionTemplate(transactions).executeWithoutResult(status -> body.run()); }
    private void inOsivRequest(Runnable body) {
        var attributes = new ServletRequestAttributes(new MockHttpServletRequest());
        EntityManager osiv = emf.createEntityManager();
        RequestContextHolder.setRequestAttributes(attributes);
        TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(osiv));
        try { body.run(); }
        finally { attributes.requestCompleted(); RequestContextHolder.resetRequestAttributes();
            TransactionSynchronizationManager.unbindResource(emf); osiv.close(); }
    }
    private void assertNoSuccess() { assertThat(counters.snapshot().get(FubonTradeOutcome.SUCCESS)).isEqualTo(successBefore); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("test barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    @TestConfiguration @EnableAspectJAutoProxy static class Config {
        @Bean static BeanFactoryPostProcessor requestScope() {
            return factory -> factory.registerScope("request", new org.springframework.web.context.request.RequestScope());
        }
        @Bean FubonTradeOutcomeCounters tradeCounters() { return new FubonTradeOutcomeCounters(); }
        @Bean FubonSyncOwnerDirectoryAdapter fubonSyncOwnerDirectoryAdapter(AppUserRepository users,
                UserAdminService configuredAdmin) {
            return new FubonSyncOwnerDirectoryAdapter(users, configuredAdmin, "trade-test@example.invalid");
        }
        @Bean FubonSyncOwnerPolicy fubonSyncOwnerPolicy(FubonSyncOwnerDirectoryAdapter directory) {
            return new FubonSyncOwnerPolicy(directory, directory);
        }
        @Bean FubonTradeSyncService tradeService(FubonConfigState config, FubonBrokerClient client, MarketDataService marketData,
                FubonSyncOwnerPort ownerPolicy, BrokerRepository brokers, AssetTransactionRepository ledger,
                StockMasterService stocks, FubonTradeWriter writer, FubonTradeOutcomeCounters counters) {
            return new FubonTradeSyncService(config, client, marketData, ownerPolicy, brokers, ledger, stocks, writer, counters, CLOCK, true);
        }
    }
}
