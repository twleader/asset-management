package com.steven.assets.service.srpp.report;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
/** Pure-read capture of extra canonical sources. Assets must be the already frozen L0. */
public interface SrppReportExtraCapturePort {
    record ExtraCapture(ObjectNode sources,ObjectNode sourceVector) {
        public ExtraCapture { sources=sources.deepCopy();sourceVector=sourceVector.deepCopy(); }
        public ObjectNode sources(){return sources.deepCopy();}
        public ObjectNode sourceVector(){return sourceVector.deepCopy();}
    }
    ExtraCapture capture(long ownerId,LocalDate date,String reportSlot,Instant capturedAt,JsonNode verifiedModel,JsonNode frozenAssets,JsonNode frozenSources);
}
