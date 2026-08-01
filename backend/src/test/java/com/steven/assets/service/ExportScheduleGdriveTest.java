package com.steven.assets.service;

import com.steven.assets.dto.ExportScheduleDto;
import com.steven.assets.model.AppUser;
import com.steven.assets.model.ExportScheduleSetting;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.ExportScheduleSettingRepository;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 歷年資產匯出頁的 Google Drive 同步（Requirement 51 / Task 243）——六個結構相近頁面的代表。
 *
 * <p>驗證規則本身由 {@link GdriveOutputSupportTest} 涵蓋；這裡測的是「service 有沒有正確串上它」，
 * 以及三條最關鍵的迴歸：
 * <ol>
 *   <li><b>非主要管理者不能啟用</b>——rclone remote 全機只有一份且綁定某個 Google 帳號，
 *       允許其他人啟用等於把他的財務報表上傳到那個帳號的雲端硬碟，而且從當事人角度完全不可見</li>
 *   <li><b>背景排程逐列重驗 owner</b>——背景執行緒沒有 {@code CurrentUserContext}，PUT 當下的檢查在此不適用</li>
 *   <li><b>上傳失敗不影響本機</b>——本機那一份是既有的留存機制，Drive 只是附加副本</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExportScheduleGdriveTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long ADMIN_ID = 1L;
    private static final long OTHER_ID = 2L;
    private static final String DRIVE_DIR = "投資理財/資產管理";

    @Mock private ExportScheduleSettingRepository settingRepo;
    @Mock private ExcelExportService excelExportService;
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private RcloneClient rcloneClient;
    @Mock private AppUserRepository userRepo;
    @Mock private UserAdminService userAdminService;
    /** 啟用當下的自檢（Task 247）；替身預設回 null＝自檢正常，要測警告時再 stub。 */
    @Mock private GdriveSelfCheck selfCheck;

    @TempDir Path baseDir;

    private ExportScheduleService service;

    @BeforeEach
    void setup() {
        GdriveOutputSupport gdrive =
                new GdriveOutputSupport(rcloneClient, userRepo, userAdminService, selfCheck, "GDriveOutput");
        service = new ExportScheduleService(
                settingRepo, excelExportService, currentUserProvider, gdrive,
                new com.steven.assets.service.export.ExcelDocRenderer(),
                new com.steven.assets.service.export.JsonDocRenderer(new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.steven.assets.service.export.DualFormatExportWriter(gdrive),
                baseDir.toString());
        when(settingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 最小合法 doc：本測試驗的是 Drive 同步與狀態欄，內容只需能被兩個 renderer 產出。 */
    private static com.steven.assets.service.export.ExportDoc doc() {
        var table = new com.steven.assets.service.export.ExportDoc.Table(
                null, null, java.util.List.of("代號"), true, false, false, null,
                java.util.List.of(java.util.List.of("2330")));
        return new com.steven.assets.service.export.ExportDoc("資產總覽",
                java.util.List.of(new com.steven.assets.service.export.ExportDoc.Sheet(
                        "當前即時資產", java.util.List.of(table), 1)));
    }

    private void givenCurrentUser(long id) {
        CurrentUserContext ctx = new CurrentUserContext();
        ctx.setEffectiveUserId(id);
        when(currentUserProvider.getObject()).thenReturn(ctx);
    }

    /** @param configuredAdmin 是否為主要管理者（{@code ADMIN_EMAIL} 本人），與 role 欄位無關 */
    private void givenUser(long id, String email, boolean configuredAdmin) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setEmail(email);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));
        when(userAdminService.isConfiguredAdmin(email)).thenReturn(configuredAdmin);
    }

    private ExportScheduleSetting setting(long owner, boolean gdriveEnabled, String gdriveSubpath) {
        return ExportScheduleSetting.builder()
                .id(owner).ownerUserId(owner)
                .enabled(true).runHour(0).runMinute(0).outputSubpath("input")
                .gdriveEnabled(gdriveEnabled).gdriveSubpath(gdriveSubpath)
                .build();
    }

    private Path expectedLocalFile(long owner) {
        return baseDir.resolve("input")
                .resolve("資產總覽_" + owner + "_" + LocalDate.now(TW).format(FILE_DATE) + ".xlsx");
    }

    // ===== 243.2.1／243.2.2：只有主要管理者能啟用 =====

    @Test
    void 非主要管理者啟用Drive回403_即使role是ADMIN() {
        // 這裡刻意用「role 為 ADMIN 但 email 非 ADMIN_EMAIL」的使用者：若實作誤用 role 判準，本測試會過不了。
        givenCurrentUser(OTHER_ID);
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        when(settingRepo.findByOwnerUserId(OTHER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateForCurrentUser(new ExportScheduleDto.SettingRequest(
                true, 8, 0, "input", true, DRIVE_DIR)))
                .isInstanceOf(AdminRequiredException.class);   // 403，不是 400
        verify(settingRepo, never()).save(any());
    }

    @Test
    void 主要管理者可啟用() {
        givenCurrentUser(ADMIN_ID);
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.empty());

        ExportScheduleDto.SettingResponse resp = service.updateForCurrentUser(
                new ExportScheduleDto.SettingRequest(true, 8, 0, "input", true, DRIVE_DIR));

        assertThat(resp.gdriveEnabled()).isTrue();
        assertThat(resp.gdriveSubpath()).isEqualTo(DRIVE_DIR);
        assertThat(resp.gdriveRemote()).isEqualTo("GDriveOutput");
    }

    @Test
    void 非主要管理者仍可設定本機路徑與排程時間() {
        // 這個不對稱是刻意的：只有 Drive 這一項會把資料送出本機。整支 PUT 限 ADMIN 會破壞既有的
        // per-user 排程設定能力（Requirement 39／49 明訂非管理者亦可設定自己的排程）。
        givenCurrentUser(OTHER_ID);
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        when(settingRepo.findByOwnerUserId(OTHER_ID)).thenReturn(Optional.empty());

        ExportScheduleDto.SettingResponse resp = service.updateForCurrentUser(
                new ExportScheduleDto.SettingRequest(true, 9, 30, "Project/SRPP/data/input", null, null));

        assertThat(resp.outputSubpath()).isEqualTo("Project/SRPP/data/input");
        assertThat(resp.runHour()).isEqualTo(9);
        assertThat(resp.gdriveEnabled()).isFalse();
    }

    // ===== 243.1.1：未送出的欄位＝不變更 =====

    @Test
    void 未送gdriveEnabled不得把已開啟的開關關掉() {
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(setting(ADMIN_ID, true, DRIVE_DIR)));

        ExportScheduleDto.SettingResponse resp = service.updateForCurrentUser(
                new ExportScheduleDto.SettingRequest(true, 8, 0, "input", null, null));

        assertThat(resp.gdriveEnabled()).isTrue();
        assertThat(resp.gdriveSubpath()).isEqualTo(DRIVE_DIR);
    }

    @Test
    void 關閉開關不清空既有子路徑() {
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(setting(ADMIN_ID, true, DRIVE_DIR)));

        ExportScheduleDto.SettingResponse resp = service.updateForCurrentUser(
                new ExportScheduleDto.SettingRequest(true, 8, 0, "input", false, null));

        assertThat(resp.gdriveEnabled()).isFalse();
        assertThat(resp.gdriveSubpath()).isEqualTo(DRIVE_DIR); // 關掉再開回來不必重填
    }

    @Test
    void 讀取既有不合法的Drive值不擲例外() {
        // 若讀取也失敗，設定頁會 500，使用者就沒有任何入口能把它改回正常值。
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID))
                .thenReturn(Optional.of(setting(ADMIN_ID, true, "/絕對路徑")));

        ExportScheduleDto.SettingResponse resp = service.getForCurrentUser();

        assertThat(resp.gdriveSubpath()).isEqualTo("/絕對路徑"); // 原樣回傳供前端顯示與修正
    }

    // ===== 247.3：啟用當下的自檢（八頁共用掛載點的串接）=====

    @Test
    void 啟用當下自檢失敗仍回2xx_設定照存且不覆蓋上次上傳兩欄() {
        // 自檢失敗不得讓儲存變成 4xx／5xx：使用者必須能先把設定存起來再去修授權，
        // 否則唯一的修正入口被自己鎖死。
        givenCurrentUser(ADMIN_ID);
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        ExportScheduleSetting s = setting(ADMIN_ID, false, null);
        LocalDateTime lastUpload = LocalDateTime.of(2026, 7, 27, 23, 0);
        s.setGdriveLastRunAt(lastUpload);
        s.setGdriveLastStatus("成功：GDriveOutput:" + DRIVE_DIR + "/資產總覽_1_20260727.xlsx（1234 bytes）");
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(s));
        when(selfCheck.checkLocal("GDriveOutput"))
                .thenReturn("讀不到 rclone 設定 /etc/rclone/rclone.conf（檔案不存在或掛載已失效）");

        ExportScheduleDto.SettingResponse resp = service.updateForCurrentUser(
                new ExportScheduleDto.SettingRequest(true, 8, 0, "input", true, DRIVE_DIR));

        assertThat(resp.gdriveSelfCheckWarning()).contains("讀不到 rclone 設定");
        assertThat(resp.gdriveEnabled()).isTrue();
        assertThat(s.isGdriveEnabled()).isTrue();               // 設定確實入庫
        assertThat(s.getGdriveSubpath()).isEqualTo(DRIVE_DIR);
        // 那兩欄的語意是「上次上傳」（九個前端頁面都這樣標）：寫進自檢結果會永久覆蓋昨晚真正的上傳記錄，
        // 且 gdrive_last_run_at 會變成一個根本沒發生過上傳的時刻。
        assertThat(s.getGdriveLastRunAt()).isEqualTo(lastUpload);
        assertThat(s.getGdriveLastStatus()).startsWith("成功：");
    }

    @Test
    void 已啟用時再次儲存不重跑自檢_回應也不帶警告() {
        givenCurrentUser(ADMIN_ID);
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);   // 明確送 true 仍會走權限檢查
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(setting(ADMIN_ID, true, DRIVE_DIR)));

        ExportScheduleDto.SettingResponse resp = service.updateForCurrentUser(
                new ExportScheduleDto.SettingRequest(true, 9, 30, "input", true, DRIVE_DIR));

        assertThat(resp.gdriveSelfCheckWarning()).isNull();
        verify(selfCheck, never()).checkLocal(anyString());
    }

    @Test
    void 讀取設定不做自檢() {
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(setting(ADMIN_ID, true, DRIVE_DIR)));

        assertThat(service.getForCurrentUser().gdriveSelfCheckWarning()).isNull();
        verify(selfCheck, never()).checkLocal(anyString());
    }

    // ===== 243.3.3：run-now 也上傳並回報落點 =====

    @Test
    void runNow啟用時上傳並回報落點() throws IOException {
        givenCurrentUser(ADMIN_ID);
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(setting(ADMIN_ID, true, DRIVE_DIR)));
        when(excelExportService.liveAssetsDoc()).thenReturn(doc());
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenReturn("GDriveOutput:" + DRIVE_DIR + "/資產總覽_1_x.xlsx");

        ExportScheduleDto.RunNowResponse r = service.runNowForCurrentUser();

        assertThat(Path.of(r.path())).exists();                       // 本機照寫
        assertThat(r.gdrivePath()).startsWith("GDriveOutput:" + DRIVE_DIR);
        assertThat(r.gdriveStatus()).startsWith("xlsx 成功：").contains("／json ");
    }

    @Test
    void 未啟用時run_now不上傳且不碰狀態欄() throws IOException {
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(setting(ADMIN_ID, false, null)));
        when(excelExportService.liveAssetsDoc()).thenReturn(doc());

        ExportScheduleDto.RunNowResponse r = service.runNowForCurrentUser();

        assertThat(r.gdriveStatus()).isNull();
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }

    // ===== 243.4：背景排程逐列複驗 owner =====

    @Test
    void 背景排程遇非主要管理者owner_跳過上傳但本機照寫() throws IOException {
        // 該列可能在啟用後 owner 被改、DB 值被 psql 直改繞過 API、或 ADMIN_EMAIL 換人。
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        ExportScheduleSetting s = setting(OTHER_ID, true, DRIVE_DIR);
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.liveAssetsDocForOwner(OTHER_ID)).thenReturn(doc());

        service.selfHealOnStartup();

        assertThat(expectedLocalFile(OTHER_ID)).exists();              // 本機那一份不受影響
        assertThat(s.getLastRunStatus()).startsWith("xlsx 成功：");    // 既有排程狀態仍為成功
        assertThat(s.getGdriveLastStatus()).contains("跳過").contains("主要管理者");
        assertThat(s.getGdriveLastRunAt()).isNotNull();                // 不可靜默跳過
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void 背景排程主要管理者_本機與Drive都寫() throws IOException {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        ExportScheduleSetting s = setting(ADMIN_ID, true, DRIVE_DIR);
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.liveAssetsDocForOwner(ADMIN_ID)).thenReturn(doc());
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenReturn("GDriveOutput:" + DRIVE_DIR + "/資產總覽_1_x.xlsx");

        service.selfHealOnStartup();

        assertThat(expectedLocalFile(ADMIN_ID)).exists();
        assertThat(s.getLastRunStatus()).startsWith("xlsx 成功：").contains("／json 成功：");
        assertThat(s.getGdriveLastStatus()).startsWith("xlsx 成功：").contains("／json ");
    }

    // ===== 243.3.4：上傳失敗為 best-effort =====

    @Test
    void 上傳失敗不影響本機也不向外擲例外() throws IOException {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        ExportScheduleSetting s = setting(ADMIN_ID, true, DRIVE_DIR);
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.liveAssetsDocForOwner(ADMIN_ID)).thenReturn(doc());
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("rclone exit 3"));

        service.selfHealOnStartup();   // 不得擲例外——一列炸掉不能拖垮其餘 owner

        assertThat(expectedLocalFile(ADMIN_ID)).exists();
        assertThat(s.getLastRunStatus()).startsWith("xlsx 成功：");        // 本機確實成功，不得標記為失敗
        assertThat(s.getGdriveLastStatus()).contains("失敗：");             // 兩個狀態欄必須可分辨
    }

    @Test
    void 逾時不寫成失敗() throws IOException {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        ExportScheduleSetting s = setting(ADMIN_ID, true, DRIVE_DIR);
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.liveAssetsDocForOwner(ADMIN_ID)).thenReturn(doc());
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenThrow(new RcloneClient.RcloneTimeoutException("timeout", 45));

        service.selfHealOnStartup();

        assertThat(s.getGdriveLastStatus()).contains("逾時").contains("可能已完成");
    }

    @Test
    void 本機產檔失敗時完全不上傳但仍寫狀態欄() throws IOException {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        ExportScheduleSetting s = setting(ADMIN_ID, true, DRIVE_DIR);
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.liveAssetsDocForOwner(ADMIN_ID))
                .thenThrow(new RuntimeException("產檔失敗"));

        service.selfHealOnStartup();

        assertThat(s.getLastRunStatus()).startsWith("失敗：");
        // 絕不上傳前一次的舊檔；狀態欄也不得停留在上一次的成功
        assertThat(s.getGdriveLastStatus()).startsWith("跳過：");
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }
}
