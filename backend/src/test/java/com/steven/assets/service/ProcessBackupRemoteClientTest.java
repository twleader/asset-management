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
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final String ROOT = "[{\"Name\":\"asset-management-backup\",\"IsDir\":true,\"ID\":\"folder-id\"}]";

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
    void exactRawParentGateRejectsLookalikeDirectoriesBeforeAnyCryptCall() throws Exception {
        List<String> wrongTranscripts = List.of(
                "[{\"Name\":\"Documents\",\"IsDir\":true}]",
                "[{\"Name\":\"投資理財-old\",\"IsDir\":true}]",
                "[{\"Name\":\"Documents\",\"IsDir\":true},"
                        + "{\"Name\":\"資產管理\",\"IsDir\":true}]",
                "[]");

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
                session.upload(caseDir.resolve("asset_manual_20261002_010101_a.dump"), "manual");
                return null;
            }))
                    .isInstanceOf(BackupRemoteUnavailableException.class)
                    .hasMessageContaining("asset-management-backup")
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
            session.upload(tempDir.resolve("asset_manual_20261002_010101_a.dump"), "manual");
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
            return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
        };
        ProcessBackupRemoteClient client = client(
                source, tempDir.resolve("writable.conf"), runner, countingMover(installs));

        client.withVerifiedSession(session -> null);

        assertThat(gates).hasValue(2);
        assertThat(installs).hasValue(2);
    }

    @Test
    void uploadAuthFailureNeverRetriesUncertainWrite() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
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

        assertThatThrownBy(() -> client.withVerifiedSession(session -> {
            session.upload(tempDir.resolve("asset_manual_20261002_010101_a.dump"), "manual");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(gates).hasValue(1);
        assertThat(uploads).hasValue(1);
    }

    @Test
    void targetAuthFailureWithUnchangedSourceDoesNotRunValidationOrRetry() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            }
            uploads.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.failure("invalid_grant");
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        assertThatThrownBy(() -> client.withVerifiedSession(session -> {
            session.upload(tempDir.resolve("asset_manual_20261002_010101_a.dump"), "manual");
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
                return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            }
            String remote = command.get(command.size() - 1);
            if (remote.endsWith("2026-10-01/")) {
                manualLists.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success("[]");
            }
            if (remote.endsWith("2026-10-02/")) {
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
            session.listJson("2026-10-01");
            session.listJson("2026-10-02");
            session.listJson("2026-10-03");
            session.listJson("2026-10-04");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(gates).hasValue(2);
        assertThat(manualLists).hasValue(1);
        assertThat(dailyLists).hasValue(2);
        assertThat(laterLists).hasValue(0);
    }

    @Test
    void nthSequentialDeleteFailureNeverRetriesAndStopsAllLaterObjects() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger firstDeletes = new AtomicInteger();
        AtomicInteger targetDeletes = new AtomicInteger();
        AtomicInteger laterDeletes = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            }
            String remote = command.get(command.size() - 1);
            if (remote.endsWith("asset_manual_20261002_010101_b.dump")) {
                firstDeletes.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success("");
            }
            if (remote.endsWith("asset_manual_20261002_010101_d.dump")) {
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
            session.delete("manual", "asset_manual_20261002_010101_b.dump");
            session.delete("manual", "asset_manual_20261002_010101_d.dump");
            session.delete("manual", "asset_manual_20261002_010101_e.dump");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(gates).hasValue(1);
        assertThat(firstDeletes).hasValue(1);
        assertThat(targetDeletes).hasValue(1);
        assertThat(laterDeletes).hasValue(0);
    }

    @Test
    void proactiveEntryReloadStillDoesNotRetryUncertainUpload() throws Exception {
        Path source = writeSource("source-v1");
        Path writable = tempDir.resolve("writable.conf");
        AtomicInteger installs = new AtomicInteger();
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger firstTarget = new AtomicInteger();
        AtomicInteger secondTarget = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            }
            String remote = command.get(command.size() - 1);
            if (remote.endsWith("2026-10-02/")) {
                if (firstTarget.incrementAndGet() == 1) {
                    Files.writeString(source, "source-v3");
                    return ProcessBackupRemoteClient.CommandResult.failure("invalid_grant");
                }
                return ProcessBackupRemoteClient.CommandResult.success("");
            }
            if (remote.endsWith("2026-10-03/")) {
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
            session.upload(tempDir.resolve("asset_manual_20261002_010101_b.dump"), "manual");
            session.upload(tempDir.resolve("asset_manual_20261002_010101_c.dump"), "daily");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);

        assertThat(installs).hasValue(1); // only proactive entry source-v2
        assertThat(gates).hasValue(1);    // one complete root gate
        assertThat(firstTarget).hasValue(1);
        assertThat(secondTarget).hasValue(0);
        assertThat(Files.readString(writable)).isEqualTo("source-v2");
    }

    @Test
    void timeoutIsUncertainAndNeverRetried() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger uploads = new AtomicInteger();
        AtomicInteger gates = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            }
            uploads.incrementAndGet();
            Files.writeString(source, "source-v2");
            return ProcessBackupRemoteClient.CommandResult.timeout();
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        assertThatThrownBy(() -> client.withVerifiedSession(session -> {
            session.upload(tempDir.resolve("asset_manual_20261002_010101_a.dump"), "manual");
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
    void timedOutRunnerTerminatesItsChildBeforeReturning() throws Exception {
        Path pidFile = tempDir.resolve("child.pid");
        ProcessBackupRemoteClient.DefaultProcessRunner runner =
                new ProcessBackupRemoteClient.DefaultProcessRunner();

        ProcessBackupRemoteClient.CommandResult result = runner.run(
                List.of("/bin/sh", "-c", "sleep 30 & echo $! > " + pidFile + "; wait"),
                tempDir.resolve("rclone.conf"), 1);

        assertThat(result.timedOut()).isTrue();
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
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
                return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
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
    void remoteLockWaitRespectsWorkflowDeadlineBeforeAnySecondGate() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (!isGate(command)) throw new AssertionError("unexpected command " + command);
            gates.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> client.withVerifiedSession(session -> {
                firstInside.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("session stalled");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return null;
            }));
            assertThat(firstInside.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> BackupWorkflowDeadline.within(Duration.ofMillis(30_050),
                    () -> client.withVerifiedSession(session -> null)))
                    .isInstanceOf(BackupRemoteUnavailableException.class);
            assertThat(gates).hasValue(1);
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void missingDateDirectoryFailsListingAndExactDeleteMayReportMissing() throws Exception {
        Path source = writeSource("source-v1");
        AtomicInteger gates = new AtomicInteger();
        AtomicInteger targets = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                gates.incrementAndGet();
                return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            }
            targets.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.failure("directory not found");
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        client.withVerifiedSession(session -> {
            assertThatThrownBy(() -> session.listJson("2026-10-04"))
                    .isInstanceOf(BackupRemoteUnavailableException.class);
            assertThat(session.delete("manual", "asset_manual_20261002_010101.dump"))
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
                return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            }
            return ProcessBackupRemoteClient.CommandResult.failure(
                    "directory not found while OAuth token fetch failed: invalid_grant");
        };
        ProcessBackupRemoteClient client = client(source, tempDir.resolve("writable.conf"), runner, defaultMover());

        assertThatThrownBy(() -> client.withVerifiedSession(session -> session.listJson("2026-10-01")))
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
                .contains("asset-management-backup")
                .contains("source fingerprint 自動 reload")
                .contains("不需要 recreate business-services");
        for (String sentinel : sentinels) {
            assertThat(exception.getMessage()).doesNotContain(sentinel);
            assertThat(output.getAll()).doesNotContain(sentinel);
        }
    }

    @Test
    void pinnedConfigRejectsOldCryptBackingBeforeAnyRemoteCall() throws Exception {
        Path source = writePinnedSource("GoogleDriver:asset-management-backup");
        AtomicInteger calls = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            calls.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.success("");
        };
        ProcessBackupRemoteClient client = pinnedClient(source, runner);

        assertThatThrownBy(() -> client.withVerifiedSession(session -> null))
                .isInstanceOf(BackupRemoteUnavailableException.class)
                .hasMessageContaining("獨立部署釘選");
        assertThat(calls).hasValue(0);
    }

    @Test
    void pinnedConfigAcceptsExistingCryptWithoutOptionalPassword2OnlyWithMatchingFingerprint() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        Files.writeString(source, Files.readString(source).replace("password2 = obscured-two\n", ""));
        String existingKeyFingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("obscured-one\u0000".getBytes(StandardCharsets.UTF_8)));
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            if (command.get(1).equals("lsjson")) return ProcessBackupRemoteClient.CommandResult.success(
                    "[{\"Name\":\"asset_daily_tw_20260929_010101.dump\",\"IsDir\":false}]");
            if (command.get(1).equals("cat")) return ProcessBackupRemoteClient.CommandResult.success("PGDMP");
            throw new AssertionError("unexpected remote command");
        };
        ProcessBackupRemoteClient allowed = new ProcessBackupRemoteClient(
                source, tempDir.resolve("optional-password2-writable.conf"), runner, defaultMover(),
                new ProcessBackupRemoteClient.DeploymentPins(
                        "folder-id", existingKeyFingerprint, "daily", "asset_daily_tw_20260929_010101.dump"));

        String verified = allowed.withVerifiedSession(session -> "verified");
        assertThat(verified).isEqualTo("verified");
        assertThatThrownBy(() -> pinnedClient(source, runner).withVerifiedSession(session -> null))
                .isInstanceOf(BackupRemoteUnavailableException.class)
                .hasMessageContaining("獨立部署釘選");

        Files.writeString(source, Files.readString(source) + "password2 =   \n");
        ProcessBackupRemoteClient explicitEmpty = new ProcessBackupRemoteClient(
                source, tempDir.resolve("empty-password2-writable.conf"), runner, defaultMover(),
                new ProcessBackupRemoteClient.DeploymentPins(
                        "folder-id", existingKeyFingerprint, "daily", "asset_daily_tw_20260929_010101.dump"));
        Boolean emptyVerified = explicitEmpty.withVerifiedSession(session -> Boolean.TRUE);
        assertThat(emptyVerified).isTrue();

        Files.writeString(source, Files.readString(source).replace("password2 =   \n", "password2 = new-salt\n"));
        assertThatThrownBy(() -> explicitEmpty.withVerifiedSession(session -> null))
                .isInstanceOf(BackupRemoteUnavailableException.class)
                .hasMessageContaining("獨立部署釘選");
    }

    @Test
    void optionalArchivePinMayBeUnsetWhileExactRootAndCryptDatePathStayVerified() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("obscured-one\u0000obscured-two".getBytes(StandardCharsets.UTF_8)));
        List<List<String>> commands = new ArrayList<>();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            commands.add(List.copyOf(command));
            if (isGate(command) && command.getLast().equals("GoogleDriver:"))
                return ProcessBackupRemoteClient.CommandResult.success(ROOT);
            if (command.contains("--dirs-only")) return ProcessBackupRemoteClient.CommandResult.success(
                    "[{\"Name\":\"2026-10-02\",\"IsDir\":true}]");
            if (command.contains("--files-only")) return ProcessBackupRemoteClient.CommandResult.success("[]");
            if (command.get(1).equals("cat")) return ProcessBackupRemoteClient.CommandResult.success("PGDMP");
            throw new AssertionError("unexpected command " + command);
        };
        ProcessBackupRemoteClient client = new ProcessBackupRemoteClient(
                source, tempDir.resolve("optional-anchor-writable.conf"), runner, defaultMover(),
                new ProcessBackupRemoteClient.DeploymentPins("folder-id", hash, null, null));

        client.withVerifiedSession(session -> {
            assertThat(session.listDateDirectoriesJson()).contains("2026-10-02");
            assertThat(session.listJson("2026-10-02")).isEqualTo("[]");
            assertThat(session.readHeader("manual", "asset_manual_20261002_010101.dump")).isEqualTo("PGDMP");
            return null;
        });

        assertThat(commands).contains(
                List.of("rclone", "lsjson", "--dirs-only", "gdrive-crypt:"),
                List.of("rclone", "lsjson", "--files-only", "gdrive-crypt:2026-10-02/"),
                List.of("rclone", "cat", "--count", "5",
                        "gdrive-crypt:2026-10-02/asset_manual_20261002_010101.dump"));
    }

    @Test
    void pinnedConfigRequiresEncryptedFilenamesAndDataAndExplicitPlainDirectoryNames() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("obscured-one\u0000obscured-two".getBytes(StandardCharsets.UTF_8)));
        String valid = Files.readString(source);
        AtomicInteger commands = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            commands.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.success(ROOT);
        };
        for (String invalid : List.of(
                valid.replace("directory_name_encryption = false\n", ""),
                valid.replace("directory_name_encryption = false", "directory_name_encryption = true"),
                valid + "filename_encryption = off\n",
                valid + "filename_encryption = obfuscate\n",
                valid + "no_data_encryption = true\n")) {
            Files.writeString(source, invalid);
            ProcessBackupRemoteClient client = new ProcessBackupRemoteClient(source,
                    tempDir.resolve("invalid-encryption-writable.conf"), runner, defaultMover(),
                    new ProcessBackupRemoteClient.DeploymentPins("folder-id", hash, null, null));
            assertThatThrownBy(() -> client.withVerifiedSession(session -> null))
                    .isInstanceOf(BackupRemoteUnavailableException.class);
        }
        assertThat(commands).hasValue(0);
    }

    @Test
    void pinnedGateRejectsWrongChildIdBeforeCryptCall() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        AtomicInteger cryptCalls = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) {
                return ProcessBackupRemoteClient.CommandResult.success(
                        ROOT.replace("folder-id", "wrong-folder"));
            }
            cryptCalls.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.success("");
        };

        assertThatThrownBy(() -> pinnedClient(source, runner).withVerifiedSession(session -> null))
                .isInstanceOf(BackupRemoteUnavailableException.class)
                .hasMessageContaining("資料夾身分");
        assertThat(cryptCalls).hasValue(0);
    }

    @Test
    void pinnedGateRequiresKnownArchiveDecryptionBeforeAnyWrite() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        AtomicInteger writes = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            if (command.get(1).equals("lsjson")) {
                return ProcessBackupRemoteClient.CommandResult.success(
                        "[{\"Name\":\"asset_daily_tw_20260929_010101.dump\",\"IsDir\":false}]");
            }
            if (command.get(1).equals("cat")) return ProcessBackupRemoteClient.CommandResult.success("garbled");
            writes.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.success("");
        };

        assertThatThrownBy(() -> pinnedClient(source, runner).withVerifiedSession(session -> {
            session.upload(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "manual");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);
        assertThat(writes).hasValue(0);
    }

    @Test
    void pinnedUploadRefusesExistingExactFilename() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        Files.writeString(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "PGDMP");
        AtomicInteger writes = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            if (command.get(1).equals("cat")) return ProcessBackupRemoteClient.CommandResult.success("PGDMP");
            if (command.get(1).equals("lsjson") && command.contains("--files-only")) {
                return ProcessBackupRemoteClient.CommandResult.success(
                        "[{\"Name\":\"asset_daily_tw_20260929_010101.dump\",\"IsDir\":false}]");
            }
            if (command.get(1).equals("lsjson") && command.contains("--stat")) {
                return ProcessBackupRemoteClient.CommandResult.success(
                        "{\"Name\":\"asset_manual_20261002_010101_123.dump\",\"IsDir\":false}");
            }
            writes.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.success("");
        };

        assertThatThrownBy(() -> pinnedClient(source, runner).withVerifiedSession(session -> {
            session.upload(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "manual");
            return null;
        })).isInstanceOf(BackupRemoteUnavailableException.class);
        assertThat(writes).hasValue(0);
    }

    @Test
    void pinnedUploadTimeoutIsUncertainAndNeverRetried() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        Files.writeString(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "PGDMP");
        AtomicInteger writes = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            if (command.get(1).equals("cat")) return ProcessBackupRemoteClient.CommandResult.success("PGDMP");
            if (command.get(1).equals("lsjson") && command.contains("--files-only")) {
                return ProcessBackupRemoteClient.CommandResult.success(
                        "[{\"Name\":\"asset_daily_tw_20260929_010101.dump\",\"IsDir\":false}]");
            }
            if (command.get(1).equals("lsjson") && command.contains("--stat")) {
                return ProcessBackupRemoteClient.CommandResult.failure("object not found");
            }
            writes.incrementAndGet();
            return ProcessBackupRemoteClient.CommandResult.timeout();
        };

        BackupRemoteUnavailableException failure = org.junit.jupiter.api.Assertions.assertThrows(
                BackupRemoteUnavailableException.class,
                () -> pinnedClient(source, runner).withVerifiedSession(session -> {
                    session.upload(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "manual");
                    return null;
                }));

        assertThat(failure.resultUncertain()).isTrue();
        assertThat(writes).hasValue(1);
    }

    @Test
    void sameSizeObjectCreatedAfterAbsenceProbeCannotPassUploadProof() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        Files.writeString(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "PGDMP");
        AtomicInteger writes = new AtomicInteger();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            if (command.get(1).equals("cat")) return ProcessBackupRemoteClient.CommandResult.success("PGDMP");
            if (command.get(1).equals("lsjson") && command.contains("--stat")) {
                return ProcessBackupRemoteClient.CommandResult.failure("object not found");
            }
            if (command.get(1).equals("lsjson") && command.contains("--metadata")) {
                return ProcessBackupRemoteClient.CommandResult.success("[{\"Name\":\"asset_manual_20261002_010101_123.dump\","
                        + "\"IsDir\":false,\"Size\":5,\"Metadata\":{\"backup-upload-nonce\":\"other-upload\"}}]");
            }
            if (command.get(1).equals("lsjson") && command.contains("--files-only")) {
                return ProcessBackupRemoteClient.CommandResult.success("[{\"Name\":\"asset_daily_tw_20260929_010101.dump\",\"IsDir\":false}]");
            }
            if (command.get(1).equals("copyto")) {
                writes.incrementAndGet();
                assertThat(command).contains("--ignore-existing", "--metadata", "--metadata-set");
                return ProcessBackupRemoteClient.CommandResult.success(""); // skipped an existing object
            }
            throw new AssertionError("unexpected command " + command);
        };

        BackupRemoteUnavailableException failure = org.junit.jupiter.api.Assertions.assertThrows(
                BackupRemoteUnavailableException.class,
                () -> pinnedClient(source, runner).withVerifiedSession(session -> {
                    session.upload(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "manual");
                    return null;
                }));

        assertThat(failure.resultUncertain()).isTrue();
        assertThat(writes).hasValue(1);
    }

    @Test
    void uploadSucceedsOnlyWhenExactObjectCarriesThisUploadsNonce() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        Files.writeString(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "PGDMP");
        java.util.concurrent.atomic.AtomicReference<String> writtenNonce = new java.util.concurrent.atomic.AtomicReference<>();
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            if (command.get(1).equals("cat")) return ProcessBackupRemoteClient.CommandResult.success("PGDMP");
            if (command.get(1).equals("lsjson") && command.contains("--stat")) {
                return ProcessBackupRemoteClient.CommandResult.failure("object not found");
            }
            if (command.get(1).equals("copyto")) {
                writtenNonce.set(command.get(5).substring("backup-upload-nonce=".length()));
                return ProcessBackupRemoteClient.CommandResult.success("");
            }
            if (command.get(1).equals("lsjson") && command.contains("--metadata")) {
                return ProcessBackupRemoteClient.CommandResult.success("[{\"Name\":\"asset_manual_20261002_010101_123.dump\","
                        + "\"IsDir\":false,\"Size\":5,\"Metadata\":{\"backup-upload-nonce\":\""
                        + writtenNonce.get() + "\"}}]");
            }
            if (command.get(1).equals("lsjson") && command.contains("--files-only")) {
                return ProcessBackupRemoteClient.CommandResult.success("[{\"Name\":\"asset_daily_tw_20260929_010101.dump\",\"IsDir\":false}]");
            }
            throw new AssertionError("unexpected command " + command);
        };

        pinnedClient(source, runner).withVerifiedSession(session -> {
            session.upload(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "manual");
            return null;
        });

        assertThat(writtenNonce.get()).isNotBlank();
    }

    @Test
    void postWriteMetadataReadFailureRemainsUncertain() throws Exception {
        Path source = writePinnedSource(ProcessBackupRemoteClient.EXPECTED_CRYPT_REMOTE);
        Files.writeString(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "PGDMP");
        ProcessBackupRemoteClient.ProcessRunner runner = (command, config, timeout) -> {
            if (isGate(command)) return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
            if (command.get(1).equals("cat")) return ProcessBackupRemoteClient.CommandResult.success("PGDMP");
            if (command.get(1).equals("lsjson") && command.contains("--stat")) {
                return ProcessBackupRemoteClient.CommandResult.failure("object not found");
            }
            if (command.get(1).equals("lsjson") && command.contains("--metadata")) {
                return ProcessBackupRemoteClient.CommandResult.failure("temporary read error");
            }
            if (command.get(1).equals("lsjson") && command.contains("--files-only")) {
                return ProcessBackupRemoteClient.CommandResult.success("[{\"Name\":\"asset_daily_tw_20260929_010101.dump\",\"IsDir\":false}]");
            }
            if (command.get(1).equals("copyto")) return ProcessBackupRemoteClient.CommandResult.success("");
            throw new AssertionError("unexpected command " + command);
        };

        BackupRemoteUnavailableException failure = org.junit.jupiter.api.Assertions.assertThrows(
                BackupRemoteUnavailableException.class,
                () -> pinnedClient(source, runner).withVerifiedSession(session -> {
                    session.upload(tempDir.resolve("asset_manual_20261002_010101_123.dump"), "manual");
                    return null;
                }));
        assertThat(failure.resultUncertain()).isTrue();
    }

    private Path writePinnedSource(String cryptRemote) throws Exception {
        Path source = tempDir.resolve("pinned.conf");
        Files.writeString(source, "[GoogleDriver]\ntype = drive\nscope = drive\n"
                + "[gdrive-crypt]\ntype = crypt\nremote = " + cryptRemote
                + "\ndirectory_name_encryption = false\npassword = obscured-one\npassword2 = obscured-two\n");
        return source;
    }

    private ProcessBackupRemoteClient pinnedClient(Path source, ProcessBackupRemoteClient.ProcessRunner runner)
            throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("obscured-one\u0000obscured-two".getBytes(StandardCharsets.UTF_8)));
        return new ProcessBackupRemoteClient(source, tempDir.resolve("pinned-writable.conf"), runner,
                defaultMover(), new ProcessBackupRemoteClient.DeploymentPins(
                "folder-id", hash, "daily", "asset_daily_tw_20260929_010101.dump"));
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
        for (String filename : List.of("asset_manual_20261002_010101_a.dump", "asset_manual_20261002_010101_b.dump", "asset_manual_20261002_010101_c.dump")) {
            try {
                Files.writeString(source.getParent().resolve(filename), "PGDMP");
            } catch (IOException ignored) {
                // The affected fixture will fail on its own missing local source.
            }
        }
        // Keep older state-machine fixtures focused on auth/reload: translate the new
        // no-overwrite upload probes to their original single-copy transcript.
        Set<String> uploaded = new HashSet<>();
        Map<String, String> uploadedNonce = new HashMap<>();
        ProcessBackupRemoteClient.ProcessRunner adapter = (command, config, timeout) -> {
            if (command.size() == 4 && "lsjson".equals(command.get(1))
                    && "--stat".equals(command.get(2))) {
                String destination = command.get(3);
                if (!uploaded.contains(destination)) {
                    return ProcessBackupRemoteClient.CommandResult.failure("object not found");
                }
                String name = destination.substring(destination.lastIndexOf('/') + 1);
                return ProcessBackupRemoteClient.CommandResult.success(
                        "{\"Name\":\"" + name + "\",\"IsDir\":false,\"Size\":5}");
            }
            if (command.size() == 5 && "lsjson".equals(command.get(1))
                    && command.contains("--metadata")) {
                String destination = command.get(4);
                StringBuilder rows = new StringBuilder("[");
                for (var entry : uploadedNonce.entrySet()) {
                    if (!entry.getKey().startsWith(destination)) continue;
                    if (rows.length() > 1) rows.append(',');
                    String name = entry.getKey().substring(destination.length());
                    rows.append("{\"Name\":\"").append(name)
                            .append("\",\"IsDir\":false,\"Size\":5,\"Metadata\":{\"backup-upload-nonce\":\"")
                            .append(entry.getValue()).append("\"}}");
                }
                return ProcessBackupRemoteClient.CommandResult.success(rows.append(']').toString());
            }
            if (command.size() == 8 && "copyto".equals(command.get(1))
                    && "--ignore-existing".equals(command.get(2))) {
                String destination = command.get(7);
                String parent = destination.substring(0, destination.lastIndexOf('/') + 1);
                ProcessBackupRemoteClient.CommandResult result = runner.run(
                        List.of("rclone", "copy", command.get(6), parent), config, timeout);
                if (result.success()) {
                    uploaded.add(destination);
                    uploadedNonce.put(destination, command.get(5).substring("backup-upload-nonce=".length()));
                }
                return result;
            }
            return runner.run(command, config, timeout);
        };
        return new ProcessBackupRemoteClient(source, writable, adapter, mover);
    }

    private static ProcessBackupRemoteClient.ProcessRunner gateSuccessRunner() {
        return (command, config, timeout) -> {
            if (!isGate(command)) {
                throw new AssertionError("unexpected crypt command " + command);
            }
            return ProcessBackupRemoteClient.CommandResult.success(gateTranscript(command));
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
        return command.size() > 3 && "lsjson".equals(command.get(1)) && "--dirs-only".equals(command.get(2));
    }

    private static String gateTranscript(List<String> command) { return ROOT; }

    private static boolean isUpload(List<String> command) {
        return command.size() > 1 && "copy".equals(command.get(1))
                && !command.get(2).startsWith(ProcessBackupRemoteClient.CRYPT_BASE);
    }
}
