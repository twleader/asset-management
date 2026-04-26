package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.BackupDto;
import com.steven.assets.model.BackupSetting;
import com.steven.assets.repository.BackupSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class BackupService {

    private static final String REMOTE_BASE = "gdrive-crypt:backups";
    private static final List<String> FOLDERS = List.of("manual", "daily", "weekly", "monthly");
    private static final int DEFAULT_MANUAL_RETENTION = 5;
    private static final int DEFAULT_DAILY_RETENTION = 50;
    private static final int DEFAULT_WEEKLY_RETENTION = 5;
    private static final String MANUAL_PREFIX = "asset_manual_";
    private static final String DAILY_TW_PREFIX = "asset_daily_tw_";
    private static final String DAILY_US_PREFIX = "asset_daily_us_";
    private static final String WEEKLY_PREFIX = "asset_weekly_";
    private static final String AUTO_PRE_RESTORE_PREFIX = "asset_auto-pre-restore_";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final long PROCESS_TIMEOUT_SEC = 300;

    /** 唯讀掛入的 rclone 設定來源（docker-compose mount） */
    private static final Path RCLONE_CONFIG_SOURCE = Path.of("/etc/rclone/rclone.conf");
    /** rclone 實際使用的 config 路徑（可寫，給 token 自動續期使用） */
    private static final Path RCLONE_CONFIG_WRITABLE = Path.of("/tmp/rclone.conf");

    private final String dbHost;
    private final String dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPassword;
    private final MarketDataService marketDataService;
    private final BackupSettingRepository settingRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public BackupService(
            @Value("${DB_HOST:postgres}") String dbHost,
            @Value("${DB_PORT:5432}") String dbPort,
            @Value("${DB_NAME}") String dbName,
            @Value("${DB_USERNAME}") String dbUser,
            @Value("${DB_PASSWORD}") String dbPassword,
            MarketDataService marketDataService,
            BackupSettingRepository settingRepo) {
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.marketDataService = marketDataService;
        this.settingRepo = settingRepo;
    }

    /** 取得目前保留代數設定（無資料時回預設值，不寫入）。 */
    public BackupSetting getSetting() {
        return settingRepo.findById(1).orElseGet(() -> BackupSetting.builder()
                .id(1)
                .manualRetention(DEFAULT_MANUAL_RETENTION)
                .dailyRetention(DEFAULT_DAILY_RETENTION)
                .weeklyRetention(DEFAULT_WEEKLY_RETENTION)
                .updatedAt(LocalDateTime.now(DISPLAY_ZONE))
                .build());
    }

    /** 更新保留代數設定，數值需介於 1～999。 */
    public BackupSetting updateSetting(Integer manual, Integer daily, Integer weekly) {
        validateRange("manualRetention", manual);
        validateRange("dailyRetention", daily);
        validateRange("weeklyRetention", weekly);
        BackupSetting s = getSetting();
        s.setId(1);
        s.setManualRetention(manual);
        s.setDailyRetention(daily);
        s.setWeeklyRetention(weekly);
        s.setUpdatedAt(LocalDateTime.now(DISPLAY_ZONE));
        return settingRepo.save(s);
    }

    private static void validateRange(String field, Integer v) {
        if (v == null || v < 1 || v > 999) {
            throw new IllegalArgumentException(field + " 必須介於 1～999");
        }
    }

    /**
     * 啟動時把唯讀掛入的 rclone 設定複製到可寫位置，
     * 避免 rclone 自動更新 OAuth token 寫回失敗（exit non-zero）
     */
    @PostConstruct
    void initRcloneConfig() {
        try {
            if (Files.exists(RCLONE_CONFIG_SOURCE)) {
                Files.copy(RCLONE_CONFIG_SOURCE, RCLONE_CONFIG_WRITABLE, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.setPosixFilePermissions(RCLONE_CONFIG_WRITABLE,
                            PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // 非 POSIX 系統，跳過
                }
                log.info("rclone config 已複製至可寫路徑：{}", RCLONE_CONFIG_WRITABLE);
            } else {
                log.warn("找不到 rclone 設定來源：{}（備份/還原功能將無法使用）", RCLONE_CONFIG_SOURCE);
            }
        } catch (IOException e) {
            log.error("初始化 rclone config 失敗：{}", e.getMessage(), e);
        }
    }

    /** 手動立即備份。autoPreRestore=true 時使用「自救點」檔名前綴，且不做 5 份輪替。 */
    public BackupDto.CreateResponse runBackup(boolean autoPreRestore) {
        String prefix = autoPreRestore ? AUTO_PRE_RESTORE_PREFIX : MANUAL_PREFIX;
        BackupDto.CreateResponse resp = doBackup("manual", prefix);
        if (!autoPreRestore) {
            rotateFolder("manual", MANUAL_PREFIX, getSetting().getManualRetention());
        }
        return resp;
    }

    /** 通用備份：dump → 上傳到指定資料夾，不負責輪替。 */
    private BackupDto.CreateResponse doBackup(String folder, String prefix) {
        String ts = LocalDateTime.now(DISPLAY_ZONE).format(TS_FMT);
        String filename = prefix + ts + ".dump";
        Path dumpFile = Path.of("/tmp", filename);

        try {
            log.info("Backup start: {}/{}", folder, filename);
            pgDump(dumpFile);
            long size;
            try {
                size = Files.size(dumpFile);
            } catch (IOException e) {
                throw new RuntimeException("無法讀取備份檔大小: " + e.getMessage(), e);
            }
            log.info("pg_dump done, size={} bytes", size);

            rcloneCopy(dumpFile, REMOTE_BASE + "/" + folder + "/");
            log.info("Uploaded to {}/{}/", REMOTE_BASE, folder);

            return BackupDto.CreateResponse.builder()
                    .filename(filename)
                    .sizeBytes(size)
                    .uploadedAt(LocalDateTime.now(DISPLAY_ZONE))
                    .build();
        } finally {
            try {
                Files.deleteIfExists(dumpFile);
            } catch (IOException e) {
                log.warn("Failed to delete temp file {}: {}", dumpFile, e.getMessage());
            }
        }
    }

    // ===== 自動排程 =====

    /** 台股交易日 15:30（收盤後 2 小時）→ daily/asset_daily_tw_*.dump */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledDailyTwBackup() {
        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        if (!marketDataService.isTwTradingDay(today)) {
            log.info("Skip TW daily backup: {} 非台股交易日", today);
            return;
        }
        try {
            doBackup("daily", DAILY_TW_PREFIX);
            rotateFolder("daily", "asset_daily_", getSetting().getDailyRetention());
        } catch (RuntimeException e) {
            log.error("台股每日備份失敗: {}", e.getMessage(), e);
        }
    }

    /** 美股收盤後 2 小時，台北時間隔日 07:00 → daily/asset_daily_us_*.dump */
    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")
    public void scheduledDailyUsBackup() {
        LocalDate prevUsDay = LocalDate.now(DISPLAY_ZONE).minusDays(1);
        if (!marketDataService.isUsTradingDay(prevUsDay)) {
            log.info("Skip US daily backup: {} 非美股交易日", prevUsDay);
            return;
        }
        try {
            doBackup("daily", DAILY_US_PREFIX);
            rotateFolder("daily", "asset_daily_", getSetting().getDailyRetention());
        } catch (RuntimeException e) {
            log.error("美股每日備份失敗: {}", e.getMessage(), e);
        }
    }

    /** 每周日 05:00 → weekly/asset_weekly_*.dump */
    @Scheduled(cron = "0 0 5 * * SUN", zone = "Asia/Taipei")
    public void scheduledWeeklyBackup() {
        try {
            doBackup("weekly", WEEKLY_PREFIX);
            rotateFolder("weekly", WEEKLY_PREFIX, getSetting().getWeeklyRetention());
        } catch (RuntimeException e) {
            log.error("每周備份失敗: {}", e.getMessage(), e);
        }
    }

    /** 列出 manual/daily/weekly/monthly 所有備份，依 modifiedAt 由新→舊排序。 */
    public List<BackupDto.BackupItem> listBackups() {
        // 4 個資料夾並行查詢，縮短整體等待時間
        List<CompletableFuture<List<BackupDto.BackupItem>>> futures = FOLDERS.stream()
                .map(folder -> CompletableFuture.supplyAsync(() -> listFolder(folder)))
                .toList();

        List<BackupDto.BackupItem> items = new ArrayList<>();
        for (CompletableFuture<List<BackupDto.BackupItem>> f : futures) {
            try {
                items.addAll(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("列出備份被中斷", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("列出備份失敗：" + cause.getMessage(), cause);
            }
        }
        items.sort(Comparator.comparing(BackupDto.BackupItem::getModifiedAt).reversed());
        return items;
    }

    private List<BackupDto.BackupItem> listFolder(String folder) {
        List<BackupDto.BackupItem> result = new ArrayList<>();
        String json = rcloneLsJson(REMOTE_BASE + "/" + folder + "/");
        if (json == null || json.isBlank()) return result;
        try {
            JsonNode arr = mapper.readTree(json);
            for (JsonNode node : arr) {
                if (node.path("IsDir").asBoolean(false)) continue;
                String name = node.path("Name").asText();
                if (!name.endsWith(".dump")) continue;
                result.add(BackupDto.BackupItem.builder()
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
        return result;
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
        } catch (RuntimeException e) {
            // 資料夾尚未建立時 rclone 會回 "directory not found"，視為空清單
            if (e.getMessage() != null && e.getMessage().contains("directory not found")) {
                log.debug("rclone lsjson: {} 不存在，視為空", remote);
                return "[]";
            }
            throw e;
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
            // rclone 設定路徑：使用可寫副本（覆寫 docker-compose 的唯讀路徑）
            pb.environment().put("RCLONE_CONFIG", RCLONE_CONFIG_WRITABLE.toString());

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

    /**
     * 保留指定資料夾下、檔名前綴匹配的最新 retention 份備份；超過者刪除最舊。
     * 其他前綴的檔案（例如 manual/ 內的自救點）不受影響。
     */
    private void rotateFolder(String folder, String prefix, int retention) {
        String json = rcloneLsJson(REMOTE_BASE + "/" + folder + "/");
        if (json == null || json.isBlank()) return;

        List<Map.Entry<String, LocalDateTime>> files = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(json);
            for (JsonNode node : arr) {
                if (node.path("IsDir").asBoolean(false)) continue;
                String name = node.path("Name").asText();
                if (!name.startsWith(prefix) || !name.endsWith(".dump")) continue;
                files.add(Map.entry(name, parseRcloneTime(node.path("ModTime").asText())));
            }
        } catch (IOException e) {
            throw new RuntimeException("輪替時解析 rclone lsjson 失敗 (" + folder + "): " + e.getMessage(), e);
        }

        if (files.size() <= retention) return;
        files.sort(Map.Entry.<String, LocalDateTime>comparingByValue().reversed());
        for (int i = retention; i < files.size(); i++) {
            String name = files.get(i).getKey();
            log.info("Rotate: deleting old backup {}/{}", folder, name);
            rcloneDelete(REMOTE_BASE + "/" + folder + "/" + name);
        }
    }

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Taipei");

    private static LocalDateTime parseRcloneTime(String iso) {
        if (iso == null || iso.isBlank()) return LocalDateTime.now(DISPLAY_ZONE);
        // rclone ModTime 為 ISO-8601 UTC（例：2026-04-25T17:00:00.123456789Z）
        // 統一轉成 Asia/Taipei 顯示
        return ZonedDateTime.parse(iso).withZoneSameInstant(DISPLAY_ZONE).toLocalDateTime();
    }
}
