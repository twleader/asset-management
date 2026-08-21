package com.steven.assets.integration.fubon;

import com.steven.assets.dto.AssetSnapshotDto;
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
import com.steven.assets.service.StockMasterService;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired AssetSnapshotRepository snapshotRepository;
    @Autowired BrokerRepository brokerRepository;
    @Autowired AssetSnapshotMutationLock mutationLock;
    @Autowired FubonInventoryWriter writer;
    @Autowired AssetService assetService;
    @Autowired PlatformTransactionManager transactionManager;

    private final LocalDate today = LocalDate.of(2026, 8, 21);
    private BrokerEntity fubon;
    private BrokerEntity other;
    private Long snapshotId;

    @BeforeEach
    void seed() {
        snapshotRepository.deleteAll();
        brokerRepository.deleteAll();
        fubon = brokerRepository.saveAndFlush(BrokerEntity.builder()
                .code("fubon").displayName("富邦證券").active(true).build());
        other = brokerRepository.saveAndFlush(BrokerEntity.builder()
                .code("other").displayName("其他券商").active(true).build());
        AssetSnapshot snapshot = AssetSnapshot.builder()
                .ownerUserId(9L).snapshotDate(today)
                .deposits(new ArrayList<>()).funds(new ArrayList<>()).stocks(new ArrayList<>())
                .build();
        snapshot.getStocks().add(holding(snapshot, "2330", fubon, "10", "10"));
        snapshot.getStocks().add(holding(snapshot, "0050", other, "50", "40"));
        new SnapshotAggregateCalculator().recalculate(snapshot);
        snapshotId = snapshotRepository.saveAndFlush(snapshot).getId();
    }

    @Test
    void fubonLockFirstThenFullUpdatePreservesNewFubonAndFullUpdateNonFubon() throws Exception {
        runRace(true);
        assertFinalState();
    }

    @Test
    void fullUpdateLockFirstThenFubonReplacePreservesBothScopesAndTotals() throws Exception {
        runRace(false);
        assertFinalState();
    }

    @Test
    void precisionOverflowRollsBackTheWholePartialReplacement() {
        assertThatThrownBy(() -> writer.replace(9L, snapshotId, today, List.of(
                new FubonInventoryWriter.PreparedPosition(
                        "1111", 1, new BigDecimal("12.345"), new BigDecimal("20"), "第一筆"),
                new FubonInventoryWriter.PreparedPosition(
                        "9999", 9_999_999_999L, new BigDecimal("1000000000"),
                        BigDecimal.ONE, "溢位筆")), false))
                .isInstanceOf(FubonInventoryWriter.CommitRejected.class)
                .hasMessage("MONEY_PRECISION_EXCEEDED");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AssetSnapshot unchanged = snapshotRepository.findById(snapshotId).orElseThrow();
            assertThat(unchanged.getStocks()).extracting(StockHolding::getStockCode)
                    .containsExactlyInAnyOrder("2330", "0050");
            assertThat(unchanged.getStocks()).noneMatch(row -> "1111".equals(row.getStockCode()));
            assertThat(unchanged.getTotalStockValue()).isEqualByComparingTo("60");
            assertThat(unchanged.getTotalAssets()).isEqualByComparingTo("60");
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
                else fullUpdate();
            }), executor);
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
                secondStarted.countDown();
                if (fubonFirst) fullUpdate();
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
        writer.replace(9L, snapshotId, today, List.of(
                new FubonInventoryWriter.PreparedPosition(
                        "2330", 3, new BigDecimal("12.345"), new BigDecimal("20"), "台積電")), false);
    }

    private void fullUpdate() {
        assetService.updateSnapshot(snapshotId, new AssetSnapshotDto.CreateSnapshotRequest(
                today, BigDecimal.ONE, "full-update", List.of(), List.of(), List.of(
                stockRequest("2330", "stale-fubon", fubon.getId(), "1", "1", "1"),
                stockRequest("0056", "元大高股息", other.getId(), "2", "30", "80"))));
    }

    private void assertFinalState() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            AssetSnapshot finalSnapshot = snapshotRepository.findById(snapshotId).orElseThrow();
            assertThat(finalSnapshot.getStocks()).hasSize(2);
            StockHolding finalFubon = finalSnapshot.getStocks().stream()
                    .filter(row -> "fubon".equals(row.getBroker().getCode())).findFirst().orElseThrow();
            StockHolding finalOther = finalSnapshot.getStocks().stream()
                    .filter(row -> "other".equals(row.getBroker().getCode())).findFirst().orElseThrow();
            assertThat(finalFubon.getStockCode()).isEqualTo("2330");
            assertThat(finalFubon.getShares()).isEqualByComparingTo("3");
            assertThat(finalFubon.getInvestmentCost()).isEqualByComparingTo("37.04");
            assertThat(finalFubon.getCurrentValue()).isEqualByComparingTo("60.00");
            assertThat(finalOther.getStockCode()).isEqualTo("0056");
            assertThat(finalOther.getCurrentValue()).isEqualByComparingTo("80");
            assertThat(finalSnapshot.getTotalStockValue()).isEqualByComparingTo("140.00");
            assertThat(finalSnapshot.getTotalAssets()).isEqualByComparingTo("140.00");
            assertThat(finalSnapshot.getTotalStockValue()).isEqualByComparingTo(
                    finalSnapshot.getStocks().stream().map(StockHolding::getCurrentValue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
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
            String current) {
        return new AssetSnapshotDto.StockRequest(code, name, "台股", brokerId,
                new BigDecimal(shares), new BigDecimal(cost), new BigDecimal(current),
                null, null, "TWD", null, null, null, null);
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
