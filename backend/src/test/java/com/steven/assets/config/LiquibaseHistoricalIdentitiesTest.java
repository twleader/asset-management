package com.steven.assets.config;

import org.junit.jupiter.api.Test;

import static com.steven.assets.config.LiquibaseHistoricalFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

class LiquibaseHistoricalIdentitiesTest {
    @Test
    void currentParserPreservesEveryFrozenHistoricalIdentityChecksumAndOrder() throws Exception {
        assertThat(FROZEN.master()).hasSize(187);
        assertThat(FROZEN.retainedHistory()).hasSize(7);
        var current = parse(MASTER, getClass().getClassLoader());
        var history = compatibleHistory(current);
        assertThat(history).containsAll(FROZEN.all());
        assertThat(current).contains(new Migration("v1.55.0-trading-calendar-export-schedule", "steven",
                CALENDAR_LOGICAL_PATH, "9:270eb3f721d219a92a3fdbb06a3f5afb"));
    }
}
