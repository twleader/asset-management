package com.steven.assets.srpp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 163／Task 452.4：manifest digest golden。改動 manifest（或 revision 投影／dataAsOf 規則的版本標記）
 * 必須同時改本 golden 值，並理解既有 registry 列會因此失效。
 */
class SrppFormulaCatalogTest {
    static final String GOLDEN_FORMULA_SET_SHA256 = "35cabe65dccf3479356958477661c1ac79686db229f703d8a2989ec77c8fb901";

    @Test
    void formulaSetSha256IsPinned() {
        assertThat(SrppFormulaCatalog.formulaSetSha256()).isEqualTo(GOLDEN_FORMULA_SET_SHA256);
        assertThat(SrppJcs.sha256Hex(SrppFormulaCatalog.manifestJcs())).isEqualTo(GOLDEN_FORMULA_SET_SHA256);
    }

    @Test
    void manifestNamesTheOnlyVersionAndMarkers() {
        var manifest = SrppFormulaCatalog.manifest();
        assertThat(manifest.path("formulaVersion").asText()).isEqualTo(SrppFormulaCatalog.FORMULA_VERSION);
        assertThat(manifest.path("calculations").path("assets").path("revisionProjection").asText())
                .isEqualTo("SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1");
        assertThat(manifest.path("calculations").path("assets").path("dataAsOf").asText())
                .isEqualTo("MIN_LIVE_STOCK_UPDATED_AT_V1");
        assertThat(SrppFormulaCatalog.manifestJcs()).doesNotContain(" \"").startsWith("{\"calculations\":");
    }

    @Test
    void manifestCopyCannotMutateConstant() {
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode) SrppFormulaCatalog.manifest();
        copy.put("formulaVersion", "X");
        assertThat(SrppFormulaCatalog.formulaSetSha256()).isEqualTo(GOLDEN_FORMULA_SET_SHA256);
        assertThat(SrppFormulaCatalog.manifest().path("formulaVersion").asText()).isEqualTo("ASSET_MGMT_SRPP_V1");
    }
}
