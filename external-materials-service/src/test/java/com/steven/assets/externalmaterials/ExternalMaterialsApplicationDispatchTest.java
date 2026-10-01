package com.steven.assets.externalmaterials;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalMaterialsApplicationDispatchTest {
    @Test void onlyExplicitBackfillCommandSelectsTheIsolatedOneShotContext() {
        assertThat(ExternalMaterialsApplication.isHistoricalBackfillCommand(new String[]{"--server.port=8080"})).isFalse();
        assertThat(ExternalMaterialsApplication.isHistoricalBackfillCommand(new String[]{"--fubon-historical-backfill"})).isTrue();
        assertThat(ExternalMaterialsApplication.isHistoricalBackfillCommand(new String[]{"--fubon-historical-backfill=run"})).isTrue();
    }
}
