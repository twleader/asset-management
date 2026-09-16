package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DividendCashEnrichmentServiceTest {
    DividendCurrentStateRepository events = mock(DividendCurrentStateRepository.class);
    StockPriceHistoryRepository prices = mock(StockPriceHistoryRepository.class);
    MarketDataService calendar = mock(MarketDataService.class);
    DividendCashEnrichmentService service = new DividendCashEnrichmentService(events, prices, calendar);
    LocalDate ex = LocalDate.of(2026, 9, 15);
    Instant completed = Instant.parse("2026-09-15T06:00:00Z");
    @BeforeEach void init() {
        when(calendar.isTradingDayCachedOnly(anyString(), any())).thenAnswer(inv -> {
            LocalDate date = inv.getArgument(1);
            return date == null ? Optional.empty() : Optional.of(date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY);
        });
    }
    DividendCurrentStateRepository.ActiveEventDetail event(BigDecimal prior, BigDecimal yield, Integer fill, LocalDate rights) {
        return new DividendCurrentStateRepository.ActiveEventDetail(1,"key",2026,ex,new BigDecimal("2"),null,
                null,null,yield,prior,fill,rights);
    }
    StockPriceHistory row(LocalDate date, String close) {
        return StockPriceHistory.builder().stockCode("2330").market("台股").tradingDate(date)
                .closePrice(close == null ? null : new BigDecimal(close)).build();
    }
    void fixture(DividendCurrentStateRepository.ActiveEventDetail event, String close) {
        when(events.findActiveEventDetails("2330","台股")).thenReturn(List.of(event));
        when(prices.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc("2330","台股",ex.minusDays(20),ex))
                .thenReturn(List.of(row(ex.minusDays(1),"100"),row(ex,close)));
    }
    @Test void sameDayHitAndDifferentRightsPreservesCashDateBasis() {
        var event = event(null,null,null,ex.minusDays(7)); fixture(event,"101");
        service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",event,new BigDecimal("100.0000"),new BigDecimal("2.0000"),0);
        assertEquals(ex.minusDays(7), event.anchorDate());
    }
    @Test void yieldOnlyMissingIsCandidateAndNoMultiSessionAssumption() {
        var event = event(new BigDecimal("100"),null,3,null); fixture(event,"98");
        service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",event,new BigDecimal("100.0000"),new BigDecimal("2.0000"),null);
    }
    @Test void intradayAndFutureEventCannotReadPrices() {
        fixture(event(null,null,null,null),"101");
        service.enrich("2330","台股",Instant.parse("2026-09-15T04:00:00Z"));
        verifyNoInteractions(prices);
    }
    @Test void unknownCalendarFailsClosed() {
        fixture(event(null,null,null,null),"101");
        when(calendar.isTradingDayCachedOnly("台股",ex)).thenReturn(Optional.empty());
        service.enrich("2330","台股",completed); verifyNoInteractions(prices);
    }
    @Test void exactPriorAndExBarCannotBeSkipped() {
        var event=event(null,null,null,null); fixture(event,"101");
        when(prices.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(any(),any(),any(),any()))
                .thenReturn(List.of(row(ex.minusDays(2),"100"),row(ex,"101")));
        service.enrich("2330","台股",completed);
        verify(events,never()).fillMissingCashEnrichment(any(),any(),any(),any(),any(),any());
    }
    @Test void pureRightsAndAlreadyCompleteDoNotQueryPrice() {
        var rights=new DividendCurrentStateRepository.ActiveEventDetail(2,"rights",2026,null,null,BigDecimal.ONE,null,null,null,null,null,ex);
        when(events.findActiveEventDetails("2330","台股")).thenReturn(List.of(rights,event(BigDecimal.TEN,BigDecimal.ONE,0,null)));
        service.enrich("2330","台股",completed); verifyNoInteractions(prices,calendar);
    }
    @Test void exactTwentyCalendarDayFloorAllowedButTwentyOneUnknown() {
        var event=event(null,null,null,null);
        when(events.findActiveEventDetails("2330","台股")).thenReturn(List.of(event));
        when(calendar.isTradingDayCachedOnly(anyString(),any())).thenAnswer(inv -> {
            LocalDate date=inv.getArgument(1);
            return date == null ? Optional.empty() : Optional.of(date.equals(ex) || date.equals(ex.minusDays(20)));
        });
        when(prices.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(any(),any(),any(),any()))
                .thenReturn(List.of(row(ex.minusDays(20),"100"),row(ex,"98")));
        service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",event,new BigDecimal("100.0000"),new BigDecimal("2.0000"),null);
        reset(prices,events);
        when(events.findActiveEventDetails("2330","台股")).thenReturn(List.of(event));
        when(calendar.isTradingDayCachedOnly(anyString(),any())).thenAnswer(inv -> {
            LocalDate date=inv.getArgument(1); return date == null ? Optional.empty() : Optional.of(date.equals(ex) || date.equals(ex.minusDays(21)));
        });
        service.enrich("2330","台股",completed); verifyNoInteractions(prices);
    }
    @Test void sameDayRightsActionAndDifferentExistingBasisRemainUnknown() {
        var event=event(null,null,null,ex);fixture(event,"101");
        service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",event,new BigDecimal("100.0000"),new BigDecimal("2.0000"),null);
        reset(events);
        var changed=event(new BigDecimal("99"),null,null,null);fixture(changed,"101");
        service.enrich("2330","台股",completed);
        verify(events,never()).fillMissingCashEnrichment(any(),any(),any(),any(),any(),any());
    }
    @Test void invalidRowsAndNumericOverflowRejected() {
        assertNull(DividendCashEnrichmentService.validatedCloses(List.of(row(ex,"1"),row(ex,"2")),ex,ex));
        assertNull(DividendCashEnrichmentService.validatedCloses(List.of(row(ex,"1"),row(ex.minusDays(1),"2")),ex.minusDays(1),ex));
        assertNull(DividendCashEnrichmentService.validatedCloses(List.of(row(ex,"0")),ex,ex));
        assertNull(DividendCashEnrichmentService.validatedCloses(List.of(row(ex,null)),ex,ex));
        assertNull(DividendCashEnrichmentService.validatedCloses(List.of(row(ex.plusDays(1),"1")),ex,ex));
        assertNull(DividendCashEnrichmentService.decimal(new BigDecimal("100000000000"),15));
        assertNull(DividendCashEnrichmentService.decimal(new BigDecimal("1000000"),10));
    }
    DividendCurrentStateRepository.ActiveEventDetail pureRights(long id, LocalDate rightsDate) {
        return new DividendCurrentStateRepository.ActiveEventDetail(id,"rights-" + id,2026,null,null,BigDecimal.ONE,
                null,null,null,null,null,rightsDate);
    }
    void withOtherActive(DividendCurrentStateRepository.ActiveEventDetail cash, DividendCurrentStateRepository.ActiveEventDetail other) {
        fixture(cash,"101");
        when(events.findActiveEventDetails("2330","台股")).thenReturn(List.of(cash,other));
    }
    @Test void differentActivePureRightsOnExSessionBlocksZeroDayFillWithoutBeingCashCandidate() {
        var cash=event(null,null,null,null);var rights=pureRights(2,ex);withOtherActive(cash,rights);
        service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",cash,new BigDecimal("100.0000"),new BigDecimal("2.0000"),null);
        verify(events,never()).fillMissingCashEnrichment(eq("2330"),eq("台股"),eq(rights),any(),any(),any());
        verify(prices,times(1)).findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(any(),any(),any(),any());
    }
    @Test void alreadyCompleteOtherCashEventStillBlocksZeroDayFillForItsExDividendDate() {
        var cash=event(null,null,null,null);
        var complete=new DividendCurrentStateRepository.ActiveEventDetail(2,"complete",2026,ex,new BigDecimal("1"),null,
                null,null,new BigDecimal("1.0000"),new BigDecimal("100.0000"),0,null);
        withOtherActive(cash,complete);service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",cash,new BigDecimal("100.0000"),new BigDecimal("2.0000"),null);
        verify(events,never()).fillMissingCashEnrichment(eq("2330"),eq("台股"),eq(complete),any(),any(),any());
    }
    @Test void alreadyCompleteOtherCashEventAlsoBlocksForItsSeparateExRightsDate() {
        var cash=event(null,null,null,null);
        var complete=new DividendCurrentStateRepository.ActiveEventDetail(2,"complete-rights",2026,ex.minusDays(7),BigDecimal.ONE,BigDecimal.ONE,
                null,null,BigDecimal.ONE,new BigDecimal("100.0000"),0,ex);
        withOtherActive(cash,complete);service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",cash,new BigDecimal("100.0000"),new BigDecimal("2.0000"),null);
    }
    @Test void anotherActionBeforePriorSessionDoesNotBlockSameSessionFill() {
        var cash=event(null,null,null,null);withOtherActive(cash,pureRights(2,ex.minusDays(2)));
        service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",cash,new BigDecimal("100.0000"),new BigDecimal("2.0000"),0);
    }
    @Test void anotherActionOnPriorSessionIsExcludedFromComparisonInterval() {
        var cash=event(null,null,null,null);withOtherActive(cash,pureRights(2,ex.minusDays(1)));
        service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",cash,new BigDecimal("100.0000"),new BigDecimal("2.0000"),0);
    }
    @Test void anotherActionAfterExSessionDoesNotBlockSameSessionFill() {
        var cash=event(null,null,null,null);withOtherActive(cash,pureRights(2,ex.plusDays(1)));
        service.enrich("2330","台股",completed);
        verify(events).fillMissingCashEnrichment("2330","台股",cash,new BigDecimal("100.0000"),new BigDecimal("2.0000"),0);
    }

}
