package com.steven.assets.service.srpp.report;

import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import org.junit.jupiter.api.*;
import org.springframework.util.LinkedMultiValueMap;
import java.time.*;
import java.util.*;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SrppReportReadServiceTest {
    CurrentUserContext owner;MarketDataService calendar;SrppPolicyRegistryService registry;PublishedSwaggerIdentity swagger;SrppReportRepository repository;SrppReportReadService service;SrppReportAssetRevision revision;
    @BeforeEach void setup(){owner=mock(CurrentUserContext.class);calendar=mock(MarketDataService.class);registry=mock(SrppPolicyRegistryService.class);swagger=mock(PublishedSwaggerIdentity.class);repository=mock(SrppReportRepository.class);revision=mock(SrppReportAssetRevision.class);service=new SrppReportReadService(owner,calendar,registry,swagger,AUTHORITY,repository,revision,Clock.fixed(AT.plusSeconds(1),ZoneOffset.UTC));when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(true));when(owner.hasUser()).thenReturn(true);when(owner.getStatus()).thenReturn("ACTIVE");when(owner.getEffectiveUserId()).thenReturn(7L);when(registry.find(supported().bundleHash())).thenReturn(Optional.of(supported()));when(swagger.sha256()).thenReturn(Optional.of(SWAGGER));}
    static LinkedMultiValueMap<String,String> q(){return SrppReportRepositoryPostgresTest.query("Codex");}
    void error(String code,LinkedMultiValueMap<String,String> q){assertThatThrownBy(()->service.read(q)).isInstanceOfSatisfying(SrppReportProblem.class,p->assertThat(p.code()).isEqualTo(code));}
    @Test void malformedUnknownDuplicateAndBlankQueriesAre400BeforeAnySources(){for(String key:List.of("force","amount","stockCodes","refresh")){var q=q();q.add(key,"1");error("INVALID_REQUEST",q);}var repeated=q();repeated.add("consumer","Codex");error("INVALID_REQUEST",repeated);var blank=q();blank.add("email"," ");error("INVALID_REQUEST",blank);var wrong=q();wrong.set("calculationRunId","bad-id");error("INVALID_REQUEST",wrong);verifyNoInteractions(owner,calendar,registry,swagger,repository);}
    @Test void noonBeforeSlotAndTaipeiDateMismatchAre400(){var noon=q();noon.set("reportSlot","12:00");error("INVALID_REQUEST",noon);var day=q();day.set("tradingDate",DATE.minusDays(1).toString());error("INVALID_REQUEST",day);verifyNoInteractions(owner,calendar,registry,swagger,repository);}
    @Test void calendarFirstUnavailabilityAndHolidayStopAllDownstream(){when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.empty());error("CALENDAR_UNAVAILABLE",q());when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(false));error("NON_TRADING_DAY",q());verifyNoInteractions(owner,registry,swagger,repository);}
    @Test void coldStartNeverCapturesAndWrongSwaggerNeverReadsPackage(){error("CONTEXT_NOT_READY",q());verify(repository).latest(any(),eq("Codex"));clearInvocations(repository);var wrong=q();wrong.set("swaggerSha256","c".repeat(64));error("SWAGGER_MISMATCH",wrong);verifyNoInteractions(repository);}
    @Test void modifiedRegistryTargetMappingFailsClosed(){var modified=new java.util.TreeMap<>(supported().targets());modified.replaceAll((key,value)->java.math.BigDecimal.ZERO);var s=supported();var bad=new com.steven.assets.srpp.SupportedPolicy(s.bundleHash(),s.formulaVersion(),s.calculationPolicySha256(),s.formulaSetSha256(),s.policyDocument(),s.manifest(),modified,s.registeredAt());when(registry.find(s.bundleHash())).thenReturn(Optional.of(bad));error("POLICY_UNSUPPORTED",q());verifyNoInteractions(swagger,repository);}
    @Test void problemsHaveExactlySevenFieldsAndOnlyTwoRetryableCodes(){for(String code:List.of("INVALID_REQUEST","CALCULATION_NOT_FOUND","NON_TRADING_DAY","POLICY_UNSUPPORTED","SWAGGER_MISMATCH","CONTEXT_IDENTITY_MISMATCH","UPSTREAM_INVALID","OWNER_UNAVAILABLE","CALENDAR_UNAVAILABLE","CONTEXT_NOT_READY")){var body=SrppReportProblemCatalog.body(code);assertThat(body).hasSize(7);assertThat(body.get("retryable")).isEqualTo(Set.of("CALENDAR_UNAVAILABLE","CONTEXT_NOT_READY").contains(code));}}

    SrppReportRepository.Package saved() {
        var envelope=SrppSatelliteBuildFixtures.publish(frozen(7,AT,"09:05","static-current"),"09:05",AUTHORITY.model());
        var scope=new SrppReportRepository.Scope(7,DATE,"09:05",supported().bundleHash(),SWAGGER,SrppReportModuleCalculator.VERSION);
        var saved=new SrppReportRepository.Package(UUID.fromString(envelope.path("calculationRunId").asText()),UUID.fromString(envelope.path("sharedCalculationId").asText()),scope,"Codex","unused-for-read",envelope.path("inputSnapshot").path("inputSnapshotSha256").asText(),com.steven.assets.srpp.SrppJcs.canonicalize(envelope),envelope.path("contentSha256").asText(),AT);
        when(repository.latest(scope,"Codex")).thenReturn(Optional.of(saved));return saved;
    }
    @Test void latestOnlyReturnsAnAlreadyValidatedPackageMatchingCompleteCurrentStaticL0(){
        var saved=saved();when(revision.matches(eq(7L),any())).thenReturn(true);
        assertThat(service.read(q())).isEqualTo(saved.content());
        verify(revision).matches(eq(7L),argThat(snapshot->snapshot.path("id").asLong()==9&&snapshot.path("deposits").size()==1));
    }
    @Test void changedSameSnapshotIdOrUnreadableCurrentL0IsTransientContextNotReady(){
        saved();error("CONTEXT_NOT_READY",q());
        when(revision.matches(eq(7L),any())).thenThrow(new IllegalStateException("read unavailable"));error("CONTEXT_NOT_READY",q());
    }
    @Test void explicitImmutableRunDoesNotReadOrRequireTheCurrentlyChangedSnapshot(){
        var saved=saved();when(repository.byId(7,saved.id())).thenReturn(Optional.of(saved));var query=q();query.add("calculationRunId",saved.id().toString());
        assertThat(service.read(query)).isEqualTo(saved.content());verifyNoInteractions(revision);
        query.set("consumer","Claude");error("CONTEXT_IDENTITY_MISMATCH",query);verifyNoInteractions(revision);
    }
}
