package com.steven.assets.service;

import com.steven.assets.dto.BackupDto;
import com.steven.assets.model.BackupRecord;
import com.steven.assets.model.BackupSetting;
import com.steven.assets.repository.BackupRecordRepository;
import com.steven.assets.repository.BackupSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

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

    @BeforeEach
    void setUp() throws Exception {
        remoteClient = new QueueRemoteClient();
        service = new BackupService(marketDataService, settingRepo, recordRepo, remoteClient, databaseProcess);
        setting = BackupSetting.builder()
                .id(1)
                .manualRetention(1)
                .dailyRetention(1)
                .weeklyRetention(1)
                .backupEnabled(true)
                .updatedAt(LocalDateTime.now())
                .build();
        when(settingRepo.findById(1)).thenReturn(Optional.of(setting));
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
    void syncNthListFailureStopsLaterFoldersAndPerformsZeroDatabaseMutation() {
        BackupRemoteClient.Session session = mock(BackupRemoteClient.Session.class);
        when(session.listJson("manual")).thenReturn("[]");
        when(session.listJson("daily")).thenThrow(BackupRemoteUnavailableException.authenticationUnavailable());
        remoteClient.use(session);

        assertThatThrownBy(service::syncFromRemote)
                .isInstanceOf(BackupRemoteUnavailableException.class);

        InOrder order = inOrder(session);
        order.verify(session).listJson("manual");
        order.verify(session).listJson("daily");
        verify(session, never()).listJson("weekly");
        verify(session, never()).listJson("monthly");
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
        BackupRecord newest = record(1, "newest.dump");
        BackupRecord oldDeleted = record(2, "old-deleted.dump");
        BackupRecord unavailable = record(3, "unavailable.dump");
        BackupRecord later = record(4, "later.dump");
        when(recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc("manual"))
                .thenReturn(List.of(newest, oldDeleted, unavailable, later));
        when(recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc("daily")).thenReturn(List.of());
        when(recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc("weekly")).thenReturn(List.of());

        BackupRemoteClient.Session manual = mock(BackupRemoteClient.Session.class);
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

        assertThatThrownBy(() -> service.runRestore("manual", "asset_manual_20260829_010101.dump"))
                .isInstanceOf(BackupRemoteUnavailableException.class);

        verifyNoInteractions(recordRepo);
        verifyNoInteractions(databaseProcess);
        assertThat(remoteClient.remaining()).isZero();
    }

    @Test
    void scheduledBackupRemainsSuccessfulWhenRotationFailsAndLogsOnlySafeWarning(CapturedOutput output) {
        BackupRemoteClient.Session upload = mock(BackupRemoteClient.Session.class);
        BackupRemoteClient.Session rotation = mock(BackupRemoteClient.Session.class);
        BackupRecord newest = record(1, "newest.dump");
        BackupRecord old = record(2, "old.dump");
        when(recordRepo.findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc("weekly"))
                .thenReturn(List.of(newest, old));
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
        BackupRecord row = record(1, "asset_manual_20260829_010101.dump");
        when(recordRepo.findAllByOrderByModifiedAtDesc()).thenReturn(List.of(row));

        assertThat(service.listBackups()).hasSize(1);

        assertThat(remoteClient.remaining()).isZero();
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
