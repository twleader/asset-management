package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T1/T2 source receipt and hostile A2 validation, selected by the official *SymbolRuleFacts* glob. */
class SrppSymbolRuleFactsBffTest {
    @Test void acceptsT1FactWithEarlierParseableT2SourceReceiptButRejectsFactT2() {
        SrppProjectionQuery query = new SrppProjectionQuery(SrppProjectionQuery.FACTS, "/internal/public-srpp/symbol-rule-facts", null, "Codex", "2026-10-14", "12:00", "a".repeat(64), "b".repeat(64), "11111111-1111-4111-8111-111111111111", null, null, "0050");
        ObjectNode valid = SrppProjectionQueryTest.factsProjection();
        assertThatCode(() -> PublicSrppProjectionService.validate(query, 7, MediaType.APPLICATION_JSON, valid.toString().getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();
        ((ObjectNode) valid.path("symbols").get(0).path("facts").get(0)).put("asOf", "2026-10-14T02:30:00Z");
        SrppProjectionQueryTest.rehash(valid);
        assertThatThrownBy(() -> PublicSrppProjectionService.validate(query, 7, MediaType.APPLICATION_JSON, valid.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsHostileSourceReceiptT2LaterThanPackageCaptureT1() {
        SrppProjectionQuery query = new SrppProjectionQuery(SrppProjectionQuery.FACTS, "/internal/public-srpp/symbol-rule-facts", null, "Codex", "2026-10-14", "12:00", "a".repeat(64), "b".repeat(64), "11111111-1111-4111-8111-111111111111", null, null, "0050");
        ObjectNode hostile = SrppProjectionQueryTest.factsProjection();
        ((ObjectNode) hostile.path("symbols").get(0).path("facts").get(0).path("receiptRefs").get(0)).put("capturedAt", "2026-10-14T03:00:01Z");
        SrppProjectionQueryTest.rehash(hostile);
        assertThatThrownBy(() -> PublicSrppProjectionService.validate(query, 7, MediaType.APPLICATION_JSON, hostile.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsHostileUnavailableFactThatCarriesAReceipt() {
        SrppProjectionQuery query = new SrppProjectionQuery(SrppProjectionQuery.FACTS, "/internal/public-srpp/symbol-rule-facts", null, "Codex", "2026-10-14", "12:00", "a".repeat(64), "b".repeat(64), "11111111-1111-4111-8111-111111111111", null, null, "0050");
        ObjectNode hostile = SrppProjectionQueryTest.factsProjection();
        ObjectNode fixed = (ObjectNode) hostile.path("symbols").get(0).path("facts").get(8);
        fixed.putArray("receiptRefs").addObject().put("kind", "SOURCE").put("sha256", "e".repeat(64)).put("capturedAt", "2026-10-14T02:30:00Z");
        SrppProjectionQueryTest.rehash(hostile);
        assertThatThrownBy(() -> PublicSrppProjectionService.validate(query, 7, MediaType.APPLICATION_JSON, hostile.toString().getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode outsideProjection() {
        ObjectNode response = SrppProjectionQueryTest.factsProjection();
        ObjectNode row = (ObjectNode) response.path("symbols").get(0);
        row.put("symbol", "2330").put("status", "UNAVAILABLE");
        row.putArray("reasonCodes").add("NOT_IN_PACKAGE_CAPTURE");
        for (JsonNode value : row.path("facts")) {
            ObjectNode fact = (ObjectNode) value;
            fact.putNull("value").put("unit", "NONE").put("status", "UNAVAILABLE");
            fact.putNull("asOf").putNull("sourceRevision");
            fact.putArray("receiptRefs");
            fact.putArray("reasonCodes").add("NOT_IN_PACKAGE_CAPTURE");
        }
        ((ObjectNode) response.path("coverage")).put("unavailableCount", 1).put("notEvaluatedCount", 0);
        SrppProjectionQueryTest.rehash(response);
        return response;
    }

    private static void validateOutside(ObjectNode response) {
        SrppProjectionQuery query = new SrppProjectionQuery(SrppProjectionQuery.FACTS,
                "/internal/public-srpp/symbol-rule-facts", null, "Codex", "2026-10-14", "12:00",
                "a".repeat(64), "b".repeat(64), "11111111-1111-4111-8111-111111111111", null, null, "2330");
        PublicSrppProjectionService.validate(query, 7, MediaType.APPLICATION_JSON,
                response.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test void acceptsExplicitOutsideSymbolWithAllTwelveUnavailableFacts() {
        assertThatCode(() -> validateOutside(outsideProjection())).doesNotThrowAnyException();
    }

    @Test void rejectsOutsideFactsThatClaimValuesSourcesReceiptsOrDerivedOverheat() {
        for (String mutation : List.of("zero", "receipt", "sourceRevision", "asOf", "overheatAvailable", "overheatTrue", "reason")) {
            ObjectNode response = outsideProjection();
            ObjectNode fact = (ObjectNode) response.path("symbols").get(0).path("facts").get(0);
            ObjectNode overheat = (ObjectNode) response.path("symbols").get(0).path("facts").get(7);
            switch (mutation) {
                case "zero" -> fact.put("value", "0");
                case "receipt" -> fact.putArray("receiptRefs").addObject().put("kind", "SOURCE")
                        .put("sha256", "e".repeat(64)).put("capturedAt", "2026-10-14T02:30:00Z");
                case "sourceRevision" -> fact.put("sourceRevision", "e".repeat(64));
                case "asOf" -> fact.put("asOf", "2026-10-14T03:00:00Z");
                case "overheatAvailable" -> overheat.put("status", "AVAILABLE");
                case "overheatTrue" -> overheat.put("value", true);
                case "reason" -> fact.putArray("reasonCodes").add("FROZEN_INPUT_UNAVAILABLE");
            }
            SrppProjectionQueryTest.rehash(response);
            assertThatThrownBy(() -> validateOutside(response)).as(mutation).isInstanceOf(IllegalArgumentException.class);
        }
    }

}
