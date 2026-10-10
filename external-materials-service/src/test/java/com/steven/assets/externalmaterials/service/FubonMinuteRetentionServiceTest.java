package com.steven.assets.externalmaterials.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonMinuteRetentionServiceTest {
    @Test void failedStartupAutomaticallyResumesPendingAndOnlyDeletesTheFixedMinuteScope() {
        var repository=mock(FubonIntradayCandleRepository.class);
        var time=Instant.parse("2026-10-09T16:01:00Z");
        var floor=LocalDate.of(2025,10,10);
        var service=new FubonMinuteRetentionService(repository,
                new MarketClock(mock(MarketCalendar.class),Clock.fixed(time,ZoneOffset.UTC)));
        when(repository.deleteExpiredBatch(floor,false)).thenThrow(new IllegalStateException("temporary timeout"));
        service.startup(); assertThat(service.lastResult().status()).isEqualTo("FAILED");
        reset(repository);
        when(repository.deleteExpiredBatch(floor,false)).thenReturn(10000,0);
        when(repository.deleteExpiredBatch(floor,true)).thenReturn(3,0);
        service.continuePending();
        assertThat(service.lastResult().status()).isEqualTo("COMPLETE");
        assertThat(service.lastResult().floor()).isEqualTo(floor);
        assertThat(service.lastResult().deleted()).isEqualTo(10003);
        verify(repository,times(3)).deleteExpiredBatch(floor,false);
        verify(repository,times(2)).deleteExpiredBatch(floor,true);
        verify(repository).hasExpired(floor);
        clearInvocations(repository);
        service.continuePending(); verifyNoInteractions(repository);
    }
}
