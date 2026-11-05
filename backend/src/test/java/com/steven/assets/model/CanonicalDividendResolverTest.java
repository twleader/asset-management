package com.steven.assets.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.steven.assets.model.CanonicalDividend.*;
import static org.assertj.core.api.Assertions.*;

class CanonicalDividendResolverTest {
    static final Key KEY=new Key("2330","台股");
    static final Instant NOW=Instant.parse("2026-11-05T08:00:00Z");
    static final LocalDate TODAY=LocalDate.of(2026,11,5);
    static BigDecimal n(String s){return s==null?null:new BigDecimal(s);}
    static LocalDate d(String s){return s==null?null:LocalDate.parse(s);}
    static Component component(String id,String date,String cash,String stock){return new Component(id,2026,d(date),n(stock)!=null&&n(stock).signum()>0?d(date):null,n(cash),n(stock),null,null,null);}
    static Evidence evidence(long id,String provider,Component... parts){return new Evidence(id,id,provider,2,"NOMINAL_PER_EX_DATE",true,"PARTIAL",false,d("2015-01-01"),d("2026-11-04"),NOW.minusSeconds(100-id),null,"hash"+id,List.of(parts));}
    static Current current(long id,String date,String cash,String stock,String status,String quality){return new Current(id,2026,d(date),null,n(cash),n(stock),null,null,"FinMind[TaiwanStockDividend]",CanonicalDividendResolver.key(KEY,d(date)),status,d(date),1L,quality,null,null,null,null,"old");}
    static List<Day> resolve(Evidence... events){return CanonicalDividendResolver.resolve(new Facts(KEY,List.of(events),List.of()),NOW,TODAY,false).days();}

    @Test void nominalPrimaryPrecisionWinsWithoutToleranceAndYesterdayScopeIsEnough(){
        var out=resolve(evidence(1,"FinMind[TaiwanStockDividend]",component("primary","2026-03-17","6.00003573","0")),
                evidence(2,"FUBON_SDK",component("sdk","2026-03-17","6.000035","0")));
        assertThat(out).singleElement().satisfies(day->{assertThat(day.cash()).isEqualByComparingTo("6.00003573");assertThat(day.stock()).isEqualByComparingTo("0");assertThat(day.resolution()).isEqualTo("RESOLVED");});
    }
    @Test void sameProviderWholeRevisionReplacesOldAmounts(){
        var out=resolve(evidence(1,"FinMind[TaiwanStockDividend]",component("same","2026-03-17","6","0")),
                evidence(2,"FinMind[TaiwanStockDividend]",component("same","2026-03-17","6.00003573","0")));
        assertThat(out).singleElement().satisfies(day->assertThat(day.cash()).isEqualByComparingTo("6.00003573"));
    }
    @Test void realSameDayEqualComponentsAndCashRightsLegsAreRetainedExactlyOnce(){
        var a=new Component("ordinary",2026,d("2026-03-17"),d("2026-03-17"),n("2"),n(".4"),d("2026-04-01"),null,null);
        var b=new Component("special",2026,d("2026-03-17"),null,n("2"),n("0"),null,null,null);
        var out=resolve(evidence(1,"FinMind[TaiwanStockDividend]",a,b,a));
        assertThat(out).singleElement().satisfies(day->{assertThat(day.cash()).isEqualByComparingTo("4");assertThat(day.stock()).isEqualByComparingTo(".4");assertThat(day.cashPayment()).isNull();assertThat(day.reason()).contains("部分未提供");});
    }
    @Test void withdrawingOneComponentDoesNotRemoveOtherSameDayDistribution(){
        var removed=new Component("ordinary",2026,d("2026-03-17"),null,n("2"),n("0"),null,null,null,true);
        var remains=component("special","2026-03-17","3","0");
        var day=resolve(evidence(1,"FinMind[TaiwanStockDividend]",removed,remains)).getFirst();
        assertThat(day.cash()).isEqualByComparingTo("3");assertThat(day.withdrawn()).isFalse();
        var all=resolve(evidence(1,"FinMind[TaiwanStockDividend]",removed)).getFirst();
        assertThat(all.withdrawn()).isTrue();assertThat(all.resolution()).isEqualTo("CANCELLED");
    }
    @Test void provenComponentDateMoveKeepsOtherOldDateComponent(){
        var old=evidence(1,"FinMind[TaiwanStockDividend]",component("FINMIND_PRIMARY_ID:moving","2026-03-17","2","0"),component("kept","2026-03-17","3","0"));
        var newer=evidence(2,"FinMind[TaiwanStockDividend]",component("FINMIND_PRIMARY_ID:moving","2026-03-18","2","0"),component("kept","2026-03-17","3","0"));
        var days=resolve(old,newer);
        assertThat(days).hasSize(2);assertThat(days.getFirst().cash()).isEqualByComparingTo("3");assertThat(days.getLast().cash()).isEqualByComparingTo("2");
        var only=resolve(evidence(1,"FinMind[TaiwanStockDividend]",component("FINMIND_PRIMARY_ID:moving","2026-03-17","2","0")),
                evidence(2,"FinMind[TaiwanStockDividend]",component("FINMIND_PRIMARY_ID:moving","2026-03-18","2","0")));
        assertThat(only.getFirst().withdrawn()).isTrue();assertThat(only.getFirst().resolution()).isEqualTo("RESOLVED");
    }
    @Test void partialAbsenceCannotErasePastFacts(){
        var old=evidence(1,"FinMind[TaiwanStockDividend]",component("same","2026-03-17","6","0"));
        var empty=evidence(2,"FinMind[TaiwanStockDividend]");
        assertThat(resolve(old,empty).getFirst().cash()).isEqualByComparingTo("6");
    }
    @Test void futurePartialIsDisplayOnlyAndCompleteAbsenceCancelsWithoutChangingPartialGate(){
        var part=new Evidence(1,1,"NASDAQ_DIVIDEND_CALENDAR",2,"NOMINAL_PER_EX_DATE",true,"PARTIAL",false,TODAY,TODAY.plusDays(45),NOW.minusSeconds(10),null,"x",List.of(component("next","2026-12-01","7","0")));
        var key=new Key("AAPL","美股");
        var facts=new Facts(key,List.of(part),List.of());
        assertThat(CanonicalDividendResolver.resolve(facts,NOW,TODAY,true).days().getFirst().resolution()).isEqualTo("FUTURE_PARTIAL");
        assertThat(CanonicalDividendResolver.resolve(facts,NOW,TODAY,false).days()).isEmpty();
        var empty=new Evidence(2,2,"NASDAQ_DIVIDEND_CALENDAR",2,"NOMINAL_PER_EX_DATE",true,"EMPTY_COMPLETE",true,TODAY,TODAY.plusDays(45),NOW.minusSeconds(1),null,"empty",List.of());
        var next=CanonicalDividendResolver.resolve(new Facts(key,List.of(part,empty),List.of()),NOW,TODAY,false).days().getFirst();
        assertThat(next.resolution()).isEqualTo("CANCELLED");assertThat(next.withdrawn()).isTrue();
        var incomplete=new Evidence(2,2,"NASDAQ_DIVIDEND_CALENDAR",2,"NOMINAL_PER_EX_DATE",true,"PARTIAL",false,TODAY,TODAY.plusDays(45),NOW.minusSeconds(1),null,"empty",List.of());
        assertThat(CanonicalDividendResolver.resolve(new Facts(key,List.of(part,incomplete),List.of()),NOW,TODAY,true).days().getFirst().cash()).isEqualByComparingTo("7");
    }
    @Test void unknownStockWrongMarketUnprovenBasisAndAnonymousAlternativesStayUnavailable(){
        var sdk=evidence(1,"FUBON_SDK",component("x","2026-03-17","6",null));
        assertThat(resolve(sdk).getFirst().cash()).isNull();
        assertThat(resolve(evidence(1,"NASDAQ.stocks",component("x","2026-03-17","6","0"))).getFirst().cash()).isNull();
        assertThat(resolve(evidence(1,"FinMind[TaiwanStockDividend]",component(null,"2026-03-17","6","0"),component(null,"2026-03-17","7","0"))).getFirst().cash()).isNull();
        var bad=new Evidence(1,1,"FinMind[TaiwanStockDividend]",1,"LEGACY_UNVERIFIED",true,"PARTIAL",false,d("2020-01-01"),TODAY,NOW,null,"x",List.of(component("x","2026-03-17","6","0")));
        assertThat(CanonicalDividendResolver.resolve(new Facts(KEY,List.of(bad),List.of(current(1,"2026-03-17","6","0","ACTIVE","LEGACY"))),NOW,TODAY,false).days().getFirst().cash()).isNull();
    }
    @Test void dateDerivedIdentitiesCannotProveMovesButUnchangedAndNewDatesRemainSeparate() {
        for (var source : List.of(new String[]{"FinMind[TaiwanStockDividend]","FINMIND_PRIMARY_DATE:"},
                new String[]{"FUBON_SDK","FUBON_SDK:"},new String[]{"FinMind[TaiwanStockDividendResult]","FINMIND_RESULT:"},
                new String[]{"Yahoo Finance (nominal from verified splits)","YAHOO:"},new String[]{"NASDAQ_DIVIDEND_CALENDAR","NASDAQ_CALENDAR:"})) {
            Key key=new Key("TEST",source[0].startsWith("NASDAQ")||source[0].startsWith("Yahoo")?"美股":"台股");
            var before=evidence(1,source[0],component(source[1]+"D1","2026-03-17","2","0"),component(source[1]+"D3","2026-03-20","3","0"));
            var after=evidence(2,source[0],component(source[1]+"D2","2026-03-18","2","0"),component(source[1]+"D3","2026-03-20","3","0"));
            var moved=CanonicalDividendResolver.resolve(new Facts(key,List.of(before,after),List.of()),NOW,TODAY,false).days();
            assertThat(moved).hasSize(3);assertThat(moved.subList(0,2)).allSatisfy(day->{assertThat(day.resolution()).isEqualTo("UNRESOLVED");assertThat(day.cash()).isNull();});
            assertThat(moved.getLast().cash()).isEqualByComparingTo("3");
            var appended=evidence(2,source[0],component(source[1]+"D1","2026-03-17","2","0"),component(source[1]+"D2","2026-03-18","2","0"),component(source[1]+"D3","2026-03-20","3","0"));
            assertThat(CanonicalDividendResolver.resolve(new Facts(key,List.of(before,appended),List.of()),NOW,TODAY,false).days())
                    .hasSize(3).allSatisfy(day->assertThat(day.resolution()).isEqualTo("RESOLVED"));
        }
    }
    @Test void explicitNewerUncertaintyShadowsOnlyItsProviderAndDateWhileFailureOrAbsenceDoesNot() {
        var old=evidence(1,"FinMind[TaiwanStockDividend]",component("FINMIND_PRIMARY_ID:x","2026-03-17","6","0"));
        var partial=evidence(2,"FinMind[TaiwanStockDividend]",component("FINMIND_PRIMARY_ID:x","2026-03-17",null,null));
        var unknown=new Evidence(2,2,partial.provider(),2,partial.basis(),false,"PARTIAL",false,partial.scopeFrom(),partial.scopeTo(),partial.observedAt(),null,"unknown",partial.components());
        assertThat(resolve(old,unknown)).singleElement().satisfies(day->{assertThat(day.cash()).isNull();assertThat(day.reason()).contains("較新公告");});
        var existing=current(1,"2026-03-17","6","0","ACTIVE","RESOLVED");
        assertThat(CanonicalDividendResolver.resolve(new Facts(KEY,List.of(old,unknown),List.of(existing)),NOW,TODAY,false).days().getFirst().cash()).isNull();
        var fallback=evidence(3,"FUBON_SDK",component("FUBON_SDK:day","2026-03-17","5","0"));
        assertThat(resolve(old,unknown,fallback).getFirst().cash()).isEqualByComparingTo("5");
        var failed=new Evidence(2,2,unknown.provider(),2,unknown.basis(),false,"FAILED",false,unknown.scopeFrom(),unknown.scopeTo(),unknown.observedAt(),null,"failed",unknown.components());
        assertThat(resolve(old,failed).getFirst().cash()).isEqualByComparingTo("6");
        var otherProvider=new Evidence(2,2,"TWSE_TWT48U_ALL+TPEX_EXRIGHT_PREPOST",2,unknown.basis(),false,"PARTIAL",false,unknown.scopeFrom(),unknown.scopeTo(),unknown.observedAt(),null,"unknown",unknown.components());
        assertThat(resolve(old,otherProvider).getFirst().cash()).isEqualByComparingTo("6");
    }
    @Test void explicitNewerUncertainFutureDateAlsoShadowsOlderCompleteAbsence() {
        var key=new Key("AAPL","美股");var date=TODAY.plusDays(10);
        var old=new Evidence(1,1,"NASDAQ_DIVIDEND_CALENDAR",2,"NOMINAL_PER_EX_DATE",true,"COMPLETE",true,TODAY,TODAY.plusDays(45),NOW.minusSeconds(10),null,"old",List.of(component("NASDAQ_CALENDAR:old",date.toString(),"6","0")));
        var empty=new Evidence(2,2,old.provider(),2,old.basis(),true,"EMPTY_COMPLETE",true,old.scopeFrom(),old.scopeTo(),NOW.minusSeconds(5),null,"empty",List.of());
        var unknown=new Evidence(3,3,old.provider(),2,old.basis(),false,"PARTIAL",false,old.scopeFrom(),old.scopeTo(),NOW.minusSeconds(1),null,"unknown",List.of(component("NASDAQ_CALENDAR:unknown",date.toString(),null,null)));
        var day=CanonicalDividendResolver.resolve(new Facts(key,List.of(old,empty,unknown),List.of()),NOW,TODAY,false).days().getFirst();
        assertThat(day.withdrawn()).isFalse();assertThat(day.resolution()).isEqualTo("UNRESOLVED");assertThat(day.cash()).isNull();
    }
    @Test void differentProviderIncompleteFutureAnnouncementCannotEraseSdkPositiveDisplay() {
        var date=TODAY.plusDays(10);var sdk=new Evidence(1,1,"FUBON_SDK",2,"NOMINAL_PER_EX_DATE",true,"PARTIAL",false,TODAY,TODAY.plusDays(45),NOW.minusSeconds(10),null,"sdk",List.of(component("FUBON_SDK:day",date.toString(),"6","0")));
        var official=new Evidence(2,2,"TWSE_TWT48U_ALL+TPEX_EXRIGHT_PREPOST",2,"NOMINAL_PER_EX_DATE",false,"PARTIAL",false,TODAY,TODAY.plusDays(45),NOW.minusSeconds(1),null,"official",List.of(component("TWSE:day",date.toString(),null,null)));
        var day=CanonicalDividendResolver.resolve(new Facts(KEY,List.of(sdk,official),List.of()),NOW,TODAY,true).days().getFirst();
        assertThat(day.cash()).isEqualByComparingTo("6");assertThat(day.resolution()).isEqualTo("FUTURE_PARTIAL");
    }
    static Current legacyRow(long id, String status, String date, String cash, String stock, String cashPay, String rights, String stockPay) {
        return new Current(id,2026,d(date),d(rights),n(cash),n(stock),d(cashPay),d(stockPay),"legacy",null,status,null,null,"LEGACY",null,null,null,null,"old");
    }
    static Evidence primary(String date, String cash, String stock, String pay) {
        return evidence(1,"FinMind[TaiwanStockDividend]",new Component("FINMIND_PRIMARY_DATE:fixture",2026,d(date),n(stock)!=null&&n(stock).signum()>0?d(date):null,n(cash),n(stock),d(pay),null,null));
    }
    static Day legacyResolve(Key key,Evidence evidence,List<Current> rows) {
        return CanonicalDividendResolver.resolve(new Facts(key,List.of(evidence),rows),NOW,TODAY,true).days().stream()
                .filter(day->day.date().equals(evidence.components().getFirst().cashDate())).findFirst().orElseThrow();
    }
    @ParameterizedTest
    @CsvSource({"00878,2026-08-18,1.01,2026-09-11","00881,2026-01-20,2.65,2026-02-12","00881,2026-08-18,4.6,2026-09-11"})
    void primaryCanConfirmOnlyMissingStockZeroForExistingLegacyActiveCash(String code,String date,String cash,String pay) {
        var active=legacyRow(1,"ACTIVE",date,cash,null,pay,null,null);
        var cancelled=legacyRow(2,"CANCELLED",date,cash,"0",pay,null,null);
        var result=legacyResolve(new Key(code,"台股"),primary(date,cash,"0",pay),List.of(active,cancelled));
        assertThat(result.cash()).isEqualByComparingTo(cash);assertThat(result.stock()).isEqualByComparingTo("0");
        assertThat(result.resolution()).isEqualTo("RESOLVED");assertThat(result.reason()).isEqualTo("保留既有ACTIVE現金，依完整主表確認股票股利為零");
    }
    @Test void legacyConfirmationRejectsMissingActiveContradictionsCanonicalWithdrawalAndUnsupportedEvidence() {
        String date="2026-08-18",pay="2026-09-11";var proof=primary(date,"1.01","0",pay);
        var active=legacyRow(1,"ACTIVE",date,"1.01",null,pay,null,null);
        var cancelled=legacyRow(2,"CANCELLED",date,"1.01","0",pay,null,null);
        List<List<Current>> blocked=List.of(
                List.of(cancelled),
                List.of(legacyRow(1,"ACTIVE",date,"1.010000000001",null,pay,null,null),cancelled),
                List.of(legacyRow(1,"ACTIVE",date,"1.01",".1",pay,null,null),cancelled),
                List.of(legacyRow(1,"ACTIVE",date,"1.01",null,"2026-09-12",null,null),cancelled),
                List.of(legacyRow(1,"ACTIVE",date,"1.01",null,pay,date,null),cancelled),
                List.of(legacyRow(1,"ACTIVE",date,"1.01",null,pay,null,pay),cancelled),
                List.of(active,legacyRow(2,"CANCELLED",date,"1.02","0",pay,null,null)),
                List.of(active,legacyRow(2,"CANCELLED",date,"1.01",".1",pay,null,null)),
                List.of(active,legacyRow(2,"CANCELLED",date,"1.01","0","2026-09-12",null,null)),
                List.of(active,legacyRow(2,"CANCELLED",date,"1.01","0",pay,"2026-08-19",null)),
                List.of(active,cancelled,new Current(3,2026,d(date),null,null,null,null,null,"canonical","canonical-key","CANCELLED",d(date),99L,"RESOLVED",null,null,null,null,"old")),
                List.of(active,legacyRow(2,"CANCELLED",date,"1.01",null,pay,null,null)),
                List.of(new Current(1,2026,d(date),null,n("1.01"),null,d(pay),null,"legacy",null,"ACTIVE",null,1L,"LEGACY",null,null,null,null,"old"),cancelled),
                List.of(active,cancelled,legacyRow(4,"SUPERSEDED",date,"1.02","0",pay,null,null)));
        for(int i=0;i<blocked.size();i++)assertThat(legacyResolve(KEY,proof,blocked.get(i)).resolution()).as("negative current fixture %s",i).isEqualTo("UNRESOLVED");
        for(var unsupported:List.of(primary(date,"1.01",null,pay),primary(date,"1.01",".1",pay),
                primary(date,"1.010000000001","0",pay),primary(date,"1.01","0",null)))
            assertThat(legacyResolve(KEY,unsupported,List.of(active,cancelled)).resolution()).as("unsupported primary %s",unsupported.components()).isEqualTo("UNRESOLVED");
        for(String provider:List.of("FinMind[TaiwanStockDividendResult]","FUBON_SDK","TWSE_TWT48U_ALL+TPEX_EXRIGHT_PREPOST")) {
            var other=new Evidence(1,1,provider,2,proof.basis(),true,"PARTIAL",false,proof.scopeFrom(),proof.scopeTo(),proof.observedAt(),null,"other",proof.components());
            assertThat(legacyResolve(KEY,other,List.of(active,cancelled)).resolution()).isEqualTo("UNRESOLVED");
        }
        var incomplete=new Evidence(1,1,proof.provider(),2,proof.basis(),false,"PARTIAL",false,proof.scopeFrom(),proof.scopeTo(),proof.observedAt(),null,"incomplete",proof.components());
        assertThat(legacyResolve(KEY,incomplete,List.of(active,cancelled)).resolution()).isEqualTo("UNRESOLVED");
        var futureDate=TODAY.plusDays(10);var futurePart=primary(futureDate.toString(),"1.01","0",pay);
        var future=new Evidence(1,1,proof.provider(),2,proof.basis(),true,"PARTIAL",false,TODAY,TODAY.plusDays(45),proof.observedAt(),null,"future",futurePart.components());
        assertThat(legacyResolve(KEY,future,List.of(legacyRow(1,"ACTIVE",futureDate.toString(),"1.01",null,pay,null,null),legacyRow(2,"CANCELLED",futureDate.toString(),"1.01","0",pay,null,null))).resolution()).isEqualTo("UNRESOLVED");
    }
    @Test void exactPureStockTwinStillQualifiesWithoutAnyLegacyCashException() {
        String date="2026-08-18";var proof=evidence(1,"FinMind[TaiwanStockDividend]",new Component("FINMIND_PRIMARY_ID:stock",2026,null,d(date),n("0"),n(".4"),null,null,null));
        var active=new Current(1,2026,null,d(date),n("0"),n(".4"),null,null,"legacy",null,"ACTIVE",null,null,"LEGACY",null,null,null,null,"old");
        var cancelled=legacyRow(2,"CANCELLED",date,"0",".4",null,null,null);
        var day=CanonicalDividendResolver.resolve(new Facts(new Key("2885","台股"),List.of(proof),List.of(active,cancelled)),NOW,TODAY,false).days().getFirst();
        assertThat(day.resolution()).isEqualTo("RESOLVED");assertThat(day.stock()).isEqualByComparingTo(".4");assertThat(day.reason()).doesNotContain("保留既有ACTIVE");
    }
    @Test void precisionLimitsAreExactAndIdentityDoesNotContainAmountOrPayment(){
        assertThat(CanonicalDividendResolver.exact(n("999999999999.123456789012345678"))).isTrue();
        assertThat(CanonicalDividendResolver.exact(n("1000000000000"))).isFalse();
        assertThat(CanonicalDividendResolver.exact(n(".1234567890123456789"))).isFalse();
        assertThat(CanonicalDividendResolver.key(KEY,d("2026-03-17"))).hasSize(64);
    }
}
