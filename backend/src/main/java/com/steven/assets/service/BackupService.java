package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.BackupDto;
import com.steven.assets.model.BackupRecord;
import com.steven.assets.model.BackupSetting;
import com.steven.assets.repository.BackupRecordRepository;
import com.steven.assets.repository.BackupSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final BackupRecordRepository recordRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public BackupService(
            @Value("${DB_HOST:postgres}") String dbHost,
            @Value("${DB_PORT:5432}") String dbPort,
            @Value("${DB_NAME}") String dbName,
            @Value("${DB_USERNAME}") String dbUser,
            @Value("${DB_PASSWORD}") String dbPassword,
            MarketDataService marketDataService,
            BackupSettingRepository settingRepo,
            BackupRecordRepository recordRepo) {
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.marketDataService = marketDataService;
        this.settingRepo = settingRepo;
        this.recordRepo = recordRepo;
    }

    /** 取得目前保留代數設定（無資料時回預設值，不寫入）。 */
    public BackupSetting getSetting() {
        return settingRepo.findById(1).orElseGet(() -> BackupSetting.builder()
                .id(1)
                .manualRetention(DEFAULT_MANUAL_RETENTION)
                .dailyRetention(DEFAULT_DAILY_RETENTION)
                .weeklyRetention(DEFAULT_WEEKLY_RETENTION)
                .backupEnabled(Boolean.TRUE)
                .updatedAt(LocalDateTime.now(DISPLAY_ZONE))
                .build());
    }

    /** 更新保留代數設定（含啟用開關），數值需介於 1～999。 */
    public BackupSetting updateSetting(Integer manual, Integer daily, Integer weekly, Boolean backupEnabled) {
        validateRange("manualRetention", manual);
        validateRange("dailyRetention", daily);
        validateRange("weeklyRetention", weekly);
        BackupSetting s = getSetting();
        s.setId(1);
        s.setManualRetention(manual);
        s.setDailyRetention(daily);
        s.setWeeklyRetention(weekly);
        if (backupEnabled != null) s.setBackupEnabled(backupEnabled);
        s.setUpdatedAt(LocalDateTime.now(DISPLAY_ZONE));
        BackupSetting saved = settingRepo.save(s);

        // 儲存設定當下立即套用新 retention，讓使用者下調保留代數時不必等到下一次排程備份才生效。
        // 任何單一資料夾失敗只記 log、不影響其他資料夾與 PUT 200 OK。
        rotateQuietly("manual", saved.getManualRetention());
        rotateQuietly("daily", saved.getDailyRetention());
        rotateQuietly("weekly", saved.getWeeklyRetention());

        return saved;
    }

    private void rotateQuietly(String folder, int retention) {
        try {
            rotateFolder(folder, retention);
        } catch (RuntimeException e) {
            log.warn("更新保留代數後輪替 {} 失敗: {}", folder, e.getMessage(), e);
        }
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

    /** 手動立即備份。autoPreRestore=true 時使用「自救點」檔名前綴，且不做 5 份輪替；
     *  也不受 backup_enabled 開關控制（還原前自救必跑）。 */
    public BackupDto.CreateResponse runBackup(boolean autoPreRestore) {
        if (!autoPreRestore && !Boolean.TRUE.equals(getSetting().getBackupEnabled())) {
            throw new IllegalStateException("備份功能已停用，請於「保留設定」中開啟「啟用備份」後再試");
        }
        String prefix = autoPreRestore ? AUTO_PRE_RESTORE_PREFIX : MANUAL_PREFIX;
        BackupDto.CreateResponse resp = doBackup("manual", prefix, autoPreRestore);
        if (!autoPreRestore) {
            rotateFolder("manual", getSetting().getManualRetention());
        }
        return resp;
    }

    /** 通用備份：dump → 上傳到指定資料夾，不負責輪替。成功上傳後寫一筆 backup_record。 */
    private BackupDto.CreateResponse doBackup(String folder, String prefix, boolean autoPreRestore) {
        LocalDateTime now = LocalDateTime.now(DISPLAY_ZONE);
        String ts = now.format(TS_FMT);
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

            // metadata 入 DB，UI 列表直接讀這裡
            recordRepo.save(BackupRecord.builder()
                    .folder(folder)
                    .filename(filename)
                    .sizeBytes(size)
                    .modifiedAt(now)
                    .autoPreRestore(autoPreRestore)
                    .createdAt(now)
                    .build());

            return BackupDto.CreateResponse.builder()
                    .filename(filename)
                    .sizeBytes(size)
                    .uploadedAt(now)
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
        if (!Boolean.TRUE.equals(getSetting().getBackupEnabled())) {
            log.info("Skip TW daily backup: 備份開關已關閉");
            return;
        }
        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        if (!marketDataService.isTwTradingDay(today)) {
            log.info("Skip TW daily backup: {} 非台股交易日", today);
            return;
        }
        try {
            doBackup("daily", DAILY_TW_PREFIX, false);
            rotateFolder("daily", getSetting().getDailyRetention());
        } catch (RuntimeException e) {
            log.error("台股每日備份失敗: {}", e.getMessage(), e);
        }
    }

    /** 美股收盤後 2 小時，台北時間隔日 07:00 → daily/asset_daily_us_*.dump */
    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")
    public void scheduledDailyUsBackup() {
        if (!Boolean.TRUE.equals(getSetting().getBackupEnabled())) {
            log.info("Skip US daily backup: 備份開關已關閉");
            return;
        }
        LocalDate prevUsDay = LocalDate.now(DISPLAY_ZONE).minusDays(1);
        if (!marketDataService.isUsTradingDay(prevUsDay)) {
            log.info("Skip US daily backup: {} 非美股交易日", prevUsDay);
            return;
        }
        try {
            doBackup("daily", DAILY_US_PREFIX, false);
            rotateFolder("daily", getSetting().getDailyRetention());
        } catch (RuntimeException e) {
            log.error("美股每日備份失敗: {}", e.getMessage(), e);
        }
    }

    /** 每周日 05:00 → weekly/asset_weekly_*.dump */
    @Scheduled(cron = "0 0 5 * * SUN", zone = "Asia/Taipei")
    public void scheduledWeeklyBackup() {
        if (!Boolean.TRUE.equals(getSetting().getBackupEnabled())) {
            log.info("Skip weekly backup: 備份開關已關閉");
            return;
        }
        try {
            doBackup("weekly", WEEKLY_PREFIX, false);
            rotateFolder("weekly", getSetting().getWeeklyRetention());
        } catch (RuntimeException e) {
            log.error("每周備份失敗: {}", e.getMessage(), e);
        }
    }

    /** 列出所有備份：直接從 DB 讀，無需連 rclone。 */
    public List<BackupDto.BackupItem> listBackups() {
        return recordRepo.findAllByOrderByModifiedAtDesc().stream()
                .map(r -> BackupDto.BackupItem.builder()
                        .folder(r.getFolder())
                        .filename(r.getFilename())
                        .sizeBytes(r.getSizeBytes())
                        .modifiedAt(r.getModifiedAt())
                        .autoPreRestore(Boolean.TRUE.equals(r.getAutoPreRestore()))
                        .build())
                .toList();
    }

    /**
     * 從 Google Drive 列出實際檔案，與 backup_record 對齊：
     *  - rclone 有但 DB 沒 → INSERT
     *  - DB 有但 rclone 沒 → DELETE
     * 用於首次部署或外部直接刪檔後手動對齊。
     */
    @Transactional
    public BackupDto.SyncResponse syncFromRemote() {
        // 1. 先抓 rclone 上所有檔案
        record Remote(String folder, String filename, long size, LocalDateTime modifiedAt) {}
        List<Remote> remoteFiles = new ArrayList<>();
        List<CompletableFuture<List<Remote>>> futures = FOLDERS.stream()
                .map(folder -> CompletableFuture.supplyAsync(() -> {
                    List<Remote> out = new ArrayList<>();
                    String json = rcloneLsJson(REMOTE_BASE + "/" + folder + "/");
                    if (json == null || json.isBlank()) return out;
                    try {
                        JsonNode arr = mapper.readTree(json);
                        for (JsonNode node : arr) {
                            if (node.path("IsDir").asBoolean(false)) continue;
                            String name = node.path("Name").asText();
                            if (!name.endsWith(".dump")) continue;
                            out.add(new Remote(folder, name,
                                    node.path("Size").asLong(0),
                                    parseRcloneTime(node.path("ModTime").asText())));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("解析 rclone lsjson 失敗 (" + folder + "): " + e.getMessage(), e);
                    }
                    return out;
                }))
                .toList();
        for (CompletableFuture<List<Remote>> f : futures) {
            try { remoteFiles.addAll(f.get()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt();
                throw new RuntimeException("同步被中斷", e); }
            catch (ExecutionException e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                throw new RuntimeException("同步失敗：" + c.getMessage(), c);
            }
        }

        // 2. upsert insert-missing
        int inserted = 0;
        java.util.Set<String> remoteKeys = new java.util.HashSet<>();
        LocalDateTime now = LocalDateTime.now(DISPLAY_ZONE);
        for (Remote r : remoteFiles) {
            remoteKeys.add(r.folder() + "/" + r.filename());
            boolean exists = recordRepo.findByFolderAndFilename(r.folder(), r.filename()).isPresent();
            if (!exists) {
                recordRepo.save(BackupRecord.builder()
                        .folder(r.folder())
                        .filename(r.filename())
                        .sizeBytes(r.size())
                        .modifiedAt(r.modifiedAt())
                        .autoPreRestore(r.filename().startsWith(AUTO_PRE_RESTORE_PREFIX))
                        .createdAt(now)
                        .build());
                inserted++;
            }
        }
        // 3. 清孤兒（DB 有但 rclone 已不存在）
        int deleted = 0;
        for (BackupRecord row : recordRepo.findAll()) {
            if (!remoteKeys.contains(row.getFolder() + "/" + row.getFilename())) {
                recordRepo.delete(row);
                deleted++;
            }
        }
        log.info("Backup sync done: inserted={}, deleted={}", inserted, deleted);
        return BackupDto.SyncResponse.builder()
                .inserted(inserted)
                .deleted(deleted)
                .total(remoteFiles.size())
                .build();
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
     * 以 DB `backup_record` 為單一事實來源輪替：取指定資料夾下、排除自救點後依 modifiedAt 排序，
     * 保留前 retention 筆，其餘對 Google Drive + DB 兩邊一併刪除。
     *
     * 改採 DB-driven 而非 rclone lsjson 過濾檔名前綴，原因：
     *  - 歷史檔名前綴可能變動（例如舊版 `asset_*.dump`、新版 `asset_weekly_*.dump`），
     *    前綴過濾會漏掉 legacy 檔案造成資料夾檔數超過 retention
     *  - DB 已記錄每筆備份的歸屬（folder + autoPreRestore flag），是更穩定的事實來源
     *
     * 自救點（auto_pre_restore=true）永遠不輪替，仍需保留以供還原失敗時手動回復。
     * 若 Google Drive 上有 DB 沒記錄的檔案，本 method 不會誤刪（DB 沒記錄就不動）。
     */
    private void rotateFolder(String folder, int retention) {
        List<BackupRecord> records = recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc(folder);
        if (records.size() <= retention) return;
        for (int i = retention; i < records.size(); i++) {
            BackupRecord r = records.get(i);
            String name = r.getFilename();
            log.info("Rotate: deleting old backup {}/{}", folder, name);
            try {
                rcloneDelete(REMOTE_BASE + "/" + folder + "/" + name);
            } catch (RuntimeException e) {
                // rclone 刪除失敗（檔案已不存在或網路異常）不應卡住 DB 清理：
                // 留一筆 warn log，仍把 DB 記錄刪掉避免下次重複嘗試
                log.warn("rclone deletefile 失敗 {}/{}: {}", folder, name, e.getMessage());
            }
            recordRepo.delete(r);
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
