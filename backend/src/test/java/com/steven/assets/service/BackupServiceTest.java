package com.steven.assets.service;

import com.steven.assets.dto.BackupDto;
import com.steven.assets.model.BackupRecord;
import com.steven.assets.model.BackupSetting;
import com.steven.assets.repository.BackupRecordRepository;
import com.steven.assets.repository.BackupSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class BackupServiceTest {

    @Mock
    private MarketDataService marketDataService;
    @Mock
    private BackupSettingRepository settingRepo;
    @Mock
    private BackupRecordRepository recordRepo;
    @Mock
    private BackupDatabaseProcess databaseProcess;

    private QueueRemoteClient remoteClient;
    private BackupService service;
    private BackupSetting setting;
    @TempDir
    java.nio.file.Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        remoteClient = new QueueRemoteClient();
        service = new BackupService(marketDataService, settingRepo, recordRepo, remoteClient,
                databaseProcess, tempDir.resolve("pending"));
        setting = BackupSetting.builder()
                .id(1)
                .manualRetention(1)
                .dailyRetention(1)
                .weeklyRetention(1)
                .backupEnabled(true)
                .updatedAt(LocalDateTime.now())
                .build();
        when(settingRepo.findById(1)).thenReturn(Optional.of(setting));
        when(recordRepo.findByFolderAndFilename("manual", "asset_manual_20261002_010101.dump"))
                .thenReturn(Optional.of(record(100, "asset_manual_20261002_010101.dump")));
        doAnswer(invocation -> {
            java.nio.file.Path output = invocation.getArgument(0);
            Files.write(output, new byte[]{1, 2, 3, 4});
            return null;
        }).when(databaseProcess).dump(any());
    }

    @Test
    void manualGateFailureDoesNotWriteBackupRecord() {
        remoteClient.fail(BackupRemoteUnavailableException.rootMissing());

        assertThatThrownBy(() -> service.runBackup(false))
                .isInstanceOf(BackupRemoteUnavailableException.class)
                .hasMessageContaining("不會自動建立");

        verify(recordRepo, never()).save(any());
        verify(recordRepo, never()).saveAndFlush(any());
        assertThat(remoteClient.remaining()).isZero();
    }

    @Test
    void manualUploadAndIndexStaySuccessfulWhenPostCommitRotationFails(CapturedOutput output) {
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        remoteClient.use(upload);
        remoteClient.fail(BackupRemoteUnavailableException.authenticationUnavailable());

        BackupDto.CreateResponse response = service.runBackup(false);

        assertThat(response.filename()).startsWith("asset_manual_").endsWith(".dump");
        assertThat(response.sizeBytes()).isEqualTo(4);
        verify(upload).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        verify(recordRepo).saveAndFlush(any(BackupRecord.class));
        assertThat(output.getOut())
                .contains("手動備份完成後輪替 manual 暫停")
                .doesNotContain("invalid_grant")
                .doesNotContain("refresh_token");
    }

    @Test
    void manualUploadAndIndexStaySuccessfulWhenRetentionReadFails(CapturedOutput output) {
        when(settingRepo.findById(1))
                .thenReturn(Optional.of(setting))
                .thenThrow(new IllegalStateException("refresh_token=RETENTION_SENTINEL"));
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        remoteClient.use(upload);

        BackupDto.CreateResponse response = service.runBackup(false);

        assertThat(response.filename()).startsWith("asset_manual_").endsWith(".dump");
        verify(upload).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        verify(recordRepo).saveAndFlush(any(BackupRecord.class));
        verify(settingRepo, org.mockito.Mockito.times(2)).findById(1);
        assertThat(output.getOut())
                .contains("手動備份完成後輪替 manual 暫停: 本地索引目前不可用")
                .doesNotContain("RETENTION_SENTINEL")
                .doesNotContain("refresh_token");
    }

    @Test
    void twoRapidManualRequestsUseDifferentFilenamesAndNeverOverwriteEachOther() {
        List<String> uploaded = new ArrayList<>();
        BackupRemoteClient.Session first = mock(BackupRemoteClient.Session.class);
        BackupRemoteClient.Session second = mock(BackupRemoteClient.Session.class);
        doAnswer(invocation -> {
            uploaded.add(((java.nio.file.Path) invocation.getArgument(0)).getFileName().toString());
            return null;
        }).when(first).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        doAnswer(invocation -> {
            uploaded.add(((java.nio.file.Path) invocation.getArgument(0)).getFileName().toString());
            return null;
        }).when(second).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        remoteClient.use(first);
        remoteClient.use(mock(BackupRemoteClient.Session.class)); // post-commit rotation
        remoteClient.use(second);
        remoteClient.use(mock(BackupRemoteClient.Session.class));

        BackupDto.CreateResponse one = service.runBackup(false);
        BackupDto.CreateResponse two = service.runBackup(false);

        assertThat(one.filename()).isNotEqualTo(two.filename());
        assertThat(uploaded).containsExactly(one.filename(), two.filename());
        verify(recordRepo, org.mockito.Mockito.times(2)).saveAndFlush(any(BackupRecord.class));
    }

    @Test
    void failedIndexCommitReportsUncertainAndPreservesExactRemoteAndLocalSource() throws Exception {
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        AtomicReference<java.nio.file.Path> localSource = new AtomicReference<>();
        doAnswer(invocation -> {
            localSource.set(invocation.getArgument(0));
            return null;
        }).when(upload).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        remoteClient.use(upload);
        when(recordRepo.saveAndFlush(any(BackupRecord.class)))
                .thenThrow(new IllegalStateException("client_secret=COMMIT_SENTINEL"));

        assertThatThrownBy(() -> service.runBackup(false))
                .isInstanceOf(BackupIndexCommitUncertainException.class)
                .hasMessageContaining("索引提交結果未定")
                .hasMessageContaining("唯讀核對")
                .hasMessageNotContaining("COMMIT_SENTINEL");

        verify(upload).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        verify(upload, never()).delete(any(), any());
        verify(recordRepo, never()).findByFolderAndFilename(any(), any());
        assertThat(localSource.get()).exists();
        assertThat(remoteClient.remaining()).isZero(); // no retention session
        Files.deleteIfExists(localSource.get());
    }

    @Test
    void confirmedRollbackLeavesOrphanAndSourceButClearsMarkerForLaterBackup() throws Exception {
        AtomicInteger rollbacks = new AtomicInteger();
        org.springframework.transaction.PlatformTransactionManager manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
                if (status.isRollbackOnly()) rollbacks.incrementAndGet();
            }

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
                rollbacks.incrementAndGet();
            }
        };
        BackupService confirmed = new BackupService(marketDataService, settingRepo, recordRepo, remoteClient,
                databaseProcess, tempDir.resolve("confirmed-rollback"));
        org.springframework.test.util.ReflectionTestUtils.setField(confirmed, "transactionManager", manager);
        BackupRemoteClient.Session first = mock(BackupRemoteClient.Session.class);
        BackupRemoteClient.Session second = mock(BackupRemoteClient.Session.class);
        AtomicReference<java.nio.file.Path> source = new AtomicReference<>();
        doAnswer(invocation -> {
            source.set(invocation.getArgument(0));
            return null;
        }).when(first).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        remoteClient.use(first);
        remoteClient.use(second);
        when(recordRepo.saveAndFlush(any(BackupRecord.class)))
                .thenThrow(new IllegalStateException("client_secret=ROLLBACK_SENTINEL"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> confirmed.runBackup(true))
                .isInstanceOf(BackupIndexRollbackConfirmedException.class)
                .hasMessageContaining("已確認回滾")
                .hasMessageNotContaining("ROLLBACK_SENTINEL");
        assertThat(rollbacks).hasValue(1);
        assertThat(source.get()).exists();
        assertThat(tempDir.resolve("confirmed-rollback/.backup-index-commit-in-flight")).doesNotExist();
        verify(first, never()).delete(any(), any());

        assertThat(confirmed.runBackup(true).filename()).startsWith("asset_auto-pre-restore_");
        assertThat(remoteClient.remaining()).isZero();
        Files.deleteIfExists(source.get());
    }

    @Test
    void rollbackCompletionFailureKeepsPersistentQuarantine() {
        org.springframework.transaction.PlatformTransactionManager manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
                throw new IllegalStateException("rollback acknowledgement lost");
            }

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
                throw new IllegalStateException("rollback acknowledgement lost");
            }
        };
        BackupService uncertain = new BackupService(marketDataService, settingRepo, recordRepo, remoteClient,
                databaseProcess, tempDir.resolve("rollback-unknown"));
        org.springframework.test.util.ReflectionTestUtils.setField(uncertain, "transactionManager", manager);
        remoteClient.use(mock(BackupRemoteClient.Session.class));
        when(recordRepo.saveAndFlush(any(BackupRecord.class)))
                .thenThrow(new IllegalStateException("DB callback failed"));

        assertThatThrownBy(() -> uncertain.runBackup(true))
                .isInstanceOf(BackupIndexCommitUncertainException.class);
        assertThat(tempDir.resolve("rollback-unknown/.backup-index-commit-in-flight")).exists();
        assertThatThrownBy(() -> uncertain.runBackup(true))
                .isInstanceOf(BackupIndexCommitUncertainException.class);
        verify(recordRepo, org.mockito.Mockito.times(1)).saveAndFlush(any(BackupRecord.class));
    }

    @Test
    void commitAcknowledgementFailureKeepsMarkerAndBlocksFollowingCreate() {
        org.springframework.transaction.PlatformTransactionManager manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
                throw new IllegalStateException("commit acknowledgement lost");
            }

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) { }
        };
        BackupService uncertain = new BackupService(marketDataService, settingRepo, recordRepo, remoteClient,
                databaseProcess, tempDir.resolve("commit-ack-unknown"));
        org.springframework.test.util.ReflectionTestUtils.setField(uncertain, "transactionManager", manager);
        remoteClient.use(mock(BackupRemoteClient.Session.class));

        assertThatThrownBy(() -> uncertain.runBackup(true))
                .isInstanceOf(BackupIndexCommitUncertainException.class);
        assertThat(tempDir.resolve("commit-ack-unknown/.backup-index-commit-in-flight")).exists();
        assertThatThrownBy(() -> uncertain.runBackup(true))
                .isInstanceOf(BackupIndexCommitUncertainException.class);
        verify(recordRepo, org.mockito.Mockito.times(1)).saveAndFlush(any(BackupRecord.class));
    }

    @Test
    void confirmedRotationIndexRollbackClearsMarkerAfterKnownRemoteDelete() {
        AtomicInteger rollbacks = new AtomicInteger();
        org.springframework.transaction.PlatformTransactionManager manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
                if (status.isRollbackOnly()) rollbacks.incrementAndGet();
            }

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
                rollbacks.incrementAndGet();
            }
        };
        BackupService rotation = new BackupService(marketDataService, settingRepo, recordRepo, remoteClient,
                databaseProcess, tempDir.resolve("rotation-rollback"));
        org.springframework.test.util.ReflectionTestUtils.setField(rotation, "transactionManager", manager);
        BackupRecord newest = record(1, "asset_manual_20261002_010101_a.dump");
        BackupRecord old = record(2, "asset_manual_20261002_010101_b.dump");
        when(recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc("manual"))
                .thenReturn(List.of(newest, old));
        when(recordRepo.findAll()).thenReturn(List.of(newest, old));
        doThrow(new IllegalStateException("DB delete rejected")).when(recordRepo).delete(old);
        BackupRemoteClient.Session session = mock(BackupRemoteClient.Session.class);
        when(session.listDateDirectoriesJson()).thenReturn("[{\"Name\":\"2026-10-02\",\"IsDir\":true}]");
        when(session.listJson("2026-10-02")).thenReturn("[{\"Name\":\"asset_manual_20261002_010101_a.dump\",\"IsDir\":false,\"Size\":10,\"ModTime\":\"2026-10-02T01:01:01Z\"},"
                + "{\"Name\":\"asset_manual_20261002_010101_b.dump\",\"IsDir\":false,\"Size\":10,\"ModTime\":\"2026-10-02T01:01:01Z\"}]");
        when(session.readHeader("manual", newest.getFilename())).thenReturn("PGDMP");
        when(session.delete("manual", old.getFilename())).thenReturn(BackupRemoteClient.DeleteResult.DELETED);
        remoteClient.use(session);

        assertThatThrownBy(() -> BackupWorkflowDeadline.within(Duration.ofSeconds(1500),
                () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(rotation,
                        "rotateFolder", "manual", 1)))
                .isInstanceOf(BackupIndexRollbackConfirmedException.class);
        assertThat(rollbacks).hasValue(1);
        assertThat(tempDir.resolve("rotation-rollback/.backup-index-commit-in-flight")).doesNotExist();
        verify(session).delete("manual", old.getFilename());
    }

    @Test
    void commitAcknowledgementLossDoesNotTurnExistingRowIntoNewCreateSuccess() throws Exception {
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        AtomicReference<java.nio.file.Path> localSource = new AtomicReference<>();
        doAnswer(invocation -> {
            localSource.set(invocation.getArgument(0));
            return null;
        }).when(upload).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        remoteClient.use(upload);
        TransactionOperations acknowledgementLost = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
                throw new IllegalStateException("commit response lost");
            }
        };
        BackupService uncertain = new BackupService(marketDataService, settingRepo, recordRepo, remoteClient,
                databaseProcess, tempDir.resolve("commit-unknown"), acknowledgementLost);
        // The readback could find the row after an uncertain commit. It must remain read-only
        // and cannot be used to convert this request into a fresh successful create.
        when(recordRepo.findByFolderAndFilename(any(), any()))
                .thenReturn(Optional.of(record(99, "already-indexed.dump")));

        assertThatThrownBy(() -> uncertain.runBackup(true))
                .isInstanceOf(BackupIndexCommitUncertainException.class)
                .hasMessageContaining("索引提交結果未定");

        verify(recordRepo).saveAndFlush(any(BackupRecord.class));
        verify(recordRepo, never()).findByFolderAndFilename(any(), any());
        verify(upload, never()).delete(any(), any());
        assertThat(localSource.get()).exists();
        Files.deleteIfExists(localSource.get());
    }

    @Test
    void syncCannotIndexUploadedFileBeforeCreateCommitCompletes() throws Exception {
        ReentrantLock remoteLock = new ReentrantLock(true);
        AtomicReference<String> uploadedName = new AtomicReference<>();
        AtomicReference<BackupRecord> pending = new AtomicReference<>();
        AtomicReference<BackupRecord> committed = new AtomicReference<>();
        AtomicReference<Thread> syncThread = new AtomicReference<>();
        AtomicInteger sessionsEntered = new AtomicInteger();
        AtomicInteger transactions = new AtomicInteger();
        CountDownLatch atCreateCommit = new CountDownLatch(1);
        CountDownLatch allowCreateCommit = new CountDownLatch(1);
        BackupRemoteClient.Session session = mock(BackupRemoteClient.Session.class);
        doAnswer(invocation -> {
            uploadedName.set(((java.nio.file.Path) invocation.getArgument(0)).getFileName().toString());
            return null;
        }).when(session).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        when(session.listDateDirectoriesJson()).thenAnswer(invocation -> {
            if (uploadedName.get() == null) return "[]";
            return "[{\"Name\":\"" + BackupPath.fromFilename(uploadedName.get()).dateFolder()
                    + "\",\"IsDir\":true}]";
        });
        when(session.listJson(any())).thenAnswer(invocation -> {
            if (uploadedName.get() == null
                    || !BackupPath.fromFilename(uploadedName.get()).dateFolder()
                            .equals(invocation.getArgument(0))) return "[]";
            return "[{\"Name\":\"" + uploadedName.get()
                    + "\",\"IsDir\":false,\"Size\":4,\"ModTime\":\"2026-10-02T00:00:00Z\"}]";
        });
        BackupRemoteClient lockedRemote = new BackupRemoteClient() {
            @Override
            public <T> T withVerifiedSession(SessionWork<T> work) {
                remoteLock.lock();
                try {
                    sessionsEntered.incrementAndGet();
                    return work.apply(session);
                } finally {
                    remoteLock.unlock();
                }
            }
        };
        when(recordRepo.saveAndFlush(any(BackupRecord.class))).thenAnswer(invocation -> {
            pending.set(invocation.getArgument(0));
            return pending.get();
        });
        when(recordRepo.findAll()).thenAnswer(invocation ->
                committed.get() == null ? List.of() : List.of(committed.get()));
        when(recordRepo.findByFolderAndFilename(any(), any())).thenAnswer(invocation ->
                Optional.ofNullable(committed.get()));
        TransactionOperations transaction = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T value = action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
                if (transactions.incrementAndGet() == 1) {
                    atCreateCommit.countDown();
                    try {
                        if (!allowCreateCommit.await(5, TimeUnit.SECONDS)) throw new AssertionError("commit stalled");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    committed.set(pending.get());
                }
                return value;
            }
        };
        BackupService concurrentService = new BackupService(marketDataService, settingRepo, recordRepo,
                lockedRemote, databaseProcess, tempDir.resolve("create-sync"), transaction);
        ReentrantLock admissionLock = (ReentrantLock) org.springframework.test.util.ReflectionTestUtils
                .getField(concurrentService, "operationLock");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var create = executor.submit(() -> concurrentService.runBackup(true));
            assertThat(atCreateCommit.await(5, TimeUnit.SECONDS)).isTrue();
            var sync = executor.submit(() -> {
                syncThread.set(Thread.currentThread());
                return concurrentService.syncFromRemote();
            });
            long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < queueDeadline
                    && (syncThread.get() == null || !admissionLock.hasQueuedThread(syncThread.get()))) {
                Thread.onSpinWait();
            }
            assertThat(admissionLock.hasQueuedThread(syncThread.get())).isTrue();
            assertThat(sessionsEntered).hasValue(1);
            verify(recordRepo, never()).save(any(BackupRecord.class));
            allowCreateCommit.countDown();
            BackupDto.CreateResponse created = create.get(5, TimeUnit.SECONDS);
            BackupDto.SyncResponse reconciled = sync.get(5, TimeUnit.SECONDS);
            assertThat(created.filename()).isEqualTo(uploadedName.get());
            assertThat(reconciled.inserted()).isZero();
            verify(recordRepo, never()).save(any(BackupRecord.class));
        } finally {
            allowCreateCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void createAdmissionLockUsesRemainingWorkflowBudget() throws Exception {
        ReentrantLock admissionLock = (ReentrantLock) org.springframework.test.util.ReflectionTestUtils
                .getField(service, "operationLock");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> {
                admissionLock.lock();
                try {
                    held.countDown();
                    release.await(5, TimeUnit.SECONDS);
                } finally {
                    admissionLock.unlock();
                }
                return null;
            });
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> BackupWorkflowDeadline.within(Duration.ofMillis(30_050),
                    () -> service.runBackup(false)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("期限不足");
            verifyNoInteractions(databaseProcess);
            assertThat(remoteClient.remaining()).isZero();
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void subsecondWorkBudgetDoesNotStartAWholeSecondChildProcess() {
        assertThatThrownBy(() -> BackupWorkflowDeadline.within(Duration.ofMillis(30_500),
                () -> BackupWorkflowDeadline.commandSeconds(300)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("期限不足");
    }

    @Test
    void expiredDatabaseCallbackCannotCommitUploadedBackup() {
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        remoteClient.use(upload);
        AtomicBoolean committed = new AtomicBoolean();
        TransactionOperations slowTransaction = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T result = action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
                committed.set(true);
                return result;
            }
        };
        BackupService slow = new BackupService(marketDataService, settingRepo, recordRepo, remoteClient,
                databaseProcess, tempDir.resolve("slow-commit"), slowTransaction);
        when(recordRepo.saveAndFlush(any(BackupRecord.class))).thenAnswer(invocation -> {
            Thread.sleep(650);
            return invocation.getArgument(0);
        });

        assertThatThrownBy(() -> BackupWorkflowDeadline.within(Duration.ofMillis(30_500),
                () -> slow.runBackup(true)))
                .isInstanceOf(BackupIndexCommitUncertainException.class)
                .hasMessageContaining("索引提交結果未定");
        assertThat(committed).isFalse();
        verify(upload, never()).delete(any(), any());
    }

    @Test
    void lateCommitAcknowledgementCannotReportBackupSuccess() throws Exception {
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        AtomicReference<java.nio.file.Path> localSource = new AtomicReference<>();
        doAnswer(invocation -> {
            localSource.set(invocation.getArgument(0));
            return null;
        }).when(upload).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        remoteClient.use(upload);
        TransactionOperations lateAcknowledgement = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T result = action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
                try {
                    // Simulate an acknowledgement that arrives only after the absolute deadline.
                    Thread.sleep(31_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return result;
            }
        };
        BackupService late = new BackupService(marketDataService, settingRepo, recordRepo, remoteClient,
                databaseProcess, tempDir.resolve("late-ack"), lateAcknowledgement);

        assertThatThrownBy(() -> BackupWorkflowDeadline.within(Duration.ofMillis(30_500),
                () -> late.runBackup(true)))
                .isInstanceOf(BackupIndexCommitUncertainException.class)
                .hasMessageContaining("索引提交結果未定");
        assertThat(localSource.get()).exists();
        verify(upload, never()).delete(any(), any());
        assertThat(remoteClient.remaining()).isZero();
        Files.deleteIfExists(localSource.get());
    }

    @Test
    void uncertainUploadPreservesLocalDumpForReconciliation() throws Exception {
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        List<java.nio.file.Path> paths = new ArrayList<>();
        doAnswer(invocation -> {
            java.nio.file.Path path = invocation.getArgument(0);
            paths.add(path);
            throw BackupRemoteUnavailableException.uncertainUpload(path.getFileName().toString());
        }).when(upload).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        remoteClient.use(upload);

        try {
            assertThatThrownBy(() -> service.runBackup(false))
                    .isInstanceOf(BackupRemoteUnavailableException.class)
                    .hasMessageContaining("結果未定");
            assertThat(paths).hasSize(1);
            assertThat(paths.getFirst()).exists();
            verify(recordRepo, never()).saveAndFlush(any(BackupRecord.class));
        } finally {
            for (java.nio.file.Path path : paths) Files.deleteIfExists(path);
        }
    }

    @Test
    void syncNthListFailureStopsLaterFoldersAndPerformsZeroDatabaseMutation() {
        BackupRemoteClient.Session session = mock(BackupRemoteClient.Session.class);
        when(session.listDateDirectoriesJson()).thenReturn("[{\"Name\":\"2026-10-01\",\"IsDir\":true},"
                + "{\"Name\":\"2026-10-02\",\"IsDir\":true}]");
        when(session.listJson("2026-10-01")).thenReturn("[]");
        when(session.listJson("2026-10-02")).thenThrow(BackupRemoteUnavailableException.authenticationUnavailable());
        remoteClient.use(session);

        assertThatThrownBy(service::syncFromRemote)
                .isInstanceOf(BackupRemoteUnavailableException.class);

        InOrder order = inOrder(session);
        order.verify(session).listDateDirectoriesJson();
        order.verify(session).listJson("2026-10-01");
        order.verify(session).listJson("2026-10-02");
        verify(session, never()).listJson("2026-10-03");
        verifyNoInteractions(recordRepo);
    }

    @Test
    void syncEntryGateFailurePerformsZeroDatabaseMutation() {
        remoteClient.fail(BackupRemoteUnavailableException.rootMissing());

        assertThatThrownBy(service::syncFromRemote)
                .isInstanceOf(BackupRemoteUnavailableException.class);

        verifyNoInteractions(recordRepo);
    }

    @Test
    void syncKeepsIndexedRowWhenExactAbsenceIsNotProven() {
        BackupRecord row = record(42, "asset_manual_20261002_030303.dump");
        when(recordRepo.findAll()).thenReturn(List.of(row));
        BackupRemoteClient.Session session = mock(BackupRemoteClient.Session.class);
        when(session.listDateDirectoriesJson()).thenReturn("[]");
        when(session.proveAbsent("manual", row.getFilename())).thenReturn(false);
        remoteClient.use(session);

        assertThatThrownBy(service::syncFromRemote)
                .isInstanceOf(BackupIndexCommitUncertainException.class);
        verify(session).proveAbsent("manual", row.getFilename());
        verify(recordRepo, never()).delete(any(BackupRecord.class));
        verify(recordRepo, never()).save(any(BackupRecord.class));
    }

    @Test
    void syncDeletesOnlyAfterIndependentExactAbsenceProof() {
        BackupRecord row = record(42, "asset_manual_20261002_030303.dump");
        BackupRecord anchor = record(43, "asset_manual_20261001_010101.dump");
        when(recordRepo.findAll()).thenReturn(List.of(row, anchor));
        BackupRemoteClient.Session session = mock(BackupRemoteClient.Session.class);
        when(session.listDateDirectoriesJson()).thenReturn("[{\"Name\":\"2026-10-01\",\"IsDir\":true}]");
        when(session.listJson("2026-10-01")).thenReturn("[{\"Name\":\"asset_manual_20261001_010101.dump\","
                + "\"IsDir\":false,\"Size\":10,\"ModTime\":\"2026-10-01T01:01:01Z\"}]");
        when(session.readHeader("manual", anchor.getFilename())).thenReturn("PGDMP");
        when(session.proveAbsent("manual", row.getFilename())).thenReturn(true);
        remoteClient.use(session);

        BackupDto.SyncResponse response = service.syncFromRemote();

        assertThat(response.deleted()).isEqualTo(1);
        verify(session).proveAbsent("manual", row.getFilename());
        verify(recordRepo).delete(row);
    }

    @Test
    void syncHoldsVerifiedSessionUntilDatabaseCommitFinishes() throws Exception {
        ReentrantLock remoteLock = new ReentrantLock(true);
        CountDownLatch atCommit = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicBoolean competingSessionEntered = new AtomicBoolean();
        BackupRemoteClient.Session emptySession = mock(BackupRemoteClient.Session.class);
        when(emptySession.listDateDirectoriesJson()).thenReturn("[]");
        BackupRemoteClient lockedRemote = new BackupRemoteClient() {
            @Override
            public <T> T withVerifiedSession(SessionWork<T> work) {
                remoteLock.lock();
                try {
                    return work.apply(emptySession);
                } finally {
                    remoteLock.unlock();
                }
            }
        };
        TransactionOperations committingTransaction = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                T result = action.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
                atCommit.countDown();
                try {
                    if (!allowCommit.await(5, TimeUnit.SECONDS)) throw new AssertionError("commit stalled");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return result;
            }
        };
        BackupService lockedService = new BackupService(marketDataService, settingRepo, recordRepo,
                lockedRemote, databaseProcess, tempDir.resolve("sync-pending"), committingTransaction);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var sync = executor.submit(lockedService::syncFromRemote);
            assertThat(atCommit.await(5, TimeUnit.SECONDS)).isTrue();
            var competing = executor.submit(() -> lockedRemote.withVerifiedSession(session -> {
                competingSessionEntered.set(true);
                return null;
            }));
            Thread.sleep(100);
            assertThat(competingSessionEntered).isFalse();
            allowCommit.countDown();
            sync.get(5, TimeUnit.SECONDS);
            competing.get(5, TimeUnit.SECONDS);
            assertThat(competingSessionEntered).isTrue();
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void updateSettingGateFailureKeepsEveryFolderAtZeroRemoteAndDatabaseMutation() {
        when(settingRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        remoteClient.fail(BackupRemoteUnavailableException.rootMissing());
        remoteClient.fail(BackupRemoteUnavailableException.rootMissing());
        remoteClient.fail(BackupRemoteUnavailableException.rootMissing());

        BackupSetting saved = service.updateSetting(1, 1, 1, true);

        assertThat(saved.getManualRetention()).isEqualTo(1);
        verify(settingRepo).saveAndFlush(setting);
        verifyNoInteractions(recordRepo);
        assertThat(remoteClient.remaining()).isZero();
    }

    @Test
    void rotationCommitsAlreadyMissingRowsThenStopsAtFirstUnavailableAndLeavesLaterRows() {
        when(settingRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        BackupRecord newest = record(1, "asset_manual_20261002_010101_a.dump");
        BackupRecord oldDeleted = record(2, "asset_manual_20261002_010101_b.dump");
        BackupRecord unavailable = record(3, "asset_manual_20261002_010101_c.dump");
        BackupRecord later = record(4, "asset_manual_20261002_010101_d.dump");
        when(recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc("manual"))
                .thenReturn(List.of(newest, oldDeleted, unavailable, later));
        when(recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc("daily")).thenReturn(List.of());
        when(recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc("weekly")).thenReturn(List.of());
        when(recordRepo.findAll()).thenReturn(List.of(newest, oldDeleted, unavailable, later));

        BackupRemoteClient.Session manual = mock(BackupRemoteClient.Session.class);
        when(manual.listDateDirectoriesJson()).thenReturn("[{\"Name\":\"2026-10-02\",\"IsDir\":true}]");
        when(manual.listJson("2026-10-02")).thenReturn("[{\"Name\":\"asset_manual_20261002_010101_a.dump\",\"IsDir\":false,\"Size\":10,\"ModTime\":\"2026-10-02T01:01:01Z\"},"
                + "{\"Name\":\"asset_manual_20261002_010101_b.dump\",\"IsDir\":false,\"Size\":10,\"ModTime\":\"2026-10-02T01:01:01Z\"},"
                + "{\"Name\":\"asset_manual_20261002_010101_c.dump\",\"IsDir\":false,\"Size\":10,\"ModTime\":\"2026-10-02T01:01:01Z\"},"
                + "{\"Name\":\"asset_manual_20261002_010101_d.dump\",\"IsDir\":false,\"Size\":10,\"ModTime\":\"2026-10-02T01:01:01Z\"}]");
        when(manual.readHeader("manual", newest.getFilename())).thenReturn("PGDMP");
        when(manual.delete("manual", oldDeleted.getFilename()))
                .thenReturn(BackupRemoteClient.DeleteResult.ALREADY_MISSING);
        when(manual.delete("manual", unavailable.getFilename()))
                .thenThrow(BackupRemoteUnavailableException.authenticationUnavailable());
        remoteClient.use(manual);
        remoteClient.use(mock(BackupRemoteClient.Session.class));
        remoteClient.use(mock(BackupRemoteClient.Session.class));

        service.updateSetting(1, 1, 1, true);

        InOrder order = inOrder(manual, recordRepo);
        order.verify(manual).delete("manual", oldDeleted.getFilename());
        order.verify(recordRepo).delete(oldDeleted);
        order.verify(manual).delete("manual", unavailable.getFilename());
        verify(recordRepo, never()).delete(unavailable);
        verify(manual, never()).delete("manual", later.getFilename());
        verify(recordRepo, never()).delete(later);
    }

    @Test
    void restoreEntryGateFailurePreventsPreRestoreDumpUploadDownloadAndPgRestore() {
        remoteClient.fail(BackupRemoteUnavailableException.rootMissing());

        assertThatThrownBy(() -> service.runRestore("manual", "asset_manual_20261002_010101.dump"))
                .isInstanceOf(BackupRemoteUnavailableException.class);

        verify(recordRepo, never()).save(any());
        verify(recordRepo, never()).delete(any());
        verifyNoInteractions(databaseProcess);
        assertThat(remoteClient.remaining()).isZero();
    }

    @Test
    void invalidDownloadedArchivePreservesRescueAndNeverStartsDestructiveRestore() throws Exception {
        BackupRemoteClient.Session entry = mock(BackupRemoteClient.Session.class);
        BackupRemoteClient.Session rescueUpload = mock(BackupRemoteClient.Session.class);
        BackupRemoteClient.Session download = mock(BackupRemoteClient.Session.class);
        remoteClient.use(entry);
        remoteClient.use(rescueUpload);
        remoteClient.use(download);
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(2), "invalid archive");
            return null;
        }).when(download).download(org.mockito.ArgumentMatchers.eq("manual"),
                org.mockito.ArgumentMatchers.eq("asset_manual_20261002_010101.dump"), any());
        doThrow(new IllegalStateException("pg_restore --list 執行失敗"))
                .when(databaseProcess).validate(any());

        assertThatThrownBy(() -> service.runRestore("manual", "asset_manual_20261002_010101.dump"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未執行 pg_restore")
                .hasMessageContaining("asset_auto-pre-restore_");

        InOrder order = inOrder(databaseProcess, recordRepo, download, rescueUpload);
        order.verify(databaseProcess).dump(any());
        order.verify(rescueUpload).upload(any(), org.mockito.ArgumentMatchers.eq("manual"));
        order.verify(recordRepo).saveAndFlush(any(BackupRecord.class));
        order.verify(download).download(org.mockito.ArgumentMatchers.eq("manual"),
                org.mockito.ArgumentMatchers.eq("asset_manual_20261002_010101.dump"), any());
        order.verify(databaseProcess).validate(any());
        verify(databaseProcess, never()).restore(any());
        assertThat(remoteClient.remaining()).isZero();
    }

    @Test
    void secondRestoreWaitsUntilFirstRestoreCompletes() throws Exception {
        assertOperationWaitsDuringRestore(candidate ->
                candidate.runRestore("manual", "asset_manual_20261002_010101.dump"));
    }

    @Test
    void manualCreateWaitsUntilRestoreCompletes() throws Exception {
        assertOperationWaitsDuringRestore(candidate -> candidate.runBackup(false));
    }

    @Test
    void scheduledCreateWaitsUntilRestoreCompletes() throws Exception {
        assertOperationWaitsDuringRestore(BackupService::scheduledWeeklyBackup);
    }

    @Test
    void syncWaitsUntilRestoreCompletes() throws Exception {
        assertOperationWaitsDuringRestore(BackupService::syncFromRemote);
    }

    @Test
    void retentionUpdateWaitsUntilRestoreCompletes() throws Exception {
        when(settingRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertOperationWaitsDuringRestore(candidate -> candidate.updateSetting(1, 1, 1, true));
    }

    private void assertOperationWaitsDuringRestore(Consumer<BackupService> competingWork) throws Exception {
        BackupRemoteClient.Session session = mock(BackupRemoteClient.Session.class);
        when(session.listDateDirectoriesJson()).thenReturn("[]");
        when(session.listJson(any())).thenReturn("[]");
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(2), "PGDMP fixture");
            return null;
        }).when(session).download(any(), any(), any());
        AtomicInteger remoteSessions = new AtomicInteger();
        BackupRemoteClient availableRemote = new BackupRemoteClient() {
            @Override
            public <T> T withVerifiedSession(SessionWork<T> work) {
                remoteSessions.incrementAndGet();
                return work.apply(session);
            }
        };
        BackupService lockedService = new BackupService(marketDataService, settingRepo, recordRepo,
                availableRemote, databaseProcess, tempDir.resolve("restore-admission"));
        ReentrantLock admissionLock = (ReentrantLock) org.springframework.test.util.ReflectionTestUtils
                .getField(lockedService, "operationLock");
        CountDownLatch restoreStarted = new CountDownLatch(1);
        CountDownLatch releaseRestore = new CountDownLatch(1);
        AtomicInteger restores = new AtomicInteger();
        doAnswer(invocation -> {
            if (restores.incrementAndGet() == 1) {
                restoreStarted.countDown();
                if (!releaseRestore.await(5, TimeUnit.SECONDS)) throw new AssertionError("restore stalled");
            }
            return null;
        }).when(databaseProcess).restore(any());
        AtomicReference<Thread> competingThread = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var restore = executor.submit(() -> lockedService.runRestore(
                    "manual", "asset_manual_20261002_010101.dump"));
            assertThat(restoreStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(remoteSessions).hasValue(3); // gate, rescue upload, selected download
            var competitor = executor.submit(() -> {
                competingThread.set(Thread.currentThread());
                competingWork.accept(lockedService);
            });
            long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < queueDeadline
                    && (competingThread.get() == null || !admissionLock.hasQueuedThread(competingThread.get()))) {
                Thread.onSpinWait();
            }
            assertThat(admissionLock.hasQueuedThread(competingThread.get())).isTrue();
            assertThat(remoteSessions).hasValue(3);
            assertThat(restores).hasValue(1);
            verify(databaseProcess, org.mockito.Mockito.times(1)).dump(any());
            releaseRestore.countDown();
            restore.get(5, TimeUnit.SECONDS);
            competitor.get(5, TimeUnit.SECONDS);
        } finally {
            releaseRestore.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void scheduledBackupRemainsSuccessfulWhenRotationFailsAndLogsOnlySafeWarning(CapturedOutput output) {
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        BackupRemoteClient.Session rotation = mock(BackupRemoteClient.Session.class);
        BackupRecord newest = record(1, "asset_weekly_20261002_010101_a.dump");
        BackupRecord old = record(2, "asset_weekly_20261002_010101_b.dump");
        newest.setFolder("weekly");
        old.setFolder("weekly");
        when(recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc("weekly"))
                .thenReturn(List.of(newest, old));
        when(recordRepo.findAll()).thenReturn(List.of(newest, old));
        when(rotation.listDateDirectoriesJson()).thenReturn("[{\"Name\":\"2026-10-02\",\"IsDir\":true}]");
        when(rotation.listJson("2026-10-02")).thenReturn("[{\"Name\":\"asset_weekly_20261002_010101_a.dump\",\"IsDir\":false,\"Size\":10,\"ModTime\":\"2026-10-02T01:01:01Z\"},"
                + "{\"Name\":\"asset_weekly_20261002_010101_b.dump\",\"IsDir\":false,\"Size\":10,\"ModTime\":\"2026-10-02T01:01:01Z\"}]");
        when(rotation.readHeader("weekly", newest.getFilename())).thenReturn("PGDMP");
        when(rotation.delete("weekly", old.getFilename()))
                .thenThrow(BackupRemoteUnavailableException.authenticationUnavailable());
        remoteClient.use(upload);
        remoteClient.use(rotation);

        service.scheduledWeeklyBackup();

        verify(recordRepo).saveAndFlush(any(BackupRecord.class));
        assertThat(output.getOut())
                .contains("每周備份完成後輪替 weekly 暫停")
                .doesNotContain("每周備份失敗")
                .doesNotContain("invalid_grant")
                .doesNotContain("Bearer ");
    }

    @Test
    void scheduledWeeklyBackupStaysSuccessfulWhenRetentionReadFails(CapturedOutput output) {
        when(settingRepo.findById(1))
                .thenReturn(Optional.of(setting))
                .thenThrow(new IllegalStateException("client_secret=WEEKLY_SENTINEL"));
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        remoteClient.use(upload);

        service.scheduledWeeklyBackup();

        verify(upload).upload(any(), org.mockito.ArgumentMatchers.eq("weekly"));
        verify(recordRepo).saveAndFlush(any(BackupRecord.class));
        verify(settingRepo, org.mockito.Mockito.times(2)).findById(1);
        assertThat(output.getOut())
                .contains("每周備份完成後輪替 weekly 暫停: 本地索引目前不可用")
                .doesNotContain("每周備份失敗")
                .doesNotContain("WEEKLY_SENTINEL")
                .doesNotContain("client_secret");
    }

    @Test
    void listBackupsIsDatabaseOnlyAndNeverTouchesRemoteLock() {
        BackupRecord row = record(1, "asset_manual_20261002_010101.dump");
        when(recordRepo.findAllByOrderByModifiedAtDesc()).thenReturn(List.of(row));

        assertThat(service.listBackups()).hasSize(1);

        assertThat(remoteClient.remaining()).isZero();
    }

    @Test
    void syncLeavesFrozenLegacyRowsUntouchedAndIndexesOnlyDateDirectory() {
        BackupRecord legacy = record(1, "asset_manual_20260928_010101.dump");
        when(recordRepo.findAll()).thenReturn(List.of(legacy));
        BackupRemoteClient.Session session = mock(BackupRemoteClient.Session.class);
        when(session.listDateDirectoriesJson()).thenReturn("[{\"Name\":\"backups\",\"IsDir\":true},"
                + "{\"Name\":\"2026-10-01\",\"IsDir\":true}]");
        when(session.listJson("2026-10-01")).thenReturn("[{\"Name\":\"asset_daily_tw_20261001_010101.dump\","
                + "\"IsDir\":false,\"Size\":42,\"ModTime\":\"2026-10-01T01:01:01Z\"}]");
        remoteClient.use(session);

        BackupDto.SyncResponse result = service.syncFromRemote();

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        verify(recordRepo, never()).delete(legacy);
        verify(session, never()).proveAbsent(any(), any());
        verify(session, never()).listJson("backups");
    }

    @Test
    void restoreRefusesLegacyAndUnlistedDateArchiveBeforeAnyRemoteWork() {
        assertThatThrownBy(() -> service.runRestore("manual", "asset_manual_20260928_010101.dump"))
                .isInstanceOf(BackupLegacyUnavailableException.class);
        assertThatThrownBy(() -> service.runRestore("manual", "asset_manual_20261002_020202.dump"))
                .isInstanceOf(BackupLegacyUnavailableException.class);
        assertThat(remoteClient.remaining()).isZero();
        verify(databaseProcess, never()).restore(any());
    }

    @Test
    void destructiveSyncNeedsExistingIndexedDateArchiveHeader() {
        BackupRecord missing = record(1, "asset_manual_20261001_010101.dump");
        when(recordRepo.findAll()).thenReturn(List.of(missing));
        BackupRemoteClient.Session session = mock(BackupRemoteClient.Session.class);
        when(session.listDateDirectoriesJson()).thenReturn("[]");
        when(session.proveAbsent("manual", missing.getFilename())).thenReturn(true);
        remoteClient.use(session);

        assertThatThrownBy(service::syncFromRemote)
                .isInstanceOf(BackupIndexCommitUncertainException.class);
        verify(recordRepo, never()).delete(missing);
    }

    private static BackupRecord record(long id, String filename) {
        return BackupRecord.builder()
                .id(id)
                .folder("manual")
                .filename(filename)
                .sizeBytes(10L)
                .modifiedAt(LocalDateTime.of(2026, 8, 29, 1, 1))
                .autoPreRestore(false)
                .createdAt(LocalDateTime.of(2026, 8, 29, 1, 1))
                .build();
    }

    private static final class QueueRemoteClient implements BackupRemoteClient {

        private final Queue<Entry> entries = new ArrayDeque<>();

        void use(Session session) {
            entries.add(new Entry(session, null));
        }

        void fail(RuntimeException failure) {
            entries.add(new Entry(null, failure));
        }

        int remaining() {
            return entries.size();
        }

        @Override
        public <T> T withVerifiedSession(SessionWork<T> work) {
            Entry entry = entries.remove();
            if (entry.failure != null) {
                throw entry.failure;
            }
            return work.apply(entry.session);
        }

        private record Entry(Session session, RuntimeException failure) {}
    }
}
