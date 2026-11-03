package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.report.SrppReportFingerprint;
import com.steven.assets.srpp.SrppJcs;

/** Reuses the shared source-fingerprint implementation with explicit US transport-clock projection. */
public final class UsReportFingerprint {
    private UsReportFingerprint(){}
    public static String hash(ObjectNode original,ObjectNode receipts){
        ObjectNode sources=original.deepCopy(),vector=receipts.deepCopy();JsonNode state=sources.path("core-state");ObjectNode imported=JsonNodeFactory.instance.objectNode();for(String key:java.util.List.of("consumer","importLedgerSha256","watchPolicySha256"))imported.set(key,state.has(key)?state.get(key):NullNode.instance);sources.remove("core-state");vector.remove("core-state");
        if(sources.path("assets").isObject())((ObjectNode)sources.path("assets")).remove("generatedAt");
        if(sources.path("market").path("radarList").isObject())((ObjectNode)sources.path("market").path("radarList")).remove("generatedAt");
        for(JsonNode symbol:sources.path("market").path("symbols"))if(symbol.path("radar").isObject())((ObjectNode)symbol.path("radar")).remove("generatedAt");
        if(sources.path("us-tactical").isObject())((ObjectNode)sources.path("us-tactical")).remove("asof");
        vector.properties().forEach(e->{ObjectNode row=(ObjectNode)e.getValue();String hash=SrppJcs.hash(sources.path(e.getKey()));row.put("revision",hash).put("sha256",hash);});
        ObjectNode input=JsonNodeFactory.instance.objectNode();input.set("sources",sources);input.set("observationImport",imported);
        return SrppReportFingerprint.hash(input,vector);
    }
}
