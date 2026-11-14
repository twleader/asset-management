package com.steven.assets.service.srpp.decision;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.srpp.report.SrppReportAuthority;
import com.steven.assets.srpp.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class D218FormulaAuthorityTest {
    private static SrppReportAuthority authority(boolean d218) throws Exception {
        DecisionPolicyAuthority original = new DecisionPolicyAuthority();
        ObjectNode model = (ObjectNode) SrppJcs.parseStrict(original.modelBytes());
        ObjectNode execution = (ObjectNode) model.path("execution_policy");
        if (d218) execution.putObject("high_dividend_stabilization_d218").put("enabled", true);
        else execution.remove("high_dividend_stabilization_d218");
        String bytes = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(model);
        ObjectNode manifest = (ObjectNode) SrppJcs.parseStrict(original.manifestBytes());
        for (String key : List.of("decision", "policy_version", "model_version")) manifest.put(key, model.path(key).asText());
        manifest.withObject("files").put("assumptions/model_inputs.json", SrppJcs.sha256Hex(bytes));
        manifest.remove("POLICY_BUNDLE_SHA256");
        manifest.put("POLICY_BUNDLE_SHA256", SrppJcs.hash(manifest));
        return new SrppReportAuthority(new DecisionPolicyAuthority(bytes, SrppJcs.canonicalize(manifest)));
    }

    private static SupportedPolicy policy(SrppReportAuthority authority, String version, String formulaHash, com.fasterxml.jackson.databind.JsonNode formula) {
        var doc = SrppPolicyDocument.parse(SrppJcs.canonicalize(
                com.steven.assets.service.srpp.report.SrppReportModuleCalculator.registryDocument(authority.model())));
        return new SupportedPolicy(authority.decision().policy().bundleHash(), version,
                SrppJcs.hash(doc.document()), formulaHash, doc.document(), formula, doc.targets(), Instant.EPOCH);
    }

    @Test
    void d218RejectsWrongFormulaVersionManifestOrDigestEvenWithTheSameBundleAndTargets() throws Exception {
        var authority = authority(true);
        String v3 = SrppFormulaCatalog.FORMULA_VERSION_V3;
        String v2 = SrppFormulaCatalog.FORMULA_VERSION_V2;
        assertThat(authority.matches(policy(authority, v3, SrppFormulaCatalog.formulaSetSha256(v3), SrppFormulaCatalog.manifest(v3)))).isTrue();
        assertThat(authority.matches(policy(authority, v2, SrppFormulaCatalog.formulaSetSha256(v2), SrppFormulaCatalog.manifest(v2)))).isFalse();
        assertThat(authority.matches(policy(authority, v3, "0".repeat(64), SrppFormulaCatalog.manifest(v3)))).isFalse();
        assertThat(authority.matches(policy(authority, v3, SrppFormulaCatalog.formulaSetSha256(v3), SrppFormulaCatalog.manifest(v2)))).isFalse();
    }

    @Test
    void modelWithoutD218KeepsItsHistoricalV2Contract() throws Exception {
        var authority = authority(false);
        String v2 = SrppFormulaCatalog.FORMULA_VERSION_V2;
        assertThat(authority.matches(policy(authority, v2, SrppFormulaCatalog.formulaSetSha256(v2), SrppFormulaCatalog.manifest(v2)))).isTrue();
    }
}
