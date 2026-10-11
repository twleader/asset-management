package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.model.AppUser;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.repository.SrppReportRepository.Scope;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.srpp.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import java.time.*;
import java.util.*;

/** Pure GET of saved FINAL. Dependencies cannot capture, compute, publish, refresh, or write sources. */
@Service
public class SrppReportReadService {
    private final CurrentUserContext owner;
    private final MarketDataService calendar;
    private final SrppPolicyRegistryService registry;
    private final PublishedSwaggerIdentity swagger;
    private final SrppReportAuthority authority;
    private final SrppReportRepository repository;
    private final Clock clock;
    @Autowired
    public SrppReportReadService(CurrentUserContext owner, MarketDataService calendar, SrppPolicyRegistryService registry,
                                 PublishedSwaggerIdentity swagger, SrppReportAuthority authority, SrppReportRepository repository) {
        this(owner,calendar,registry,swagger,authority,repository,Clock.system(SrppTime.TW_ZONE));
    }
    SrppReportReadService(CurrentUserContext owner, MarketDataService calendar, SrppPolicyRegistryService registry,
                          PublishedSwaggerIdentity swagger, SrppReportAuthority authority, SrppReportRepository repository,Clock clock) {
        this.owner=owner;this.calendar=calendar;this.registry=registry;this.swagger=swagger;this.authority=authority;this.repository=repository;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public String read(MultiValueMap<String,String> values) {
        SrppReportRequest q=SrppReportRequest.parse(values);ZonedDateTime now=ZonedDateTime.now(clock.withZone(SrppTime.TW_ZONE));
        if(!q.date().equals(now.toLocalDate())||now.toLocalTime().isBefore(LocalTime.parse(q.slot())))throw new SrppReportProblem("INVALID_REQUEST");
        Optional<Boolean> flag;try{flag=calendar.isTwTradingDayCachedOnly(q.date());}catch(RuntimeException unavailable){flag=Optional.empty();}
        if(flag==null||flag.isEmpty())throw new SrppReportProblem("CALENDAR_UNAVAILABLE");if(!flag.get())throw new SrppReportProblem("NON_TRADING_DAY");
        if(!owner.hasUser()||!AppUser.STATUS_ACTIVE.equals(owner.getStatus()))throw new SrppReportProblem("OWNER_UNAVAILABLE");long ownerId=owner.getEffectiveUserId();
        Optional<SupportedPolicy> policy=registry.find(q.policyHash());if(policy.isEmpty()||!authority.matches(policy.get()))throw new SrppReportProblem("POLICY_UNSUPPORTED");
        Optional<String> published=swagger.sha256();if(published.isEmpty())throw new SrppReportProblem("CONTEXT_NOT_READY");if(!published.get().equals(q.swaggerHash()))throw new SrppReportProblem("SWAGGER_MISMATCH");
        Scope scope=new Scope(ownerId,q.date(),q.slot(),q.policyHash(),q.swaggerHash(),SrppReportModuleCalculator.VERSION);
        var saved=q.runId()==null?repository.latest(scope,q.consumer()).orElseThrow(()->new SrppReportProblem("CONTEXT_NOT_READY")):repository.byId(ownerId,q.runId()).orElseThrow(()->new SrppReportProblem("CALCULATION_NOT_FOUND"));
        if(!saved.scope().equals(scope)||!q.consumer().equals(saved.consumer()))throw new SrppReportProblem("CONTEXT_IDENTITY_MISMATCH");
        JsonNode verified=SrppReportEnvelopeValidator.validate(saved.content());
        JsonNode contentIdentity=verified.path("identity");
        if(!scope.date().toString().equals(contentIdentity.path("tradingDate").asText())||!scope.slot().equals(contentIdentity.path("reportSlot").asText())||!scope.policy().equals(contentIdentity.path("policyBundleSha256").asText())||!scope.swagger().equals(contentIdentity.path("swaggerSha256").asText())||!scope.calculator().equals(verified.path("authorityRevision").path("calculatorVersion").asText()))throw new SrppReportProblem("UPSTREAM_INVALID");
        if(!saved.id().toString().equals(verified.path("calculationRunId").asText())||!saved.sharedId().toString().equals(verified.path("sharedCalculationId").asText())||!saved.contentHash().equals(verified.path("contentSha256").asText())||!saved.inputHash().equals(verified.path("inputSnapshot").path("inputSnapshotSha256").asText())||verified.path("identity").path("ownerUserId").asLong()!=ownerId||!verified.path("identity").path("consumer").asText().equals(q.consumer())||!verified.path("authorityRevision").path("modelInputsSha256").asText().equals(authority.decision().policy().modelHash())||!verified.path("authorityRevision").path("policyManifestSha256").asText().equals(authority.decision().policy().manifestHash())||!verified.path("authorityRevision").path("targetAllocationSha256").asText().equals(authority.targetsHash()))throw new SrppReportProblem("UPSTREAM_INVALID");
        return saved.content();
    }
}
