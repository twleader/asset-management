package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.steven.assets.dto.BackupDto;
import com.steven.assets.model.BackupRecord;
import com.steven.assets.model.BackupSetting;
import com.steven.assets.repository.BackupRecordRepository;
import com.steven.assets.repository.BackupSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    private static final LocalTime TW_DAILY_RECOVERY_AFTER = LocalTime.of(15, 30);
    private static final Path DEFAULT_PENDING_DIR = Path.of("/home/steven/.asset-backup-pending");
    static final String TW_DAILY_STARTUP_THREAD_NAME = "backup-tw-daily-startup-recovery";

    private final MarketDataService marketDataService;
    private final BackupSettingRepository settingRepo;
    private final BackupRecordRepository recordRepo;
    private final BackupRemoteClient remoteClient;
    private final BackupDatabaseProcess databaseProcess;
    private final ObjectMapper mapper;
    private final Path pendingDir;
    private final TransactionOperations indexTransaction;
    private final Supplier<ZonedDateTime> taipeiNow;
    private final Consumer<Runnable> backgroundStarter;
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;
    /** Lock order: operationLock, then remoteLock. Restore rescue reenters operationLock. */
    private final ReentrantLock operationLock = new ReentrantLock(true);
    private final AtomicBoolean destructiveWorkQuarantined = new AtomicBoolean();
    private final AtomicBoolean twDailyStartupSubmitted = new AtomicBoolean();

    @Autowired
    public BackupService(
            MarketDataService marketDataService,
            BackupSettingRepository settingRepo,
            BackupRecordRepository recordRepo,
            BackupRemoteClient remoteClient,
            BackupDatabaseProcess databaseProcess,
            PlatformTransactionManager transactionManager) {
        this(marketDataService, settingRepo, recordRepo, remoteClient, databaseProcess, DEFAULT_PENDING_DIR,
                new TransactionTemplate(transactionManager));
        this.transactionManager = transactionManager;
    }

    BackupService(
            MarketDataService marketDataService,
            BackupSettingRepository settingRepo,
            BackupRecordRepository recordRepo,
            BackupRemoteClient remoteClient,
            BackupDatabaseProcess databaseProcess,
            Path pendingDir) {
        this(marketDataService, settingRepo, recordRepo, remoteClient, databaseProcess, pendingDir,
                TransactionOperations.withoutTransaction());
    }

    BackupService(
            MarketDataService marketDataService,
            BackupSettingRepository settingRepo,
            BackupRecordRepository recordRepo,
            BackupRemoteClient remoteClient,
            BackupDatabaseProcess databaseProcess,
            Path pendingDir,
            TransactionOperations indexTransaction) {
        this(marketDataService, settingRepo, recordRepo, remoteClient, databaseProcess, pendingDir,
                indexTransaction, () -> ZonedDateTime.now(DISPLAY_ZONE),
                task -> Thread.ofVirtual().name(TW_DAILY_STARTUP_THREAD_NAME).start(task));
    }

    BackupService(
            MarketDataService marketDataService,
            BackupSettingRepository settingRepo,
            BackupRecordRepository recordRepo,
            BackupRemoteClient remoteClient,
            BackupDatabaseProcess databaseProcess,
            Path pendingDir,
            TransactionOperations indexTransaction,
            Supplier<ZonedDateTime> taipeiNow,
            Consumer<Runnable> backgroundStarter) {
        this.marketDataService = marketDataService;
        this.settingRepo = settingRepo;
        this.recordRepo = recordRepo;
        this.remoteClient = remoteClient;
        this.databaseProcess = databaseProcess;
        this.pendingDir = pendingDir;
        this.indexTransaction = indexTransaction;
        this.taipeiNow = taipeiNow;
        this.backgroundStarter = backgroundStarter;
        this.mapper = new ObjectMapper();
    }

    private ZonedDateTime currentTaipei() {
        return taipeiNow.get().withZoneSameInstant(DISPLAY_ZONE);
    }

    private <T> T executeIndexTransaction(TransactionCallback<T> work) {
        BackupWorkflowDeadline.requireWorkBudget();
        TransactionOperations transaction = indexTransaction;
        if (transactionManager != null) {
            TransactionTemplate bounded = new TransactionTemplate(transactionManager);
            long seconds = (BackupWorkflowDeadline.workMillis() + 999) / 1000;
            bounded.setTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds)));
            transaction = bounded;
        }
        return transaction.execute(status -> {
            if (entityManager != null) {
                // PostgreSQL 16 applies both limits to statements in this transaction only.
                // A waiting row lock must not outlive the workflow's remaining budget.
                String timeout = Math.max(1, Math.min(30_000, BackupWorkflowDeadline.workMillis())) + "ms";
                // Also bound the JDBC wait for COMMIT, which statement_timeout does not cover.
                entityManager.unwrap(Session.class).doWork(connection ->
                        connection.setNetworkTimeout(Runnable::run, 25_000));
                entityManager.createNativeQuery(
                        "select set_config('statement_timeout', ?1, true), set_config('lock_timeout', ?2, true)")
                        .setParameter(1, timeout)
                        .setParameter(2, timeout)
                        .getSingleResult();
            }
            T result = work.doInTransaction(status);
            BackupWorkflowDeadline.requireWorkBudget();
            return result;
        });
    }

    private Path mutationMarker() { return pendingDir.resolve(".backup-index-commit-in-flight"); }

    private void requireNoUncertainCommit() {
        if (destructiveWorkQuarantined.get()) throw BackupIndexCommitUncertainException.forOperation("提交狀態");
        try {
            Files.readAttributes(mutationMarker(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            destructiveWorkQuarantined.set(true);
            throw BackupIndexCommitUncertainException.forOperation("提交狀態");
        } catch (NoSuchFileException clear) {
            // The previous mutation has a known completion and cleared its marker.
        } catch (IOException unknown) {
            destructiveWorkQuarantined.set(true);
            throw BackupIndexCommitUncertainException.forOperation("提交狀態");
        }
    }

    private void markMutationInFlight(String operation, String exact) {
        requireNoUncertainCommit();
        try {
            Files.createDirectories(pendingDir);
            Files.setPosixFilePermissions(pendingDir, PosixFilePermissions.fromString("rwx------"));
            // Retained across restarts. Only an operator who has read back the exact DB row
            // and active remote object may remove this marker after resolving the outcome.
            Files.writeString(mutationMarker(), "commit acknowledgement pending\noperation=" + operation
                    + "\nexact=" + exact + "\n", StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(mutationMarker(), PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | RuntimeException unknown) {
            destructiveWorkQuarantined.set(true);
            throw BackupIndexCommitUncertainException.forOperation("提交狀態");
        }
    }

    private void clearMutationMarker() {
        try {
            Files.delete(mutationMarker());
        } catch (IOException unknown) {
            destructiveWorkQuarantined.set(true);
            throw BackupIndexCommitUncertainException.forOperation("提交狀態");
        }
    }

    private <T> T executeDurableMutation(String label, TransactionCallback<T> work) {
        return executeDurableMutation(label, "see exact DB and active Drive listing", work);
    }

    private <T> T executeDurableMutation(String label, String exact, TransactionCallback<T> work) {
        // A deadline failure before entering the transaction cannot have written an index row.
        BackupWorkflowDeadline.requireWorkBudget();
        markMutationInFlight(label, exact);
        return finishMarkedMutation(label, work);
    }

    private <T> T finishMarkedMutation(String label, TransactionCallback<T> work) {
        AtomicBoolean callbackEntered = new AtomicBoolean();
        final MutationOutcome<T> outcome;
        try {
            outcome = executeIndexTransaction(status -> {
                callbackEntered.set(true);
                try {
                    return new MutationOutcome<>(work.doInTransaction(status), null);
                } catch (RuntimeException failure) {
                    // Returning with rollback-only lets TransactionTemplate finish rollback.
                    // An exception from that completion is still an unknown outcome.
                    status.setRollbackOnly();
                    return new MutationOutcome<>(null, failure);
                }
            });
        } catch (RuntimeException uncertain) {
            if (!callbackEntered.get()) {
                clearMutationMarker();
                throw BackupIndexRollbackConfirmedException.beforeTransaction(label);
            }
            destructiveWorkQuarantined.set(true);
            throw BackupIndexCommitUncertainException.forOperation(label);
        }
        if (outcome == null) {
            destructiveWorkQuarantined.set(true);
            throw BackupIndexCommitUncertainException.forOperation(label);
        }
        if (outcome.failure() != null) {
            // The production PlatformTransactionManager returned normally after rollback-only.
            // Test-only TransactionOperations without a manager cannot prove rollback.
            if (transactionManager == null) {
                destructiveWorkQuarantined.set(true);
                throw BackupIndexCommitUncertainException.forOperation(label);
            }
            clearMutationMarker();
            throw BackupIndexRollbackConfirmedException.forOperation(label);
        }
        // An acknowledgement arriving after the HTTP workflow budget cannot be reported as success.
        if (BackupWorkflowDeadline.expired()) {
            destructiveWorkQuarantined.set(true);
            throw BackupIndexCommitUncertainException.forOperation(label);
        }
        clearMutationMarker();
        return outcome.value();
    }

    private record MutationOutcome<T>(T value, RuntimeException failure) {}

    /** 取得目前保留代數設定（無資料時回預設值，不寫入）。 */
    public BackupSetting getSetting() {
        return executeIndexTransaction(status -> settingRepo.findById(1).orElseGet(() -> BackupSetting.builder()
                .id(1)
                .manualRetention(DEFAULT_MANUAL_RETENTION)
                .dailyRetention(DEFAULT_DAILY_RETENTION)
                .weeklyRetention(DEFAULT_WEEKLY_RETENTION)
                .backupEnabled(Boolean.TRUE)
                .updatedAt(LocalDateTime.now(DISPLAY_ZONE))
                .build()));
    }

    /** 更新保留代數設定（含啟用開關），數值需介於 1～999。 */
    public BackupSetting updateSetting(Integer manual, Integer daily, Integer weekly, Boolean backupEnabled) {
        return BackupWorkflowDeadline.within(Duration.ofSeconds(1500), () -> {
            BackupWorkflowDeadline.lockWithinDeadline(operationLock);
            try {
                requireNoUncertainCommit();
                return updateSettingWithinDeadline(manual, daily, weekly, backupEnabled);
            } finally {
                operationLock.unlock();
            }
        });
    }

    private BackupSetting updateSettingWithinDeadline(Integer manual, Integer daily, Integer weekly, Boolean backupEnabled) {
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
        String intended = "manual=" + manual + ",daily=" + daily + ",weekly=" + weekly
                + ",enabled=" + setting.getBackupEnabled();
        BackupSetting saved = executeDurableMutation("保留設定", intended,
                status -> settingRepo.saveAndFlush(setting));
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
        return BackupWorkflowDeadline.within(Duration.ofSeconds(900), () -> {
            BackupWorkflowDeadline.lockWithinDeadline(operationLock);
            try {
                requireNoUncertainCommit();
                return runBackupWithinDeadline(autoPreRestore);
            } finally {
                operationLock.unlock();
            }
        });
    }

    private BackupDto.CreateResponse runBackupWithinDeadline(boolean autoPreRestore) {
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
        return doBackup(folder, prefix, autoPreRestore, null);
    }

    /**
     * expectedTaipeiDate prevents a queued daily workflow from naming a new-day dump as if it
     * belonged to the startup candidate. A null value preserves manual, US daily and weekly flows.
     */
    private BackupDto.CreateResponse doBackup(String folder, String prefix, boolean autoPreRestore,
                                               LocalDate expectedTaipeiDate) {
        return BackupWorkflowDeadline.within(Duration.ofSeconds(900), () -> {
            BackupWorkflowDeadline.lockWithinDeadline(operationLock);
            try {
                return doBackupExclusive(folder, prefix, autoPreRestore, expectedTaipeiDate);
            } finally {
                operationLock.unlock();
            }
        });
    }

    private BackupDto.CreateResponse doBackupExclusive(String folder, String prefix, boolean autoPreRestore,
                                                        LocalDate expectedTaipeiDate) {
        ZonedDateTime current = currentTaipei();
        if (expectedTaipeiDate != null && !current.toLocalDate().equals(expectedTaipeiDate)) {
            log.info("Skip TW daily backup: 台北日期已跨日，未重建歷史 snapshot");
            return null;
        }
        LocalDateTime now = current.toLocalDateTime();
        Path dumpFile = createPrivateDumpFile(prefix + now.format(TS_FMT) + "_");
        String filename = dumpFile.getFileName().toString();
        boolean preserveSource = false;
        boolean confirmedRollback = false;
        AtomicBoolean uploadAttempted = new AtomicBoolean();

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

            BackupDto.CreateResponse response = remoteClient.withVerifiedSession(session -> {
                uploadAttempted.set(true);
                session.upload(dumpFile, folder);
                // The transaction commits before the verified session releases remoteLock.
                // Sync must never observe this upload without its durable index row.
                try {
                    return executeDurableMutation("索引", folder + "/" + filename, status -> {
                        recordRepo.saveAndFlush(BackupRecord.builder()
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
                    });
                } catch (BackupIndexCommitUncertainException ignored) {
                    // A COMMIT error or timeout may mean committed or rolled back. Never infer absence.
                    throw BackupIndexCommitUncertainException.forFilename(filename);
                }
            });
            log.info("Backup uploaded and indexed: {}/{}", folder, filename);
            return response;
        } catch (BackupRemoteUnavailableException unavailable) {
            preserveSource = unavailable.resultUncertain();
            throw unavailable;
        } catch (BackupIndexCommitUncertainException uncertain) {
            preserveSource = true;
            throw uncertain;
        } catch (BackupIndexRollbackConfirmedException rolledBack) {
            preserveSource = true;
            confirmedRollback = true;
            throw rolledBack.beforeTransaction()
                    ? BackupIndexRollbackConfirmedException.forFilenameBeforeTransaction(filename)
                    : BackupIndexRollbackConfirmedException.forFilename(filename);
        } catch (RuntimeException failure) {
            if (uploadAttempted.get()) {
                preserveSource = true;
                throw BackupRemoteUnavailableException.uncertainUpload(filename);
            }
            throw failure;
        } finally {
            if (preserveSource) {
                if (confirmedRollback) {
                    log.warn("備份索引已確認回滾，已保留遠端 orphan 與本地來源供唯讀對帳: {}", filename);
                } else {
                    log.warn("備份上傳或索引提交結果未定，已保留本地來源供唯讀對帳: {}", filename);
                }
            } else {
                try {
                    Files.deleteIfExists(dumpFile);
                } catch (IOException ignored) {
                    log.warn("備份暫存檔清理失敗: {}", filename);
                }
            }
        }
    }

    private Path createPrivateDumpFile(String prefix) {
        try {
            Files.createDirectories(pendingDir);
            if (Files.isSymbolicLink(pendingDir)) throw new IOException("pending directory link");
            Files.setPosixFilePermissions(pendingDir, PosixFilePermissions.fromString("rwx------"));
            Path temp = Files.createTempFile(pendingDir, prefix, ".dump");
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (IOException | UnsupportedOperationException | SecurityException failure) {
                Files.deleteIfExists(temp);
                throw failure;
            }
            return temp;
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            throw new IllegalStateException("無法建立安全且可持續保存的備份來源暫存檔");
        }
    }

    // ===== 自動排程（Task 388：cron 與 zone 逐字維持不變） =====

    /**
     * Same-day only gap fill. ApplicationReady is deliberately non-blocking: the backup workflow
     * remains behind the existing admission lock and every failure is isolated from readiness.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverTwDailyOnApplicationReady() {
        ZonedDateTime candidate = currentTaipei();
        if (!candidate.toLocalTime().isAfter(TW_DAILY_RECOVERY_AFTER)) return;
        if (!twDailyStartupSubmitted.compareAndSet(false, true)) return;
        try {
            backgroundStarter.accept(() -> {
                try {
                    recoverTwDailyOnStartup(candidate.toLocalDate());
                } catch (RuntimeException ignored) {
                    log.warn("TW daily startup recovery outcome=FAILED reason=UNEXPECTED_FAILURE");
                }
            });
        } catch (RuntimeException ignored) {
            log.warn("TW daily startup recovery outcome=FAILED reason=THREAD_START_FAILED");
        }
    }

    /** Visible to deterministic tests: candidate date is captured by the ready listener. */
    void recoverTwDailyOnStartup(LocalDate candidateDate) {
        BackupWorkflowDeadline.within(Duration.ofSeconds(900), () -> {
            BackupWorkflowDeadline.lockWithinDeadline(operationLock);
            try {
                ZonedDateTime current = currentTaipei();
                if (!current.toLocalDate().equals(candidateDate)) {
                    log.info("Skip TW daily startup recovery: 台北日期已跨日，未重建歷史 snapshot");
                    return null;
                }
                if (!current.toLocalTime().isAfter(TW_DAILY_RECOVERY_AFTER)) {
                    log.info("Skip TW daily startup recovery: 尚未晚於 15:30");
                    return null;
                }
                requireNoUncertainCommit();
                scheduledDailyTwWithinDeadline(candidateDate);
            } finally {
                operationLock.unlock();
            }
            return null;
        });
    }

    /** 台股交易日 15:30（收盤後 2 小時）→ daily/asset_daily_tw_*.dump */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledDailyTwBackup() {
        BackupWorkflowDeadline.within(Duration.ofSeconds(900), () -> {
            BackupWorkflowDeadline.lockWithinDeadline(operationLock);
            try {
                requireNoUncertainCommit();
                scheduledDailyTwWithinDeadline(currentTaipei().toLocalDate());
            } finally {
                operationLock.unlock();
            }
            return null;
        });
    }

    /** Both cron and startup recovery call this only while operationLock is held. */
    private void scheduledDailyTwWithinDeadline(LocalDate today) {
        if (!Boolean.TRUE.equals(getSetting().getBackupEnabled())) {
            log.info("Skip TW daily backup: 備份開關已關閉");
            return;
        }
        if (!marketDataService.isTwTradingDay(today)) {
            log.info("Skip TW daily backup: {} 非台股交易日", today);
            return;
        }
        String filenamePrefix = DAILY_TW_PREFIX + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "_";
        if (recordRepo.existsByFolderAndFilenameStartingWith("daily", filenamePrefix)) {
            log.info("Skip TW daily backup: {} 已有 durable backup_record", today);
            return;
        }
        if (runScheduledBackup("daily", DAILY_TW_PREFIX, "台股每日備份", today)) {
            rotateUsingCurrentSettingQuietly("daily", "台股每日備份完成後");
        }
    }

    /** 美股收盤後 2 小時，台北時間隔日 07:00 → daily/asset_daily_us_*.dump */
    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")
    public void scheduledDailyUsBackup() {
        BackupWorkflowDeadline.within(Duration.ofSeconds(900), () -> {
            BackupWorkflowDeadline.lockWithinDeadline(operationLock);
            try {
                requireNoUncertainCommit();
                scheduledDailyUsWithinDeadline();
            } finally {
                operationLock.unlock();
            }
            return null;
        });
    }

    private void scheduledDailyUsWithinDeadline() {
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
        BackupWorkflowDeadline.within(Duration.ofSeconds(900), () -> {
            BackupWorkflowDeadline.lockWithinDeadline(operationLock);
            try {
                requireNoUncertainCommit();
                scheduledWeeklyWithinDeadline();
            } finally {
                operationLock.unlock();
            }
            return null;
        });
    }

    private void scheduledWeeklyWithinDeadline() {
        if (!Boolean.TRUE.equals(getSetting().getBackupEnabled())) {
            log.info("Skip weekly backup: 備份開關已關閉");
            return;
        }
        if (runScheduledBackup("weekly", WEEKLY_PREFIX, "每周備份")) {
            rotateUsingCurrentSettingQuietly("weekly", "每周備份完成後");
        }
    }

    private boolean runScheduledBackup(String folder, String prefix, String label) {
        return runScheduledBackup(folder, prefix, label, null);
    }

    private boolean runScheduledBackup(String folder, String prefix, String label, LocalDate expectedTaipeiDate) {
        try {
            return doBackup(folder, prefix, false, expectedTaipeiDate) != null;
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
    public BackupDto.SyncResponse syncFromRemote() {
        return BackupWorkflowDeadline.within(Duration.ofSeconds(1500), () -> {
            BackupWorkflowDeadline.lockWithinDeadline(operationLock);
            try {
                requireNoUncertainCommit();
                return syncWithinDeadline();
            } finally {
                operationLock.unlock();
            }
        });
    }

    private BackupDto.SyncResponse syncWithinDeadline() {
        return remoteClient.withVerifiedSession(session -> {
            List<RemoteBackupFile> collected = collectRemoteFiles(session);
            Set<String> keys = new HashSet<>();
            for (RemoteBackupFile file : collected) {
                if (!keys.add(file.folder() + "/" + file.filename())) {
                    throw new IllegalStateException("備份 remote 清單有重複項目");
                }
            }
            return executeDurableMutation("同步", status -> {
                List<BackupRecord> indexed = recordRepo.findAll();
                List<BackupRecord> absent = new ArrayList<>();
                for (BackupRecord row : indexed) {
                    if (!isActiveRow(row)) continue; // frozen legacy is never reconciled away
                    if (!keys.contains(row.getFolder() + "/" + row.getFilename())) {
                        if (!session.proveAbsent(row.getFolder(), row.getFilename())) {
                            throw BackupRemoteUnavailableException.remoteUnavailable();
                        }
                        absent.add(row);
                    }
                }
                if (!absent.isEmpty()) requireDynamicAnchor(session, indexed, keys);
                int inserted = 0;
                LocalDateTime now = LocalDateTime.now(DISPLAY_ZONE);
                for (RemoteBackupFile remote : collected) {
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
                for (BackupRecord row : absent) {
                    recordRepo.delete(row);
                    deleted++;
                }
                log.info("Backup sync done: inserted={}, deleted={}", inserted, deleted);
                return BackupDto.SyncResponse.builder()
                        .inserted(inserted)
                        .deleted(deleted)
                        .total(collected.size())
                        .build();
            });
        });
    }

    private List<RemoteBackupFile> collectRemoteFiles(BackupRemoteClient.Session session) {
        final JsonNode directories;
        try {
            directories = mapper.readTree(session.listDateDirectoriesJson());
            if (!directories.isArray()) throw new IOException("invalid date root listing");
        } catch (IOException | RuntimeException invalid) {
            throw BackupRemoteUnavailableException.remoteUnavailable();
        }
        Set<String> seen = new HashSet<>();
        List<RemoteBackupFile> collected = new ArrayList<>();
        for (JsonNode entry : directories) {
            if (!entry.hasNonNull("Name") || !entry.has("IsDir")) {
                throw BackupRemoteUnavailableException.remoteUnavailable();
            }
            String name = entry.path("Name").asText();
            if (!name.matches("\\d{4}-\\d{2}-\\d{2}")) continue; // legacy/non-date paths remain untouched
            final LocalDate date;
            try {
                date = LocalDate.parse(name);
            } catch (RuntimeException invalid) {
                throw BackupRemoteUnavailableException.remoteUnavailable();
            }
            if (!entry.path("IsDir").asBoolean(false) || !seen.add(name)) {
                throw BackupRemoteUnavailableException.remoteUnavailable();
            }
            if (!date.isBefore(BackupPath.CUTOVER)) {
                collected.addAll(parseRemoteFiles(name, session.listJson(name)));
            }
        }
        return collected;
    }

    private static boolean isActiveRow(BackupRecord row) {
        try {
            return BackupPath.of(row.getFolder(), row.getFilename()).active();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void requireDynamicAnchor(BackupRemoteClient.Session session, List<BackupRecord> indexed,
                                      Set<String> remoteKeys) {
        BackupRecord anchor = indexed.stream()
                .filter(BackupService::isActiveRow)
                .filter(row -> remoteKeys.contains(row.getFolder() + "/" + row.getFilename()))
                .max(java.util.Comparator.comparing(BackupRecord::getModifiedAt))
                .orElseThrow(BackupRemoteUnavailableException::remoteUnavailable);
        if (!"PGDMP".equals(session.readHeader(anchor.getFolder(), anchor.getFilename()))) {
            throw BackupRemoteUnavailableException.remoteUnavailable();
        }
    }

    private List<RemoteBackupFile> parseRemoteFiles(String dateFolder, String json) {
        List<RemoteBackupFile> files = new ArrayList<>();
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("備份 remote 清單不完整");
        }
        try {
            JsonNode array = mapper.readTree(json);
            if (!array.isArray()) {
                throw new IOException("not an array");
            }
            for (JsonNode node : array) {
                if (!node.hasNonNull("Name") || !node.has("IsDir")
                        || !node.hasNonNull("Size") || !node.hasNonNull("ModTime")) {
                    throw new IOException("incomplete listing row");
                }
                if (node.path("IsDir").asBoolean(false)) {
                    continue;
                }
                String name = node.path("Name").asText();
                BackupPath path = BackupPath.fromFilename(name);
                if (!path.active() || !path.dateFolder().equals(dateFolder)) {
                    throw new IOException("mismatched backup date");
                }
                files.add(new RemoteBackupFile(
                        path.folder(),
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
        return BackupWorkflowDeadline.within(Duration.ofSeconds(1500), () -> {
            BackupWorkflowDeadline.lockWithinDeadline(operationLock);
            try {
                requireNoUncertainCommit();
                return restoreWithinDeadline(folder, filename);
            } finally {
                operationLock.unlock();
            }
        });
    }

    private BackupDto.RestoreResponse restoreWithinDeadline(String folder, String filename) {
        if (!FOLDERS.contains(folder)) {
            throw new IllegalArgumentException("不允許的備份資料夾：" + folder);
        }
        final BackupPath path;
        try {
            path = BackupPath.of(folder, filename);
        } catch (IllegalArgumentException unavailable) {
            throw new BackupLegacyUnavailableException();
        }
        if (!path.active() || executeIndexTransaction(status ->
                recordRepo.findByFolderAndFilename(folder, filename)).isEmpty()) {
            throw new BackupLegacyUnavailableException();
        }

        // Keep the entire preflight within 570 seconds, preserving 900 + 30 for pg_restore and cleanup.
        RestorePreflight preflight = BackupWorkflowDeadline.within(Duration.ofSeconds(570), () -> {
            remoteClient.withVerifiedSession(session -> null);
            log.info("Restore: creating pre-restore backup");
            BackupDto.CreateResponse rescue = doBackup("manual", AUTO_PRE_RESTORE_PREFIX, true);
            Path target;
            try {
                target = Files.createTempFile("backup-restore-", ".dump");
            } catch (IOException ignored) {
                throw new IllegalStateException("無法建立還原暫存檔；自救點為 " + rescue.filename());
            }
            try {
                remoteClient.withVerifiedSession(session -> {
                    session.download(folder, filename, target);
                    return null;
                });
                databaseProcess.validate(target);
                return new RestorePreflight(rescue, target);
            } catch (RuntimeException failure) {
                try { Files.deleteIfExists(target); } catch (IOException ignored) { }
                throw new IllegalStateException("來源下載或驗證失敗，未執行 pg_restore；已保留自救點 "
                        + rescue.filename());
            }
        });
        BackupDto.CreateResponse preRestore = preflight.rescue();
        Path localFile = preflight.localFile();
        try {
            try {
                BackupWorkflowDeadline.requireSeconds(930);
            } catch (IllegalStateException expired) {
                throw new IllegalStateException("還原前置階段超過期限，未執行 pg_restore；已保留自救點 "
                        + preRestore.filename());
            }
            log.info("Restore: running pg_restore");
            try {
                databaseProcess.restore(localFile);
            } catch (RuntimeException ignored) {
                throw new IllegalStateException("pg_restore 失敗或逾時，資料庫可能部分還原；已保留自救點 "
                        + preRestore.filename() + "，請以該 exact 檔案重新還原");
            }
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
        requireNoUncertainCommit();
        remoteClient.withVerifiedSession(session -> {
            List<BackupRecord> records = executeIndexTransaction(status ->
                    recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc(folder))
                    .stream().filter(BackupService::isActiveRow).toList();
            if (records.size() <= retention) {
                return null;
            }
            List<RemoteBackupFile> remoteFiles = collectRemoteFiles(session);
            Set<String> remoteKeys = new HashSet<>();
            for (RemoteBackupFile file : remoteFiles) {
                if (!remoteKeys.add(file.folder() + "/" + file.filename())) {
                    throw BackupRemoteUnavailableException.remoteUnavailable();
                }
            }
            List<BackupRecord> indexed = executeIndexTransaction(status -> recordRepo.findAll());
            requireDynamicAnchor(session, indexed, remoteKeys);
            for (int index = retention; index < records.size(); index++) {
                BackupRecord record = records.get(index);
                String filename = record.getFilename();
                log.info("Rotate: deleting old backup {}/{}", folder, filename);
                // Persist the exact candidate before the first destructive command. A timed-out
                // delete may have succeeded remotely; its result must remain quarantined.
                markMutationInFlight("輪替", folder + "/" + filename);
                try {
                    if (remoteKeys.contains(folder + "/" + filename)) {
                        session.delete(folder, filename);
                    } else if (!session.proveAbsent(folder, filename)) {
                        throw BackupRemoteUnavailableException.remoteUnavailable();
                    }
                    // Each row commits separately while both admission and remote locks remain held.
                    finishMarkedMutation("輪替", status -> {
                        recordRepo.delete(record);
                        return null;
                    });
                } catch (BackupIndexRollbackConfirmedException rolledBack) {
                    // The remote delete returned a known result and DB rollback completed.
                    // A later full listing can reconcile the still-indexed row safely.
                    throw rolledBack;
                } catch (RuntimeException uncertain) {
                    destructiveWorkQuarantined.set(true);
                    throw BackupIndexCommitUncertainException.forOperation("輪替");
                }
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

    private static LocalDateTime parseRcloneTime(String iso) {
        if (iso == null || iso.isBlank()) {
            throw new IllegalArgumentException("remote timestamp missing");
        }
        return ZonedDateTime.parse(iso).withZoneSameInstant(DISPLAY_ZONE).toLocalDateTime();
    }

    private record RemoteBackupFile(String folder, String filename, long size, LocalDateTime modifiedAt) {}
    private record RestorePreflight(BackupDto.CreateResponse rescue, Path localFile) {}
}
