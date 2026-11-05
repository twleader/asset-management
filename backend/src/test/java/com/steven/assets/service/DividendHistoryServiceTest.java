package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividend.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DividendHistoryServiceTest {
    @Test void bothGetPathsOnlyReadCanonicalPortAndPreservePrecisionQualityAndSafeEnrichment(){
        var port=mock(CanonicalDividendDisplayReadPort.class);var key=new Key("2330","台股");
        var day=new Day(LocalDate.of(2026,3,17),new BigDecimal("6.00003573"),BigDecimal.ZERO,LocalDate.of(2026,4,9),null,
                "FinMind[TaiwanStockDividend]",1L,1L,Instant.parse("2026-11-04T09:00:00Z"),"RESOLVED",null,"key",true,false,
                new BigDecimal(".60000357"),new BigDecimal("1000"),0);
        when(port.read(eq(List.of(key)),any())).thenReturn(Map.of(key,new Display("2330","台股",List.of(day))));
        var service=new DividendHistoryService(port);
        for(var result:List.of(service.findFromDb("2330","台股",10),service.findFromDbReadOnly("2330","台股",10))){
            assertThat(result.quality()).isEqualTo("RESOLVED");assertThat(result.rows().getFirst().cashDividend()).isEqualByComparingTo("6.00003573");
            assertThat(result.rows().getFirst().previousClose()).isEqualByComparingTo("1000");assertThat(result.rows().getFirst().fillDays()).isZero();
            assertThat(result.annualSummaries().getFirst().cashDividend()).isEqualByComparingTo("6.00003573");
            assertThat(result.annualSummaries().getFirst().yieldPct()).isEqualByComparingTo(".600004");
        }
        verify(port,times(2)).read(eq(List.of(key)),any());verifyNoMoreInteractions(port);
    }
    @Test void unknownAndUndatedKeepYearTotalsUnknownAndSourcesAreNotFirstRow(){
        var port=mock(CanonicalDividendDisplayReadPort.class);var key=new Key("X","台股");
        var one=new Day(LocalDate.of(2026,1,1),BigDecimal.ONE,BigDecimal.ZERO,null,null,"sourceA",1L,1L,Instant.now(),"RESOLVED",null,"a",true,false);
        var two=new Day(LocalDate.of(2026,2,1),null,null,null,null,"sourceB",null,null,null,"UNRESOLVED","來源無法核對","b",false,false);
        when(port.read(eq(List.of(key)),any())).thenReturn(Map.of(key,new Display("X","台股",List.of(one,two),List.of(new Undated(2026,"sourceC","缺日期")))));
        var result=new DividendHistoryService(port).findFromDb("X","台股",10);
        assertThat(result.quality()).isEqualTo("UNRESOLVED");assertThat(result.rows()).hasSize(3);assertThat(result.annualSummaries().getFirst().cashDividend()).isNull();
        assertThat(result.annualSummaries().getFirst().unavailableCount()).isEqualTo(2);assertThat(result.source()).contains("sourceA","sourceB");
    }
    @Test void emptyIsUnknownAndReadFailuresDoNotBecomeZero(){
        var port=mock(CanonicalDividendDisplayReadPort.class);when(port.read(any(),any())).thenReturn(Map.of());
        var service=new DividendHistoryService(port);assertThat(service.findFromDb("X","台股",10).quality()).isEqualTo("UNRESOLVED");
        when(port.read(any(),any())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(()->service.findFromDb("X","台股",10)).isInstanceOf(IllegalStateException.class);
    }    @Test void usRowsAndAnnualYieldNeverExposeUnknownSplitPriceBasis() {
        var port=mock(CanonicalDividendDisplayReadPort.class);var key=new Key("AAPL","美股");
        var day=new Day(LocalDate.of(2026,3,17),new BigDecimal(".82"),BigDecimal.ZERO,null,null,"NASDAQ.stocks",1L,1L,Instant.now(),"RESOLVED",null,"key",true,false,new BigDecimal("1.6"),new BigDecimal("50"),0);
        when(port.read(eq(List.of(key)),any())).thenReturn(Map.of(key,new Display("AAPL","美股",List.of(day))));
        var result=new DividendHistoryService(port).findFromDb("AAPL","美股",10);
        assertThat(result.rows().getFirst().cashDividend()).isEqualByComparingTo(".82");assertThat(result.rows().getFirst().yieldPct()).isNull();
        assertThat(result.rows().getFirst().previousClose()).isNull();assertThat(result.rows().getFirst().fillDays()).isNull();assertThat(result.annualSummaries().getFirst().yieldPct()).isNull();
        assertThat(result.annualSummaries().getFirst().cashDividend()).isEqualByComparingTo(".82");
    }

}
