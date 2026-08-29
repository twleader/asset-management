package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetTransaction;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.security.TenantFilterAspect;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL evidence (Requirement 120 / Task 385, spec item 385.11) that
 * {@code ux_asset_transaction_owner_broker_filled_no} — the partial unique index from
 * {@code v1.119.0-asset-transaction-fubon-source.sql} — actually enforces the
 * {@code (owner_user_id, broker_filled_no)} idempotency invariant under real concurrent inserts,
 * not just under a mocked repository. This is deliberately not H2: H2's partial-index / concurrent
 * transaction semantics do not reliably reproduce PostgreSQL's row-level unique-index blocking
 * behavior that {@link FubonTradeSyncService#processTrades} depends on.
 *
 * <p>{@code ddl-auto=create-drop} builds the base table from the JPA entity mapping (which already
 * declares {@code source}/{@code broker_filled_no} — see {@link AssetTransaction}); the partial
 * unique index itself is not expressible via JPA annotations, so this test replays the exact DDL
 * statement from the changeset once at startup, against the same container the concurrent inserts
 * run on.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FubonTradeSyncUniqueIndexPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("asset_transaction_unique_test")
            .withUsername("assets")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired AssetTransactionRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery(
                    "CREATE UNIQUE INDEX IF NOT EXISTS ux_asset_transaction_owner_broker_filled_no "
                            + "ON asset_transaction (owner_user_id, broker_filled_no) "
                            + "WHERE broker_filled_no IS NOT NULL").executeUpdate();
            repository.deleteAll();
            repository.flush();
        });
    }

    @Test
    void concurrentInsertsWithSameOwnerAndFilledNoLeaveExactlyOneRowAndFailTheOther() throws Exception {
        Long ownerId = 9L;
        String filledNo = "F-RACE-001";
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstInFlight = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        AtomicInteger secondSucceeded = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
                repository.saveAndFlush(candidate(ownerId, filledNo, "第一筆"));
                firstInFlight.countDown();
                await(releaseFirst);
            }), executor);
            assertThat(firstInFlight.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
                try {
                    tx.executeWithoutResult(status ->
                            repository.saveAndFlush(candidate(ownerId, filledNo, "第二筆競態")));
                    secondSucceeded.incrementAndGet();
                } catch (RuntimeException race) {
                    secondFailure.set(race);
                }
            }, executor);

            // Give the second transaction a moment to actually reach the blocking insert before
            // releasing the first — otherwise this would just be two sequential, non-racing writes.
            Thread.sleep(250);
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(secondFailure.get())
                .as("second concurrent insert of the same (ownerUserId, brokerFilledNo) must fail"
                        + " on the partial unique index, not silently succeed")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(secondSucceeded.get()).isZero();

        List<AssetTransaction> persisted = repository.findAll();
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getBrokerFilledNo()).isEqualTo(filledNo);
        assertThat(persisted.get(0).getOwnerUserId()).isEqualTo(ownerId);
    }

    @Test
    void differentFilledNoForSameOwnerBothPersist() {
        Long ownerId = 9L;
        repository.saveAndFlush(candidate(ownerId, "F-A", "A"));
        repository.saveAndFlush(candidate(ownerId, "F-B", "B"));

        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    void nullBrokerFilledNoRowsAreExemptFromTheUniqueIndex() {
        Long ownerId = 9L;
        AssetTransaction manual1 = candidate(ownerId, null, "手動1");
        AssetTransaction manual2 = candidate(ownerId, null, "手動2");

        repository.saveAndFlush(manual1);
        repository.saveAndFlush(manual2);

        assertThat(repository.findAll()).hasSize(2);
    }

    /**
     * Regression for the arch-review finding on this task: {@link TenantFilterAspect} enables
     * Hibernate's {@code ownerFilter} on every repository call made from an HTTP request that has
     * no tenant identity (fail-closed to an impossible owner id) — which is exactly what happens
     * when {@link FubonTradeSyncController} is hit directly (internal-token protected, no
     * {@code X-User-*} headers). Before the fix, {@code existsByOwnerUserIdAndBrokerFilledNo} was
     * a derived HQL query, so this fail-closed filter (AND'd with the explicit {@code ownerId}
     * parameter) always produced an empty result — the idempotency pre-check silently reported
     * "not found" even for a row that plainly exists, regardless of the real owner. This test
     * enables the same fail-closed filter by hand (bypassing the aspect entirely, since this is a
     * {@code @DataJpaTest} with no servlet request in flight) and proves the now-native-query
     * method still returns the correct answer, because native queries are never subject to
     * Hibernate's {@code @Filter}.
     */
    @Test
    void existsCheckIgnoresAFailClosedOwnerFilterEnabledByAnUnrelatedHttpRequest() {
        Long ownerId = 9L;
        String filledNo = "F-FILTER-BYPASS-001";
        repository.saveAndFlush(candidate(ownerId, filledNo, "已同步"));

        entityManager.unwrap(Session.class)
                .enableFilter("ownerFilter")
                .setParameter("ownerId", -1L);
        try {
            assertThat(repository.existsByOwnerUserIdAndBrokerFilledNo(ownerId, filledNo))
                    .as("native query must see the row by its real owner even while an unrelated"
                            + " HTTP request has fail-closed the Hibernate filter to owner -1")
                    .isTrue();
            assertThat(repository.existsByOwnerUserIdAndBrokerFilledNo(ownerId, "F-NEVER-SYNCED"))
                    .isFalse();
        } finally {
            entityManager.unwrap(Session.class).disableFilter("ownerFilter");
        }
    }

    private AssetTransaction candidate(Long ownerId, String filledNo, String name) {
        AssetTransaction.AssetTransactionBuilder builder = AssetTransaction.builder()
                .ownerUserId(ownerId)
                .transactionType("買")
                .assetType("股票")
                .assetName(name)
                .assetCode("2330")
                .market("台股")
                .currency("TWD")
                .channel("富邦證券")
                .tradeDate(LocalDate.of(2026, 8, 21))
                .shares(BigDecimal.ONE)
                .price(BigDecimal.TEN)
                .amount(BigDecimal.TEN);
        if (filledNo != null) {
            builder.source("FUBON_SYNC").brokerFilledNo(filledNo);
        }
        return builder.build();
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
