package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.AppUser;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.service.srpp.report.SrppReportAuthority;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import java.time.*;
import static com.steven.assets.service.srpp.us.UsReportReadPort.*;

/** Pure GET. Dependencies deliberately exclude every capture, calculator, publisher and observation writer. */
@Service
public class UsReportReadService {
    private final CurrentUserContext owner;private final UsReportCalendar calendar;private final SrppPolicyRegistryService registry;private final PublishedSwaggerIdentity swagger;private final SrppReportAuthority authority;private final UsReportReadPort repository;private final Clock clock;
    @Autowired public UsReportReadService(CurrentUserContext owner,UsReportCalendar calendar,SrppPolicyRegistryService registry,PublishedSwaggerIdentity swagger,SrppReportAuthority authority,UsReportReadPort repository){this(owner,calendar,registry,swagger,authority,repository,Clock.systemUTC());}
    UsReportReadService(CurrentUserContext owner,UsReportCalendar calendar,SrppPolicyRegistryService registry,PublishedSwaggerIdentity swagger,SrppReportAuthority authority,UsReportReadPort repository,Clock clock){this.owner=owner;this.calendar=calendar;this.registry=registry;this.swagger=swagger;this.authority=authority;this.repository=repository;this.clock=clock;}
    @Transactional(readOnly=true)
    public String read(MultiValueMap<String,String> values){UsReportRequest q=UsReportRequest.parse(values);Instant now=clock.instant();if(!q.sessionDate().equals(now.atZone(UsReportCalendar.NY).toLocalDate()))throw new UsReportProblem("INVALID_REQUEST");calendar.session(q.sessionDate());
        if(!owner.hasUser()||!AppUser.STATUS_ACTIVE.equals(owner.getStatus()))throw new UsReportProblem("OWNER_UNAVAILABLE");long id=owner.getEffectiveUserId();
        var registered=registry.find(q.policyHash());if(registered.isEmpty()||!authority.matches(registered.get()))throw new UsReportProblem("POLICY_UNSUPPORTED");var published=swagger.sha256();if(published.isEmpty())throw new UsReportProblem("CONTEXT_NOT_READY");if(!published.get().equals(q.swaggerHash()))throw new UsReportProblem("SWAGGER_MISMATCH");
        if(q.runId()==null&&!calendar.reportWindow(now))throw new UsReportProblem("OUTSIDE_REPORT_WINDOW");Scope scope=new Scope(id,q.consumer(),q.market(),q.sessionDate(),q.reportSlot(),q.policyHash(),q.swaggerHash(),UsReportPublisher.VERSION);
        Saved saved=q.runId()==null?repository.latest(scope).orElseThrow(()->new UsReportProblem("CONTEXT_NOT_READY")):repository.byId(id,q.runId()).orElseThrow(()->new UsReportProblem("CALCULATION_NOT_FOUND"));if(!saved.scope().equals(scope))throw new UsReportProblem("CONTEXT_IDENTITY_MISMATCH");JsonNode out=UsReportEnvelopeValidator.validate(saved.content()),identity=out.path("identity"),policy=out.path("inputBundle").path("sources").path("policy");
        if(!saved.sourceHash().equals(UsReportFingerprint.hash((ObjectNode)out.path("inputBundle").path("sources"),(ObjectNode)out.path("sourceVector")))||!saved.id().toString().equals(out.path("calculationRunId").asText())||!saved.contentHash().equals(out.path("contentSha256").asText())||!saved.inputHash().equals(out.path("inputSnapshotSha256").asText())||identity.path("ownerUserId").asLong()!=id||!q.consumer().equals(identity.path("consumer").asText())||!scope.date().toString().equals(identity.path("sessionDate").asText())||!scope.slot().equals(identity.path("reportSlot").asText())||!scope.market().equals(identity.path("market").asText())||!scope.policy().equals(identity.path("policyBundleSha256").asText())||!scope.swagger().equals(identity.path("swaggerSha256").asText())||!authority.decision().modelBytes().equals(policy.path("rawModelBytes").asText())||!authority.decision().manifestBytes().equals(policy.path("rawManifestBytes").asText())||Instant.parse(out.path("publishedAt").asText()).isAfter(now)||Instant.parse(out.path("capturedAt").asText()).isAfter(now))throw new UsReportProblem("UPSTREAM_INVALID");return saved.content();
    }
}
