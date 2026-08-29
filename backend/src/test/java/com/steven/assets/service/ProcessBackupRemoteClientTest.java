package com.steven.assets.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ProcessBackupRemoteClientTest {

    private static final String ROOT = "asset-management-backup/\n";

    @TempDir
    Path tempDir;

    @Test
    void sourceFingerprintControlsAtomicReloadAndPreservesRcloneRefreshedWritableBytes() throws Exception {
        Path source = tempDir.resolve("source.conf");
        Path writable = tempDir.resolve("rclone.conf");
        Files.writeString(source, "source-v1");
        AtomicInteger installs = new AtomicInteger();
        ProcessBackupRemoteClient client = client(source, writable, gateSuccessRunner(), countingMover(installs));

        client.initializeSnapshot();
        assertThat(Files.readString(writable)).isEqualTo("source-v1");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(writable))).isEqualTo("rw-------");
        assertThat(installs).hasValue(1);

        Files.writeString(writable, "rclone-refreshed-token");
        client.withVerifiedSession(session -> null);
        assertThat(Files.readString(writable)).isEqualTo("rclone-refreshed-token");
        assertThat(installs).hasValue(1);

        Files.writeString(source, "source-v2");
        client.withVerifiedSession(session -> null);
        assertThat(Files.readString(writable)).isEqualTo("source-v2");
        assertThat(installs).hasValue(2);
    }

    @Test
    void atomicMoveFailureKeepsOldSnapshotAndFingerprintAndCleansTemp() throws Exception {
        Path source = tempDir.resolve("source.conf");
        Path writable = tempDir.resolve("rclone.conf");
        Files.writeString(source, "source-v1");
        AtomicBoolean failMove = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();
        ProcessBackupRemoteClient.SnapshotMover mover = (from, to) -> {
            attempts.incrementAndGet();
            if (failMove.get()) {
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "offline fixture");
            }
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        };
        ProcessBackupRemoteClient client = client(source, writable, gateSuccessRunner(), mover);
        client.initializeSnapshot();

        Files.writeString(source, "source-v2");
        failMove.set(true);
        assertThatThrownBy(() -> client.withVerifiedSession(session -> null))
                .isInstanceOf(BackupRemoteUnavailableException.class);
        assertThat(Files.readString(writable)).isEqualTo("source-v1");
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".rclone.conf-") && name.endsWith(".tmp")))
                    .isEmpty();
        }

        failMove.set(false);
        client.withVerifiedSession(session -> null);
        assertThat(Files.readString(writable)).isEqualTo("source-v2");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void unreadableSourceDoesNotBreakInitializationButWorkflowFailsClosed() {
        Path source = tempDir.resolve("missing.conf");
        Path writable = tempDir.resolve("rclone.conf");
        ProcessBackupRemoteClient client = client(source, writable, gateSuccessRunner(), defaultMover());

        client.initializeSnapshot();

        assertThatThrownBy(() -> client.withVerifiedSession(session -> null))
                .isInstanceOf(BackupRemoteUnavailableException.class)
                .hasMessageContaining("host rclone");
        assertThat(writable).doesNotExist();
    }

    @Test
    void exactRawRootGateRejectsWrongAccountTranscriptsBeforeAnyCryptCall() throws Exception {
        List<String> wrongTranscripts = List.of(
                "Documents/\n",
                "asset-management-backup-old/\n",
                "Documents/\nasset-management-backup-old/\n",
                "");

        for (int index = 0; index < wrongTranscripts.size(); index++) {
            int caseIndex = index;
            Path caseDir = Files.createDirectory(tempDir.resolve("wrong-" + index));
            Path source = caseDir.resolve("source.conf");
            Files.writeString(source, "config");
            AtomicInteger cryptCalls = new AtomicInteger();
            ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
                if (isGate(command)) {
                    return ProcessBackupRemoteClient.CommandResult.success(wrongTranscripts.get(caseIndex));
                }
                cryptCalls.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success("");
            };
            ProcessBackupRemoteClient client = client(
                    source, caseDir.resolve("rclone.conf"), runner, defaultMover());

            assertThatThrownBy(() -> client.withVerifiedSession(session -> {
                session.upload(caseDir.resolve("x.dump"), "manual");
                return null;
            }))
                    .isInstanceOf(BackupRemoteUnavailableException.class)
                    .hasMessageContaining("GoogleDriver:asset-management-backup")
                    .hasMessageContaining("不會自動建立");
            assertThat(cryptCalls).hasValue(0);
        }
    }

    @Test
    void initialGateAuthFailureWithUnchangedSourceDoesNotRetry() throws Exception {
        Path source = writeSource("same-source");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger crypt = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.failure("invalid_grant");
            }
            crypt.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.success("");
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        assertThatThrownBy(() -> client.withVerifiedSession(session -> {
            session.upload(tempDir.resolve("x.dump"), "manual");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(gates).hasValue(1);
        assertThat(crypt).hasValue(0);
    }

    @Test
    void changedSourceAfterInitialGateAuthFailureAllowsOnlyFinalGate() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger installs = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (!isGate(command)) {
                throw new AssertionError("unexpected crypt command");
            }
            if (gates.incrementAndGet() == 1) {
                Files.writeString(source, "source-v2");
                return ProcessBackupRemoteClient.CommandResult.failure("OAuth token fetch failure: invalid_grant");
            }
            return ProcessBackupRemoteClient.CommandResult.success(ROOT);
        };
        ProcessBackupRemoteClient client = client(
                source, tempDir.resolve("writable.conf"), runner, countingMover(installs));

        client.withVerifiedSession(session -> null);

        assertThat(gates).hasValue(2);
        assertThat(installs).hasValue(2);
    }

    @Test
    void changedSourceAfterTargetAuthFailureRunsValidationGateAndRetriesSameTargetOnce() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            }
            if (isUpload(command)) {
                if (uploads.incrementAndGet() == 1) {
                    Files.writeString(source, "source-v2");
                    return ProcessBackupRemoteClient.CommandResult.failure("HTTP 401 unauthorized");
                }
                return ProcessBackupRemoteClient.CommandResult.success("");
            }
            throw new AssertionError("unexpected command " + command);
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        client.withVerifiedSession(session -> {
            session.upload(tempDir.resolve("x.dump"), "manual");
            return null;
        });

        assertThat(gates).hasValue(2);
        assertThat(uploads).hasValue(2);
    }

    @Test
    void targetAuthFailureWithUnchangedSourceDoesNotRunValidationOrRetry() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            }
            uploads.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.failure("invalid_grant");
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        assertThatThrownBy(() -> client.withVerifiedSession(session -> {
            session.upload(tempDir.resolve("x.dump"), "manual");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(gates).hasValue(1);
        assertThat(uploads).hasValue(1);
    }

    @Test
    void nthSequentialListRetryFailureStopsAllLaterFolders() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger manualLists = new AtomicInteger();
        AtomicInteger dailyLists = new AtomicInteger();
        AtomicInteger laterLists = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            }
            String remote = command.get(command.size() - 1);
            if (remote.endsWith("manual/")) {
                manualLists.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success("[]");
            }
            if (remote.endsWith("daily/")) {
                if (dailyLists.incrementAndGet() == 1) {
                    Files.writeString(source, "source-v2");
                }
                return ProcessBackupRemoteClient.CommandResult.failure("HTTP 401 unauthorized");
            }
            laterLists.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.success("[]");
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        assertThatThrownBy(() -> client.withVerifiedSession(session -> {
            session.listJson("manual");
            session.listJson("daily");
            session.listJson("weekly");
            session.listJson("monthly");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(gates).hasValue(2);
        assertThat(manualLists).hasValue(1);
        assertThat(dailyLists).hasValue(2);
        assertThat(laterLists).hasValue(0);
    }

    @Test
    void nthSequentialDeleteRetryFailureStopsAllLaterObjects() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger firstDeletes = new AtomicInteger();
        AtomicInteger targetDeletes = new AtomicInteger();
        AtomicInteger laterDeletes = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            }
            String remote = command.get(command.size() - 1);
            if (remote.endsWith("first.dump")) {
                firstDeletes.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success("");
            }
            if (remote.endsWith("target.dump")) {
                if (targetDeletes.incrementAndGet() == 1) {
                    Files.writeString(source, "source-v2");
                }
                return ProcessBackupRemoteClient.CommandResult.failure("invalid_grant");
            }
            laterDeletes.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.success("");
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        assertThatThrownBy(() -> client.withVerifiedSession(session -> {
            session.delete("manual", "first.dump");
            session.delete("manual", "target.dump");
            session.delete("manual", "later.dump");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(gates).hasValue(2);
        assertThat(firstDeletes).hasValue(1);
        assertThat(targetDeletes).hasValue(2);
        assertThat(laterDeletes).hasValue(0);
    }

    @Test
    void proactiveEntryReloadDoesNotConsumeRecoveryAndLaterAuthCannotRecoverTwice() throws Exception {
        Path source = writeSource("source-v1");
        Path writable = tempDir.resolve("writable.conf");
        AtomicInteger installs = new AtomicInteger();
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger firstTarget = new AtomicInteger();
        AtomicInteger secondTarget = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            }
            String remote = command.get(command.size() - 1);
            if (remote.endsWith("manual/")) {
                if (firstTarget.incrementAndGet() == 1) {
                    Files.writeString(source, "source-v3");
                    return ProcessBackupRemoteClient.CommandResult.failure("invalid_grant");
                }
                return ProcessBackupRemoteClient.CommandResult.success("");
            }
            if (remote.endsWith("daily/")) {
                secondTarget.incrementAndGet();
                Files.writeString(source, "source-v4");
                return ProcessBackupRemoteClient.CommandResult.failure("token expired");
            }
            throw new AssertionError("unexpected command " + command);
        };
        ProcessBackupRemoteClient client = client(source, writable, runner, countingMover(installs));
        client.initializeSnapshot();
        installs.set(0);
        Files.writeString(source, "source-v2");

        assertThatThrownBy(() -> client.withVerifiedSession(session -> {
            session.upload(tempDir.resolve("first.dump"), "manual");
            session.upload(tempDir.resolve("second.dump"), "daily");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(installs).hasValue(2); // entry source-v2 + recovery source-v3；source-v4 不得再 reload
        assertThat(gates).hasValue(2);    // INITIAL + VALIDATION
        assertThat(firstTarget).hasValue(2);
        assertThat(secondTarget).hasValue(1);
        assertThat(Files.readString(writable)).isEqualTo("source-v3");
    }

    @Test
    void timeoutIsUncertainAndNeverRetried() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger gates = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            }
            uploads.incrementAndGet();
            Files.writeString(source, "source-v2");
            return ProcessBackupRemoteClient.CommandResult.timeout();
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        assertThatThrownBy(() -> client.withVerifiedSession(session -> {
            session.upload(tempDir.resolve("x.dump"), "manual");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(gates).hasValue(1);
        assertThat(uploads).hasValue(1);
    }

    @Test
    void tempCleanupFailureCannotReverseSuccessfulProcessOrTriggerRetry(CapturedOutput output) throws Exception {
        List<Path> attemptedDeletes = new ArrayList<>();
        ProcessBackupRemoteClient.DefaultProcessRunner runner =
                new ProcessBackupRemoteClient.DefaultProcessRunner(path -> {
                    attemptedDeletes.add(path);
                    throw new IOException("refresh_token=CLEANUP_SENTINEL");
                });

        try {
            ProcessBackupRemoteClient.CommandResult result = runner.run(
                    List.of("/usr/bin/true"), tempDir.resolve("rclone.conf"), 5);

            assertThat(result.success()).isTrue();
            assertThat(attemptedDeletes).hasSize(2); // 一次 process 的 stdout/stderr；不得自動重跑命令。
            assertThat(output.getOut())
                    .contains("rclone 子行程暫存檔清理失敗；已安排程序結束時重試")
                    .doesNotContain("CLEANUP_SENTINEL")
                    .doesNotContain("refresh_token");
            for (Path path : attemptedDeletes) {
                assertThat(output.getOut()).doesNotContain(path.toString());
            }
        } finally {
            for (Path path : attemptedDeletes) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void concurrentWorkflowsUseOneProcessAtATimeInstallSourceOnceAndDoNotDeadlock() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger installs = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("fixture timed out");
                    }
                }
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            } finally {
                active.decrementAndGet();
            }
        };
        ProcessBackupRemoteClient client = client(
                source, tempDir.resolve("writable.conf"), runner, countingMover(installs));
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    startTogether.await(5, TimeUnit.SECONDS);
                    client.withVerifiedSession(session -> null);
                    return null;
                }));
            }
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(calls).hasValue(1);
            releaseFirst.countDown();
            for (Future<Void> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertThat(maxActive).hasValue(1);
        assertThat(calls).hasValue(2);
        assertThat(installs).hasValue(1);
        assertThat(Files.readString(tempDir.resolve("writable.conf"))).isEqualTo("source-v1");
    }

    @Test
    void childDirectoryMissingIsEmptyOnlyAfterSuccessfulRawGateAndDeleteMissingIsDurableOutcome() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger targets = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            }
            targets.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.failure("directory not found");
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        client.withVerifiedSession(session -> {
            assertThat(session.listJson("monthly")).isEqualTo("[]");
            assertThat(session.delete("manual", "asset_manual_20260829_010101.dump"))
                    .isEqualTo(BackupRemoteClient.DeleteResult.ALREADY_MISSING);
            return null;
        });

        assertThat(gates).hasValue(1);
        assertThat(targets).hasValue(2);
    }

    @Test
    void authClassificationWinsWhenRcloneErrorAlsoMentionsDirectoryMissing() throws Exception {
        Path source = writeSource("source-v1");
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            }
            return ProcessBackupRemoteClient.CommandResult.failure(
                    "directory not found while OAuth token fetch failed: invalid_grant");
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        assertThatThrownBy(() -> client.withVerifiedSession(session -> session.listJson("manual")))
                .isInstanceOf(BackupRemoteUnavailableException.class)
                .hasMessageContaining("授權目前不可用");
    }

    @Test
    void rawSecretBearingAuthErrorIsClassifiedButNeverEscapesException(CapturedOutput output) throws Exception {
        Path source = writeSource("source-v1");
        List<String> sentinels = List.of(
                "ACCESS_SENTINEL", "REFRESH_SENTINEL", "CLIENT_ID_SENTINEL", "CLIENT_SECRET_SENTINEL",
                "TOKEN_JSON_SENTINEL", "PASSWORD_SENTINEL", "PASSWORD2_SENTINEL", "CRYPT_SENTINEL",
                "BEARER_SENTINEL");
        String raw = """
                access_token = ACCESS_SENTINEL
                refresh_token: REFRESH_SENTINEL
                client_id = CLIENT_ID_SENTINEL
                client_secret: CLIENT_SECRET_SENTINEL
                token = {"access_token":"TOKEN_JSON_SENTINEL"}
                password = PASSWORD_SENTINEL
                password2 = PASSWORD2_SENTINEL
                crypt password: CRYPT_SENTINEL
                Authorization: Bearer BEARER_SENTINEL
                invalid_grant
                """;
        ProcessBackupRemoteClient.ProcessRunner runner =
                (command, config, timeout) -> ProcessBackupRemoteClient.CommandResult.failure(raw);
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        BackupRemoteUnavailableException exception = org.junit.jupiter.api.Assertions.assertThrows(
                BackupRemoteUnavailableException.class,
                () -> client.withVerifiedSession(session -> null));

        assertThat(exception.getCause()).isNull();
        assertThat(exception.getStackTrace()).isEmpty();
        assertThat(exception.getMessage())
                .contains("reconnect GoogleDriver:")
                .contains("GoogleDriver:asset-management-backup")
                .contains("source fingerprint 自動 reload")
                .contains("不需要 recreate business-services");
        for (String sentinel : sentinels) {
            assertThat(exception.getMessage()).doesNotContain(sentinel);
            assertThat(output.getAll()).doesNotContain(sentinel);
        }
    }

    private Path writeSource(String content) throws IOException {
        Path source = tempDir.resolve("source.conf");
        Files.writeString(source, content);
        return source;
    }

    private static ProcessBackupRemoteClient client(
            Path source,
            Path writable,
            ProcessBackupRemoteClient.ProcessRunner runner,
            ProcessBackupRemoteClient.SnapshotMover mover) {
        return new ProcessBackupRemoteClient(source, writable, runner, mover);
    }

    private static ProcessBackupRemoteClient.ProcessRunner gateSuccessRunner() {
        return (command, config, timeout) -> {
            if (!isGate(command)) {
                throw new AssertionError("unexpected crypt command " + command);
            }
            return ProcessBackupRemoteClient.CommandResult.success(ROOT);
        };
    }

    private static ProcessBackupRemoteClient.SnapshotMover countingMover(AtomicInteger installs) {
        return (from, to) -> {
            installs.incrementAndGet();
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        };
    }

    private static ProcessBackupRemoteClient.SnapshotMover defaultMover() {
        return (from, to) -> Files.move(
                from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isGate(List<String> command) {
        return command.size() > 1 && "lsf".equals(command.get(1));
    }

    private static boolean isUpload(List<String> command) {
        return command.size() > 1 && "copy".equals(command.get(1))
                && !command.get(2).startsWith(ProcessBackupRemoteClient.CRYPT_BASE);
    }
}
