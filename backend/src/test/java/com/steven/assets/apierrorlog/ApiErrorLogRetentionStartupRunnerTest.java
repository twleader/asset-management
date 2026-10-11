package com.steven.assets.apierrorlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ApiErrorLogRetentionStartupRunnerTest {
    @Test
    void each_application_runner_invocation_delegatesOnceAndLogsExactSuccessfulOutcome(CapturedOutput output) {
        ApiErrorLogRetentionScheduler scheduler = mock(ApiErrorLogRetentionScheduler.class);
        ApiErrorLogRetentionStartupRunner runner = new ApiErrorLogRetentionStartupRunner(scheduler);
        ApplicationArguments args = mock(ApplicationArguments.class);
        when(scheduler.deleteExpired()).thenReturn(OptionalInt.of(0));

        runner.run(args);
        verify(scheduler, times(1)).deleteExpired();

        when(scheduler.deleteExpired()).thenReturn(OptionalInt.of(3));
        runner.run(args);
        verify(scheduler, times(2)).deleteExpired();
        assertThat(output).contains("API error log startup retention completed deleted=0")
                .contains("API error log startup retention completed deleted=3");
    }

    @Test
    void emptyOutcomeDoesNotLogSuccessfulRetention(CapturedOutput output) {
        ApiErrorLogRetentionScheduler scheduler = mock(ApiErrorLogRetentionScheduler.class);
        when(scheduler.deleteExpired()).thenReturn(OptionalInt.empty());

        new ApiErrorLogRetentionStartupRunner(scheduler).run(mock(ApplicationArguments.class));

        verify(scheduler).deleteExpired();
        assertThat(output).doesNotContain("API error log startup retention completed deleted=");
    }

    @Test
    void transaction_proxy_failure_does_not_stop_application_startup() {
        ApiErrorLogRetentionScheduler scheduler = mock(ApiErrorLogRetentionScheduler.class);
        doThrow(new IllegalStateException("transaction unavailable")).when(scheduler).deleteExpired();

        assertDoesNotThrow(() -> new ApiErrorLogRetentionStartupRunner(scheduler).run(mock(ApplicationArguments.class)));
        verify(scheduler).deleteExpired();
    }
}
