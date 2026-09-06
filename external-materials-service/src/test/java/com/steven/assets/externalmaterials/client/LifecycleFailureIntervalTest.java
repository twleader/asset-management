package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleFailureIntervalTest {

    @Test
    void stale_lifecycle_cannot_reopen_or_replace_a_newer_failure_interval() {
        LifecycleFailureInterval interval = new LifecycleFailureInterval();

        assertThat(interval.claim(1L)).isTrue();
        assertThat(interval.claim(3L)).isTrue();
        assertThat(interval.claim(1L)).isFalse();
        assertThat(interval.claim(3L)).isFalse();

        interval.reset(1L);
        assertThat(interval.claim(3L)).isFalse();
        interval.reset(3L);
        assertThat(interval.claim(3L)).isTrue();
    }
}
