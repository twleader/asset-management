package com.steven.assets.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
