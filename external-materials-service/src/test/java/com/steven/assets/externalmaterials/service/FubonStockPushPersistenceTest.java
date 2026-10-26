package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.model.marketdata.FubonMarketData;
import com.steven.assets.externalmaterials.model.marketdata.PriceData.PriceResult;
import com.steven.assets.externalmaterials.model.marketdata.PriceProjectionData;
import com.steven.assets.externalmaterials.model.marketdata.StockMarketData;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static com.steven.assets.externalmaterials.model.marketdata.StockMarketData.IntradayPersistenceResult.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonStockPushPersistenceTest {
    final Instant now = Instant.parse("2026-08-28T02:15:10Z");
    final MarketClock clock = mock(MarketClock.class);
    final FubonRadarScope radar = mock(FubonRadarScope.class);
    final StockSourceQuery source = mock(StockSourceQuery.class);
    final PriceCacheWriter writer = mock(PriceCacheWriter.class);
    final FubonStockPushConsumer consumer = new FubonStockPushConsumer(clock, radar, writer, source);
    FubonStockPushPersistenceTest() {
        when(clock.instant()).thenReturn(now);
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        when(radar.current(300)).thenReturn(List.of("2330"));
        consumer.authorize(List.of("2330"), now.plusSeconds(45));
    }
    FubonMarketData.StockEvent event() {
        Instant trade = now.minusSeconds(1);
        return new FubonMarketData.StockEvent("2330", LocalDate.of(2026,8,28), trade,
                trade.getEpochSecond()*1_000_000, 1, new BigDecimal("100"), new BigDecimal("99"),
                new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("98"), "台積電");
    }
    StockMarketData.IntradayQuote row(String price) {
        return new StockMarketData.IntradayQuote("2330", "台股", LocalDate.of(2026,8,28), now.minusSeconds(1),
                FubonMarketData.STOCK_SOURCE, new BigDecimal(price), new BigDecimal("99"), new BigDecimal("99"),
                new BigDecimal("101"), new BigDecimal("98"), null,null,null,"主檔名稱");
    }
    @Test void failedDatabaseNeverReachesRedisAndNoDailyHistoryWrite() {
        when(source.persistIntradayQuote(any())).thenReturn(new StockMarketData.IntradayPersistenceResult(FAILED,null));
        assertThat(consumer.accept(event())).isEqualTo("DB_FAILED");
        verifyNoInteractions(writer);
        verify(source).persistIntradayQuote(any());
        verifyNoMoreInteractions(source);
    }
    @Test void staleRawPayloadUsesOnlyReturnedCanonicalAndCommitCallPrecedesCache() {
        var canonical = row("101");
        when(source.persistIntradayQuote(any())).thenReturn(new StockMarketData.IntradayPersistenceResult(STALE_OR_EQUAL,canonical));
        when(writer.writeTaiwanLive(canonical.asPriceResult(),true)).thenReturn(PriceProjectionData.CacheWriteOutcome.REJECTED_STALE);
        assertThat(consumer.accept(event())).isEqualTo("REJECTED_STALE");
        var order = inOrder(source,writer);
        order.verify(source).persistIntradayQuote(argThat((PriceResult raw) -> raw.price().compareTo(new BigDecimal("100"))==0
                && raw.volume()==null && raw.buyPrice()==null && raw.sellPrice()==null));
        order.verify(writer).writeTaiwanLive(canonical.asPriceResult(),true);
        verifyNoMoreInteractions(writer);
    }
    @Test void committedDatabaseSurvivesRedisFailureAndRenewalRepairsLatestCanonicalWithoutAnotherTrade() {
        var first = row("100"); var latest = row("101");
        when(source.persistIntradayQuote(any())).thenReturn(new StockMarketData.IntradayPersistenceResult(APPLIED,first));
        when(writer.writeTaiwanLive(first.asPriceResult(),true)).thenReturn(PriceProjectionData.CacheWriteOutcome.FAILED);
        when(source.findIntradayQuote("2330","台股")).thenReturn(Optional.of(latest));
        when(writer.writeTaiwanLive(latest.asPriceResult(),true)).thenReturn(PriceProjectionData.CacheWriteOutcome.WRITTEN);
        assertThat(consumer.accept(event())).isEqualTo("FAILED");
        consumer.repairPending(); consumer.repairPending();
        verify(source,times(1)).persistIntradayQuote(any());
        verify(source,times(1)).findIntradayQuote("2330","台股");
        verify(writer,times(1)).writeTaiwanLive(latest.asPriceResult(),true);
    }
    @Test void repairRechecksCalendarRadarLeaseAndRemovalDiscardsPendingRow() {
        var canonical = row("100");
        when(source.persistIntradayQuote(any())).thenReturn(new StockMarketData.IntradayPersistenceResult(APPLIED,canonical));
        when(writer.writeTaiwanLive(any(),eq(true))).thenReturn(PriceProjectionData.CacheWriteOutcome.FAILED);
        consumer.accept(event());
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.empty());
        consumer.repairPending(); verify(source,never()).findIntradayQuote(any(),any());
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        when(radar.current(300)).thenReturn(List.of()); consumer.repairPending();
        when(radar.current(300)).thenReturn(List.of("2330")); consumer.repairPending();
        verify(source,never()).findIntradayQuote(any(),any());
        verify(writer,times(1)).writeTaiwanLive(any(),eq(true));
    }
}
