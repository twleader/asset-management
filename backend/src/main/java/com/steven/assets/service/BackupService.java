package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.BackupDto;
import com.steven.assets.model.BackupRecord;
import com.steven.assets.model.BackupSetting;
import com.steven.assets.repository.BackupRecordRepository;
import com.steven.assets.repository.BackupSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class BackupService {

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
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Taipei");

    private final MarketDataService marketDataService;
    private final BackupSettingRepository settingRepo;
    private final BackupRecordRepository recordRepo;
    private final BackupRemoteClient remoteClient;
    private final BackupDatabaseProcess databaseProcess;
    private final ObjectMapper mapper;

    public BackupService(
            MarketDataService marketDataService,
            BackupSettingRepository settingRepo,
            BackupRecordRepository recordRepo,
            BackupRemoteClient remoteClient,
            BackupDatabaseProcess databaseProcess) {
        this.marketDataService = marketDataService;
        this.settingRepo = settingRepo;
        this.recordRepo = recordRepo;
        this.remoteClient = remoteClient;
        this.databaseProcess = databaseProcess;
        this.mapper = new ObjectMapper();
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
        BackupSetting setting = getSetting();
        setting.setId(1);
        setting.setManualRetention(manual);
        setting.setDailyRetention(daily);
        setting.setWeeklyRetention(weekly);
        if (backupEnabled != null) {
            setting.setBackupEnabled(backupEnabled);
        }
        setting.setUpdatedAt(LocalDateTime.now(DISPLAY_ZONE));

        // Repository 自己的 transaction 在 return 前 commit；rotation 不得反轉已保存的設定。
        BackupSetting saved = settingRepo.saveAndFlush(setting);
        rotateQuietly("manual", saved.getManualRetention(), "更新保留代數");
        rotateQuietly("daily", saved.getDailyRetention(), "更新保留代數");
        rotateQuietly("weekly", saved.getWeeklyRetention(), "更新保留代數");
        return saved;
    }

    private static void validateRange(String field, Integer value) {
        if (value == null || value < 1 || value > 999) {
            throw new IllegalArgumentException(field + " 必須介於 1～999");
        }
    }

    /** 手動立即備份；自救點不受 backup_enabled 控制且不做 manual retention。 */
    public BackupDto.CreateResponse runBackup(boolean autoPreRestore) {
        if (!autoPreRestore && !Boolean.TRUE.equals(getSetting().getBackupEnabled())) {
            throw new IllegalStateException("備份功能已停用，請於「保留設定」中開啟「啟用備份」後再試");
        }
        String prefix = autoPreRestore ? AUTO_PRE_RESTORE_PREFIX : MANUAL_PREFIX;
        BackupDto.CreateResponse response = doBackup("manual", prefix, autoPreRestore);
        if (!autoPreRestore) {
            rotateUsingCurrentSettingQuietly("manual", "手動備份完成後");
        }
        return response;
    }

    /** dump → verified remote upload → durable backup_record；rotation 由呼叫端在成功後另行處理。 */
    private BackupDto.CreateResponse doBackup(String folder, String prefix, boolean autoPreRestore) {
        LocalDateTime now = LocalDateTime.now(DISPLAY_ZONE);
        String filename = prefix + now.format(TS_FMT) + ".dump";
        Path dumpFile = Path.of("/tmp", filename);

        try {
            log.info("Backup start: {}/{}", folder, filename);
            databaseProcess.dump(dumpFile);
            long size;
            try {
                size = Files.size(dumpFile);
            } catch (IOException ignored) {
                throw new IllegalStateException("無法讀取備份檔大小");
            }
            log.info("pg_dump done, size={} bytes", size);

            remoteClient.withVerifiedSession(session -> {
                session.upload(dumpFile, folder);
                return null;
            });

            // 本 method 無外層 transaction，saveAndFlush 的 repository transaction 會在 rotate 前提交。
            recordRepo.saveAndFlush(BackupRecord.builder()
                    .folder(folder)
                    .filename(filename)
                    .sizeBytes(size)
                    .modifiedAt(now)
                    .autoPreRestore(autoPreRestore)
                    .createdAt(now)
                    .build());
            log.info("Backup uploaded and indexed: {}/{}", folder, filename);

            return BackupDto.CreateResponse.builder()
                    .filename(filename)
                    .sizeBytes(size)
                    .uploadedAt(now)
                    .build();
        } finally {
            try {
                Files.deleteIfExists(dumpFile);
            } catch (IOException ignored) {
                log.warn("備份暫存檔清理失敗: {}", filename);
            }
        }
    }

    // ===== 自動排程（Task 388：cron 與 zone 逐字維持不變） =====

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
        if (runScheduledBackup("daily", DAILY_TW_PREFIX, "台股每日備份")) {
            rotateUsingCurrentSettingQuietly("daily", "台股每日備份完成後");
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
        if (runScheduledBackup("daily", DAILY_US_PREFIX, "美股每日備份")) {
            rotateUsingCurrentSettingQuietly("daily", "美股每日備份完成後");
        }
    }

    /** 每周日 05:00 → weekly/asset_weekly_*.dump */
    @Scheduled(cron = "0 0 5 * * SUN", zone = "Asia/Taipei")
    public void scheduledWeeklyBackup() {
        if (!Boolean.TRUE.equals(getSetting().getBackupEnabled())) {
            log.info("Skip weekly backup: 備份開關已關閉");
            return;
        }
        if (runScheduledBackup("weekly", WEEKLY_PREFIX, "每周備份")) {
            rotateUsingCurrentSettingQuietly("weekly", "每周備份完成後");
        }
    }

    private boolean runScheduledBackup(String folder, String prefix, String label) {
        try {
            doBackup(folder, prefix, false);
            return true;
        } catch (BackupRemoteUnavailableException exception) {
            log.error("{}失敗: {}", label, exception.getMessage());
            return false;
        } catch (RuntimeException ignored) {
            log.error("{}失敗: 備份子行程或本地索引目前不可用", label);
            return false;
        }
    }

    /** 列出所有備份：直接從 DB 讀，無需連 rclone，也不取 remote lock。 */
    public List<BackupDto.BackupItem> listBackups() {
        return recordRepo.findAllByOrderByModifiedAtDesc().stream()
                .map(record -> BackupDto.BackupItem.builder()
                        .folder(record.getFolder())
                        .filename(record.getFilename())
                        .sizeBytes(record.getSizeBytes())
                        .modifiedAt(record.getModifiedAt())
                        .autoPreRestore(Boolean.TRUE.equals(record.getAutoPreRestore()))
                        .build())
                .toList();
    }

    /** 四個 folder 必須在同一 verified session 依序列舉，全部成功後才開始 DB mutation。 */
    @Transactional
    public BackupDto.SyncResponse syncFromRemote() {
        List<RemoteBackupFile> remoteFiles = remoteClient.withVerifiedSession(session -> {
            List<RemoteBackupFile> collected = new ArrayList<>();
            for (String folder : FOLDERS) {
                collected.addAll(parseRemoteFiles(folder, session.listJson(folder)));
            }
            return collected;
        });

        int inserted = 0;
        Set<String> remoteKeys = new HashSet<>();
        LocalDateTime now = LocalDateTime.now(DISPLAY_ZONE);
        for (RemoteBackupFile remote : remoteFiles) {
            remoteKeys.add(remote.folder() + "/" + remote.filename());
            if (recordRepo.findByFolderAndFilename(remote.folder(), remote.filename()).isEmpty()) {
                recordRepo.save(BackupRecord.builder()
                        .folder(remote.folder())
                        .filename(remote.filename())
                        .sizeBytes(remote.size())
                        .modifiedAt(remote.modifiedAt())
                        .autoPreRestore(remote.filename().startsWith(AUTO_PRE_RESTORE_PREFIX))
                        .createdAt(now)
                        .build());
                inserted++;
            }
        }

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

    private List<RemoteBackupFile> parseRemoteFiles(String folder, String json) {
        List<RemoteBackupFile> files = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return files;
        }
        try {
            JsonNode array = mapper.readTree(json);
            if (!array.isArray()) {
                throw new IOException("not an array");
            }
            for (JsonNode node : array) {
                if (node.path("IsDir").asBoolean(false)) {
                    continue;
                }
                String name = node.path("Name").asText();
                if (!isSafeDumpFilename(name)) {
                    continue;
                }
                files.add(new RemoteBackupFile(
                        folder,
                        name,
                        node.path("Size").asLong(0),
                        parseRcloneTime(node.path("ModTime").asText())));
            }
            return files;
        } catch (IOException | RuntimeException ignored) {
            throw new IllegalStateException("解析備份 remote 清單失敗");
        }
    }

    /** 還原：先守門 → 建自救點 → 再守門下載 → pg_restore。 */
    public BackupDto.RestoreResponse runRestore(String folder, String filename) {
        if (!FOLDERS.contains(folder)) {
            throw new IllegalArgumentException("不允許的備份資料夾：" + folder);
        }
        if (!isSafeDumpFilename(filename)) {
            throw new IllegalArgumentException("不合法的備份檔名：" + filename);
        }

        // 先獨立守門；若帳號／root 不對，禁止建立自救點，更不得進 pg_restore。
        remoteClient.withVerifiedSession(session -> null);

        log.info("Restore: creating pre-restore backup");
        BackupDto.CreateResponse preRestore = doBackup("manual", AUTO_PRE_RESTORE_PREFIX, true);

        Path localFile = Path.of("/tmp", filename);
        try {
            remoteClient.withVerifiedSession(session -> {
                session.download(folder, filename);
                return null;
            });
            log.info("Restore: running pg_restore");
            databaseProcess.restore(localFile);
            log.info("Restore done: {}/{}", folder, filename);
            return BackupDto.RestoreResponse.builder()
                    .status("success")
                    .preRestoreBackup(preRestore.filename())
                    .restoredFrom(folder + "/" + filename)
                    .build();
        } finally {
            try {
                Files.deleteIfExists(localFile);
            } catch (IOException ignored) {
                log.warn("還原暫存檔清理失敗: {}", filename);
            }
        }
    }

    /**
     * 入口 gate 在讀 DB rows 之前；每筆 remote delete 成功／明確 missing 後才個別提交 DB delete。
     * 第一筆 unavailable 會中止 lambda，後序 command 與 DB row 維持不動。
     */
    private void rotateFolder(String folder, int retention) {
        remoteClient.withVerifiedSession(session -> {
            List<BackupRecord> records =
                    recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc(folder);
            if (records.size() <= retention) {
                return null;
            }
            for (int index = retention; index < records.size(); index++) {
                BackupRecord record = records.get(index);
                String filename = record.getFilename();
                if (!isSafeDumpFilename(filename)) {
                    throw new IllegalStateException("備份索引含不合法檔名");
                }
                log.info("Rotate: deleting old backup {}/{}", folder, filename);
                session.delete(folder, filename);
                // rotateFolder 無外層 transaction；每次 repository delete 各自 commit。
                recordRepo.delete(record);
            }
            return null;
        });
    }

    private void rotateQuietly(String folder, int retention, String context) {
        try {
            rotateFolder(folder, retention);
        } catch (BackupRemoteUnavailableException exception) {
            log.warn("{}輪替 {} 暫停: {}", context, folder, exception.getMessage());
        } catch (RuntimeException ignored) {
            log.warn("{}輪替 {} 暫停: 本地索引目前不可用", context, folder);
        }
    }

    /** 已完成的主要備份不可被 retention 讀取或輪替維護失敗反轉。 */
    private void rotateUsingCurrentSettingQuietly(String folder, String context) {
        try {
            BackupSetting current = getSetting();
            int retention = switch (folder) {
                case "manual" -> current.getManualRetention();
                case "daily" -> current.getDailyRetention();
                case "weekly" -> current.getWeeklyRetention();
                default -> throw new IllegalArgumentException("不允許的備份資料夾");
            };
            rotateFolder(folder, retention);
        } catch (BackupRemoteUnavailableException exception) {
            log.warn("{}輪替 {} 暫停: {}", context, folder, exception.getMessage());
        } catch (RuntimeException ignored) {
            log.warn("{}輪替 {} 暫停: 本地索引目前不可用", context, folder);
        }
    }

    private static boolean isSafeDumpFilename(String filename) {
        return filename != null
                && !filename.isBlank()
                && !filename.contains("/")
                && !filename.contains("\\")
                && !filename.contains("..")
                && filename.endsWith(".dump");
    }

    private static LocalDateTime parseRcloneTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return LocalDateTime.now(DISPLAY_ZONE);
        }
        return ZonedDateTime.parse(iso).withZoneSameInstant(DISPLAY_ZONE).toLocalDateTime();
    }

    private record RemoteBackupFile(String folder, String filename, long size, LocalDateTime modifiedAt) {}
}
