package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.repository.UsReportRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.service.srpp.report.SrppReportFixtures;
import com.steven.assets.srpp.SrppJcs;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsReportCalendarTest {
    @Test void exchangeHolidayTableUsesRealExistingAuthorityWithoutExternalReads(){var external=mock(com.steven.assets.service.port.MarketDataReadPort.class);var real=new MarketDataService(external,mock(com.steven.assets.repository.StockRepository.class),mock(com.steven.assets.repository.FubonEtfHoldingsSnapshotRepository.class));var calendar=new UsReportCalendar(real);for(LocalDate day:List.of(LocalDate.of(2026,11,26),LocalDate.of(2026,12,25),LocalDate.of(2027,7,5),LocalDate.of(2028,6,19)))assertThatThrownBy(()->calendar.session(day)).isInstanceOfSatisfying(UsReportProblem.class,p->assertThat(p.code()).isEqualTo("NON_TRADING_DAY"));assertThat(calendar.session(LocalDate.of(2027,12,31)).date()).isEqualTo(LocalDate.of(2027,12,31));assertThat(calendar.session(LocalDate.of(2027,7,2)).close()).isEqualTo(Instant.parse("2027-07-02T20:00:00Z"));verifyNoInteractions(external);}
    @Test void nySessionHandlesDstTaipeiCrossDayAndOfficialEarlyCloses(){var c=UsReportFixtures.calendar();assertThat(c.displayTime(LocalDate.of(2026,10,26))).isEqualTo("21:45");assertThat(c.displayTime(LocalDate.of(2026,11,3))).isEqualTo("22:45");var summer=c.session(LocalDate.of(2026,10,26));assertThat(summer.contains(Instant.parse("2026-10-26T17:00:00Z"))).isTrue();assertThat(Instant.parse("2026-10-26T17:00:00Z").atZone(UsReportCalendar.TPE).toLocalDate()).isEqualTo(LocalDate.of(2026,10,27));assertThat(c.session(LocalDate.of(2026,11,27)).close()).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"));assertThat(c.session(LocalDate.of(2026,12,24)).contains(Instant.parse("2026-12-24T18:00:00Z"))).isFalse();assertThat(c.session(LocalDate.of(2027,7,2)).close()).isEqualTo(Instant.parse("2027-07-02T20:00:00Z"));}
    @Test void reportAndProducerUseHalfOpenWindowsWithoutManualCapture(){var c=UsReportFixtures.calendar();assertThat(c.producerWindow(Instant.parse("2026-10-26T13:40:00Z"))).isTrue();assertThat(c.reportWindow(Instant.parse("2026-10-26T13:40:00Z"))).isTrue();assertThat(c.reportWindow(Instant.parse("2026-10-26T13:45:00Z"))).isTrue();assertThat(c.reportWindow(Instant.parse("2026-10-26T14:45:00Z"))).isFalse();assertThat(c.producerWindow(Instant.parse("2026-10-26T14:45:00Z"))).isFalse();}
    @Test void holidayUnknownYearAndUnknownCacheDoNotBecomeWeekdays(){MarketDataService m=mock(MarketDataService.class);when(m.isTradingDayCachedOnly(eq("美股"),any())).thenReturn(Optional.empty());var c=new UsReportCalendar(m);assertThatThrownBy(()->c.session(LocalDate.of(2026,11,26))).isInstanceOfSatisfying(UsReportProblem.class,p->assertThat(p.code()).isEqualTo("CALENDAR_UNAVAILABLE"));when(m.isTradingDayCachedOnly("美股",LocalDate.of(2026,11,26))).thenReturn(Optional.of(false));assertThatThrownBy(()->c.session(LocalDate.of(2026,11,26))).isInstanceOfSatisfying(UsReportProblem.class,p->assertThat(p.code()).isEqualTo("NON_TRADING_DAY"));assertThatThrownBy(()->c.session(LocalDate.of(2029,1,2))).isInstanceOfSatisfying(UsReportProblem.class,p->assertThat(p.code()).isEqualTo("CALENDAR_UNAVAILABLE"));}

    // t568: producer and latest read windows both open at NY 09:35 (half-open, ends 10:45), in summer (UTC-4) and winter (UTC-5).
    private static final List<String> SUMMER=List.of("2026-10-26T13:34:59Z","2026-10-26T13:35:00Z","2026-10-26T14:44:59Z","2026-10-26T14:45:00Z");
    private static final List<String> WINTER=List.of("2026-11-03T14:34:59Z","2026-11-03T14:35:00Z","2026-11-03T15:44:59Z","2026-11-03T15:45:00Z");
    private static final List<Boolean> INSIDE=List.of(false,true,true,false);
    @Test void producerAndReportWindowsOpenAtNy0935AndCloseAt1045AcrossDst(){
        var c=UsReportFixtures.calendar();
        for(List<String> season:List.of(SUMMER,WINTER))for(int i=0;i<4;i++){Instant at=Instant.parse(season.get(i));
            assertThat(c.producerWindow(at)).as("producer %s",at).isEqualTo(INSIDE.get(i));assertThat(c.reportWindow(at)).as("report %s",at).isEqualTo(INSIDE.get(i));}
        assertThat(Instant.parse(SUMMER.get(1)).atZone(UsReportCalendar.NY).toLocalTime()).isEqualTo(LocalTime.of(9,35));
        assertThat(Instant.parse(WINTER.get(1)).atZone(UsReportCalendar.NY).toLocalTime()).isEqualTo(LocalTime.of(9,35));
        assertThat(c.displayTime(LocalDate.of(2026,10,26))).isEqualTo("21:45");assertThat(c.displayTime(LocalDate.of(2026,11,3))).isEqualTo("22:45");
        Instant saturdayInWindow=Instant.parse("2026-10-31T13:40:00Z");assertThat(saturdayInWindow.atZone(UsReportCalendar.NY).toLocalTime()).isEqualTo(LocalTime.of(9,40));
        assertThatThrownBy(()->c.producerWindow(saturdayInWindow)).isInstanceOfSatisfying(UsReportProblem.class,p->assertThat(p.code()).isEqualTo("NON_TRADING_DAY"));
        assertThatThrownBy(()->c.reportWindow(saturdayInWindow)).isInstanceOfSatisfying(UsReportProblem.class,p->assertThat(p.code()).isEqualTo("NON_TRADING_DAY"));
        Instant saturdayBeforeWindow=Instant.parse("2026-10-31T13:34:59Z");assertThat(c.producerWindow(saturdayBeforeWindow)).isFalse();assertThat(c.reportWindow(saturdayBeforeWindow)).isFalse();
    }

    private static UsReportReadPort.Saved published(Instant capture){
        var publisher=new UsReportPublisher(mock(UsReportRepository.class),mock(UsReportCapturePort.class),UsReportFixtures.AUTHORITY,UsReportFixtures.calendar());
        LocalDate day=capture.atZone(UsReportCalendar.NY).toLocalDate();var base=UsReportFixtures.scope(7,"Codex");
        var scope=new UsReportReadPort.Scope(base.owner(),base.consumer(),base.market(),day,base.slot(),base.policy(),base.swagger(),base.calculator());
        return publisher.publish(scope,UsReportFixtures.frozen(false,capture),UsReportFixtures.state(7,"Codex",true),capture.plusSeconds(1));
    }
    /** Coherent rehash of one published envelope with every validator-checked capture/generation time moved by the same amount. */
    private static String shifted(String content,long seconds,String display){
        ObjectNode out=(ObjectNode)SrppJcs.parseStrict(content);java.util.function.UnaryOperator<String> move=t->Instant.parse(t).plusSeconds(seconds).toString();
        out.put("capturedAt",move.apply(out.path("capturedAt").asText())).put("publishedAt",move.apply(out.path("publishedAt").asText()));if(display!=null)out.put("displayReportTime",display);
        ObjectNode input=(ObjectNode)out.path("inputBundle");input.put("capturedAt",move.apply(input.path("capturedAt").asText()));ObjectNode sources=(ObjectNode)input.path("sources");
        sources.withObject("assets").put("generatedAt",move.apply(sources.path("assets").path("generatedAt").asText()));
        for(var entry:sources.properties()){String digest=SrppJcs.hash(entry.getValue());ObjectNode receipt=(ObjectNode)out.path("sourceVector").path(entry.getKey());receipt.put("sha256",digest).put("revision",digest).put("capturedAt",move.apply(receipt.path("capturedAt").asText()));}
        out.put("inputSnapshotSha256",SrppJcs.hash(input));out.remove("contentSha256");out.put("contentSha256",SrppJcs.hash(out));return SrppJcs.canonicalize(out);
    }
    /** Accepts, or rejects only with the validator's UPSTREAM_INVALID; any other failure propagates. */
    private static boolean accepts(String content){
        try{UsReportEnvelopeValidator.validate(content);return true;}catch(UsReportProblem rejected){assertThat(rejected.code()).isEqualTo("UPSTREAM_INVALID");return false;}
    }
    @Test void envelopeValidatorLowerBoundIsNy0935ByOneSecondDifferenceOnTheSamePackage(){
        var c=UsReportFixtures.calendar();
        for(List<String> season:List.of(SUMMER,WINTER)){
            Instant open=Instant.parse(season.get(1)),last=Instant.parse(season.get(2));String atOpen=published(open).content(),atLast=published(last).content();
            assertThat(accepts(atOpen)).isTrue();assertThat(accepts(shifted(atOpen,0,null))).as("rehash control").isTrue();assertThat(accepts(shifted(atOpen,1,null))).isTrue();
            assertThat(accepts(shifted(atOpen,-1,null))).as("same package moved to 09:34:59").isFalse();
            assertThat(accepts(atLast)).isTrue();assertThat(accepts(shifted(atLast,0,null))).isTrue();assertThat(accepts(shifted(atLast,-1,null))).isTrue();
            assertThat(accepts(shifted(atLast,1,null))).as("same package moved to 10:45:00").isFalse();
            assertThat(SrppJcs.parseStrict(shifted(atOpen,-1,null)).path("capturedAt").asText()).isEqualTo(season.get(0));assertThat(SrppJcs.parseStrict(shifted(atLast,1,null)).path("capturedAt").asText()).isEqualTo(season.get(3));
            Map<String,String> samples=Map.of(season.get(0),shifted(atOpen,-1,null),season.get(1),atOpen,season.get(2),atLast,season.get(3),shifted(atLast,1,null));
            for(var sample:samples.entrySet())assertThat(accepts(sample.getValue())).as("validator == producerWindow at %s",sample.getKey()).isEqualTo(c.producerWindow(Instant.parse(sample.getKey())));
            Instant before=Instant.parse(season.get(0));assertThatThrownBy(()->published(before)).as("publish validates before insert").isInstanceOfSatisfying(UsReportProblem.class,p->assertThat(p.code()).isEqualTo("UPSTREAM_INVALID"));
            String display=SrppJcs.parseStrict(atOpen).path("displayReportTime").asText(),wrong=display.equals("21:45")?"22:45":"21:45";
            assertThat(display).isEqualTo(season==SUMMER?"21:45":"22:45");assertThat(accepts(shifted(atOpen,0,display))).isTrue();assertThat(accepts(shifted(atOpen,0,wrong))).as("display of other DST season").isFalse();
        }
    }

    private static UsReportReadService reader(UsReportReadPort port,String now){
        var owner=mock(CurrentUserContext.class);when(owner.hasUser()).thenReturn(true);when(owner.getStatus()).thenReturn("ACTIVE");when(owner.getEffectiveUserId()).thenReturn(7L);
        var registry=mock(SrppPolicyRegistryService.class);when(registry.find(UsReportFixtures.scope(7,"Codex").policy())).thenReturn(Optional.of(SrppReportFixtures.supported()));
        var swagger=mock(PublishedSwaggerIdentity.class);when(swagger.sha256()).thenReturn(Optional.of(UsReportFixtures.SWAGGER));
        return new UsReportReadService(owner,UsReportFixtures.calendar(),registry,swagger,UsReportFixtures.AUTHORITY,port,Clock.fixed(Instant.parse(now),ZoneOffset.UTC));
    }
    private static void fails(UsReportReadService service,org.springframework.util.MultiValueMap<String,String> q,String code){assertThatThrownBy(()->service.read(q)).isInstanceOfSatisfying(UsReportProblem.class,p->assertThat(p.code()).isEqualTo(code));}
    @Test void latestReadWindowOpensAtNy0935WhileFixedRunStaysUnwindowed(){
        var port=mock(UsReportReadPort.class);
        fails(reader(port,"2026-10-26T13:34:59Z"),UsReportRequestTest.query(),"OUTSIDE_REPORT_WINDOW");verifyNoInteractions(port);
        fails(reader(port,"2026-10-26T13:36:00Z"),UsReportRequestTest.query(),"CONTEXT_NOT_READY");verify(port).latest(UsReportFixtures.scope(7,"Codex"));
        fails(reader(port,"2026-10-26T14:45:00Z"),UsReportRequestTest.query(),"OUTSIDE_REPORT_WINDOW");verifyNoMoreInteractions(port);
        var saved=published(Instant.parse("2026-10-26T13:35:00Z"));when(port.latest(UsReportFixtures.scope(7,"Codex"))).thenReturn(Optional.of(saved));
        assertThat(reader(port,"2026-10-26T13:36:00Z").read(UsReportRequestTest.query())).isEqualTo(saved.content());
        var unknown=UsReportRequestTest.query();unknown.add("calculationRunId",UUID.randomUUID().toString());
        fails(reader(port,"2026-10-26T13:34:59Z"),unknown,"CALCULATION_NOT_FOUND");fails(reader(port,"2026-10-26T14:45:00Z"),unknown,"CALCULATION_NOT_FOUND");
        var fixed=UsReportRequestTest.query();fixed.add("calculationRunId",saved.id().toString());when(port.byId(7L,saved.id())).thenReturn(Optional.of(saved));
        assertThat(reader(port,"2026-10-26T14:45:00Z").read(fixed)).isEqualTo(saved.content());
    }
}
