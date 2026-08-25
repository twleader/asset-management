package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IntradayOrderBookSnapshotStoreValidationTest {

    @Test
    void zeroAveragePriceCannotEnterCanonicalFubonSnapshot() {
        IntradayOrderBookSnapshotStore store = new IntradayOrderBookSnapshotStore(
                mock(JdbcTemplate.class), mock(PlatformTransactionManager.class));
        var valid = snapshot(BigDecimal.valueOf(100));
        var zeroAverage = new TwQuoteDetailFetchClient.QuoteDetailResult(
                valid.stockCode(), valid.stockName(), valid.market(), valid.supported(), valid.available(),
                valid.source(), valid.message(), valid.sourceTime(), valid.fetchedAt(), valid.marketStatus(),
                valid.price(), valid.previousClose(), valid.openPrice(), valid.highPrice(), valid.lowPrice(),
                BigDecimal.ZERO, valid.change(), valid.changePercent(), valid.turnoverYi(), valid.volumeLots(),
                valid.previousVolumeLots(), valid.amplitudePercent(), valid.innerVolumeLots(), valid.outerVolumeLots(),
                valid.innerPercent(), valid.outerPercent(), valid.bidTotalLots(), valid.askTotalLots(), valid.levels());

        assertThat(store.isPersistable(valid)).isTrue();
        assertThat(store.isPersistable(zeroAverage)).isFalse();
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult snapshot(BigDecimal averagePrice) {
        List<TwQuoteDetailFetchClient.OrderBookLevel> levels = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(level -> new TwQuoteDetailFetchClient.OrderBookLevel(level,
                        BigDecimal.valueOf(100 - level), (long) level,
                        BigDecimal.valueOf(100 + level), (long) (level + 10)))
                .toList();
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                "2330", "台積電", "台股", true, true, "FUBON_BOOKS", null,
                Instant.parse("2026-08-21T05:00:00.123456Z"), Instant.parse("2026-08-21T05:00:01Z"), "OPEN",
                BigDecimal.valueOf(100), BigDecimal.valueOf(99), BigDecimal.valueOf(100),
                BigDecimal.valueOf(101), BigDecimal.valueOf(98), averagePrice,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 10L, null,
                BigDecimal.ONE, 5L, 5L, BigDecimal.valueOf(50), BigDecimal.valueOf(50), 15L, 65L, levels);
    }
}
