package com.steven.assets.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessBackupDatabaseProcessTest {

    @TempDir
    Path tempDir;

    @Test
    void dumpAndRestoreUseTheirActualProcessTimeoutCaps() {
        CapturingStarter starter = new CapturingStarter();
        ProcessBackupDatabaseProcess process = process(starter);

        process.dump(tempDir.resolve("backup.dump"));
        process.restore(tempDir.resolve("backup.dump"));
        process.validate(tempDir.resolve("backup.dump"));

        assertThat(starter.commands).extracting(command -> command.getFirst())
                .containsExactly("pg_dump", "pg_restore", "pg_restore");
        assertThat(starter.waitTimeouts)
                .containsExactly(ProcessBackupDatabaseProcess.DUMP_TIMEOUT_SECONDS,
                        ProcessBackupDatabaseProcess.RESTORE_TIMEOUT_SECONDS,
                        ProcessBackupDatabaseProcess.VALIDATE_TIMEOUT_SECONDS);
    }

    @Test
    void dumpTimeoutIsClampedByTheWorkflowCleanupReserve() {
        CapturingStarter starter = new CapturingStarter();
        ProcessBackupDatabaseProcess process = process(starter);

        BackupWorkflowDeadline.within(Duration.ofSeconds(32), () -> {
            process.dump(tempDir.resolve("backup.dump"));
            return null;
        });

        assertThat(starter.waitTimeouts).hasSize(1);
        assertThat(starter.waitTimeouts.getFirst())
                .isPositive()
                .isLessThan(ProcessBackupDatabaseProcess.DUMP_TIMEOUT_SECONDS);
    }

    @Test
    void dumpCommandExcludesExactlyTheFourDataTablesAndKeepsEveryOtherFlag() {
        CapturingStarter starter = new CapturingStarter();
        ProcessBackupDatabaseProcess process = process(starter);

        process.dump(tempDir.resolve("backup.dump"));

        // 刻意以字面字串斷言，不引用 production 常數，避免同義反覆（Requirement 192／Task 498）。
        assertThat(starter.commands.getFirst()).containsExactly(
                "pg_dump",
                "-h", "postgres",
                "-p", "5432",
                "-U", "assets",
                "-d", "assets",
                "--format=custom",
                "--compress=9",
                "--no-owner",
                "--no-acl",
                "--exclude-table-data=public.ai_flow_run",
                "--exclude-table-data=public.ai_flow_event",
                "--exclude-table-data=public.srpp_report_calculation_package",
                "--exclude-table-data=public.srpp_report_calculation_base");
    }

    @Test
    void restoreAndValidateCommandsAreUnchanged() {
        CapturingStarter starter = new CapturingStarter();
        ProcessBackupDatabaseProcess process = process(starter);
        Path archive = tempDir.resolve("backup.dump");

        process.restore(archive);
        process.validate(archive);

        assertThat(starter.commands.get(0)).containsExactly(
                "pg_restore",
                "-h", "postgres",
                "-p", "5432",
                "-U", "assets",
                "-d", "assets",
                "--clean",
                "--if-exists",
                "--no-owner",
                "--no-acl",
                archive.toString());
        assertThat(starter.commands.get(1)).containsExactly("pg_restore", "--list", archive.toString());
    }

    @Test
    void dumpTimeoutThrowsSafeFailureWithFixedMessageAndTerminatesTheProcess() {
        ScriptedProcess scripted = ScriptedProcess.timesOut();
        ProcessBackupDatabaseProcess process = processReturning(scripted);

        assertThatThrownBy(() -> process.dump(tempDir.resolve("backup.dump")))
                .isInstanceOf(BackupSafeFailureException.class)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("pg_dump 執行逾時");
        assertThat(scripted.destroyed).isTrue();
    }

    @Test
    void dumpNonZeroExitThrowsSafeFailureWithFixedMessage() {
        ProcessBackupDatabaseProcess process = processReturning(ScriptedProcess.exitsWith(1));

        assertThatThrownBy(() -> process.dump(tempDir.resolve("backup.dump")))
                .isInstanceOf(BackupSafeFailureException.class)
                .hasMessage("pg_dump 執行失敗");
    }

    @Test
    void dumpStartFailureThrowsSafeFailureWithoutLeakingTheCause() {
        String secret = "START_FAILURE_SENTINEL";
        ProcessBackupDatabaseProcess process = new ProcessBackupDatabaseProcess(
                "postgres", "5432", "assets", "assets", "secret",
                builder -> { throw new IOException(secret); });

        assertThatThrownBy(() -> process.dump(tempDir.resolve("backup.dump")))
                .isInstanceOf(BackupSafeFailureException.class)
                .hasMessage("pg_dump 無法執行")
                .hasMessageNotContaining(secret);
    }

    @Test
    void dumpInterruptThrowsSafeFailureAndRestoresTheInterruptFlag() {
        ScriptedProcess scripted = ScriptedProcess.interrupted();
        ProcessBackupDatabaseProcess process = processReturning(scripted);

        try {
            assertThatThrownBy(() -> process.dump(tempDir.resolve("backup.dump")))
                    .isInstanceOf(BackupSafeFailureException.class)
                    .hasMessage("pg_dump 執行被中斷");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(scripted.destroyed).isTrue();
        } finally {
            // 無論斷言結果如何都清除旗標，避免污染同一執行緒上的後續測試。
            Thread.interrupted();
        }
    }

    @Test
    void insufficientWorkflowDeadlineThrowsSafeFailureBeforeStartingAnyProcess() {
        CapturingStarter starter = new CapturingStarter();
        ProcessBackupDatabaseProcess process = process(starter);

        assertThatThrownBy(() -> BackupWorkflowDeadline.within(Duration.ofSeconds(1), () -> {
            process.dump(tempDir.resolve("backup.dump"));
            return null;
        }))
                .isInstanceOf(BackupSafeFailureException.class)
                .hasMessage("備份流程期限不足，未啟動子行程");
        assertThat(starter.commands).isEmpty();
    }

    @Test
    void commandSecondsRejectsAnExhaustedDeadlineWithSafeFailure() {
        assertThatThrownBy(() -> BackupWorkflowDeadline.within(Duration.ofSeconds(1),
                () -> BackupWorkflowDeadline.commandSeconds(
                        ProcessBackupDatabaseProcess.DUMP_TIMEOUT_SECONDS)))
                .isInstanceOf(BackupSafeFailureException.class)
                .hasMessage("備份流程期限不足，未啟動子行程");
    }

    private static ProcessBackupDatabaseProcess processReturning(Process scripted) {
        return new ProcessBackupDatabaseProcess("postgres", "5432", "assets", "assets", "secret",
                builder -> scripted);
    }

    private static ProcessBackupDatabaseProcess process(CapturingStarter starter) {
        return new ProcessBackupDatabaseProcess("postgres", "5432", "assets", "assets", "secret", starter);
    }

    private static final class CapturingStarter implements ProcessBackupDatabaseProcess.ProcessStarter {
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Long> waitTimeouts = new ArrayList<>();

        @Override
        public Process start(ProcessBuilder builder) {
            commands.add(List.copyOf(builder.command()));
            return new CompletedProcess(waitTimeouts);
        }
    }

    /**
     * 可腳本化結果的 fake Process。JDK 預設 {@code toHandle()} 會丟 UnsupportedOperationException，
     * 而逾時與中斷路徑都會進 terminateAndWait 讀 descendants，故必須覆寫並回傳無子孫、已結束的 handle。
     */
    private static final class ScriptedProcess extends Process {
        private final boolean finishesInTime;
        private final int exitValue;
        private final boolean interruptedWhileWaiting;
        private final ProcessHandle handle = quiescentHandle();
        private boolean destroyed;

        private ScriptedProcess(boolean finishesInTime, int exitValue, boolean interruptedWhileWaiting) {
            this.finishesInTime = finishesInTime;
            this.exitValue = exitValue;
            this.interruptedWhileWaiting = interruptedWhileWaiting;
        }

        static ScriptedProcess timesOut() {
            return new ScriptedProcess(false, 0, false);
        }

        static ScriptedProcess exitsWith(int exitValue) {
            return new ScriptedProcess(true, exitValue, false);
        }

        static ScriptedProcess interrupted() {
            return new ScriptedProcess(true, 0, true);
        }

        private static ProcessHandle quiescentHandle() {
            ProcessHandle handle = mock(ProcessHandle.class);
            when(handle.descendants()).thenAnswer(invocation -> Stream.<ProcessHandle>empty());
            when(handle.isAlive()).thenReturn(false);
            return handle;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exitValue;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (interruptedWhileWaiting) throw new InterruptedException("test interrupt");
            return finishesInTime;
        }

        @Override
        public int exitValue() {
            return exitValue;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }
    }

    private static final class CompletedProcess extends Process {
        private final List<Long> waitTimeouts;

        private CompletedProcess(List<Long> waitTimeouts) {
            this.waitTimeouts = waitTimeouts;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            assertThat(unit).isEqualTo(TimeUnit.SECONDS);
            waitTimeouts.add(timeout);
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            // The fixture completes before termination is needed.
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
