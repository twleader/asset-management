package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;

/** Trusted build-time mirror; accepts only this release's complete source-bound V3 manifest. */
final class SrppFormulaV3Identity {
    static final String VERSION = "ASSET_MGMT_SRPP_V3";
    static final String DIGEST = loadDigest();

    private SrppFormulaV3Identity() {}

    private static String loadDigest() {
        try (var input = SrppFormulaV3Identity.class.getResourceAsStream("/srpp/formula_manifest_v3.json")) {
            if (input == null) throw new IllegalStateException("V3_FORMULA_IDENTITY_MISSING");
            JsonNode manifest = SrppDailyContextResponseValidator.parseStrict(input.readAllBytes());
            if (!VERSION.equals(manifest.path("formulaVersion").asText())
                    || !"SRPP_FORMULA_MANIFEST_V3".equals(manifest.path("schema").asText())
                    || manifest.path("calculations").path("dividendStabilization").path("sourceSha256").size() != 4) {
                throw new IllegalStateException("V3_FORMULA_IDENTITY_INVALID");
            }
            return SrppJcs.sha256Hex(SrppJcs.canonicalize(manifest));
        } catch (Exception invalid) {
            throw new IllegalStateException("V3_FORMULA_IDENTITY_UNAVAILABLE", invalid);
        }
    }
}
