package com.steven.assets.service.srpp.decision;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.srpp.report.SrppReportAuthority;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionPolicyAuthorityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String REPORT_ROUTE = "/api/public/srpp/report-calculations";
    private static final List<String> CANDIDATE_FIELDS = List.of(
            "publication_status", "daily_adoption_status", "enabled_for_daily", "report_slots",
            "uses_legacy_api_slot_mapping", "calculator_version", "consumer_verifier",
            "prerequisites", "observations_pending", "fallback_reason");

    @Test
    void d204AuthorityAcceptsCurrentCandidateAndAllowsAdditionalReadOnlyRoutes() throws Exception {
        DecisionPolicyAuthority current = new DecisionPolicyAuthority();
        SrppReportAuthority authority = new SrppReportAuthority(current);
        assertThat(authority.decision().policy().decision()).isEqualTo("D-204");
        assertThat(authority.decision().policy().policyVersion()).isEqualTo("2.53.0");
        assertThat(authority.decision().policy().modelVersion()).isEqualTo("3.22.0");

        ObjectNode model = currentModel();
        ((ArrayNode) orchestrated(model).get("read_only_routes")).add("/api/public/srpp/future-projection");
        assertThatNoException().isThrownBy(() -> new SrppReportAuthority(selfConsistent(model)));
    }

    @Test
    void everyCandidateFieldFailsClosedForMissingWrongTypeAndWrongValue() throws Exception {
        for (String field : CANDIDATE_FIELDS) {
            ObjectNode missing = currentModel();
            candidate(missing).remove(field);
            assertSemanticRejected(missing);

            ObjectNode wrongType = currentModel();
            JsonNode original = candidate(wrongType).get(field);
            if (original.isTextual()) candidate(wrongType).put(field, true);
            else candidate(wrongType).put(field, "WRONG_TYPE");
            assertSemanticRejected(wrongType);

            ObjectNode wrongValue = currentModel();
            JsonNode expected = candidate(wrongValue).get(field);
            if (expected.isTextual()) candidate(wrongValue).put(field, expected.textValue() + "_MUTANT");
            else if (expected.isBoolean()) candidate(wrongValue).put(field, !expected.booleanValue());
            else {
                ArrayNode replacement = candidate(wrongValue).putArray(field);
                for (int index = 0; index < expected.size(); index++) {
                    replacement.add(index == 0 ? "MUTANT" : expected.get(index).textValue());
                }
            }
            assertSemanticRejected(wrongValue);
        }
    }

    @Test
    void extraCandidateFieldsAndEveryOrderedArrayExtraValueFailClosed() throws Exception {
        ObjectNode extraField = currentModel();
        candidate(extraField).put("unexpected", "forbidden");
        assertSemanticRejected(extraField);

        for (String field : List.of("report_slots", "prerequisites", "observations_pending")) {
            ObjectNode extraValue = currentModel();
            ((ArrayNode) candidate(extraValue).get(field)).add("EXTRA");
            assertSemanticRejected(extraValue);
        }
    }

    @Test
    void historicalD201CandidateAndMissingReportRouteCannotPassTheCurrentContract() throws Exception {
        ObjectNode historical = currentModel();
        historical.put("decision", "D-201").put("policy_version", "2.49.0");
        orchestrated(historical).put("decision", "D-201");
        candidate(historical).put("publication_status", "PUBLISHED_SWAGGER_1_25_0");
        assertSemanticRejected(historical);

        ObjectNode noRoute = currentModel();
        ((ArrayNode) orchestrated(noRoute).get("read_only_routes")).removeAll();
        assertSemanticRejected(noRoute);
    }

    @Test
    void duplicateModelOrManifestKeysAreRejectedBeforeTheyCanBecomeAuthority() throws Exception {
        DecisionPolicyAuthority current = new DecisionPolicyAuthority();
        String duplicateModel = current.modelBytes().replaceFirst(
                "\\n  \"model_version\": \"3\\.22\\.0\",",
                "\n  \"model_version\": \"3.22.0\",\n  \"model_version\": \"3.22.0\",");
        assertThatThrownBy(() -> new DecisionPolicyAuthority(duplicateModel,
                selfConsistentManifest(duplicateModel, currentModel())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DecisionPolicyAuthority(current.modelBytes(),
                "{\"manifest\":1,\"manifest\":2}"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertSemanticRejected(ObjectNode model) throws Exception {
        DecisionPolicyAuthority decision = selfConsistent(model);
        assertThat(decision.policy().bundleHash()).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> new SrppReportAuthority(decision))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ObjectNode currentModel() throws Exception {
        return (ObjectNode) MAPPER.readTree(new DecisionPolicyAuthority().modelBytes());
    }

    private static ObjectNode orchestrated(ObjectNode model) {
        return (ObjectNode) model.path("execution_policy").path("llm_orchestrated_api_d197");
    }

    private static ObjectNode candidate(ObjectNode model) {
        return (ObjectNode) orchestrated(model).path("published_read_candidates").path(REPORT_ROUTE);
    }

    private static DecisionPolicyAuthority selfConsistent(ObjectNode model) throws Exception {
        String modelBytes = MAPPER.writeValueAsString(model);
        return new DecisionPolicyAuthority(modelBytes, selfConsistentManifest(modelBytes, model));
    }

    private static String selfConsistentManifest(String modelBytes, ObjectNode model) throws Exception {
        ObjectNode manifest = (ObjectNode) SrppJcs.parseStrict(new DecisionPolicyAuthority().manifestBytes()).deepCopy();
        for (String field : List.of("decision", "policy_version", "model_version")) {
            manifest.put(field, model.path(field).asText());
        }
        manifest.withObject("files").put("assumptions/model_inputs.json", SrppJcs.sha256Hex(modelBytes));
        manifest.remove("POLICY_BUNDLE_SHA256");
        manifest.put("POLICY_BUNDLE_SHA256", SrppJcs.hash(manifest));
        return MAPPER.writeValueAsString(manifest);
    }
}
