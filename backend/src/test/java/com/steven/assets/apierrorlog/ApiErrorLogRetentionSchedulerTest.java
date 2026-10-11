package com.steven.assets.apierrorlog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiErrorLogRetentionSchedulerTest {
    @Test void repository_failure_is_swallowed_so_next_scheduled_run_can_retry() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        doThrow(new IllegalStateException("database unavailable")).when(repository).deleteExpired();

        var result = new ApiErrorLogRetentionScheduler(repository).deleteExpired();

        assertThat(result).isEmpty();
        verify(repository).deleteExpired();
    }
}
