package com.steven.assets.integration.fubon;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.AssetClassifier;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.AssetSnapshotMutationLock;
import com.steven.assets.service.FundDividendService;
import com.steven.assets.service.FundNavService;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.SnapshotAggregateCalculator;
import com.steven.assets.service.SnapshotStockScopeOwnershipPort;
import com.steven.assets.service.StockMasterService;
import com.steven.assets.service.UserAdminService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL evidence for both the source-owned lock race and all manual
 * payload-owned Fubon configurations. Every full-PUT assertion re-reads a
 * cleared persistence context instead of trusting the response DTO.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AssetSnapshotMutationLock.class, SnapshotAggregateCalculator.class,
        FubonInventoryWriter.class, AssetService.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FubonSnapshotLockPostgresTest {
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T02:00:00Z"), TW_ZONE);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("assets_lock_test")
            .withUsername("assets")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean MarketDataService marketDataService;
    @MockBean FundNavService fundNavService;
    @MockBean FundDividendService fundDividendService;
    @MockBean AssetClassifier assetClassifier;
    @MockBean StockMasterService stockMasterService;
    @MockBean com.steven.assets.security.TenantGuard tenantGuard;
    @MockBean SnapshotStockScopeOwnershipPort stockScopeOwnershipPort;
    @MockBean FubonConfigState configState;
    @MockBean UserAdminService userAdminService;

    @Autowired AssetSnapshotRepository snapshotRepository;
    @Autowired BrokerRepository brokerRepository;
    @Autowired AssetSnapshotMutationLock mutationLock;
    @Autowired FubonInventoryWriter writer;
    @Autowired AssetService assetService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;

    private BrokerEntity fubon;
    private BrokerEntity other;
    private Long snapshotId;

    @BeforeEach
    void seed() {
        seed(TODAY, 9L);
        useOwnership(FubonConfigState.State.READY, true, false, 9L);
    }

    @Test
    void disabledManualFullPutPersistsFubonRowsAfterDatabaseReadback() {
        useOwnership(FubonConfigState.State.DISABLED, true, false, 9L);

        manualFullUpdate(TODAY, "00865B", "2000", "97038", "97600", "買", TODAY.minusDays(52));

        assertPayloadPersisted("00865B", "2000", "97038", "97600", "買", TODAY.minusDays(52));
    }

    @Test
    void misconfiguredManualFullPutPersistsFubonRowsAfterDatabaseReadback() {
        useOwnership(FubonConfigState.State.MISCONFIGURED, true, false, 9L);

        manualFullUpdate(TODAY, "00865B", "1000", "49390", "48800", "賣", TODAY.minusDays(25));

        assertPayloadPersisted("00865B", "1000", "49390", "48800", "賣", TODAY.minusDays(25));
    }

    @Test
    void trackedDefaultWithoutInventoryWriterPersistsFubonRowsAfterDatabaseReadback() {
        useOwnership(FubonConfigState.State.READY, false, true, 9L);

        manualFullUpdate(TODAY, "00865B", "1500", "72800", "73200", "買", TODAY.minusDays(17));

        assertPayloadPersisted("00865B", "1500", "72800", "73200", "買", TODAY.minusDays(17));
    }

    @Test
    void inventorySyncCapacityConflictPersistsFubonRowsAfterDatabaseReadback() {
        useOwnership(FubonConfigState.State.READY, true, true, 9L);

        manualFullUpdate(TODAY, "00865B", "1800", "87300", "87900", "買", TODAY.minusDays(9));

        assertPayloadPersisted("00865B", "1800", "87300", "87900", "買", TODAY.minusDays(9));
    }

    @Test
    void historicalTargetPersistsFubonRowsAfterDatabaseReadback() {
        seed(TODAY.minusDays(1), 9L);
        useOwnership(FubonConfigState.State.READY, true, false, 9L);

        manualFullUpdate(TODAY.minusDays(1), "00865B", "2100", "101900", "102900", "買", TODAY.minusDays(60));

        assertPayloadPersisted("00865B", "2100", "101900", "102900", "買", TODAY.minusDays(60));
    }

    @Test
    void nonConfiguredAdminTargetPersistsFubonRowsAfterDatabaseReadback() {
        seed(TODAY, 10L);
        useOwnership(FubonConfigState.State.READY, true, false, 9L);

        manualFullUpdate(TODAY, "00865B", "2200", "106800", "107800", "買", TODAY.minusDays(32));

        assertPayloadPersisted("00865B", "2200", "106800", "107800", "買", TODAY.minusDays(32));
    }

    @Test
    void changingSourceTargetToNonTodayDatePersistsFubonRowsAfterDatabaseReadback() {
        useOwnership(FubonConfigState.State.READY, true, false, 9L);
        LocalDate finalDate = TODAY.minusDays(2);

        manualFullUpdate(finalDate, "00865B", "2300", "111700", "112700", "買", TODAY.minusDays(45));

        assertPayloadPersisted("00865B", "2300", "111700", "112700", "買", TODAY.minusDays(45));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.clear();
            assertThat(snapshotRepository.findById(snapshotId).orElseThrow().getSnapshotDate()).isEqualTo(finalDate);
        });
    }

    @Test
    void onlyEligibleSourceTargetRejectsStaleFubonPayloadButUpdatesNonFubonRows() {
        useOwnership(FubonConfigState.State.READY, true, false, 9L);

        manualFullUpdate(TODAY, "2330", "1", "1", "1", "STALE", TODAY.minusDays(1));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.clear();
            AssetSnapshot reloaded = snapshotRepository.findById(snapshotId).orElseThrow();
            StockHolding sourceFubon = holdingForBroker(reloaded, "fubon");
            StockHolding updatedOther = holdingForBroker(reloaded, "other");
            assertThat(sourceFubon.getStockCode()).isEqualTo("2330");
            assertThat(sourceFubon.getShares()).isEqualByComparingTo("1");
            assertThat(sourceFubon.getInvestmentCost()).isEqualByComparingTo("10");
            assertThat(sourceFubon.getCurrentValue()).isEqualByComparingTo("10");
            assertThat(sourceFubon.getTransactionType()).isEqualTo("SYNC");
            assertThat(sourceFubon.getTransactionDate()).isEqualTo(TODAY.minusDays(90));
            assertThat(updatedOther.getStockCode()).isEqualTo("0056");
            assertThat(updatedOther.getCurrentValue()).isEqualByComparingTo("80");
            assertTotalsMatchChildren(reloaded);
        });
    }

    @Test
    void fubonLockFirstThenFullUpdatePreservesNewFubonAndFullUpdateNonFubon() throws Exception {
        runRace(true);
        assertFinalRaceState();
    }

    @Test
    void fullUpdateLockFirstThenFubonReplacePreservesBothScopesAndTotals() throws Exception {
        runRace(false);
        assertFinalRaceState();
    }

    @Test
    void precisionOverflowRollsBackTheWholePartialReplacement() {
        assertThatThrownBy(() -> writer.replace(9L, snapshotId, TODAY, List.of(
                new FubonInventoryWriter.PreparedPosition(
                        "1111", 1, new BigDecimal("12.345"), new BigDecimal("20"), "第一筆"),
                new FubonInventoryWriter.PreparedPosition(
                        "9999", 9_999_999_999L, new BigDecimal("1000000000"),
                        BigDecimal.ONE, "溢位筆")), false))
                .isInstanceOf(FubonInventoryWriter.CommitRejected.class)
                .hasMessage("MONEY_PRECISION_EXCEEDED");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.clear();
            AssetSnapshot unchanged = snapshotRepository.findById(snapshotId).orElseThrow();
            assertThat(unchanged.getStocks()).extracting(StockHolding::getStockCode)
                    .containsExactlyInAnyOrder("2330", "0050");
            assertThat(unchanged.getStocks()).noneMatch(row -> "1111".equals(row.getStockCode()));
            assertThat(unchanged.getTotalStockValue()).isEqualByComparingTo("60");
            assertThat(unchanged.getTotalAssets()).isEqualByComparingTo("60");
        });
    }

    private void seed(LocalDate date, Long ownerUserId) {
        snapshotRepository.deleteAll();
        snapshotRepository.flush();
        brokerRepository.deleteAll();
        brokerRepository.flush();
        fubon = brokerRepository.saveAndFlush(BrokerEntity.builder()
                .code("fubon").displayName("富邦證券").active(true).build());
        other = brokerRepository.saveAndFlush(BrokerEntity.builder()
                .code("other").displayName("其他券商").active(true).build());
        AssetSnapshot snapshot = AssetSnapshot.builder()
                .ownerUserId(ownerUserId).snapshotDate(date)
                .deposits(new ArrayList<>()).funds(new ArrayList<>()).stocks(new ArrayList<>())
                .build();
        StockHolding source = holding(snapshot, "2330", fubon, "10", "10");
        source.setTransactionType("SYNC");
        source.setTransactionDate(TODAY.minusDays(90));
        snapshot.getStocks().add(source);
        snapshot.getStocks().add(holding(snapshot, "0050", other, "50", "40"));
        new SnapshotAggregateCalculator().recalculate(snapshot);
        snapshotId = snapshotRepository.saveAndFlush(snapshot).getId();
        entityManager.clear();
    }

    private void useOwnership(
            FubonConfigState.State state,
            boolean inventoryEnabled,
            boolean liveEnabled,
            Long configuredAdminId) {
        reset(stockScopeOwnershipPort, configState, userAdminService);
        when(configState.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                state, state == FubonConfigState.State.READY ? "token" : null, state.name()));
        if (configuredAdminId == null) {
            when(userAdminService.configuredAdmin()).thenReturn(Optional.empty());
        } else {
            when(userAdminService.configuredAdmin()).thenReturn(Optional.of(AppUser.builder()
                    .id(configuredAdminId)
                    .role(AppUser.ROLE_ADMIN)
                    .status(AppUser.STATUS_ACTIVE)
                    .build()));
        }
        FubonSnapshotStockScopeOwnershipAdapter adapter = new FubonSnapshotStockScopeOwnershipAdapter(
                configState, userAdminService, snapshotRepository, CLOCK, inventoryEnabled, liveEnabled);
        when(stockScopeOwnershipPort.capture(any())).thenAnswer(invocation -> adapter.capture(invocation.getArgument(0)));
    }

    private void manualFullUpdate(
            LocalDate snapshotDate,
            String fubonCode,
            String shares,
            String cost,
            String current,
            String transactionType,
            LocalDate transactionDate) {
        assetService.updateSnapshot(snapshotId, new AssetSnapshotDto.CreateSnapshotRequest(
                snapshotDate, BigDecimal.ONE, "full-update", List.of(), List.of(), List.of(
                stockRequest(fubonCode, "富邦測試標的", fubon.getId(), shares, cost, current,
                        transactionType, transactionDate),
                stockRequest("0056", "元大高股息", other.getId(), "2", "30", "80",
                        "買", TODAY.minusDays(3)))));
    }

    private void assertPayloadPersisted(
            String expectedCode,
            String expectedShares,
            String expectedCost,
            String expectedCurrent,
            String expectedTransactionType,
            LocalDate expectedTransactionDate) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.clear();
            AssetSnapshot reloaded = snapshotRepository.findById(snapshotId).orElseThrow();
            StockHolding persisted = holdingForBroker(reloaded, "fubon");
            assertThat(persisted.getStockCode()).isEqualTo(expectedCode);
            assertThat(persisted.getShares()).isEqualByComparingTo(expectedShares);
            assertThat(persisted.getInvestmentCost()).isEqualByComparingTo(expectedCost);
            assertThat(persisted.getCurrentValue()).isEqualByComparingTo(expectedCurrent);
            assertThat(persisted.getTransactionType()).isEqualTo(expectedTransactionType);
            assertThat(persisted.getTransactionDate()).isEqualTo(expectedTransactionDate);
            assertThat(persisted.getTransactionExchangeRate()).isEqualByComparingTo("31.2345");
            assertThat(persisted.getCurrency()).isEqualTo("TWD");
            assertThat(persisted.getEstimatedDividend()).isEqualByComparingTo("123");
            assertThat(persisted.getDividendRate()).isEqualByComparingTo("0.05");
            assertThat(persisted.getDisplayOrder()).isZero();
            assertThat(holdingForBroker(reloaded, "other").getStockCode()).isEqualTo("0056");
            assertTotalsMatchChildren(reloaded);
        });
    }

    private void runRace(boolean fubonFirst) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
                mutationLock.lockById(snapshotId);
                firstLocked.countDown();
                await(releaseFirst);
                if (fubonFirst) replaceFubon();
                else fullUpdateForRace();
            }), executor);
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
                secondStarted.countDown();
                if (fubonFirst) fullUpdateForRace();
                else replaceFubon();
            }, executor);
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250);
            assertThat(second).as("second transaction must wait on the PostgreSQL row lock").isNotDone();

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
    }

    private void replaceFubon() {
        writer.replace(9L, snapshotId, TODAY, List.of(
                new FubonInventoryWriter.PreparedPosition(
                        "2330", 3, new BigDecimal("12.345"), new BigDecimal("20"), "台積電")), false);
    }

    private void fullUpdateForRace() {
        manualFullUpdate(TODAY, "2330", "1", "1", "1", "STALE", TODAY.minusDays(1));
    }

    private void assertFinalRaceState() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.clear();
            AssetSnapshot finalSnapshot = snapshotRepository.findById(snapshotId).orElseThrow();
            assertThat(finalSnapshot.getStocks()).hasSize(2);
            StockHolding finalFubon = holdingForBroker(finalSnapshot, "fubon");
            StockHolding finalOther = holdingForBroker(finalSnapshot, "other");
            assertThat(finalFubon.getStockCode()).isEqualTo("2330");
            assertThat(finalFubon.getShares()).isEqualByComparingTo("3");
            assertThat(finalFubon.getInvestmentCost()).isEqualByComparingTo("37.04");
            assertThat(finalFubon.getCurrentValue()).isEqualByComparingTo("60.00");
            assertThat(finalOther.getStockCode()).isEqualTo("0056");
            assertThat(finalOther.getCurrentValue()).isEqualByComparingTo("80");
            assertThat(finalSnapshot.getTotalStockValue()).isEqualByComparingTo("140.00");
            assertThat(finalSnapshot.getTotalAssets()).isEqualByComparingTo("140.00");
            assertTotalsMatchChildren(finalSnapshot);
        });
    }

    private StockHolding holding(
            AssetSnapshot snapshot,
            String code,
            BrokerEntity broker,
            String current,
            String cost) {
        return StockHolding.builder().snapshot(snapshot).stockCode(code).market("台股").broker(broker)
                .shares(BigDecimal.ONE).investmentCost(new BigDecimal(cost))
                .currentValue(new BigDecimal(current)).currency("TWD").build();
    }

    private AssetSnapshotDto.StockRequest stockRequest(
            String code,
            String name,
            Long brokerId,
            String shares,
            String cost,
            String current,
            String transactionType,
            LocalDate transactionDate) {
        return new AssetSnapshotDto.StockRequest(code, name, "台股", brokerId,
                new BigDecimal(shares), new BigDecimal(cost), new BigDecimal(current),
                new BigDecimal("123"), new BigDecimal("0.05"), "TWD", null,
                transactionType, transactionDate, new BigDecimal("31.2345"));
    }

    private static StockHolding holdingForBroker(AssetSnapshot snapshot, String brokerCode) {
        return snapshot.getStocks().stream()
                .filter(row -> brokerCode.equals(row.getBroker().getCode()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertTotalsMatchChildren(AssetSnapshot snapshot) {
        BigDecimal stockValue = snapshot.getStocks().stream()
                .map(StockHolding::getCurrentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        SnapshotAggregateCalculator calculator = new SnapshotAggregateCalculator();
        BigDecimal stockCost = snapshot.getStocks().stream()
                .map(holding -> calculator.stockInvestmentCostTwd(holding, snapshot.getUsdExchangeRate()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(snapshot.getTotalStockValue()).isEqualByComparingTo(stockValue);
        assertThat(snapshot.getTotalStockCost()).isEqualByComparingTo(stockCost);
        assertThat(snapshot.getTotalAssets()).isEqualByComparingTo(stockValue);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
    }
}
