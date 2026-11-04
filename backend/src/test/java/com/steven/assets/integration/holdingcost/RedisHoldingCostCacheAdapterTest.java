package com.steven.assets.integration.holdingcost;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.HoldingCostSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
class RedisHoldingCostCacheAdapterTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static RedisHoldingCostCacheAdapter cache;

    @BeforeAll static void connect() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet(); connection.start();
        redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
        cache = new RedisHoldingCostCacheAdapter(redis);
    }
    @AfterAll static void close() { connection.destroy(); }

    private static HoldingCostSnapshot snapshot(long owner, String cost) {
        return new HoldingCostSnapshot(owner, 15L, LocalDate.of(2026, 11, 4), List.of(
                new HoldingCostSnapshot.Entry(100L, 1L, "009826", "台股", "TWD", new BigDecimal("2000.00000"), new BigDecimal(cost))));
    }

    @Test void realRedisStoresCanonicalPrivateCostWithFiniteTtlAndReadsWithoutWriting() {
        var expected = snapshot(1, "20120.00");
        assertThat(cache.publish(expected)).isTrue();
        assertThat(redis.opsForValue().get(expected.cacheKey())).isEqualTo(expected.canonicalJson());
        assertThat(redis.getExpire(expected.cacheKey(), TimeUnit.SECONDS)).isBetween(129590L, 129600L);
        assertThat(cache.readMatching(expected)).containsEntry(100L, new BigDecimal("20120.00"));
        assertThat(expected.cacheKey()).startsWith("fubon:holding-cost:1:15:");
    }

    @Test void manualCostQuantityDateAndOwnerChangesCannotReadOldCostAndReadsDoNotRepair() {
        var original = snapshot(2, "20120.00");
        assertThat(cache.publish(original)).isTrue();
        var corrected = snapshot(2, "20130.00");
        assertThat(cache.readMatching(corrected)).isEmpty();
        assertThat(redis.hasKey(corrected.cacheKey())).isFalse();
        assertThat(cache.readMatching(snapshot(3, "20120.00"))).isEmpty();
        var nextDate = new HoldingCostSnapshot(2L, 15L, LocalDate.of(2026, 11, 5), original.entries());
        assertThat(cache.readMatching(nextDate)).isEmpty();
        var row = original.entries().getFirst();
        var changedShares = new HoldingCostSnapshot(2L, 15L, original.snapshotDate(), List.of(new HoldingCostSnapshot.Entry(
                row.holdingId(), row.brokerId(), row.stockCode(), row.market(), row.currency(), new BigDecimal("3000"), row.investmentCost())));
        assertThat(cache.readMatching(changedShares)).isEmpty();
    }

    @Test void reverseCommitPublicationCannotOverwriteNewerContent() {
        var old = snapshot(4, "10100.00"); var current = snapshot(4, "20120.00");
        assertThat(cache.publish(current)).isTrue(); assertThat(cache.publish(old)).isTrue();
        assertThat(cache.readMatching(current)).containsEntry(100L, new BigDecimal("20120.00"));
        assertThat(old.cacheKey()).isNotEqualTo(current.cacheKey());
    }

    @Test void anyMalformedCrossOwnerExtraOrMissingContentAtExactKeyIsRejected() {
        var expected = snapshot(5, "20120.00");
        for (String corrupt : List.of("{", snapshot(6, "20120.00").canonicalJson(),
                expected.canonicalJson().replace("20120", "99999"), expected.canonicalJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                expected.canonicalJson().replace("\"entries\":[", "\"extra\":true,\"entries\":["))) {
            redis.opsForValue().set(expected.cacheKey(), corrupt);
            assertThat(cache.readMatching(expected)).isEmpty();
            assertThat(redis.opsForValue().get(expected.cacheKey())).isEqualTo(corrupt);
        }
    }

    @Test void publishRefusesAnUncommittedTransactionAndLeavesNoKey() {
        var expected = snapshot(7, "20120.00");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThat(cache.publish(expected)).isFalse(); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertThat(redis.hasKey(expected.cacheKey())).isFalse();
    }

    @Test void redisFailureFallsBackAndSubsequentPublishRepairsWithoutChangingDatabaseCost() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new IllegalStateException("synthetic cache failure"));
        var unavailable = new RedisHoldingCostCacheAdapter(broken); var expected = snapshot(8, "20120.00");
        assertThat(unavailable.publish(expected)).isFalse();
        assertThat(unavailable.readMatching(expected)).isEmpty();
        assertThat(cache.publish(expected)).isTrue();
        assertThat(cache.readMatching(expected)).containsEntry(100L, new BigDecimal("20120.00"));
    }

    @Test void canonicalIdentityIgnoresInputOrderAndDecimalScaleButIncludesCompleteScope() {
        var one = snapshot(9, "20120.00"); var row = one.entries().getFirst();
        var two = new HoldingCostSnapshot(9L, 15L, one.snapshotDate(), List.of(new HoldingCostSnapshot.Entry(
                101L, 1L, "0050", "台股", "TWD", BigDecimal.ONE, BigDecimal.TEN), row));
        var reverse = new HoldingCostSnapshot(9L, 15L, one.snapshotDate(), List.of(row, two.entries().getFirst()));
        assertThat(two.cacheKey()).isEqualTo(reverse.cacheKey()).isNotEqualTo(one.cacheKey());
        assertThat(one.cacheKey()).isEqualTo(snapshot(9, "20120").cacheKey());
    }

    @Test void actualAssetDetailConsumesMirrorAndFallsBackAfterManualCostEdit() {
        var entity = AssetSnapshot.builder().id(15L).ownerUserId(10L).snapshotDate(LocalDate.of(2026, 11, 4))
                .stocks(new ArrayList<>()).build();
        var stock = StockHolding.builder().id(100L).snapshot(entity).stockCode("009826").market("台股").currency("TWD")
                .broker(BrokerEntity.builder().id(1L).code("fubon").build()).shares(new BigDecimal("2000"))
                .investmentCost(new BigDecimal("20120.00")).currentValue(new BigDecimal("20060.00")).build();
        entity.getStocks().add(stock);
        var reader = spy(cache); var master = mock(StockRepository.class);
        when(master.findByCodeAndMarket(any(), any())).thenReturn(Optional.empty());
        var service = new AssetService(null, null, null, null, null, null, null, null, null, master, null, null, null, null,
                null, null, null, null, null, new com.steven.assets.service.SnapshotAggregateCalculator(), null, reader);
        var persisted = HoldingCostSnapshot.capture(entity);
        assertThat(cache.publish(persisted)).isTrue();
        assertThat(service.getSnapshotDetail(entity).stocks().getFirst().investmentCost()).isEqualByComparingTo("20120");
        verify(reader).readMatching(persisted);
        stock.setInvestmentCost(new BigDecimal("20200.00"));
        assertThat(service.getSnapshotDetail(entity).stocks().getFirst().investmentCost()).isEqualByComparingTo("20200");
        assertThat(redis.hasKey(HoldingCostSnapshot.capture(entity).cacheKey())).isFalse();
    }
}
