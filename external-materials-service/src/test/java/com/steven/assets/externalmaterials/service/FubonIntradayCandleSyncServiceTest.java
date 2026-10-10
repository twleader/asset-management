package com.steven.assets.externalmaterials.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonIntradayCandleSyncServiceTest {
    final Instant now=Instant.parse("2026-10-09T02:20:00Z");
    final FubonMarketRunGate gate=mock(FubonMarketRunGate.class);
    final FubonRadarScope scope=mock(FubonRadarScope.class);
    final FubonMarketDataPort client=mock(FubonMarketDataPort.class);
    final FubonIntradayCandleRepository repository=mock(FubonIntradayCandleRepository.class);
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    FubonIntradayCandleSyncService service(Instant time) {
        return new FubonIntradayCandleSyncService("true",gate,scope,client,repository,
                new MarketClock(mock(MarketCalendar.class),Clock.fixed(time,ZoneOffset.UTC)),redis);
    }
    @Test void fairCursorCapsEachRoundToFiveOfThirtyCodesAndPreservesHttpStartProof() {
        var codes=java.util.stream.IntStream.range(2300,2330).mapToObj(Integer::toString).toList();
        when(scope.current(30)).thenReturn(codes);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var cursor=new AtomicReference<String>();
        when(values.get(anyString())).thenAnswer(a->cursor.get());
        doAnswer(a->{cursor.set(a.getArgument(1));return null;}).when(values).set(anyString(),anyString());
        var read=FubonIntradayCandleRepositoryPostgresTest.read(now,FubonIntradayCandleRepositoryPostgresTest.rows("2026-10-09T02:00:00Z",21));
        when(client.candles(anyString(),any())).thenAnswer(a -> new FubonMarketData.IntradayCandlesRead(
                a.getArgument(0),read.sourceDate(),read.observedAt(),read.exchange(),read.sourceMarket(),
                read.timeframe(),read.status(),read.reason(),read.candles()));
        when(repository.capture(any(),eq(now))).thenReturn(new FubonIntradayCandleRepository.WriteResult("AVAILABLE",18,0));
        var sync=service(now);
        assertThat(sync.sync().requested()).isEqualTo(5);
        assertThat(cursor.get()).isEqualTo("2304");
        assertThat(sync.sync().requested()).isEqualTo(5);
        assertThat(cursor.get()).isEqualTo("2309");
        verify(client).candles("2300",java.time.LocalDate.of(2026,10,9));
        verify(client).candles("2305",java.time.LocalDate.of(2026,10,9));
        verify(repository,times(10)).capture(any(),eq(now));
    }
    @Test void unknownCalendarAndClosingTimePerformNoSourceOrCursorIo() {
        when(gate.reason("true","INTRADAY_CANDLE_SYNC_DISABLED",false)).thenReturn("CALENDAR_UNKNOWN");
        assertThat(service(now).sync().status()).isEqualTo("CALENDAR_UNKNOWN");
        reset(gate);
        assertThat(service(Instant.parse("2026-10-09T05:30:00Z")).sync().status()).isEqualTo("OUTSIDE_SESSION");
        verifyNoInteractions(scope,client,repository,redis);
    }
    @Test void realQuotaFailurePublishesUnavailableReceiptAndStopsRemainingRound() {
        when(scope.current(30)).thenReturn(List.of("2330","2317"));
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(client.candles(anyString(),any())).thenThrow(new FubonMarketData.Unavailable("RATE_LIMITED",true));
        var result=service(now).sync();
        assertThat(result.status()).isEqualTo("RATE_LIMITED"); assertThat(result.requested()).isEqualTo(1);
        verify(repository).unavailable("2330",java.time.LocalDate.of(2026,10,9),now,now,"UNAVAILABLE","RATE_LIMITED");
        verify(client,times(1)).candles(anyString(),any());
    }
}
