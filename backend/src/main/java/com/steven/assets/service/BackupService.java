package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.BackupDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class BackupService {

    private static final String REMOTE_BASE = "gdrive-crypt:backups";
    private static final List<String> FOLDERS = List.of("manual", "daily", "weekly", "monthly");
    private static final int MANUAL_RETENTION = 5;
    private static final String MANUAL_PREFIX = "asset_manual_";
    private static final String AUTO_PRE_RESTORE_PREFIX = "asset_auto-pre-restore_";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final long PROCESS_TIMEOUT_SEC = 300;

    private final String dbHost;
    private final String dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPassword;
    private final ObjectMapper mapper = new ObjectMapper();

    public BackupService(
            @Value("${DB_HOST:postgres}") String dbHost,
            @Value("${DB_PORT:5432}") String dbPort,
            @Value("${DB_NAME}") String dbName,
            @Value("${DB_USERNAME}") String dbUser,
            @Value("${DB_PASSWORD}") String dbPassword) {
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
    }

    /** 立即備份。autoPreRestore=true 時使用「自救點」檔名前綴，且不做 5 份輪替。 */
    public BackupDto.CreateResponse runBackup(boolean autoPreRestore) {
        String ts = LocalDateTime.now().format(TS_FMT);
        String prefix = autoPreRestore ? AUTO_PRE_RESTORE_PREFIX : MANUAL_PREFIX;
        String filename = prefix + ts + ".dump";
        Path dumpFile = Path.of("/tmp", filename);

        try {
            // 1. pg_dump → file
            log.info("Backup start: {}", filename);
            pgDump(dumpFile);
            long size;
            try {
                size = Files.size(dumpFile);
            } catch (IOException e) {
                throw new RuntimeException("無法讀取備份檔大小: " + e.getMessage(), e);
            }
            log.info("pg_dump done, size={} bytes", size);

            // 2. rclone copy → manual/
            rcloneCopy(dumpFile, REMOTE_BASE + "/manual/");
            log.info("Uploaded to {}/manual/", REMOTE_BASE);

            // 3. 輪替（自救點不算）
            if (!autoPreRestore) {
                rotateManual();
            }

            return BackupDto.CreateResponse.builder()
                    .filename(filename)
                    .sizeBytes(size)
                    .uploadedAt(LocalDateTime.now())
                    .build();
        } finally {
            try {
                Files.deleteIfExists(dumpFile);
            } catch (IOException e) {
                log.warn("Failed to delete temp file {}: {}", dumpFile, e.getMessage());
            }
        }
    }

    /** 列出 manual/daily/weekly/monthly 所有備份，依 modifiedAt 由新→舊排序。 */
    public List<BackupDto.BackupItem> listBackups() {
        List<BackupDto.BackupItem> items = new ArrayList<>();
        for (String folder : FOLDERS) {
            String json = rcloneLsJson(REMOTE_BASE + "/" + folder + "/");
            if (json == null || json.isBlank()) continue;
            try {
                JsonNode arr = mapper.readTree(json);
                for (JsonNode node : arr) {
                    if (node.path("IsDir").asBoolean(false)) continue;
                    String name = node.path("Name").asText();
                    if (!name.endsWith(".dump")) continue;
                    items.add(BackupDto.BackupItem.builder()
                            .folder(folder)
                            .filename(name)
                            .sizeBytes(node.path("Size").asLong(0))
                            .modifiedAt(parseRcloneTime(node.path("ModTime").asText()))
                            .autoPreRestore(name.startsWith(AUTO_PRE_RESTORE_PREFIX))
                            .build());
                }
            } catch (IOException e) {
                throw new RuntimeException("解析 rclone lsjson 失敗 (" + folder + "): " + e.getMessage(), e);
            }
        }
        items.sort(Comparator.comparing(BackupDto.BackupItem::getModifiedAt).reversed());
        return items;
    }

    /** 還原：先建自救點 → rclone copy → pg_restore --clean。 */
    public BackupDto.RestoreResponse runRestore(String folder, String filename) {
        if (!FOLDERS.contains(folder)) {
            throw new IllegalArgumentException("不允許的備份資料夾：" + folder);
        }
        if (filename == null || filename.contains("/") || filename.contains("..") || !filename.endsWith(".dump")) {
            throw new IllegalArgumentException("不合法的備份檔名：" + filename);
        }

        // 1. 自救點
        log.info("Restore: creating pre-restore backup");
        BackupDto.CreateResponse pre = runBackup(true);

        // 2. 下載備份
        Path localFile = Path.of("/tmp", filename);
        try {
            log.info("Restore: downloading {}/{}/{}", REMOTE_BASE, folder, filename);
            rcloneCopyToLocal(REMOTE_BASE + "/" + folder + "/" + filename, "/tmp/");

            // 3. pg_restore
            log.info("Restore: running pg_restore");
            pgRestore(localFile);

            log.info("Restore done: {}/{}", folder, filename);
            return BackupDto.RestoreResponse.builder()
                    .status("success")
                    .preRestoreBackup(pre.getFilename())
                    .restoredFrom(folder + "/" + filename)
                    .build();
        } finally {
            try {
                Files.deleteIfExists(localFile);
            } catch (IOException e) {
                log.warn("Failed to delete temp file {}: {}", localFile, e.getMessage());
            }
        }
    }

    // ===== Process helpers =====

    private void pgDump(Path outFile) {
        List<String> cmd = List.of(
                "pg_dump",
                "-h", dbHost,
                "-p", dbPort,
                "-U", dbUser,
                "-d", dbName,
                "--format=custom",
                "--compress=9",
                "--no-owner",
                "--no-acl"
        );
        execProcess(cmd, outFile, null, "pg_dump");
    }

    private void pgRestore(Path inFile) {
        List<String> cmd = List.of(
                "pg_restore",
                "-h", dbHost,
                "-p", dbPort,
                "-U", dbUser,
                "-d", dbName,
                "--clean",
                "--if-exists",
                "--no-owner",
                "--no-acl",
                inFile.toString()
        );
        execProcess(cmd, null, null, "pg_restore");
    }

    private void rcloneCopy(Path local, String remote) {
        execProcess(List.of("rclone", "copy", local.toString(), remote), null, null, "rclone copy");
    }

    private void rcloneCopyToLocal(String remoteFile, String localDir) {
        execProcess(List.of("rclone", "copy", remoteFile, localDir), null, null, "rclone copy (download)");
    }

    private String rcloneLsJson(String remote) {
        Path tmpOut;
        try {
            tmpOut = Files.createTempFile("rclone-ls-", ".json");
        } catch (IOException e) {
            throw new RuntimeException("無法建立暫存檔: " + e.getMessage(), e);
        }
        try {
            execProcess(List.of("rclone", "lsjson", "--files-only", remote), tmpOut, null, "rclone lsjson");
            return Files.readString(tmpOut);
        } catch (IOException e) {
            throw new RuntimeException("讀取 rclone lsjson 結果失敗: " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(tmpOut); } catch (IOException ignored) {}
        }
    }

    private void rcloneDelete(String remoteFile) {
        execProcess(List.of("rclone", "deletefile", remoteFile), null, null, "rclone deletefile");
    }

    private void execProcess(List<String> cmd, Path stdoutFile, Path stdinFile, String label) {
        log.debug("Exec: {}", String.join(" ", cmd));
        Path errFile;
        try {
            errFile = Files.createTempFile("proc-err-", ".log");
        } catch (IOException e) {
            throw new RuntimeException(label + " 暫存檔建立失敗: " + e.getMessage(), e);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // PostgreSQL 密碼
            pb.environment().put("PGPASSWORD", dbPassword);
            // rclone 設定路徑（docker-compose 已設 RCLONE_CONFIG，這裡保險再注入一次）
            pb.environment().putIfAbsent("RCLONE_CONFIG", "/etc/rclone/rclone.conf");

            if (stdoutFile != null) pb.redirectOutput(stdoutFile.toFile());
            if (stdinFile != null) pb.redirectInput(stdinFile.toFile());
            pb.redirectError(errFile.toFile());

            Process p = pb.start();
            boolean finished = p.waitFor(PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new RuntimeException(label + " 執行逾時（" + PROCESS_TIMEOUT_SEC + "s）");
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String err = Files.readString(errFile);
                throw new RuntimeException(label + " 失敗 (exit=" + exit + "): " + err.strip());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException(label + " 執行錯誤: " + e.getMessage(), e);
        } finally {
            try { Files.deleteIfExists(errFile); } catch (IOException ignored) {}
        }
    }

    // ===== 輔助 =====

    /** 保留最新 5 份 manual/asset_manual_*.dump，自救點不計入此限。 */
    private void rotateManual() {
        String json = rcloneLsJson(REMOTE_BASE + "/manual/");
        if (json == null || json.isBlank()) return;

        List<Map.Entry<String, LocalDateTime>> manualFiles = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(json);
            for (JsonNode node : arr) {
                if (node.path("IsDir").asBoolean(false)) continue;
                String name = node.path("Name").asText();
                if (!name.startsWith(MANUAL_PREFIX) || !name.endsWith(".dump")) continue;
                manualFiles.add(Map.entry(name, parseRcloneTime(node.path("ModTime").asText())));
            }
        } catch (IOException e) {
            throw new RuntimeException("輪替時解析 rclone lsjson 失敗: " + e.getMessage(), e);
        }

        if (manualFiles.size() <= MANUAL_RETENTION) return;
        manualFiles.sort(Map.Entry.<String, LocalDateTime>comparingByValue().reversed());
        for (int i = MANUAL_RETENTION; i < manualFiles.size(); i++) {
            String name = manualFiles.get(i).getKey();
            log.info("Rotate: deleting old manual backup {}", name);
            rcloneDelete(REMOTE_BASE + "/manual/" + name);
        }
    }

    private static LocalDateTime parseRcloneTime(String iso) {
        if (iso == null || iso.isBlank()) return LocalDateTime.now();
        // rclone ModTime 為 ISO-8601，例如 2026-04-25T17:00:00.123456789Z
        return ZonedDateTime.parse(iso).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
