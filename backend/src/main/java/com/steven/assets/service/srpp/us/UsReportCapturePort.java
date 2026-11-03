package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.Set;

/** Background-only read port. HTTP reads never depend on it. */
public interface UsReportCapturePort {
    record Frozen(ObjectNode sources,ObjectNode sourceVector,Instant capturedAt){}
    Frozen capture(long owner,LocalDate sessionDate,Set<String> watchedSymbols,LocalDate earliestBasis);
}
