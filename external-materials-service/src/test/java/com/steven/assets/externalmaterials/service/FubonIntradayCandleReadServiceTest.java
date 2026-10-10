package com.steven.assets.externalmaterials.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonIntradayCandleReadServiceTest {
    final Instant now=Instant.parse("2026-10-09T02:20:00Z");
    final LocalDate day=LocalDate.of(2026,10,9);
    final FubonIntradayCandleRepository repository=mock(FubonIntradayCandleRepository.class);
    final MarketCalendar calendar=mock(MarketCalendar.class);
    final FubonIntradayCandleReadService service=new FubonIntradayCandleReadService(repository,
            new MarketClock(calendar,Clock.fixed(now,ZoneOffset.UTC)),calendar);
    @Test void exactLatestMissingIsUnavailableNotFallbackToEarlierFact() {
        when(calendar.peekTwTradingDayKnown(day)).thenReturn(Optional.of(true));
        var previous=new FubonIntradayCandleBatch.Candle(now.minusSeconds(240),"10","11","9","10",1);
        when(repository.read(List.of("2330"),day,now,day.minusYears(1))).thenReturn(List.of(
                new FubonIntradayCandleRepository.Snapshot("2330",day,now.minusSeconds(10),now,
                        now.minusSeconds(120),"AVAILABLE",null,List.of(previous))));
        var stock=service.read("2330",day.toString(),now.toString()).stocks().getFirst();
        assertThat(stock.status()).isEqualTo("UNAVAILABLE"); assertThat(stock.candles()).isEmpty();
        verify(repository).read(List.of("2330"),day,now,day.minusYears(1)); verifyNoMoreInteractions(repository);
    }
    @Test void calendarUnknownAndExpiredNeverReadDatabaseOrRefreshSources() {
        when(calendar.peekTwTradingDayKnown(day)).thenReturn(Optional.empty());
        assertThat(service.read("2330",day.toString(),now.toString()).stocks().getFirst().reason()).isEqualTo("CALENDAR_UNKNOWN");
        assertThat(service.read("2330","2025-10-08","2025-10-08T02:00:00Z").stocks().getFirst().reason()).isEqualTo("RETENTION_EXPIRED");
        verifyNoInteractions(repository);
        verify(calendar,never()).isTwTradingDayKnown(any());
    }
    @Test void futureDuplicateTooManyNonUtcAndMismatchedDayAreInvalid() {
        for(String codes:List.of("2330,2330","0000"," 2330",String.join(",",java.util.Collections.nCopies(31,"2330"))))
            assertThatThrownBy(()->service.read(codes,day.toString(),now.toString())).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(()->service.read("2330",day.toString(),now.plusSeconds(1).toString())).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(()->service.read("2330","2026-10-08",now.toString())).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(()->service.read("2330",day.toString(),"2026-10-09T10:20:00+08:00")).hasMessage("INVALID_REQUEST");
        verifyNoInteractions(repository);
    }
    @Test void conflictAndStaleNeverCarryUsableCandles() {
        when(calendar.peekTwTradingDayKnown(day)).thenReturn(Optional.of(true));
        var candle=new FubonIntradayCandleBatch.Candle(now.minusSeconds(600),"10","11","9","10",1);
        when(repository.read(List.of("2330"),day,now,day.minusYears(1))).thenReturn(List.of(
                new FubonIntradayCandleRepository.Snapshot("2330",day,now.minusSeconds(470),now.minusSeconds(460),
                        now.minusSeconds(540),"AVAILABLE",null,List.of(candle))));
        assertThat(service.read("2330",day.toString(),now.toString()).stocks().getFirst().status()).isEqualTo("STALE");
        when(repository.read(List.of("2330"),day,now,day.minusYears(1))).thenReturn(List.of(
                new FubonIntradayCandleRepository.Snapshot("2330",day,now.minusSeconds(5),now,null,
                        "CONFLICT","CONFLICT_NO_SOURCE_REVISION",List.of(candle))));
        var stock=service.read("2330",day.toString(),now.toString()).stocks().getFirst();
        assertThat(stock.status()).isEqualTo("CONFLICT"); assertThat(stock.candles()).isEmpty();
    }
}
