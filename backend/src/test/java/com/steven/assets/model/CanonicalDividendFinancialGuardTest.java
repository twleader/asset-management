package com.steven.assets.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CanonicalDividendFinancialGuardTest {
    private static final LocalDate DATE=LocalDate.parse("2026-03-17");
    private static CanonicalDividendFinancialGuard.Fact fact(String id,String cash,String stock,String quality,Long snapshot){
        return new CanonicalDividendFinancialGuard.Fact(id,"2330","台股",DATE,
                cash==null?null:new BigDecimal(cash),stock==null?null:new BigDecimal(stock),quality,snapshot,"source disputed");
    }
    @Test void unverifiedSourceLabelsCannotMakeLegacyOrPartialFinancialFacts(){
        for(String quality:List.of("LEGACY","UNRESOLVED","FUTURE_PARTIAL")){
            var result=CanonicalDividendFinancialGuard.assess(List.of(fact("1","6","0",quality,1L)));
            assertThat(result.available()).isFalse();assertThat(result.events()).isEmpty();
            assertThatThrownBy(result::requireAvailable).isInstanceOf(CanonicalDividendFinancialGuard.Unavailable.class);
        }
        assertThat(CanonicalDividendFinancialGuard.assess(List.of(fact("1","6","0","RESOLVED",null))).available()).isFalse();
        assertThat(CanonicalDividendFinancialGuard.assess(List.of(fact("1","6",null,"RESOLVED",1L))).available()).isFalse();
    }
    @Test void preservesEighteenDigitsAndCombinesOneSelectedSourceSetPerEffectiveDate(){
        var out=CanonicalDividendFinancialGuard.assess(List.of(
                fact("ordinary","6.000035730000000001","0","RESOLVED",1L),
                fact("special","0.100000000000000002","0.000000000000000003","RESOLVED",1L)));
        assertThat(out.available()).isTrue();assertThat(out.events()).singleElement().satisfies(e->{
            assertThat(e.cash()).isEqualByComparingTo("6.100035730000000003");
            assertThat(e.stock()).isEqualByComparingTo("0.000000000000000003");});
    }
    @Test void repeatedPersistedRowIsNotAnotherDistributionAndConflictingProvenanceIsUnavailable(){
        var row=fact("persisted:1","6.00003573","0","RESOLVED",1L);
        assertThat(CanonicalDividendFinancialGuard.assess(List.of(row,row)).events()).singleElement()
                .satisfies(e->assertThat(e.cash()).isEqualByComparingTo("6.00003573"));
        assertThat(CanonicalDividendFinancialGuard.assess(List.of(row,fact("persisted:1","6","0","RESOLVED",1L))).available()).isFalse();
        assertThat(CanonicalDividendFinancialGuard.assess(List.of(row,fact("other-source","6","0","RESOLVED",2L))).available()).isFalse();
    }
    @Test void anEmptyObservedSetNeverQualifiesNonDistributingStatus(){
        assertThat(CanonicalDividendFinancialGuard.assess(List.of()).events()).isEmpty();
        assertThat(com.steven.assets.service.CanonicalDividendFinancialFacts.verified(List.of())).isFalse();
    }
}
