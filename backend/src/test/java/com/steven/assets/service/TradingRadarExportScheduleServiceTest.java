package com.steven.assets.service;

import com.fasterxml.jackson.databind.node.TextNode;
import com.steven.assets.dto.TradingRadarExportDto;
import com.steven.assets.model.TradingRadarExportSetting;
import com.steven.assets.model.TradingRadarExportTime;
import com.steven.assets.repository.TradingRadarExportSettingRepository;
import com.steven.assets.repository.TradingRadarExportTimeRepository;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingRadarExportScheduleService 單元測試（Requirement 48 追加 / Task 231）。
 *
 * <p>最關鍵的一條是「同日多個時間點各自跑一次」——當日 guard 若被誤放在 owner 層，
 * 第二個時間點永遠不會執行，該測試會失敗。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingRadarExportScheduleServiceTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Mock private TradingRadarExportTimeRepository timeRepo;
    @Mock private TradingRadarExportSettingRepository settingRepo;
    @Mock private TradingRadarExportService exportService;
    @Mock private TradingRadarSnapshotStore snapshotStore;
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private RcloneClient rcloneClient;
    @Mock private com.steven.assets.repository.AppUserRepository appUserRepo;
    @Mock private UserAdminService userAdminService;
    // 啟用當下的自檢（Task 247）：替身預設回 null（＝自檢正常），本測試的斷言不受影響，
    // 同時保證這裡不會去讀容器內的 /etc/rclone/rclone.conf。
    @Mock private GdriveSelfCheck selfCheck;

    @TempDir Path baseDir;

    private TradingRadarExportScheduleService service;

    @BeforeEach
    void setup() {
        GdriveOutputSupport gdrive =
                new GdriveOutputSupport(rcloneClient, appUserRepo, userAdminService, selfCheck, "GDriveOutput");
        service = new TradingRadarExportScheduleService(
                timeRepo, settingRepo, exportService, snapshotStore, currentUserProvider,
                gdrive, baseDir.toString());
    }

    private static LocalDate today() { return LocalDate.now(TW); }
    private static LocalDate yesterday() { return today().minusDays(1); }

    private static TradingRadarExportTime time(long owner, int h, int m, LocalDate lastRun) {
        return TradingRadarExportTime.builder()
                .id((long) (owner * 100 + h * 60 + m))
                .ownerUserId(owner).runHour(h).runMinute(m)
                .enabled(true).lastRunDate(lastRun).build();
    }

    private static TradingRadarExportSetting setting(long owner, String subpath) {
        return TradingRadarExportSetting.builder().ownerUserId(owner).outputSubpath(subpath).build();
    }

    /** 有快照可寫。 */
    private void givenSnapshots(long owner) throws IOException {
        when(snapshotStore.range(eq(owner), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(
                        List.of(TextNode.valueOf("snap")), 1, 0));
        when(exportService.exportForOwner(eq(owner), anyLong(), anyLong()))
                .thenReturn("xlsx-bytes".getBytes());
    }

    private Path expectedFile(long owner, String subpath) {
        return baseDir.resolve(subpath).resolve("交易雷達_" + owner + "_" + today().format(FILE_DATE) + ".xlsx");
    }

    @Test
    void 同日多個時間點各自跑一次_guard必須per時間點() throws Exception {
        TradingRadarExportTime t1 = time(1L, 0, 0, yesterday());
        TradingRadarExportTime t2 = time(1L, 0, 1, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t1, t2));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        givenSnapshots(1L);

        service.tick();

        // 兩個時間點都必須被標記為今日；guard 誤放 owner 層時 t2 會維持昨日
        assertThat(t1.getLastRunDate()).isEqualTo(today());
        assertThat(t2.getLastRunDate()).isEqualTo(today());
        verify(timeRepo, times(2)).save(any(TradingRadarExportTime.class));
        assertThat(expectedFile(1L, "out")).exists();
    }

    @Test
    void 今日已跑過的時間點不重跑() throws Exception {
        when(timeRepo.findAll()).thenReturn(List.of(time(1L, 0, 0, today())));

        service.tick();

        verify(exportService, never()).exportForOwner(anyLong(), anyLong(), anyLong());
    }

    @Test
    void 停用的時間點不跑() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        t.setEnabled(false);
        when(timeRepo.findAll()).thenReturn(List.of(t));

        service.tick();

        verify(exportService, never()).exportForOwner(anyLong(), anyLong(), anyLong());
        assertThat(t.getLastRunDate()).isEqualTo(yesterday());   // 未被動到
    }

    @Test
    void 尚未到執行時間不跑() throws Exception {
        // 23:59 幾乎必然晚於測試執行當下
        when(timeRepo.findAll()).thenReturn(List.of(time(1L, 23, 59, yesterday())));

        service.tick();

        verify(exportService, never()).exportForOwner(anyLong(), anyLong(), anyLong());
    }

    @Test
    void 當日零快照時不寫檔但仍設guard並記狀態() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        when(snapshotStore.range(eq(1L), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));

        service.tick();

        verify(exportService, never()).exportForOwner(anyLong(), anyLong(), anyLong());
        assertThat(Files.exists(baseDir.resolve("out"))).isFalse();   // 目錄都不該被建立
        assertThat(t.getLastRunDate()).isEqualTo(today());            // guard 仍設，避免整天重試

        ArgumentCaptor<TradingRadarExportSetting> cap = ArgumentCaptor.forClass(TradingRadarExportSetting.class);
        verify(settingRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getLastRunStatus())
                .isEqualTo(TradingRadarExportScheduleService.NO_SNAPSHOT_STATUS);
    }

    @Test
    void 單一owner失敗不影響其他owner() throws Exception {
        TradingRadarExportTime bad = time(1L, 0, 0, yesterday());
        TradingRadarExportTime good = time(2L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(bad, good));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out1")));
        when(settingRepo.findByOwnerUserId(2L)).thenReturn(Optional.of(setting(2L, "out2")));
        when(snapshotStore.range(anyLong(), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(
                        List.of(TextNode.valueOf("snap")), 1, 0));
        when(exportService.exportForOwner(eq(1L), anyLong(), anyLong()))
                .thenThrow(new IOException("磁碟壞了"));
        when(exportService.exportForOwner(eq(2L), anyLong(), anyLong()))
                .thenReturn("xlsx-bytes".getBytes());

        service.tick();

        assertThat(expectedFile(2L, "out2")).exists();          // 另一位使用者仍成功
        assertThat(bad.getLastRunDate()).isEqualTo(today());    // 失敗列仍設 guard
        assertThat(good.getLastRunDate()).isEqualTo(today());
    }

    @Test
    void 設定列不存在時recordStatus會建立一列() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.empty());   // 只設了時間點、未設資料夾
        when(snapshotStore.range(eq(1L), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));

        service.tick();

        ArgumentCaptor<TradingRadarExportSetting> cap = ArgumentCaptor.forClass(TradingRadarExportSetting.class);
        verify(settingRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getOwnerUserId()).isEqualTo(1L);
        assertThat(cap.getValue().getOutputSubpath()).isEqualTo(TradingRadarExportSetting.DEFAULT_SUBPATH);
    }

    // ===== HTTP 路徑 =====

    private void givenCurrentUser(long id) {
        CurrentUserContext ctx = new CurrentUserContext();
        ctx.setEffectiveUserId(id);
        when(currentUserProvider.getObject()).thenReturn(ctx);
    }

    @Test
    void 輸出子路徑跳脫基底目錄被擋() {
        givenCurrentUser(1L);
        assertThatThrownBy(() -> service.saveSetting(new TradingRadarExportDto.SettingRequest("../../etc", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 重複時分回IllegalArgument而非撞UNIQUE() {
        givenCurrentUser(1L);
        assertThatThrownBy(() -> service.replaceTimes(List.of(
                new TradingRadarExportDto.TimeItem(8, 20, true),
                new TradingRadarExportDto.TimeItem(8, 20, true))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 不合法時分回IllegalArgument() {
        givenCurrentUser(1L);
        assertThatThrownBy(() -> service.replaceTimes(List.of(
                new TradingRadarExportDto.TimeItem(24, 0, true))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
