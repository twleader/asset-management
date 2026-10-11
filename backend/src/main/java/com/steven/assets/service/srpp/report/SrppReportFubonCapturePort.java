package com.steven.assets.service.srpp.report;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.srpp.decision.DecisionFacts.Evidence;
import java.time.*;
/** Pure read of already persisted broker evidence. Scope is exactly one Fubon account. */
public interface SrppReportFubonCapturePort {
    record Capture(ObjectNode source, boolean verified) {
        public Capture { source=source.deepCopy(); }
        public ObjectNode source(){return source.deepCopy();}
        public Evidence pendingFor(String symbol,String broker){
            if(!"fubon".equals(broker)||!verified)return new Evidence("UNAVAILABLE",source.path("reason").asText("FUBON_PENDING_UNAVAILABLE"));
            for(var row:source.path("orders"))if(symbol.equals(row.path("stock_no").asText())&&row.path("active").asBoolean())return new Evidence("UNAVAILABLE","PENDING_EXACT_LINK_UNAVAILABLE");
            return new Evidence("PASS","FUBON_ACCOUNT_PENDING_VERIFIED_ZERO");
        }
        public Evidence subaccounts(){return new Evidence("UNAVAILABLE","CATHAY_OFFICIAL_SUBACCOUNT_SOURCE_UNAVAILABLE");}
    }
    Capture capture(long owner,LocalDate date,Instant capturedAt);
}
