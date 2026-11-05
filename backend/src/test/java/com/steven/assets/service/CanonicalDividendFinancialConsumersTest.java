package com.steven.assets.service;

import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CanonicalDividendFinancialConsumersTest {
    private static final LocalDate DATE=LocalDate.parse("2026-03-17");
    private static StockDividendHistory event(long id,String cash,String stock,String quality){
        return StockDividendHistory.builder().id(id).stockCode("2330").market("台股").year(2026)
                .cashDividend(new BigDecimal(cash)).stockDividend(new BigDecimal(stock))
                .exDividendDate(DATE).exRightsDate(DATE).canonicalDate(DATE).sourceSnapshotId(7L)
                .source("FinMind[TaiwanStockDividend]").resolutionStatus(quality).eventStatus("ACTIVE").build();
    }
    private static StockPriceHistory bar(LocalDate date,String close){
        BigDecimal n=new BigDecimal(close);return StockPriceHistory.builder().stockCode("2330").market("台股")
                .tradingDate(date).openPrice(n).highPrice(n).lowPrice(n).closePrice(n).volume(100L).build();
    }
    @Test void sameDayCashAndStockFormOneFactorWithoutPerRowCrossTerm(){
        var rows=List.of(bar(DATE,"100"),bar(DATE.minusDays(1),"115"));
        var out=new DistributionAdjustedPriceService().adjust(rows,List.of(event(1,"2","0","RESOLVED"),event(2,"3","1","RESOLVED")));
        assertThat(out.appliedEventDates()).containsExactly(DATE);
        assertThat(out.rowsDesc().getLast().getClosePrice()).isEqualByComparingTo("100");
        assertThat(out.rowsDesc().getLast().getVolume()).isEqualTo(110L);
    }
    @Test void unknownEventsNeverBecomeAZeroAdjustmentOrVerifiedSource(){
        var event=event(1,"6","0","LEGACY");
        assertThat(CanonicalDividendFinancialFacts.verified(List.of(event))).isFalse();
        assertThatThrownBy(()->new DistributionAdjustedPriceService().adjust(
                List.of(bar(DATE,"100"),bar(DATE.minusDays(1),"106")),List.of(event)))
                .isInstanceOf(CanonicalDividendFinancialGuard.Unavailable.class);
    }
    @Test void windowingKeepsUndatedUnknownAndUsesTheCanonicalEffectiveDate(){
        var unknown=event(1,"6","0","LEGACY");unknown.setCanonicalDate(null);unknown.setExDividendDate(null);unknown.setExRightsDate(null);
        var canonical=event(2,"6","0","RESOLVED");canonical.setExDividendDate(DATE.minusYears(1));
        var outside=event(3,"6","0","RESOLVED");outside.setCanonicalDate(DATE.plusYears(1));
        var selected=CanonicalDividendFinancialFacts.within(List.of(unknown,canonical,outside),DATE.minusDays(1),DATE);
        assertThat(selected).containsExactly(unknown,canonical);
        assertThat(CanonicalDividendFinancialFacts.assess(selected).available()).isFalse();
    }
    @Test void nominalUsDividendsNeedPriceBasisProofOnlyInsideTheActualCalculationWindow(){
        var cash=event(1,"0.82","0","RESOLVED");cash.setMarket("美股");cash.setStockCode("AAPL");
        assertThat(CanonicalDividendFinancialFacts.verified(List.of(cash))).isTrue(); // nominal amount itself is verified
        assertThatThrownBy(()->new DistributionAdjustedPriceService().adjust(
                List.of(bar(DATE,"100"),bar(DATE.minusDays(1),"100.82")),List.of(cash)))
                .isInstanceOf(CanonicalDividendFinancialGuard.Unavailable.class).hasMessage("US_DIVIDEND_PRICE_BASIS_UNVERIFIED");
        assertThatCode(()->new DistributionAdjustedPriceService().adjust(
                List.of(bar(DATE.minusDays(1),"100"),bar(DATE.minusDays(2),"100")),List.of(cash))).doesNotThrowAnyException();
        cash.setCanonicalDate(DATE.minusYears(10));
        assertThatCode(()->new DistributionAdjustedPriceService().adjust(
                List.of(bar(DATE,"100"),bar(DATE.minusDays(1),"100")),List.of(cash))).doesNotThrowAnyException();
    }
    @Test void completedTechnicalApiUsesItsExistingUnavailableShapeForUnresolvedHistory(){
        var prices=mock(StockPriceHistoryRepository.class);var dividends=mock(StockDividendHistoryRepository.class);
        when(prices.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc("2330","台股",DATE.minusYears(2),DATE))
                .thenReturn(List.of(bar(DATE.minusDays(1),"106"),bar(DATE,"100")));
        when(dividends.findAdjustmentEvents("2330","台股",DATE.minusDays(1),DATE)).thenReturn(List.of(event(1,"6","0","UNRESOLVED")));
        var facts=new SrppCompletedTechnicalService(prices,dividends,new DistributionAdjustedPriceService()).read("台股",DATE,List.of("2330"));
        assertThat(facts.symbols()).singleElement().satisfies(symbol->{assertThat(symbol.status()).isEqualTo("UNAVAILABLE");
            assertThat(symbol.priceBasis()).isEqualTo("UNAVAILABLE");assertThat(symbol.sourceSha256()).isNull();
            assertThat(symbol.missing()).contains("DIVIDEND_HISTORY_UNVERIFIED");});
    }
    @Test void privateCanonicalMetadataDoesNotExtendPublishedEntityWireShape(){
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var node=mapper.valueToTree(event(1,"6","0","RESOLVED"));
        for(String field:List.of("canonicalDate","sourceSnapshotId","resolutionStatus","resolutionReason"))assertThat(node.has(field)).isFalse();
        assertThat(node.path("cashDividend").decimalValue()).isEqualByComparingTo("6");
    }
}
