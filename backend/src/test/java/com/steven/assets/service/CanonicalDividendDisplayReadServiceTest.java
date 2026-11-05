package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividend.*;
import com.steven.assets.model.CanonicalDividendResolver;
import com.steven.assets.model.AnnualDividendData;
import com.steven.assets.security.TenantGuard;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CanonicalDividendDisplayReadServiceTest {
    static final Instant NOW=Instant.parse("2026-11-05T08:00:00Z");
    static final LocalDate DATE=LocalDate.of(2026,10,1);
    @Test void historicalWithdrawalRemainsWithdrawnAndDoesNotBecomeAnUnknownUiOrAnnualRow(){
        Key key=new Key("AAPL","美股");var repo=mock(CanonicalDividendRepository.class);
        var proof=new Evidence(2,2,"NASDAQ_DIVIDEND_CALENDAR",2,"NOMINAL_PER_EX_DATE",true,"EMPTY_COMPLETE",true,
                DATE.minusDays(10),DATE.plusDays(45),NOW.minusSeconds(100),null,"proof",List.of());
        var tombstone=new Current(1,2026,DATE,null,null,null,null,null,"NASDAQ_DIVIDEND_CALENDAR",CanonicalDividendResolver.key(key,DATE),
                "CANCELLED",DATE,2L,"RESOLVED","撤回已證",null,null,null,"old");
        when(repo.load(eq(key),any())).thenReturn(new Facts(key,List.of(proof),List.of(tombstone)));
        var port=new CanonicalDividendDisplayReadService(repo);var display=port.read(List.of(key),NOW).get(key);
        assertThat(display.days()).singleElement().satisfies(day->assertThat(day.withdrawn()).isTrue());
        var history=new DividendHistoryService(port).findFromDb("AAPL","美股",10);assertThat(history.rows()).isEmpty();assertThat(history.annualSummaries()).isEmpty();
        var owner=mock(TenantGuard.class);when(owner.requireCurrentUserId()).thenReturn(1L);var positions=mock(AnnualDividendReadRepository.class);
        when(positions.load(1,LocalDate.of(2026,11,5))).thenReturn(new AnnualDividendData.Facts(List.of(new AnnualDividendData.Security("AAPL","Apple","美股")),
                List.of(new AnnualDividendData.Snapshot(LocalDate.of(2026,1,1),List.of(new AnnualDividendData.Position("AAPL","美股",BigDecimal.TEN)))),
                List.of(),List.of(),List.of(new AnnualDividendData.Market("美股","美國股市"))));
        var annual=new AnnualDividendService(owner,positions,port);var result=annual.summary("2026","");
        assertThat(result.rows()).isEmpty();assertThat(result.gaps()).isEmpty();assertThat(result.currencyTotals()).isEmpty();
    }
    @Test void unresolvedLegacyAlternativesAreCollapsedAndCanonicalEnrichmentIsRetained(){
        Key key=new Key("2330","台股");var repo=mock(CanonicalDividendRepository.class);
        var one=new Current(1,2026,DATE,null,new BigDecimal("6"),BigDecimal.ZERO,null,null,"first",null,"ACTIVE",null,null,"LEGACY",null,null,null,null,"old");
        var two=new Current(2,2026,DATE,null,new BigDecimal("6.000035"),BigDecimal.ZERO,null,null,"second",null,"ACTIVE",null,null,"LEGACY",null,null,null,null,"old");
        when(repo.load(eq(key),any())).thenReturn(new Facts(key,List.of(),List.of(one,two)));
        var port=new CanonicalDividendDisplayReadService(repo);assertThat(port.read(List.of(key),NOW).get(key).days()).singleElement().satisfies(day->{assertThat(day.cash()).isNull();assertThat(day.resolution()).isEqualTo("UNRESOLVED");assertThat(day.source()).contains("first","second");});
        var current=new Current(3,2026,DATE,null,new BigDecimal("6.00003573"),BigDecimal.ZERO,null,null,"FinMind[TaiwanStockDividend]","key","ACTIVE",DATE,1L,"RESOLVED",null,new BigDecimal(".6"),new BigDecimal("1000"),0,"old");
        when(repo.load(eq(key),any())).thenReturn(new Facts(key,List.of(),List.of(current)));
        assertThat(port.read(List.of(key),NOW).get(key).days()).singleElement().satisfies(day->{assertThat(day.cash()).isEqualByComparingTo("6.00003573");assertThat(day.previousClose()).isEqualByComparingTo("1000");assertThat(day.fillDays()).isZero();});
    }    @Test void usLegacyEnrichmentIsNotExposedEvenForResolvedNominalCanonicalCash() {
        Key key=new Key("AAPL","美股");var repo=mock(CanonicalDividendRepository.class);
        var current=new Current(1,2026,DATE,null,new BigDecimal(".82"),BigDecimal.ZERO,null,null,"NASDAQ.stocks",CanonicalDividendResolver.key(key,DATE),"ACTIVE",DATE,1L,"RESOLVED",null,new BigDecimal("1.6"),new BigDecimal("50"),0,"old");
        when(repo.load(eq(key),any())).thenReturn(new Facts(key,List.of(),List.of(current)));
        var day=new CanonicalDividendDisplayReadService(repo).read(List.of(key),NOW).get(key).days().getFirst();
        assertThat(day.cash()).isEqualByComparingTo(".82");assertThat(day.yieldPct()).isNull();assertThat(day.previousClose()).isNull();assertThat(day.fillDays()).isNull();
    }

}
