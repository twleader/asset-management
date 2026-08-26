package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.TwQuoteDetailFetchClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IntradayOrderBookSnapshotStoreValidationTest {

    @Test
    void zeroAveragePriceCannotEnterCanonicalFubonSnapshot() {
        IntradayOrderBookSnapshotStore store = store();
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

    @Test
    void onlyTwoApprovedSourcesWithFivePositivePairedLevelsArePersistable() {
        IntradayOrderBookSnapshotStore store = store();
        var valid = snapshot(BigDecimal.valueOf(100));
        var yahoo = copy(valid, "YAHOO_TW", valid.sourceTime(), valid.levels());
        var unknown = copy(valid, "UNTRUSTED", valid.sourceTime(), valid.levels());
        var zeroLevels = new ArrayList<>(valid.levels());
        zeroLevels.set(3, new TwQuoteDetailFetchClient.OrderBookLevel(4, BigDecimal.valueOf(97), 4L,
                BigDecimal.valueOf(104), 0L));

        assertThat(store.isPersistable(yahoo)).isTrue();
        assertThat(store.isPersistable(unknown)).isFalse();
        assertThat(store.isPersistable(copy(valid, valid.source(), valid.sourceTime(), zeroLevels))).isFalse();
    }

    private static IntradayOrderBookSnapshotStore store() {
        return new IntradayOrderBookSnapshotStore(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class));
    }

    private static TwQuoteDetailFetchClient.QuoteDetailResult copy(
            TwQuoteDetailFetchClient.QuoteDetailResult value, String source, Instant sourceTime,
            List<TwQuoteDetailFetchClient.OrderBookLevel> levels) {
        return new TwQuoteDetailFetchClient.QuoteDetailResult(
                value.stockCode(), value.stockName(), value.market(), value.supported(), value.available(),
                source, value.message(), sourceTime, value.fetchedAt(), value.marketStatus(), value.price(),
                value.previousClose(), value.openPrice(), value.highPrice(), value.lowPrice(), value.averagePrice(),
                value.change(), value.changePercent(), value.turnoverYi(), value.volumeLots(), value.previousVolumeLots(),
                value.amplitudePercent(), value.innerVolumeLots(), value.outerVolumeLots(), value.innerPercent(),
                value.outerPercent(), value.bidTotalLots(), value.askTotalLots(), List.copyOf(levels));
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
