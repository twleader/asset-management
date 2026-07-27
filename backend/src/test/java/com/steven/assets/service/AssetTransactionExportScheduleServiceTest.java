package com.steven.assets.service;

import com.steven.assets.dto.AssetTransactionExportDto;
import com.steven.assets.model.AssetTransactionExportSchedule;
import com.steven.assets.repository.AssetTransactionExportScheduleRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetTransactionExportScheduleService 單元測試（Requirement 49 / Task 238）。
 *
 * <p>最關鍵的是背景排程走 {@code exportAssetTransactionsForOwner(ownerId)}（owner-scoped）
 * 而非 HTTP 版 {@code exportAssetTransactions()}——後者在無 request context 的背景執行緒會匯出所有人的交易。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetTransactionExportScheduleServiceTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Mock private AssetTransactionExportScheduleRepository settingRepo;
    @Mock private ExcelExportService excelExportService;
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private RcloneClient rcloneClient;
    @Mock private com.steven.assets.repository.AppUserRepository appUserRepo;
    @Mock private UserAdminService userAdminService;

    @TempDir Path baseDir;

    private AssetTransactionExportScheduleService service;

    @BeforeEach
    void setup() {
        // 注入真實的 GdriveOutputSupport（只把 rclone／使用者查詢換成替身），驗證規則才會真的被跑到。
        GdriveOutputSupport gdrive =
                new GdriveOutputSupport(rcloneClient, appUserRepo, userAdminService, "GDriveOutput");
        service = new AssetTransactionExportScheduleService(
                settingRepo, excelExportService, currentUserProvider, gdrive, baseDir.toString());
    }

    private static LocalDate today() { return LocalDate.now(TW); }
    private static LocalDate yesterday() { return today().minusDays(1); }

    private static AssetTransactionExportSchedule sched(long owner, int h, int m, boolean enabled,
                                                        String subpath, LocalDate lastRun) {
        return AssetTransactionExportSchedule.builder()
                .id(owner).ownerUserId(owner).runHour(h).runMinute(m)
                .enabled(enabled).outputSubpath(subpath).lastRunDate(lastRun).build();
    }

    private Path expectedFile(long owner, String subpath) {
        return baseDir.resolve(subpath).resolve("交易紀錄_" + owner + "_" + today().format(FILE_DATE) + ".xlsx");
    }

    private void givenCurrentUser(long id) {
        CurrentUserContext ctx = new CurrentUserContext();
        ctx.setEffectiveUserId(id);
        when(currentUserProvider.getObject()).thenReturn(ctx);
    }

    // ===== HTTP 路徑 =====

    @Test
    void 時越界擲例外() {
        givenCurrentUser(1L);
        assertThatThrownBy(() -> service.updateForCurrentUser(
                new AssetTransactionExportDto.SettingRequest(true, 24, 0, "input", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 分越界擲例外() {
        givenCurrentUser(1L);
        assertThatThrownBy(() -> service.updateForCurrentUser(
                new AssetTransactionExportDto.SettingRequest(true, 8, 60, "input", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 子路徑跳脫基底被resolveDir擋下() {
        givenCurrentUser(1L);
        assertThatThrownBy(() -> service.updateForCurrentUser(
                new AssetTransactionExportDto.SettingRequest(true, 8, 0, "../../etc", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 空子路徑正規化為input() {
        givenCurrentUser(1L);
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.empty());
        when(settingRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        AssetTransactionExportDto.SettingResponse resp = service.updateForCurrentUser(
                new AssetTransactionExportDto.SettingRequest(true, 8, 0, "  ", null, null));

        assertThat(resp.outputSubpath()).isEqualTo("input");
    }

    @Test
    void 無使用者時擲UnauthenticatedException() {
        CurrentUserContext ctx = new CurrentUserContext(); // 無 effectiveUserId
        when(currentUserProvider.getObject()).thenReturn(ctx);
        assertThatThrownBy(() -> service.getForCurrentUser())
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void runNow不動lastRunDate() throws Exception {
        givenCurrentUser(1L);
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(
                sched(1L, 8, 0, true, "out", yesterday())));
        when(excelExportService.exportAssetTransactions()).thenReturn("xlsx".getBytes());
        when(settingRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        AssetTransactionExportDto.RunNowResponse resp = service.runNowForCurrentUser();

        assertThat(resp.path()).contains("交易紀錄_1_");
        // run-now 走 HTTP 版（非 owner 版），且不改當日 guard
        verify(excelExportService).exportAssetTransactions();
        verify(excelExportService, never()).exportAssetTransactionsForOwner(anyLong());
        org.mockito.ArgumentCaptor<AssetTransactionExportSchedule> cap =
                org.mockito.ArgumentCaptor.forClass(AssetTransactionExportSchedule.class);
        verify(settingRepo).save(cap.capture());
        assertThat(cap.getValue().getLastRunDate()).isEqualTo(yesterday()); // 未被動到
    }

    // ===== 背景排程 =====

    @Test
    void 命中執行時間且今日未跑則產檔並設當日guard_走owner版() throws Exception {
        when(settingRepo.findAll()).thenReturn(List.of(sched(1L, 0, 0, true, "out", yesterday())));
        when(excelExportService.exportAssetTransactionsForOwner(1L)).thenReturn("xlsx".getBytes());
        when(settingRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        service.tick();

        // 背景必須走 owner-scoped 版，而非 HTTP 版（否則洩漏所有人的交易）
        verify(excelExportService).exportAssetTransactionsForOwner(1L);
        verify(excelExportService, never()).exportAssetTransactions();
        assertThat(expectedFile(1L, "out")).exists();
    }

    @Test
    void 今日已跑不重跑() throws Exception {
        when(settingRepo.findAll()).thenReturn(List.of(sched(1L, 0, 0, true, "out", today())));

        service.tick();

        verify(excelExportService, never()).exportAssetTransactionsForOwner(anyLong());
    }

    @Test
    void 停用列不跑() throws Exception {
        AssetTransactionExportSchedule s = sched(1L, 0, 0, false, "out", yesterday());
        when(settingRepo.findAll()).thenReturn(List.of(s));

        service.tick();

        verify(excelExportService, never()).exportAssetTransactionsForOwner(anyLong());
        assertThat(s.getLastRunDate()).isEqualTo(yesterday()); // 未被動到
    }

    @Test
    void 尚未到執行時間不跑() throws Exception {
        // 23:59 幾乎必然晚於測試執行當下
        when(settingRepo.findAll()).thenReturn(List.of(sched(1L, 23, 59, true, "out", yesterday())));

        service.tick();

        verify(excelExportService, never()).exportAssetTransactionsForOwner(anyLong());
    }

    @Test
    void 單一owner失敗記失敗仍設guard且不影響他人() throws Exception {
        AssetTransactionExportSchedule bad = sched(1L, 0, 0, true, "out1", yesterday());
        AssetTransactionExportSchedule good = sched(2L, 0, 0, true, "out2", yesterday());
        when(settingRepo.findAll()).thenReturn(List.of(bad, good));
        when(excelExportService.exportAssetTransactionsForOwner(1L)).thenThrow(new IOException("磁碟壞了"));
        when(excelExportService.exportAssetTransactionsForOwner(2L)).thenReturn("xlsx".getBytes());
        when(settingRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));

        service.tick();

        assertThat(expectedFile(2L, "out2")).exists();          // 另一位使用者仍成功
        assertThat(bad.getLastRunStatus()).startsWith("失敗：");
        assertThat(bad.getLastRunDate()).isEqualTo(today());    // 失敗列仍設 guard
        assertThat(good.getLastRunDate()).isEqualTo(today());
        assertThat(Files.exists(baseDir.resolve("out1"))).isFalse();
    }
}
