package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    static final String EXPECTED_RAW_ROOT = "asset-management-backup";
    static final String EXPECTED_CRYPT_REMOTE = "GoogleDriver,root_folder_id=folder-id:"; // fixture only
    static final String CRYPT_BASE = "gdrive-crypt:";
    static final long PROCESS_TIMEOUT_SECONDS = 300;

    private static final Set<java.time.LocalDate> MOVED_DATES = Set.of(
            java.time.LocalDate.of(2026, 9, 29), java.time.LocalDate.of(2026, 9, 30),
            java.time.LocalDate.of(2026, 10, 1));
    private static final List<String> GATE_COMMAND = List.of(
            "rclone", "lsjson", "--dirs-only", "--max-depth", "1", RAW_REMOTE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicBoolean PROCESS_CLEANUP_UNSAFE = new AtomicBoolean();

    private final Path configSource;
    private final Path configWritable;
    private final ProcessRunner processRunner;
    private final SnapshotMover snapshotMover;
    private final DeploymentPins pins;
    private final ReentrantLock remoteLock = new ReentrantLock(true);

    /** 只記錄最後一次成功原子安裝的 source bytes fingerprint。 */
    private String lastLoadedSourceFingerprint;

    ProcessBackupRemoteClient() {
        this(DEFAULT_CONFIG_SOURCE, DEFAULT_CONFIG_WRITABLE,
                new DefaultProcessRunner(),
                (source, target) -> Files.move(source, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                DeploymentPins.fromEnvironment());
    }

    ProcessBackupRemoteClient(
            Path configSource,
            Path configWritable,
            ProcessRunner processRunner,
            SnapshotMover snapshotMover) {
        this(configSource, configWritable, processRunner, snapshotMover, null);
    }

    ProcessBackupRemoteClient(
            Path configSource,
            Path configWritable,
            ProcessRunner processRunner,
            SnapshotMover snapshotMover,
            DeploymentPins pins) {
        this.configSource = Objects.requireNonNull(configSource);
        this.configWritable = Objects.requireNonNull(configWritable);
        this.processRunner = Objects.requireNonNull(processRunner);
        this.snapshotMover = Objects.requireNonNull(snapshotMover);
        this.pins = pins;
    }

    /** 啟動時先盡力安裝；失敗只記安全警告，實際 workflow 仍會重新讀 source 並 503 fail closed。 */
    @PostConstruct
    void initializeSnapshot() {
        try {
            BackupWorkflowDeadline.lockWithinDeadline(remoteLock);
        } catch (IllegalStateException ignored) {
            log.warn("DB 備份 rclone 設定初始化鎖逾時；下一次備份 remote 操作會 fail closed");
            return;
        }
        try {
            if (PROCESS_CLEANUP_UNSAFE.get()) throw BackupRemoteUnavailableException.remoteUnavailable();
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
        try {
            BackupWorkflowDeadline.lockWithinDeadline(remoteLock);
        } catch (IllegalStateException ignored) {
            throw BackupRemoteUnavailableException.remoteUnavailable();
        }
        try {
            if (PROCESS_CLEANUP_UNSAFE.get()) throw BackupRemoteUnavailableException.remoteUnavailable();
            // workflow-entry 主動同步；即使 reload，也不消耗下方 state 的 auth recovery。
            reloadIfSourceChanged();
            verifyInstalledConfig();
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

    private void verifyInstalledConfig() {
        if (pins == null) return; // package-private fixture constructor; production always supplies pins
        if (!pins.valid()) throw BackupRemoteUnavailableException.configUnavailable();
        final Map<String, Map<String, String>> sections;
        try {
            sections = parseConfig(Files.readString(configWritable, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            throw BackupRemoteUnavailableException.configUnavailable();
        }
        Map<String, String> drive = sections.getOrDefault("GoogleDriver", Map.of());
        Map<String, String> crypt = sections.getOrDefault("gdrive-crypt", Map.of());
        String secretText = crypt.getOrDefault("password", "") + "\u0000" + crypt.getOrDefault("password2", "");
        if (!"drive".equals(drive.get("type")) || !"drive".equals(drive.get("scope"))
                || !drive.getOrDefault("root_folder_id", "").isBlank()
                || !"crypt".equals(crypt.get("type"))
                || !("GoogleDriver,root_folder_id=" + pins.folderId() + ":").equals(crypt.get("remote"))
                || !"false".equals(crypt.get("directory_name_encryption"))
                || !"standard".equals(crypt.getOrDefault("filename_encryption", "standard"))
                || !"false".equals(crypt.getOrDefault("no_data_encryption", "false"))
                || crypt.getOrDefault("password", "").isBlank()
                || !pins.cryptConfigSha256().equals(sha256(secretText.getBytes(StandardCharsets.UTF_8)))) {
            throw BackupRemoteUnavailableException.configUnavailable();
        }
    }

    private static Map<String, Map<String, String>> parseConfig(String contents) {
        Map<String, Map<String, String>> sections = new HashMap<>();
        Map<String, String> current = null;
        for (String raw : contents.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                String name = line.substring(1, line.length() - 1).trim();
                current = new HashMap<>();
                if (sections.putIfAbsent(name, current) != null) throw new IllegalArgumentException("duplicate section");
            } else {
                int separator = line.indexOf('=');
                if (current == null || separator < 1) throw new IllegalArgumentException("invalid config");
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (current.putIfAbsent(key, value) != null) throw new IllegalArgumentException("duplicate key");
            }
        }
        return sections;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw BackupRemoteUnavailableException.configUnavailable();
        }
    }

    private void runInitialGate(WorkflowState state) {
        CommandResult initial = gateAttempt();
        if (initial == null) {
            requireKnownArchive(state);
            return;
        }
        if (isAuthFailure(initial) && !state.recoveryConsumed) {
            if (!reloadIfSourceChanged()) {
                throw BackupRemoteUnavailableException.authenticationUnavailable();
            }
            state.recoveryConsumed = true;
            verifyInstalledConfig();
            CommandResult retry = gateAttempt();
            if (retry != null) throw gateFailure(retry);
            requireKnownArchive(state);
            return;
        }
        throw gateFailure(initial);
    }

    /** One complete My Drive root listing proves the exact active child and its pinned ID. */
    private CommandResult gateAttempt() {
        CommandResult root = execute(GATE_COMMAND);
        return gatePassed(root) ? null : root;
    }

    private boolean gatePassed(CommandResult result) {
        if (!result.success()) {
            return false;
        }
        try {
            JsonNode array = JSON.readTree(result.stdout());
            if (!array.isArray()) return false;
            int matches = 0;
            for (JsonNode node : array) {
                if (EXPECTED_RAW_ROOT.equals(node.path("Name").asText())) {
                    if (!node.path("IsDir").asBoolean(false) || node.path("Trashed").asBoolean(false)
                            || (pins == null ? node.path("ID").asText().isBlank()
                            : !pins.folderId().equals(node.path("ID").asText()))) return false;
                    matches++;
                }
            }
            return matches == 1;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private void requireKnownArchive(WorkflowState state) {
        if (pins == null || pins.knownFilename() == null || pins.knownFilename().isBlank()) return;
        BackupPath known = BackupPath.of(pins.knownFolder(), pins.knownFilename());
        if (!known.active()) throw BackupRemoteUnavailableException.configUnavailable();
        TargetResult listed = executeCrypt(state,
                List.of("rclone", "lsjson", "--files-only", CRYPT_BASE + known.dateFolder() + "/"),
                MissingPolicy.FAIL);
        try {
            JsonNode files = JSON.readTree(listed.stdout());
            if (!files.isArray()) throw BackupRemoteUnavailableException.remoteUnavailable();
            int matches = 0;
            for (JsonNode file : files) {
                if (!file.path("IsDir").asBoolean(false)
                        && pins.knownFilename().equals(file.path("Name").asText())) matches++;
            }
            if (matches != 1) throw BackupRemoteUnavailableException.remoteUnavailable();
        } catch (IOException ignored) {
            throw BackupRemoteUnavailableException.remoteUnavailable();
        }
        TargetResult header = executeCrypt(state,
                List.of("rclone", "cat", "--count", "5",
                        CRYPT_BASE + known.dateFolder() + "/" + pins.knownFilename()),
                MissingPolicy.FAIL);
        if (!"PGDMP".equals(header.stdout())) throw BackupRemoteUnavailableException.remoteUnavailable();
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
            verifyInstalledConfig();
            CommandResult validation = gateAttempt();
            if (validation != null) throw gateFailure(validation);
            if (pins != null) requireKnownArchive(state);
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
            return processRunner.run(List.copyOf(command), configWritable,
                    BackupWorkflowDeadline.commandSeconds(PROCESS_TIMEOUT_SECONDS));
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
        if (command.size() == 5 && "rclone".equals(command.get(0))
                && "cat".equals(command.get(1)) && "--count".equals(command.get(2))
                && "5".equals(command.get(3))) return;
        if (command.size() == 8 && "rclone".equals(command.get(0))
                && "copyto".equals(command.get(1)) && "--ignore-existing".equals(command.get(2))
                && "--metadata".equals(command.get(3)) && "--metadata-set".equals(command.get(4))
                && command.get(5).startsWith("backup-upload-nonce=")) return;
        if (command.size() == 4 && "rclone".equals(command.get(0))
                && "copyto".equals(command.get(1))) return;
        if (command.size() == 4 && "rclone".equals(command.get(0))
                && "lsjson".equals(command.get(1)) && "--stat".equals(command.get(2))) return;
        if (command.size() == 4 && "rclone".equals(command.get(0))
                && "lsjson".equals(command.get(1)) && "--files-only".equals(command.get(2))) {
            return;
        }
        if (command.size() == 4 && "rclone".equals(command.get(0))
                && "lsjson".equals(command.get(1)) && "--dirs-only".equals(command.get(2))) return;
        if (command.size() == 5 && "rclone".equals(command.get(0))
                && "lsjson".equals(command.get(1)) && "--files-only".equals(command.get(2))
                && "--metadata".equals(command.get(3))) return;
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
            Objects.requireNonNull(localFile, "localFile");
            String filename = localFile.getFileName().toString();
            BackupPath path = activePath(folder, filename);
            long expectedSize;
            try {
                expectedSize = Files.size(localFile);
            } catch (IOException ignored) {
                throw BackupRemoteUnavailableException.remoteUnavailable();
            }
            if (!proveAbsent(folder, filename)) throw BackupRemoteUnavailableException.remoteUnavailable();
            String uploadNonce = UUID.randomUUID().toString();
            CommandResult write;
            try {
                write = execute(List.of("rclone", "copyto", "--ignore-existing", "--metadata",
                        "--metadata-set", "backup-upload-nonce=" + uploadNonce,
                        localFile.toString(), CRYPT_BASE + path.dateFolder() + "/" + filename));
            } catch (BackupRemoteUnavailableException unavailable) {
                throw BackupRemoteUnavailableException.uncertainUpload(filename);
            }
            // A write with an uncertain result is never retried automatically.
            if (write.timedOut()) throw BackupRemoteUnavailableException.uncertainUpload(filename);
            if (!write.success()) throw BackupRemoteUnavailableException.uncertainUpload(filename);
            boolean uploadedObjectVerified;
            try {
                uploadedObjectVerified = exactFilePresent(folder, filename, expectedSize, uploadNonce);
            } catch (BackupRemoteUnavailableException unavailable) {
                throw BackupRemoteUnavailableException.uncertainUpload(filename);
            }
            if (!uploadedObjectVerified)
                throw BackupRemoteUnavailableException.uncertainUpload(filename);
        }

        @Override
        public void download(String folder, String filename, Path target) {
            BackupPath path = activePath(folder, filename);
            Objects.requireNonNull(target, "target");
            executeCrypt(state,
                    List.of("rclone", "copyto", CRYPT_BASE + path.dateFolder() + "/" + filename,
                            target.toString()),
                    MissingPolicy.FAIL);
        }

        @Override
        public String listDateDirectoriesJson() {
            return executeCrypt(state, List.of("rclone", "lsjson", "--dirs-only", CRYPT_BASE),
                    MissingPolicy.FAIL).stdout();
        }

        @Override
        public String listJson(String dateFolder) {
            validateDateFolder(dateFolder);
            TargetResult result = executeCrypt(state,
                    List.of("rclone", "lsjson", "--files-only", CRYPT_BASE + dateFolder + "/"),
                    MissingPolicy.FAIL);
            return result.stdout();
        }

        @Override
        public boolean proveAbsent(String folder, String filename) {
            BackupPath path = activePath(folder, filename);
            CommandResult result = execute(List.of("rclone", "lsjson", "--stat",
                    CRYPT_BASE + path.dateFolder() + "/" + filename));
            if (result.success()) return false;
            if (isAuthFailure(result)) throw BackupRemoteUnavailableException.authenticationUnavailable();
            if (isMissingFailure(result)) return true;
            throw BackupRemoteUnavailableException.remoteUnavailable();
        }

        private boolean exactFilePresent(String folder, String filename, long expectedSize, String uploadNonce) {
            BackupPath path = activePath(folder, filename);
            CommandResult result = execute(List.of("rclone", "lsjson", "--files-only", "--metadata",
                    CRYPT_BASE + path.dateFolder() + "/"));
            if (!result.success()) return false;
            try {
                JsonNode rows = JSON.readTree(result.stdout());
                if (!rows.isArray()) return false;
                int matches = 0;
                for (JsonNode object : rows) {
                    if (!filename.equals(object.path("Name").asText())) continue;
                    matches++;
                    if (object.path("IsDir").asBoolean(true)
                            || !object.hasNonNull("Size")
                            || object.path("Size").asLong(-1) != expectedSize
                            || !uploadNonce.equals(object.path("Metadata").path("backup-upload-nonce").asText())) {
                        return false;
                    }
                }
                return matches == 1;
            } catch (IOException ignored) {
                return false;
            }
        }

        @Override
        public DeleteResult delete(String folder, String filename) {
            BackupPath path = activePath(folder, filename);
            if (pins != null && folder.equals(pins.knownFolder())
                    && filename.equals(pins.knownFilename())) {
                throw BackupRemoteUnavailableException.remoteUnavailable();
            }
            CommandResult result = execute(
                    List.of("rclone", "deletefile", CRYPT_BASE + path.dateFolder() + "/" + filename));
            if (result.success()) return DeleteResult.DELETED;
            if (!isAuthFailure(result) && isMissingFailure(result)) return DeleteResult.ALREADY_MISSING;
            throw targetFailure(result); // delete result may be uncertain; never retry a destructive command
        }

        @Override
        public String readHeader(String folder, String filename) {
            BackupPath path = activePath(folder, filename);
            return executeCrypt(state, List.of("rclone", "cat", "--count", "5",
                    CRYPT_BASE + path.dateFolder() + "/" + filename), MissingPolicy.FAIL).stdout();
        }
    }

    private static BackupPath activePath(String folder, String filename) {
        BackupPath path = BackupPath.of(folder, filename);
        if (!path.active()) throw new IllegalArgumentException("舊備份不可由日期路徑操作");
        return path;
    }

    private static void validateDateFolder(String dateFolder) {
        try {
            if (!java.time.LocalDate.parse(dateFolder).toString().equals(dateFolder)
                    || java.time.LocalDate.parse(dateFolder).isBefore(BackupPath.CUTOVER)) {
                throw new IllegalArgumentException("不合法的日期資料夾");
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("不合法的日期資料夾");
        }
    }

    private static final class WorkflowState {
        private boolean recoveryConsumed;
    }

    record DeploymentPins(String folderId, String cryptConfigSha256,
                          String knownFolder, String knownFilename) {
        static DeploymentPins fromEnvironment() {
            return new DeploymentPins(System.getenv("BACKUP_DRIVE_FOLDER_ID"),
                    System.getenv("BACKUP_CRYPT_CONFIG_SHA256"),
                    System.getenv("BACKUP_KNOWN_ARCHIVE_FOLDER"),
                    System.getenv("BACKUP_KNOWN_ARCHIVE_FILENAME"));
        }

        boolean valid() {
            boolean base = folderId != null && !folderId.isBlank()
                    && cryptConfigSha256 != null && cryptConfigSha256.matches("[0-9a-f]{64}")
                    && !folderId.contains("/") && !folderId.contains(":");
            if (!base) return false;
            boolean noFolder = knownFolder == null || knownFolder.isBlank();
            boolean noFilename = knownFilename == null || knownFilename.isBlank();
            if (noFolder && noFilename) return true;
            if (noFolder || noFilename || !"daily".equals(knownFolder)) return false;
            try {
                return MOVED_DATES.contains(BackupPath.of(knownFolder, knownFilename).date());
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
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
            List<ProcessHandle> descendants = new ArrayList<>(process.toHandle().descendants().toList());
            for (ProcessHandle child : descendants) child.destroyForcibly();
            process.destroyForcibly();
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                    && System.nanoTime() < deadline) {
                try {
                    process.waitFor(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
                PROCESS_CLEANUP_UNSAFE.set(true);
                throw new IllegalStateException("備份子行程無法安全停止");
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
