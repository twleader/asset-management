package com.steven.assets.service;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixed Task408 boundary: 99/100 seconds are readable, 101 is stale. */
class RadarTechnicalResolverFreshnessTest {

    @Test
    void logicalReadBoundaryIsInclusiveAtOneHundredSeconds() {
        Instant oldest = Instant.parse("2026-08-31T00:00:00Z");
        Instant until = oldest.plusSeconds(100);

        assertThat(RadarTechnicalResolver.isFresh(oldest, until, oldest.plusSeconds(99))).isTrue();
        assertThat(RadarTechnicalResolver.isFresh(oldest, until, oldest.plusSeconds(100))).isTrue();
        assertThat(RadarTechnicalResolver.isFresh(oldest, until, oldest.plusSeconds(101))).isFalse();
    }
}
