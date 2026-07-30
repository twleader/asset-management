package com.steven.assets.service;

import com.fasterxml.jackson.databind.node.TextNode;
import com.steven.assets.dto.TradingCalendarExportDto;
import com.steven.assets.dto.TradingRadarExportDto;
import com.steven.assets.model.AppUser;
import com.steven.assets.model.TradingCalendarExportSchedule;
import com.steven.assets.model.TradingRadarExportSetting;
import com.steven.assets.model.TradingRadarExportTime;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.TradingCalendarExportScheduleRepository;
import com.steven.assets.repository.TradingRadarExportSettingRepository;
import com.steven.assets.repository.TradingRadarExportTimeRepository;
import com.steven.assets.security.AdminRequiredException;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交易雷達與交易日曆的 Google Drive 同步（Requirement 51 / Task 244）——兩個結構例外。
 *
 * <p>通用的權限與 best-effort 迴歸已由 {@link ExportScheduleGdriveTest} 涵蓋；這裡只測這兩頁<b>特有</b>
 * 的失敗模式：
 * <ul>
 *   <li><b>交易雷達</b>：設定 DTO 原本是裸單欄 {@code SettingRequest(String)}，controller／service／前端
 *       一路裸傳字串。只送 {@code outputSubpath} 時不得清掉已存的 Drive 設定。一天可能上傳多次。</li>
 *   <li><b>交易日曆</b>：沒有 run-now，手動匯出是 {@code POST /run} 且 {@code subpath} 來自 query param。
 *       <b>Drive 目錄必須取自設定列而非 query param</b>——否則使用者每次手動匯出都可能把檔案倒進
 *       Drive 的不同位置。這是本任務最容易做錯的一項。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingExportGdriveTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long ADMIN_ID = 1L;
    private static final long OTHER_ID = 2L;
    private static final String DRIVE_DIR = "投資理財/資產管理";

    // 交易雷達
    @Mock private TradingRadarExportTimeRepository timeRepo;
    @Mock private TradingRadarExportSettingRepository radarSettingRepo;
    @Mock private TradingRadarExportService radarExportService;
    @Mock private TradingRadarSnapshotStore snapshotStore;
    // 交易日曆
    @Mock private TradingCalendarExportScheduleRepository calendarRepo;
    @Mock private TradingCalendarExportService calendarExportService;
    // 共用
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private RcloneClient rcloneClient;
    @Mock private AppUserRepository userRepo;
    @Mock private UserAdminService userAdminService;
    // 啟用當下的自檢（Task 247）：替身預設回 null（＝自檢正常），本測試的斷言不受影響，
    // 同時保證這裡不會去讀容器內的 /etc/rclone/rclone.conf。
    @Mock private GdriveSelfCheck selfCheck;
    // Task 260：交易雷達排程新增的三個建構子依賴。本檔不測產檔前重算本身（那是
    // TradingRadarExportScheduleServiceTest 的範圍），只需讓既有的 tick()/runNow() 測試
    // 能通過「是否交易日」分支——不 stub 為 true 會讓這裡既有的成功案例全部誤判成休市日。
    @Mock private TradingRadarService radarService;
    @Mock private PriceQueryService priceQueryService;
    @Mock private MarketDataService marketDataService;

    @TempDir Path baseDir;

    private TradingRadarExportScheduleService radar;
    private TradingCalendarExportScheduleService calendar;

    @BeforeEach
    void setup() {
        GdriveOutputSupport gdrive =
                new GdriveOutputSupport(rcloneClient, userRepo, userAdminService, selfCheck, "GDriveOutput");
        radar = new TradingRadarExportScheduleService(timeRepo, radarSettingRepo, radarExportService,
                snapshotStore, currentUserProvider, gdrive, baseDir.toString(),
                radarService, priceQueryService, marketDataService);
        calendar = new TradingCalendarExportScheduleService(calendarRepo, calendarExportService,
                currentUserProvider, gdrive, baseDir.toString());
        when(radarSettingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(calendarRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(calendarExportService.requireValidFormat(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(calendarExportService.requireValidSubpath(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
    }

    private void givenCurrentUser(long id) {
        CurrentUserContext ctx = new CurrentUserContext();
        ctx.setEffectiveUserId(id);
        when(currentUserProvider.getObject()).thenReturn(ctx);
    }

    private void givenUser(long id, String email, boolean configuredAdmin) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setEmail(email);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));
        when(userAdminService.isConfiguredAdmin(email)).thenReturn(configuredAdmin);
    }

    private static LocalDate today() { return LocalDate.now(TW); }

    // ==========================================================================
    // 交易雷達
    // ==========================================================================

    private TradingRadarExportSetting radarSetting(long owner, boolean gdriveEnabled, String gdriveSubpath) {
        return TradingRadarExportSetting.builder()
                .ownerUserId(owner).outputSubpath("out")
                .gdriveEnabled(gdriveEnabled).gdriveSubpath(gdriveSubpath).build();
    }

    private void givenSnapshots(long owner) throws IOException {
        when(snapshotStore.range(eq(owner), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(
                        List.of(TextNode.valueOf("snap")), 1, 0));
        when(radarExportService.exportForOwner(eq(owner), anyLong(), anyLong()))
                .thenReturn("xlsx-bytes".getBytes());
    }

    @Test
    void 雷達_只送outputSubpath不得清掉既有Drive設定() {
        // 本頁原本一路裸傳字串，是最容易在改造中把 Drive 兩欄弄丟的形狀。
        givenCurrentUser(ADMIN_ID);
        when(radarSettingRepo.findByOwnerUserId(ADMIN_ID))
                .thenReturn(Optional.of(radarSetting(ADMIN_ID, true, DRIVE_DIR)));

        TradingRadarExportDto.SettingResponse resp =
                radar.saveSetting(new TradingRadarExportDto.SettingRequest("input", null, null));

        assertThat(resp.outputSubpath()).isEqualTo("input");   // 本機路徑照改
        assertThat(resp.gdriveEnabled()).isTrue();             // Drive 設定原封不動
        assertThat(resp.gdriveSubpath()).isEqualTo(DRIVE_DIR);
    }

    @Test
    void 雷達_非主要管理者啟用回403() {
        givenCurrentUser(OTHER_ID);
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        when(radarSettingRepo.findByOwnerUserId(OTHER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> radar.saveSetting(
                new TradingRadarExportDto.SettingRequest("input", true, DRIVE_DIR)))
                .isInstanceOf(AdminRequiredException.class);
    }

    @Test
    void 雷達_一天兩個時間點都會上傳_狀態為最後一次() throws Exception {
        // 執行時間點存於另一張表，與其餘七頁「每日單一時間」不同，故狀態欄是「最後一次」語意。
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        TradingRadarExportTime t1 = TradingRadarExportTime.builder()
                .id(1L).ownerUserId(ADMIN_ID).runHour(0).runMinute(0)
                .enabled(true).lastRunDate(today().minusDays(1)).build();
        TradingRadarExportTime t2 = TradingRadarExportTime.builder()
                .id(2L).ownerUserId(ADMIN_ID).runHour(0).runMinute(1)
                .enabled(true).lastRunDate(today().minusDays(1)).build();
        when(timeRepo.findAll()).thenReturn(List.of(t1, t2));
        when(radarSettingRepo.findByOwnerUserId(ADMIN_ID))
                .thenReturn(Optional.of(radarSetting(ADMIN_ID, true, DRIVE_DIR)));
        givenSnapshots(ADMIN_ID);
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenReturn("GDriveOutput:" + DRIVE_DIR + "/交易雷達_1_x.xlsx");

        radar.tick();

        verify(rcloneClient, times(2)).copyTo(anyString(), any(), eq(DRIVE_DIR), anyString());
        ArgumentCaptor<TradingRadarExportSetting> cap =
                ArgumentCaptor.forClass(TradingRadarExportSetting.class);
        verify(radarSettingRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getGdriveLastStatus()).startsWith("成功：");
    }

    @Test
    void 雷達_當日無快照時不上傳但寫跳過狀態() throws Exception {
        // writeDailyExport 當日無快照回 null 且不寫檔；此時絕不可上傳前一次的舊檔，
        // 但狀態欄仍須更新，否則會停在上一次的成功、顯示過期的好消息。
        givenCurrentUser(ADMIN_ID);
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        when(radarSettingRepo.findByOwnerUserId(ADMIN_ID))
                .thenReturn(Optional.of(radarSetting(ADMIN_ID, true, DRIVE_DIR)));
        when(snapshotStore.range(eq(ADMIN_ID), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));

        TradingRadarExportDto.RunNowResponse r = radar.runNow();

        assertThat(r.path()).isNull();
        assertThat(r.gdriveStatus()).contains("跳過").contains("快照");
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void 雷達_runNow上傳並回報落點() throws Exception {
        givenCurrentUser(ADMIN_ID);
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        when(radarSettingRepo.findByOwnerUserId(ADMIN_ID))
                .thenReturn(Optional.of(radarSetting(ADMIN_ID, true, DRIVE_DIR)));
        givenSnapshots(ADMIN_ID);
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenReturn("GDriveOutput:" + DRIVE_DIR + "/交易雷達_1_x.xlsx");

        TradingRadarExportDto.RunNowResponse r = radar.runNow();

        assertThat(Path.of(r.path())).exists();                  // 本機照寫
        assertThat(r.gdrivePath()).startsWith("GDriveOutput:" + DRIVE_DIR);
        assertThat(r.gdriveStatus()).startsWith("成功：");
    }

    @Test
    void 雷達_背景遇非主要管理者owner跳過上傳但本機照寫() throws Exception {
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        TradingRadarExportTime t = TradingRadarExportTime.builder()
                .id(1L).ownerUserId(OTHER_ID).runHour(0).runMinute(0)
                .enabled(true).lastRunDate(today().minusDays(1)).build();
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(radarSettingRepo.findByOwnerUserId(OTHER_ID))
                .thenReturn(Optional.of(radarSetting(OTHER_ID, true, DRIVE_DIR)));
        givenSnapshots(OTHER_ID);

        radar.tick();

        assertThat(baseDir.resolve("out")
                .resolve("交易雷達_" + OTHER_ID + "_" + today().format(FILE_DATE) + ".xlsx")).exists();
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
        ArgumentCaptor<TradingRadarExportSetting> cap =
                ArgumentCaptor.forClass(TradingRadarExportSetting.class);
        verify(radarSettingRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getGdriveLastStatus()).contains("跳過").contains("主要管理者");
    }

    // ==========================================================================
    // 交易日曆
    // ==========================================================================

    private TradingCalendarExportSchedule calendarSetting(long owner, boolean gdriveEnabled, String gdriveSubpath) {
        return TradingCalendarExportSchedule.builder()
                .id(owner).ownerUserId(owner)
                .enabled(true).runHour(0).runMinute(0).format("json").outputSubpath("input")
                .gdriveEnabled(gdriveEnabled).gdriveSubpath(gdriveSubpath).build();
    }

    /** 讓 exportToDir 真的把檔案落在它收到的子路徑，回傳與正式實作同形狀的 RunResponse。 */
    private void givenCalendarExportWrites() {
        when(calendarExportService.exportToDir(anyInt(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    int year = inv.getArgument(0);
                    String sub = inv.getArgument(2);
                    Path dir = baseDir.resolve(sub);
                    Files.createDirectories(dir);
                    Path f = dir.resolve("交易日曆_" + year + ".json");
                    Files.writeString(f, "{}");
                    return TradingCalendarExportDto.RunResponse.builder()
                            .path(f.toString()).sizeBytes(2).format("json").year(year).totalDays(365).build();
                });
    }

    @Test
    void 日曆_手動匯出的Drive目錄取自設定列而非queryParam() {
        // 本機目錄＝「這次匯出到哪」（query param）；Drive 目錄＝「Drive 同步的固定目的地」（設定列）。
        // 兩者刻意不同；若讓 Drive 也吃 query param，使用者每次手動匯出都可能倒進 Drive 的不同位置。
        givenCurrentUser(ADMIN_ID);
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        when(calendarRepo.findByOwnerUserId(ADMIN_ID))
                .thenReturn(Optional.of(calendarSetting(ADMIN_ID, true, DRIVE_DIR)));
        givenCalendarExportWrites();
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenReturn("GDriveOutput:" + DRIVE_DIR + "/交易日曆_2026.json");

        TradingCalendarExportDto.RunResponse r =
                calendar.runManualForCurrentUser(2026, "json", "Downloads");

        assertThat(r.path()).contains("Downloads");                       // 本機落在 query param 指定處
        verify(rcloneClient).copyTo(anyString(), any(), eq(DRIVE_DIR), anyString());  // Drive 落在設定列
        assertThat(r.gdriveStatus()).startsWith("成功：");
    }

    @Test
    void 日曆_未設過排程列時手動匯出不上傳也不建列() {
        givenCurrentUser(ADMIN_ID);
        when(calendarRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.empty());
        givenCalendarExportWrites();

        TradingCalendarExportDto.RunResponse r =
                calendar.runManualForCurrentUser(2026, "json", "Downloads");

        assertThat(r.gdriveStatus()).isNull();
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
        verify(calendarRepo, never()).save(any());
    }

    @Test
    void 日曆_排程路徑會上傳且本機照寫() {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        TradingCalendarExportSchedule s = calendarSetting(ADMIN_ID, true, DRIVE_DIR);
        when(calendarRepo.findAll()).thenReturn(List.of(s));
        givenCalendarExportWrites();
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenReturn("GDriveOutput:" + DRIVE_DIR + "/交易日曆_2026.json");

        calendar.selfHealOnStartup();

        assertThat(s.getLastRunStatus()).startsWith("成功：");
        assertThat(s.getGdriveLastStatus()).startsWith("成功：");
        verify(rcloneClient).copyTo(anyString(), any(), eq(DRIVE_DIR), anyString());
    }

    @Test
    void 日曆_排程本機失敗時不上傳但寫狀態欄() {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        TradingCalendarExportSchedule s = calendarSetting(ADMIN_ID, true, DRIVE_DIR);
        when(calendarRepo.findAll()).thenReturn(List.of(s));
        when(calendarExportService.exportToDir(anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("產檔失敗"));

        calendar.selfHealOnStartup();

        assertThat(s.getLastRunStatus()).startsWith("失敗：");
        assertThat(s.getGdriveLastStatus()).startsWith("跳過：");
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void 日曆_非主要管理者啟用回403() {
        givenCurrentUser(OTHER_ID);
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        when(calendarRepo.findByOwnerUserId(OTHER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> calendar.updateForCurrentUser(
                new TradingCalendarExportDto.ScheduleSettingRequest(
                        true, 8, 0, "json", "input", true, DRIVE_DIR)))
                .isInstanceOf(AdminRequiredException.class);
    }

    @Test
    void 日曆_只改格式不得清掉既有Drive設定() {
        givenCurrentUser(ADMIN_ID);
        when(calendarRepo.findByOwnerUserId(ADMIN_ID))
                .thenReturn(Optional.of(calendarSetting(ADMIN_ID, true, DRIVE_DIR)));

        TradingCalendarExportDto.ScheduleSettingResponse resp = calendar.updateForCurrentUser(
                new TradingCalendarExportDto.ScheduleSettingRequest(
                        true, 8, 0, "excel", "input", null, null));

        assertThat(resp.format()).isEqualTo("excel");
        assertThat(resp.gdriveEnabled()).isTrue();
        assertThat(resp.gdriveSubpath()).isEqualTo(DRIVE_DIR);
    }
}
