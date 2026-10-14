package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

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
}
