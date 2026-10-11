package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;

/** Same-consumer canonical state only. A missing source cannot be an empty successful state. */
public interface SrppReportConsumerStatePort {
    ObjectNode read(long ownerId, String consumer, LocalDate date, String reportSlot);
}
