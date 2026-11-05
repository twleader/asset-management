package com.steven.assets.service;

import com.steven.assets.model.*;
import com.steven.assets.model.DailyInventory.*;
import com.steven.assets.repository.AssetSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailyInventoryCaptureServiceTest {
    static final ZoneId TPE=ZoneId.of("Asia/Taipei");
    static final Session TW=new Session("台股",LocalDate.of(2026,11,6),TPE,Instant.parse("2026-11-06T05:30:00Z"));
    AssetSnapshotMutationLock lock=mock(AssetSnapshotMutationLock.class);
    AssetSnapshotRepository sources=mock(AssetSnapshotRepository.class);
    DailyInventoryHistoryRepository history=mock(DailyInventoryHistoryRepository.class);
    Clock clock=mock(Clock.class);
    DailyInventoryCaptureService service=new DailyInventoryCaptureService(lock,sources,history,clock);
    AssetSnapshot source() {
        var s=new AssetSnapshot();s.setId(10L);s.setOwnerUserId(1L);s.setSnapshotDate(LocalDate.of(2026,11,6));
        when(lock.lockLatestForOwner(1L)).thenReturn(Optional.of(s));
        when(sources.findFirstByOwnerUserIdOrderBySnapshotDateDesc(1L)).thenReturn(Optional.of(s));
        when(clock.instant()).thenReturn(Instant.parse("2026-11-06T11:30:02Z"));
        return s;
    }
    StockHolding holding(long id,String market,String shares) {
        return StockHolding.builder().id(id).stockCode("ABC").market(market).shares(new BigDecimal(shares))
                .investmentCost(new BigDecimal("120.50")).currency("USD").transactionExchangeRate(new BigDecimal("32.1234")).build();
    }
    @Test void lockFirstThenReadRealClockAndLatestRetainsFractionalCostCurrencyOnlyForSelectedMarket() {
        var s=source();s.getStocks().addAll(List.of(holding(1,"台股","0.12345"),holding(2,"美股","200")));
        when(history.insert(any())).thenReturn(true,false);
        assertThat(service.capture(1,TW)).isTrue();
        assertThat(service.capture(1,TW)).isFalse();
        var order=inOrder(lock,clock,sources,history);
        order.verify(lock).lockLatestForOwner(1L);order.verify(clock).instant();
        order.verify(sources).findFirstByOwnerUserIdOrderBySnapshotDateDesc(1L);order.verify(history).insert(any());
        var c=ArgumentCaptor.forClass(Capture.class);verify(history,times(2)).insert(c.capture());
        assertThat(c.getValue().positions()).singleElement().satisfies(p->{
            assertThat(p.shares()).isEqualByComparingTo("0.12345");assertThat(p.investmentCost()).isEqualByComparingTo("120.50");
            assertThat(p.currency()).isEqualTo("USD");assertThat(p.transactionExchangeRate()).isEqualByComparingTo("32.1234");
        });
        assertThat(c.getValue().capturedAt()).isEqualTo(Instant.parse("2026-11-06T11:30:02Z"));
    }
    @Test void waitingForLockAcrossMidnightDoesNotWriteYesterdayEvenIfTickWasDue() {
        source();when(lock.lockLatestForOwner(1L)).thenAnswer(call->{
            when(clock.instant()).thenReturn(Instant.parse("2026-11-06T16:00:00Z"));return Optional.of(sourceEntity());
        });
        assertThat(service.capture(1,TW)).isFalse();verifyNoInteractions(history,sources);
    }
    AssetSnapshot sourceEntity(){var s=new AssetSnapshot();s.setId(10L);s.setOwnerUserId(1L);s.setSnapshotDate(TW.tradingDate());return s;}
    @Test void newLatestCommittedWhileWaitingForRootMakesOldRootIneligible() {
        source();var newer=sourceEntity();newer.setId(11L);
        when(sources.findFirstByOwnerUserIdOrderBySnapshotDateDesc(1L)).thenReturn(Optional.of(newer));
        assertThat(service.capture(1,TW)).isFalse();verifyNoInteractions(history);
    }
    @Test void emptyMarketIsSavedButMissingFutureAndInvalidSourceCannotProduceSuccess() {
        var s=source();s.getStocks().add(holding(1,"美股","1"));
        service.capture(1,TW);var c=ArgumentCaptor.forClass(Capture.class);verify(history).insert(c.capture());
        assertThat(c.getValue().positions()).isEmpty();clearInvocations(history);
        when(lock.lockLatestForOwner(2L)).thenReturn(Optional.empty());assertThat(service.capture(2,TW)).isFalse();verifyNoInteractions(history);
        s.setSnapshotDate(LocalDate.of(2026,11,7));assertThatThrownBy(()->service.capture(1,TW)).isInstanceOf(IllegalStateException.class);
        s.setSnapshotDate(TW.tradingDate());s.getStocks().add(holding(3,"台股","-1"));
        assertThatThrownBy(()->service.capture(1,TW)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(history);
    }
    @Test void failedOwnerAndMarketDoNotBlockOtherOwnersOrMarkets() {
        var calendar=mock(DailyInventoryCalendarPort.class);var captures=mock(DailyInventoryCaptureService.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-06T11:30:00Z"));
        when(calendar.dueSession(anyString(),any())).thenReturn(Optional.of(TW));
        when(sources.findDistinctOwnerUserIds()).thenReturn(List.of(1L,2L));
        when(captures.capture(eq(1L),any())).thenThrow(new IllegalStateException());
        new DailyInventoryScheduler(calendar,sources,captures,history,clock).tick();
        verify(captures,times(2)).capture(2L,TW);
        verify(calendar).dueSession(eq("美股"),any());
    }

    @Test void retentionRunsOnFirstTickBeforeHolidayOrUnavailableCalendarAndFailureDoesNotBlockCapture() {
        var calendar=mock(DailyInventoryCalendarPort.class);var captures=mock(DailyInventoryCaptureService.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-06T01:00:00Z"));
        when(calendar.dueSession(eq("台股"),any())).thenThrow(new IllegalStateException());
        when(calendar.dueSession(eq("美股"),any())).thenReturn(Optional.empty());
        var scheduler=new DailyInventoryScheduler(calendar,sources,captures,history,clock);
        scheduler.tick();
        var order=inOrder(history,calendar);order.verify(history).deleteExpired();
        order.verify(calendar).dueSession(eq("台股"),any());order.verify(calendar).dueSession(eq("美股"),any());
        verifyNoInteractions(captures,sources);
        when(history.deleteExpired()).thenThrow(new IllegalStateException());
        when(calendar.dueSession(eq("台股"),any())).thenReturn(Optional.of(TW));
        when(sources.findDistinctOwnerUserIds()).thenReturn(List.of(2L));
        scheduler.tick();verify(captures).capture(2L,TW);verify(history,times(2)).deleteExpired();
    }
}
