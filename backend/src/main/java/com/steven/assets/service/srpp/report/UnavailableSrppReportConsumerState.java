package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.time.LocalDate;

/** No canonical D-174 state feed currently exists; discloses the gap without mutating consumer state. */
@Component
public class UnavailableSrppReportConsumerState implements SrppReportConsumerStatePort {
    public ObjectNode read(long ownerId, String consumer, LocalDate date, String reportSlot) {
        ObjectNode n = JsonNodeFactory.instance.objectNode(); n.put("ownerUserId", ownerId); n.put("consumer", consumer); n.put("tradingDate", date.toString()); n.put("reportSlot", reportSlot); n.put("status", "UNAVAILABLE"); n.put("reason", "CONSUMER_STATE_SOURCE_UNAVAILABLE"); n.putNull("stateBefore"); return n;
    }
}
