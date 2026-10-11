package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.fubon.FubonSrppSourceStore;
import org.springframework.stereotype.Component;
import java.time.*;

/** No vendor, Redis, sync service or broker client dependencies. */
@Component
public class PersistedSrppReportFubonCapture implements SrppReportFubonCapturePort {
    private final FubonSrppSourceStore store;private final com.steven.assets.service.fubon.FubonSyncOwnerPort owners;
    public PersistedSrppReportFubonCapture(FubonSrppSourceStore store,com.steven.assets.service.fubon.FubonSyncOwnerPort owners){this.store=store;this.owners=owners;}
    @Override public Capture capture(long owner,LocalDate date,Instant at){
        var current=owners.preflight();if(!current.allowed()||current.ownerId()!=owner)return unavailable(owner,date,"FUBON_OWNER_NOT_CONFIGURED");
        java.util.Optional<FubonSrppSourceStore.Observation> latest;try{latest=store.latest(owner,date);}catch(RuntimeException invalid){return unavailable(owner,date,"FUBON_SOURCE_INVALID");}
        if(latest.isEmpty())return unavailable(owner,date,"FUBON_SOURCE_NOT_CAPTURED");
        var row=latest.get();
        if(row.owner()!=owner||!date.equals(row.date()))return unavailable(owner,date,"FUBON_SOURCE_IDENTITY_MISMATCH");
        if(!"VERIFIED".equals(row.status()))return unavailable(owner,date,row.payload().path("reason").asText("FUBON_LATEST_CAPTURE_FAILED"));
        if(row.capturedAt().isAfter(at)||Duration.between(row.capturedAt(),at).compareTo(Duration.ofSeconds(120))>0)return unavailable(owner,date,"FUBON_SOURCE_STALE");
        ObjectNode payload=row.payload();
        if(!payload.path("complete").asBoolean()||!date.toString().equals(payload.path("queryDate").asText())||row.accountFingerprint()==null||!row.accountFingerprint().equals(payload.path("accountFingerprint").asText())||!row.revision().equals(payload.path("revision").asText()))return unavailable(owner,date,"FUBON_SOURCE_IDENTITY_MISMATCH");
        try{com.steven.assets.service.fubon.FubonSrppSourceContract.validate(payload,date,at);}catch(RuntimeException invalid){return unavailable(owner,date,"FUBON_SOURCE_INVALID");}
        payload.put("ownerUserId",owner);payload.put("tradingDate",date.toString());payload.put("status","VERIFIED");payload.put("coverage","COMPLETE");payload.put("sourceId","fubon-account");payload.put("snapshotId",row.id().toString());
        return new Capture(payload,true);
    }
    private Capture unavailable(long owner,LocalDate date,String reason){
        ObjectNode source=JsonNodeFactory.instance.objectNode();source.put("sourceId","fubon-account");source.put("ownerUserId",owner);source.put("tradingDate",date.toString());source.put("broker","fubon");source.put("status","UNAVAILABLE");source.put("coverage","NONE");source.put("reason",reason);return new Capture(source,false);
    }
}
