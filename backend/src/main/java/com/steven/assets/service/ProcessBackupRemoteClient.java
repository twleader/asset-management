package com.steven.assets.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 以 source fingerprint、原子 writable snapshot 與 raw-root gate 保護 DB 備份 remote。
 *
 * <p>所有使用 {@code /tmp/rclone.conf} 的 rclone 子行程都只能從本類的 verified session
 * 進入，並由同一把 fair lock 序列化。原始 stderr 只在本類記憶體內做分類，不會成為例外
 * cause、message 或 log。</p>
 */
@Component
@Slf4j
final class ProcessBackupRemoteClient implements BackupRemoteClient {

    static final Path DEFAULT_CONFIG_SOURCE = Path.of("/etc/rclone/rclone.conf");
    static final Path DEFAULT_CONFIG_WRITABLE = Path.of("/tmp/rclone.conf");
    static final String RAW_REMOTE = "GoogleDriver:";
    static final String EXPECTED_RAW_ROOT = "asset-management-backup/";
    static final String CRYPT_BASE = "gdrive-crypt:backups";
    static final long PROCESS_TIMEOUT_SECONDS = 300;

    private static final Set<String> FOLDERS = Set.of("manual", "daily", "weekly", "monthly");
    private static final List<String> GATE_COMMAND = List.of(
            "rclone", "lsf", "--dirs-only", "--max-depth", "1", RAW_REMOTE);

    private final Path configSource;
    private final Path configWritable;
    private final ProcessRunner processRunner;
    private final SnapshotMover snapshotMover;
    private final ReentrantLock remoteLock = new ReentrantLock(true);

    /** 只記錄最後一次成功原子安裝的 source bytes fingerprint。 */
    private String lastLoadedSourceFingerprint;

    ProcessBackupRemoteClient() {
        this(DEFAULT_CONFIG_SOURCE, DEFAULT_CONFIG_WRITABLE,
                new DefaultProcessRunner(),
                (source, target) -> Files.move(source, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    ProcessBackupRemoteClient(
            Path configSource,
            Path configWritable,
            ProcessRunner processRunner,
            SnapshotMover snapshotMover) {
        this.configSource = Objects.requireNonNull(configSource);
        this.configWritable = Objects.requireNonNull(configWritable);
        this.processRunner = Objects.requireNonNull(processRunner);
        this.snapshotMover = Objects.requireNonNull(snapshotMover);
    }

    /** 啟動時先盡力安裝；失敗只記安全警告，實際 workflow 仍會重新讀 source 並 503 fail closed。 */
    @PostConstruct
    void initializeSnapshot() {
        remoteLock.lock();
        try {
            try {
                reloadIfSourceChanged();
            } catch (BackupRemoteUnavailableException ignored) {
                log.warn("DB 備份 rclone 設定尚不可用；服務維持啟動，下一次備份 remote 操作會 fail closed");
            }
        } finally {
            remoteLock.unlock();
        }
    }

    @Override
    public <T> T withVerifiedSession(SessionWork<T> work) {
        Objects.requireNonNull(work, "work");
        remoteLock.lock();
        try {
            // workflow-entry 主動同步；即使 reload，也不消耗下方 state 的 auth recovery。
            reloadIfSourceChanged();
            WorkflowState state = new WorkflowState();
            runInitialGate(state);
            return work.apply(new VerifiedSession(state));
        } finally {
            remoteLock.unlock();
        }
    }

    private boolean reloadIfSourceChanged() {
        final byte[] sourceBytes;
        try {
            sourceBytes = Files.readAllBytes(configSource);
        } catch (IOException | RuntimeException ignored) {
            throw BackupRemoteUnavailableException.configUnavailable();
        }
        String sourceFingerprint = sha256(sourceBytes);
        if (sourceFingerprint.equals(lastLoadedSourceFingerprint)) {
            return false;
        }

        Path parent = configWritable.getParent();
        if (parent == null) {
            throw BackupRemoteUnavailableException.configUnavailable();
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(parent, "." + configWritable.getFileName() + "-", ".tmp");
            Files.write(temp, sourceBytes);
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            snapshotMover.move(temp, configWritable);
            lastLoadedSourceFingerprint = sourceFingerprint;
            return true;
        } catch (AtomicMoveNotSupportedException ignored) {
            throw BackupRemoteUnavailableException.configUnavailable();
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            throw BackupRemoteUnavailableException.configUnavailable();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // temp 名稱不含 secret；清理失敗也不得把原始 IO cause 帶到外層。
                }
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw BackupRemoteUnavailableException.configUnavailable();
        }
    }

    private void runInitialGate(WorkflowState state) {
        CommandResult initial = execute(GATE_COMMAND);
        if (gatePassed(initial)) {
            return;
        }
        if (isAuthFailure(initial) && !state.recoveryConsumed) {
            if (!reloadIfSourceChanged()) {
                throw BackupRemoteUnavailableException.authenticationUnavailable();
            }
            state.recoveryConsumed = true;
            // FINAL_GATE 關閉自癒；失敗直接映射，不再 reload 或遞迴。
            requireGatePassed(execute(GATE_COMMAND));
            return;
        }
        throw gateFailure(initial);
    }

    private void requireGatePassed(CommandResult result) {
        if (!gatePassed(result)) {
            throw gateFailure(result);
        }
    }

    private boolean gatePassed(CommandResult result) {
        if (!result.success()) {
            return false;
        }
        return result.stdout().lines().anyMatch(EXPECTED_RAW_ROOT::equals);
    }

    private BackupRemoteUnavailableException gateFailure(CommandResult result) {
        if (isAuthFailure(result)) {
            return BackupRemoteUnavailableException.authenticationUnavailable();
        }
        if (result.success() || isMissingFailure(result)) {
            return BackupRemoteUnavailableException.rootMissing();
        }
        return BackupRemoteUnavailableException.remoteUnavailable();
    }

    private TargetResult executeCrypt(
            WorkflowState state,
            List<String> command,
            MissingPolicy missingPolicy) {
        CommandResult original = execute(command);
        TargetResult originalResult = classifyTargetResult(original, missingPolicy);
        if (originalResult != null) {
            return originalResult;
        }

        if (isAuthFailure(original) && !state.recoveryConsumed) {
            if (!reloadIfSourceChanged()) {
                throw BackupRemoteUnavailableException.authenticationUnavailable();
            }
            state.recoveryConsumed = true;
            // VALIDATION_GATE 關閉自癒，通過後只 retry 同一 target 一次。
            requireGatePassed(execute(GATE_COMMAND));
            CommandResult retry = execute(command);
            TargetResult retryResult = classifyTargetResult(retry, missingPolicy);
            if (retryResult != null) {
                return retryResult;
            }
            throw targetFailure(retry);
        }
        throw targetFailure(original);
    }

    /** 成功／允許的 missing 回非 null；其他失敗交由 state machine 判斷是否可 auth recovery。 */
    private TargetResult classifyTargetResult(CommandResult result, MissingPolicy missingPolicy) {
        if (result.success()) {
            return new TargetResult(result.stdout(), false);
        }
        if (missingPolicy == MissingPolicy.ALLOW_AS_EMPTY_OR_ABSENT
                && !isAuthFailure(result) && isMissingFailure(result)) {
            return new TargetResult("", true);
        }
        return null;
    }

    private BackupRemoteUnavailableException targetFailure(CommandResult result) {
        if (isAuthFailure(result)) {
            return BackupRemoteUnavailableException.authenticationUnavailable();
        }
        return BackupRemoteUnavailableException.remoteUnavailable();
    }

    private CommandResult execute(List<String> command) {
        validateWhitelistedCommand(command);
        try {
            return processRunner.run(List.copyOf(command), configWritable, PROCESS_TIMEOUT_SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw BackupRemoteUnavailableException.remoteUnavailable();
        } catch (IOException | RuntimeException ignored) {
            throw BackupRemoteUnavailableException.remoteUnavailable();
        }
    }

    private static void validateWhitelistedCommand(List<String> command) {
        if (GATE_COMMAND.equals(command)) {
            return;
        }
        if (command.size() == 4 && "rclone".equals(command.get(0)) && "copy".equals(command.get(1))) {
            return;
        }
        if (command.size() == 4 && "rclone".equals(command.get(0))
                && "lsjson".equals(command.get(1)) && "--files-only".equals(command.get(2))) {
            return;
        }
        if (command.size() == 3 && "rclone".equals(command.get(0))
                && "deletefile".equals(command.get(1))) {
            return;
        }
        throw new IllegalArgumentException("不允許的 backup rclone 命令");
    }

    private static boolean isAuthFailure(CommandResult result) {
        if (result.timedOut()) {
            return false;
        }
        String raw = (result.stderr() + "\n" + result.stdout()).toLowerCase(Locale.ROOT);
        return raw.contains("invalid_grant")
                || raw.contains("token expired")
                || raw.contains("no refresh token")
                || raw.contains("oauth token")
                || raw.contains("failed to fetch token")
                || raw.contains("couldn't fetch token")
                || raw.contains("failed to get token")
                || raw.contains("failed to refresh token")
                || raw.contains("couldn't refresh token")
                || raw.contains("token refresh failed")
                || raw.contains("http 401")
                || raw.contains("status code 401")
                || raw.contains("error 401")
                || raw.contains("401 unauthorized")
                || raw.contains("unauthorized")
                || raw.contains("invalid credentials")
                || raw.contains("authentication failed")
                || raw.contains("auth error")
                || raw.contains("autherror");
    }

    private static boolean isMissingFailure(CommandResult result) {
        if (result.timedOut()) {
            return false;
        }
        String raw = (result.stderr() + "\n" + result.stdout()).toLowerCase(Locale.ROOT);
        return raw.contains("directory not found")
                || raw.contains("object not found")
                || raw.contains("file not found")
                || raw.contains("couldn't find")
                || raw.contains("not found in");
    }

    private final class VerifiedSession implements Session {

        private final WorkflowState state;

        private VerifiedSession(WorkflowState state) {
            this.state = state;
        }

        @Override
        public void upload(Path localFile, String folder) {
            validateFolder(folder);
            Objects.requireNonNull(localFile, "localFile");
            executeCrypt(state,
                    List.of("rclone", "copy", localFile.toString(), CRYPT_BASE + "/" + folder + "/"),
                    MissingPolicy.FAIL);
        }

        @Override
        public void download(String folder, String filename) {
            validateFolder(folder);
            validateFilename(filename);
            executeCrypt(state,
                    List.of("rclone", "copy", CRYPT_BASE + "/" + folder + "/" + filename, "/tmp/"),
                    MissingPolicy.FAIL);
        }

        @Override
        public String listJson(String folder) {
            validateFolder(folder);
            TargetResult result = executeCrypt(state,
                    List.of("rclone", "lsjson", "--files-only", CRYPT_BASE + "/" + folder + "/"),
                    MissingPolicy.ALLOW_AS_EMPTY_OR_ABSENT);
            return result.missing() ? "[]" : result.stdout();
        }

        @Override
        public DeleteResult delete(String folder, String filename) {
            validateFolder(folder);
            validateFilename(filename);
            TargetResult result = executeCrypt(state,
                    List.of("rclone", "deletefile", CRYPT_BASE + "/" + folder + "/" + filename),
                    MissingPolicy.ALLOW_AS_EMPTY_OR_ABSENT);
            return result.missing() ? DeleteResult.ALREADY_MISSING : DeleteResult.DELETED;
        }
    }

    private static void validateFolder(String folder) {
        if (!FOLDERS.contains(folder)) {
            throw new IllegalArgumentException("不允許的備份資料夾");
        }
    }

    private static void validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\")
                || filename.contains("..") || !filename.endsWith(".dump")) {
            throw new IllegalArgumentException("不合法的備份檔名");
        }
    }

    private static final class WorkflowState {
        private boolean recoveryConsumed;
    }

    private enum MissingPolicy {
        FAIL,
        ALLOW_AS_EMPTY_OR_ABSENT
    }

    private record TargetResult(String stdout, boolean missing) {}

    record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        CommandResult {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }

        boolean success() {
            return !timedOut && exitCode == 0;
        }

        static CommandResult success(String stdout) {
            return new CommandResult(0, stdout, "", false);
        }

        static CommandResult failure(String stderr) {
            return new CommandResult(1, "", stderr, false);
        }

        static CommandResult timeout() {
            return new CommandResult(-1, "", "", true);
        }

        /** 避免 assertion／診斷工具意外把 raw stderr 或 stdout 寫進測試輸出。 */
        @Override
        public String toString() {
            return "CommandResult[exitCode=" + exitCode
                    + ", stdoutLength=" + stdout.length()
                    + ", stderrLength=" + stderr.length()
                    + ", timedOut=" + timedOut + "]";
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        CommandResult run(List<String> command, Path writableConfig, long timeoutSeconds)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface SnapshotMover {
        void move(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface TempFileDeleter {
        void delete(Path path) throws IOException;
    }

    static final class DefaultProcessRunner implements ProcessRunner {

        private final TempFileDeleter tempFileDeleter;

        DefaultProcessRunner() {
            this(Files::deleteIfExists);
        }

        DefaultProcessRunner(TempFileDeleter tempFileDeleter) {
            this.tempFileDeleter = Objects.requireNonNull(tempFileDeleter);
        }

        @Override
        public CommandResult run(List<String> command, Path writableConfig, long timeoutSeconds)
                throws IOException, InterruptedException {
            Path stdoutFile = null;
            Path stderrFile = null;
            Process process = null;
            try {
                stdoutFile = createPrivateTemp("backup-rclone-out-", ".tmp");
                stderrFile = createPrivateTemp("backup-rclone-err-", ".tmp");
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.environment().put("RCLONE_CONFIG", writableConfig.toString());
                builder.redirectOutput(stdoutFile.toFile());
                builder.redirectError(stderrFile.toFile());
                process = builder.start();
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    terminateAndWait(process);
                    return CommandResult.timeout();
                }
                return new CommandResult(
                        process.exitValue(),
                        Files.readString(stdoutFile, StandardCharsets.UTF_8),
                        Files.readString(stderrFile, StandardCharsets.UTF_8),
                        false);
            } catch (InterruptedException interrupted) {
                if (process != null) {
                    terminateAndWait(process);
                }
                throw interrupted;
            } finally {
                // 清理是 process 結果之後的 maintenance；不得把成功 copy 反轉成 503 或觸發重試。
                deleteTempQuietly(stdoutFile);
                deleteTempQuietly(stderrFile);
            }
        }

        private void deleteTempQuietly(Path temp) {
            if (temp == null) {
                return;
            }
            try {
                tempFileDeleter.delete(temp);
            } catch (IOException | RuntimeException ignored) {
                boolean scheduled = false;
                try {
                    temp.toFile().deleteOnExit();
                    scheduled = true;
                } catch (RuntimeException ignoredFallback) {
                    // 固定安全警告；不輸出 temp path、process output 或 exception cause。
                }
                if (scheduled) {
                    log.warn("rclone 子行程暫存檔清理失敗；已安排程序結束時重試");
                } else {
                    log.warn("rclone 子行程暫存檔清理失敗");
                }
            }
        }

        private static Path createPrivateTemp(String prefix, String suffix) throws IOException {
            Path temp = Files.createTempFile(prefix, suffix);
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
                return temp;
            } catch (IOException | UnsupportedOperationException | SecurityException failure) {
                Files.deleteIfExists(temp);
                if (failure instanceof IOException io) {
                    throw io;
                }
                throw new IOException("無法建立安全的子行程暫存檔");
            }
        }

        /** lock 只有在 runner return 後才釋放，所以 timeout／interrupt 也必須先等子行程確實退出。 */
        private static void terminateAndWait(Process process) {
            process.destroyForcibly();
            boolean interrupted = false;
            while (process.isAlive()) {
                try {
                    process.waitFor();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
