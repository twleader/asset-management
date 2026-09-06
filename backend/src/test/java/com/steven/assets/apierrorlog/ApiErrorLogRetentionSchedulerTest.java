package com.steven.assets.apierrorlog;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ApiErrorLogRetentionSchedulerTest {
    @Test void repository_failure_is_swallowed_so_next_scheduled_run_can_retry() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        doThrow(new IllegalStateException("database unavailable")).when(repository).deleteExpired();

        new ApiErrorLogRetentionScheduler(repository).deleteExpired();

        verify(repository).deleteExpired();
    }
}
