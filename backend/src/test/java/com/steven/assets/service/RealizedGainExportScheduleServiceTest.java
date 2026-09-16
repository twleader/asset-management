package com.steven.assets.service;

import com.steven.assets.dto.RealizedGainExportDto;
import com.steven.assets.model.AppUser;
import com.steven.assets.model.RealizedGainExportSchedule;
import com.steven.assets.model.RealizedGainExportScheduleTime;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.RealizedGainExportScheduleRepository;
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
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 已實現損益排程自動匯出（Requirement 39 / Task 196；多時間點 Requirement 73 / Task 331）——
 * {@code RealizedGainExportScheduleService} 專屬測試。
 *
 * <p>main 上本服務原本沒有專屬測試檔（{@link com.steven.assets.service.export.SingleTableScheduleServiceDualFormatTest}
 * 只涵蓋雙格式落檔的既有單時間行為，未觸及本任務新增的多時間點邏輯），故本檔為新建，不是擴充既有測試。
 * 寫法比照歷年資產頁同一種改造的 {@link ExportScheduleGdriveTest}（Requirement 69 / Task 326）與
 * 油價金價頁的 {@link CommodityExportScheduleServiceTest}（Requirement 72 / Task 330）。
 *
 * <p><b>與油價金價那支的關鍵差異</b>：本頁資料是 per-user 損益，背景每列必須走
 * {@code realizedGainsDocForOwner(ownerId)} 而非全域 doc——{@link #背景逐列走owner_scoped版而非全域doc()}
 * 把這條租戶隔離釘成回歸斷言。
 *
 * <p>兩個 renderer 與 {@link com.steven.assets.service.export.DualFormatExportWriter} 一律注入<b>真實實例</b>：
 * 換成 mock 就驗不到「兩份檔真的被寫出來」。rclone 以介面替身注入、不實際連網。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealizedGainExportScheduleServiceTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long ADMIN_ID = 1L;
    private static final long OTHER_ID = 2L;
    private static final String DRIVE_DIR = "投資理財/已實現損益";

    @Mock private RealizedGainExportScheduleRepository settingRepo;
    @Mock private ExcelExportService excelExportService;
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private RcloneClient rcloneClient;
    @Mock private AppUserRepository userRepo;
    @Mock private UserAdminService userAdminService;
    /** 啟用當下的自檢（Task 247）；替身預設回 null＝自檢正常，要測警告時再 stub。 */
    @Mock private GdriveSelfCheck selfCheck;

    @TempDir Path baseDir;

    private GdriveOutputSupport gdrive;
    private RealizedGainExportScheduleService service;

    @BeforeEach
    void setup() {
        gdrive = new GdriveOutputSupport(rcloneClient, userRepo, userAdminService, selfCheck, "GDriveOutput");
        service = new RealizedGainExportScheduleService(
                settingRepo, excelExportService, currentUserProvider, gdrive,
                new com.steven.assets.service.export.ExcelDocRenderer(),
                new com.steven.assets.service.export.JsonDocRenderer(new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.steven.assets.service.export.DualFormatExportWriter(gdrive),
                baseDir.toString());
        com.steven.assets.service.ExportScheduleUnitHarness.attach(service, settingRepo);
        when(settingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 最小合法 doc：本測試驗的是排程／guard／Drive 狀態，內容只需能被兩個 renderer 產出。 */
    private static com.steven.assets.service.export.ExportDoc doc() {
        var table = new com.steven.assets.service.export.ExportDoc.Table(
                null, null, java.util.List.of("代號"), true, false, false, null,
                java.util.List.of(java.util.List.of("2330")));
        return new com.steven.assets.service.export.ExportDoc("已實現損益",
                java.util.List.of(new com.steven.assets.service.export.ExportDoc.Sheet(
                        "已實現損益", java.util.List.of(table), 1)));
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

    private RealizedGainExportSchedule setting(long owner, boolean gdriveEnabled, String gdriveSubpath) {
        return RealizedGainExportSchedule.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId())
                .id(owner).ownerUserId(owner)
                .enabled(true).outputSubpath("input")
                .gdriveEnabled(gdriveEnabled).gdriveSubpath(gdriveSubpath)
                .build();
    }

    private Path expectedLocalFile(long owner) {
        return baseDir.resolve("input")
                .resolve("已實現損益_" + owner + "_" + LocalDate.now(TW).format(FILE_DATE) + ".xlsx");
    }

    // ===== transient 預設與 rolling-upgrade fallback（GET 一律不寫 DB） =====

    @Test
    void 無設定時GET回transient啟用0800且不寫DB() {
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.empty());

        RealizedGainExportDto.SettingResponse response = service.getForCurrentUser();

        assertThat(response.enabled()).isFalse();
        assertThat(response.times()).singleElement().satisfies(time -> {
            assertThat(time.runHour()).isEqualTo(8);
            assertThat(time.runMinute()).isZero();
            assertThat(time.enabled()).isTrue();
            assertThat(time.lastRunAt()).isNull();
            assertThat(time.lastRunStatus()).isNull();
        });
        verify(settingRepo, never()).save(any());
    }

    @Test
    void DB列的times為空時GET以legacy時分補一列且不寫DB() {
        givenCurrentUser(ADMIN_ID);
        RealizedGainExportSchedule legacy = setting(ADMIN_ID, false, null);
        legacy.setRunHour(9);
        legacy.setRunMinute(30);
        legacy.setLastRunStatus("昨天的狀態");
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(legacy));

        RealizedGainExportDto.SettingResponse response = service.getForCurrentUser();

        assertThat(response.times()).singleElement().satisfies(time -> {
            assertThat(time.runHour()).isEqualTo(9);
            assertThat(time.runMinute()).isEqualTo(30);
            assertThat(time.lastRunStatus()).isEqualTo("昨天的狀態");
        });
        verify(settingRepo, never()).save(any());
    }

    // ===== times[] 排序、整包取代、驗證 =====

    @Test
    void 多個時間依時分排序且各自保留相同時間的guard_新時間guard為null() {
        givenCurrentUser(ADMIN_ID);
        RealizedGainExportSchedule current = setting(ADMIN_ID, false, null);
        var nineThirty = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId())
                .runHour(9).runMinute(30).enabled(true).lastRunDate(LocalDate.now(TW).minusDays(1))
                .lastRunStatus("舊狀態").build();
        current.addTime(nineThirty);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(current));

        RealizedGainExportDto.SettingResponse response = service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(true, "input", null, null,
                        List.of(new RealizedGainExportDto.TimeRequest(12, 0, true),
                                new RealizedGainExportDto.TimeRequest(9, 30, true))));

        assertThat(response.times()).extracting(RealizedGainExportDto.TimeResponse::runHour).containsExactly(9, 12);
        assertThat(current.getTimes()).hasSize(2);
        assertThat(current.getTimes()).anySatisfy(time -> {
            assertThat(time.getRunHour()).isEqualTo(9);
            assertThat(time.getLastRunDate()).isEqualTo(LocalDate.now(TW).minusDays(1));
            assertThat(time.getLastRunStatus()).isEqualTo("舊狀態");
        });
        assertThat(current.getTimes()).anySatisfy(time -> {
            assertThat(time.getRunHour()).isEqualTo(12);
            assertThat(time.getLastRunDate()).isNull();
            assertThat(time.getLastRunStatus()).isNull();
        });
        // rollback representative 也必須是最早 enabled child（時分與其 guard 一起 dual-write）。
        assertThat(current.getRunHour()).isEqualTo(9);
        assertThat(current.getRunMinute()).isEqualTo(30);
        assertThat(current.getLastRunDate()).isEqualTo(LocalDate.now(TW).minusDays(1));
    }

    @Test
    void 整包取代時不在清單內的舊時間被移除() {
        givenCurrentUser(ADMIN_ID);
        RealizedGainExportSchedule current = setting(ADMIN_ID, false, null);
        current.addTime(RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(20).runMinute(0).enabled(true).build());
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(current));

        RealizedGainExportDto.SettingResponse response = service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(true, "input", null, null,
                        List.of(new RealizedGainExportDto.TimeRequest(8, 0, true))));

        assertThat(response.times()).extracting(RealizedGainExportDto.TimeResponse::runHour).containsExactly(8);
        assertThat(current.getTimes()).extracting(RealizedGainExportScheduleTime::getRunHour).containsExactly(8);
    }

    @Test
    void 全部停用時representative取最早的那一列() {
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.empty());

        service.updateForCurrentUser(new RealizedGainExportDto.SettingRequest(false, "input", null, null,
                List.of(new RealizedGainExportDto.TimeRequest(18, 0, false),
                        new RealizedGainExportDto.TimeRequest(7, 15, false))));

        ArgumentCaptor<RealizedGainExportSchedule> saved = ArgumentCaptor.forClass(RealizedGainExportSchedule.class);
        verify(settingRepo).save(saved.capture());
        assertThat(saved.getValue().getRunHour()).isEqualTo(7);
        assertThat(saved.getValue().getRunMinute()).isEqualTo(15);
    }

    @Test
    void 儲存拒絕空時間清單() {
        givenCurrentUser(ADMIN_ID);

        assertThatThrownBy(() -> service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(false, "input", null, null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一個執行時間");
        verify(settingRepo, never()).save(any());
    }

    @Test
    void 儲存拒絕重複時間() {
        givenCurrentUser(ADMIN_ID);

        assertThatThrownBy(() -> service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(false, "input", null, null,
                        List.of(new RealizedGainExportDto.TimeRequest(8, 0, true),
                                new RealizedGainExportDto.TimeRequest(8, 0, false)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("執行時間不可重複");
        verify(settingRepo, never()).save(any());
    }

    @Test
    void 儲存拒絕非法時分() {
        givenCurrentUser(ADMIN_ID);

        assertThatThrownBy(() -> service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(false, "input", null, null,
                        List.of(new RealizedGainExportDto.TimeRequest(24, 0, true)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("00:00～23:59");
        verify(settingRepo, never()).save(any());
    }

    @Test
    void 總排程啟用時拒絕所有時間停用() {
        givenCurrentUser(ADMIN_ID);

        assertThatThrownBy(() -> service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(true, "input", null, null,
                        List.of(new RealizedGainExportDto.TimeRequest(8, 0, false),
                                new RealizedGainExportDto.TimeRequest(12, 0, false)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需啟用一個時間");
        verify(settingRepo, never()).save(any());
    }

    @Test
    void 總排程停用時允許所有時間停用() {
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.empty());

        RealizedGainExportDto.SettingResponse response = service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(false, "input", null, null,
                        List.of(new RealizedGainExportDto.TimeRequest(8, 0, false))));

        assertThat(response.enabled()).isFalse();
        assertThat(response.times()).singleElement()
                .satisfies(t -> assertThat(t.enabled()).isFalse());
    }

    @Test
    void 本機子路徑不得跳脫基底() {
        givenCurrentUser(ADMIN_ID);

        assertThatThrownBy(() -> service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(false, "../../etc", null, null,
                        List.of(new RealizedGainExportDto.TimeRequest(8, 0, true)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可跳脫基底目錄");
        verify(settingRepo, never()).save(any());
    }

    // ===== 背景 due runner：多時間點各自判斷 =====

    @Test
    void 兩個到點時間各自執行_其一已guard不阻擋另一() throws Exception {
        RealizedGainExportSchedule s = setting(ADMIN_ID, false, null);
        var already = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0)
                .enabled(true).lastRunDate(LocalDate.now(TW)).build(); // 今天已跑過
        var due = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(1)
                .enabled(true).build(); // 未跑過
        s.addTime(already);
        s.addTime(due);
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.realizedGainsDocForOwner(ADMIN_ID)).thenReturn(doc());

        service.selfHealOnStartup();

        assertThat(already.getLastRunStatus()).isNull(); // 未被動到
        assertThat(due.getLastRunDate()).isEqualTo(LocalDate.now(TW));
        assertThat(due.getLastRunStatus()).startsWith("xlsx 成功：");
        verify(excelExportService, times(1)).realizedGainsDocForOwner(ADMIN_ID);
    }

    @Test
    void 停用的時間點不執行() throws Exception {
        RealizedGainExportSchedule s = setting(ADMIN_ID, false, null);
        var disabledTime = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0).enabled(false).build();
        s.addTime(disabledTime);
        when(settingRepo.findAll()).thenReturn(List.of(s));

        service.selfHealOnStartup();

        verify(excelExportService, never()).realizedGainsDocForOwner(anyLong());
        assertThat(disabledTime.getLastRunDate()).isNull();
    }

    @Test
    void 停用的parent其到點child也不執行() throws Exception {
        RealizedGainExportSchedule s = setting(ADMIN_ID, false, null);
        s.setEnabled(false);
        var due = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0).enabled(true).build();
        s.addTime(due);
        when(settingRepo.findAll()).thenReturn(List.of(s));

        service.selfHealOnStartup();

        verify(excelExportService, never()).realizedGainsDocForOwner(anyLong());
        assertThat(due.getLastRunDate()).isNull();
    }

    @Test
    void 尚未到執行時間不跑() throws Exception {
        RealizedGainExportSchedule s = setting(ADMIN_ID, false, null);
        s.addTime(RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(23).runMinute(59).enabled(true).build());
        when(settingRepo.findAll()).thenReturn(List.of(s));

        service.selfHealOnStartup();

        verify(excelExportService, never()).realizedGainsDocForOwner(anyLong());
    }

    @Test
    void 空children的舊parent仍以legacy時分跑一次() throws Exception {
        // rollback 期間由舊 image 新建的 parent 沒有 child：不得因此靜默完全停跑。
        RealizedGainExportSchedule s = setting(ADMIN_ID, false, null);
        s.setRunHour(0);
        s.setRunMinute(0);
        s.setLastRunDate(LocalDate.now(TW).minusDays(1));
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.realizedGainsDocForOwner(ADMIN_ID)).thenReturn(doc());

        service.selfHealOnStartup();

        assertThat(expectedLocalFile(ADMIN_ID)).exists();
        assertThat(s.getTimes()).singleElement()
                .satisfies(t -> assertThat(t.getLastRunDate()).isEqualTo(LocalDate.now(TW)));
        assertThat(s.getLastRunDate()).isEqualTo(LocalDate.now(TW)); // representative guard 同步回 parent
    }

    @Test
    void 多個overdue時間各跑一次_前一失敗不阻斷後一且parent摘要取最後完成child() throws Exception {
        RealizedGainExportSchedule s = setting(ADMIN_ID, false, null);
        var first = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0).enabled(true).build();
        var second = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(1).enabled(true).build();
        s.addTime(first);
        s.addTime(second);
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.realizedGainsDocForOwner(ADMIN_ID))
                .thenThrow(new RuntimeException("第一個失敗"))
                .thenReturn(doc());

        service.selfHealOnStartup();

        assertThat(first.getLastRunDate()).isEqualTo(LocalDate.now(TW));
        assertThat(second.getLastRunDate()).isEqualTo(LocalDate.now(TW));
        assertThat(first.getLastRunStatus()).startsWith("失敗：第一個失敗");
        assertThat(second.getLastRunStatus()).startsWith("xlsx 成功：");
        assertThat(s.getRunHour()).isZero();
        assertThat(s.getRunMinute()).isZero();
        assertThat(s.getLastRunDate()).isEqualTo(first.getLastRunDate());
        assertThat(s.getLastRunStatus()).isEqualTo(second.getLastRunStatus());
        assertThat(s.getLastRunAt()).isEqualTo(second.getLastRunAt());
        verify(excelExportService, times(2)).realizedGainsDocForOwner(ADMIN_ID);
    }

    @Test
    void startup與minuteTick共用同一CAS避免同一時間重入() throws Exception {
        RealizedGainExportSchedule s = setting(ADMIN_ID, false, null);
        var due = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0).enabled(true).build();
        s.addTime(due);
        when(settingRepo.findAll()).thenReturn(List.of(s));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(excelExportService.realizedGainsDocForOwner(ADMIN_ID)).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("測試等待逾時");
            return doc();
        });

        CompletableFuture<Void> startup = CompletableFuture.runAsync(service::selfHealOnStartup);
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            service.tick();
        } finally {
            release.countDown();
        }
        startup.get(5, TimeUnit.SECONDS);

        verify(excelExportService, times(1)).realizedGainsDocForOwner(ADMIN_ID);
        assertThat(due.getLastRunDate()).isEqualTo(LocalDate.now(TW));
    }

    // ===== 租戶隔離（本頁與油價金價最大的差異） =====

    @Test
    void 背景逐列走owner_scoped版而非全域doc() throws Exception {
        RealizedGainExportSchedule a = setting(ADMIN_ID, false, null);
        a.addTime(RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0).enabled(true).build());
        RealizedGainExportSchedule b = setting(OTHER_ID, false, null);
        b.addTime(RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0).enabled(true).build());
        when(settingRepo.findAll()).thenReturn(List.of(a, b));
        when(excelExportService.realizedGainsDocForOwner(anyLong())).thenReturn(doc());

        service.selfHealOnStartup();

        assertThat(expectedLocalFile(ADMIN_ID)).exists();
        assertThat(expectedLocalFile(OTHER_ID)).exists();
        verify(excelExportService, times(1)).realizedGainsDocForOwner(ADMIN_ID);
        verify(excelExportService, times(1)).realizedGainsDocForOwner(OTHER_ID);
        // 背景無 request context：走 HTTP 版會把所有人的損益寫進每個人的檔案
        verify(excelExportService, never()).realizedGainsDoc();
    }

    // ===== run-now：不消耗任何時間點 =====

    @Test
    void runNow既有多時間設定不新增也不修改childGuard() throws Exception {
        givenCurrentUser(ADMIN_ID);
        RealizedGainExportSchedule s = setting(ADMIN_ID, false, null);
        var morning = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(8).runMinute(0)
                .enabled(true).lastRunDate(LocalDate.now(TW).minusDays(1)).lastRunStatus("原有狀態").build();
        s.addTime(morning);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(s));
        when(excelExportService.realizedGainsDoc()).thenReturn(doc());

        service.runNowForCurrentUser();

        assertThat(s.getTimes()).containsExactly(morning);
        assertThat(morning.getLastRunDate()).isEqualTo(LocalDate.now(TW).minusDays(1));
        assertThat(morning.getLastRunStatus()).isEqualTo("原有狀態");
    }

    @Test
    void runNow無設定只產檔而不建立設定或child() throws Exception {
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.empty());
        when(excelExportService.realizedGainsDoc()).thenReturn(doc());

        var result = service.runNowForCurrentUser();
        assertThat(result.path()).isNotNull();
        assertThat(result.jsonPath()).isNotNull();
        verify(settingRepo, never()).save(any());
        verify(settingRepo, never()).saveAndFlush(any());
    }

    @Test
    void runNow不動任何child當日guard_即使已到點() throws Exception {
        givenCurrentUser(ADMIN_ID);
        RealizedGainExportSchedule s = setting(ADMIN_ID, false, null);
        var due = RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0).enabled(true).build(); // 從未執行過
        s.addTime(due);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(s));
        when(excelExportService.realizedGainsDoc()).thenReturn(doc());

        service.runNowForCurrentUser();

        // run-now 本身會更新 parent 的 lastRunAt/lastRunStatus，但不得動任何 child 的 guard。
        assertThat(due.getLastRunDate()).isNull();
        assertThat(due.getLastRunAt()).isNull();
        assertThat(due.getLastRunStatus()).isNull();
        assertThat(s.getLastRunAt()).isNotNull();
    }

    @Test
    void runNow走HTTP版doc而非owner_scoped版() throws Exception {
        // HTTP 情境由 TenantFilterAspect 自動 owner-scoped；這裡確保沒有誤用背景那一支。
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(setting(ADMIN_ID, false, null)));
        when(excelExportService.realizedGainsDoc()).thenReturn(doc());

        RealizedGainExportDto.RunNowResponse r = service.runNowForCurrentUser();

        assertThat(Path.of(r.path())).exists();
        assertThat(Path.of(r.jsonPath())).exists();
        verify(excelExportService, times(1)).realizedGainsDoc();
        verify(excelExportService, never()).realizedGainsDocForOwner(anyLong());
    }

    // ===== Google Drive best-effort（規則本身由 GdriveOutputSupportTest 涵蓋，這裡驗 service 有串上） =====

    @Test
    void runNow啟用時上傳並回報落點() throws IOException {
        givenCurrentUser(ADMIN_ID);
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(setting(ADMIN_ID, true, DRIVE_DIR)));
        when(excelExportService.realizedGainsDoc()).thenReturn(doc());
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenReturn("GDriveOutput:" + DRIVE_DIR + "/已實現損益_1_x.xlsx");

        RealizedGainExportDto.RunNowResponse r = service.runNowForCurrentUser();

        assertThat(Path.of(r.path())).exists();                       // 本機照寫
        assertThat(r.gdrivePath()).startsWith("GDriveOutput:" + DRIVE_DIR);
        assertThat(r.gdriveStatus()).startsWith("xlsx 成功：").contains("／json ");
    }

    @Test
    void 未啟用時run_now不上傳且不碰狀態欄() throws IOException {
        givenCurrentUser(ADMIN_ID);
        when(settingRepo.findByOwnerUserId(ADMIN_ID)).thenReturn(Optional.of(setting(ADMIN_ID, false, null)));
        when(excelExportService.realizedGainsDoc()).thenReturn(doc());

        RealizedGainExportDto.RunNowResponse r = service.runNowForCurrentUser();

        assertThat(r.gdriveStatus()).isNull();
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void 上傳失敗不影響本機也不向外擲例外() throws IOException {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        RealizedGainExportSchedule s = setting(ADMIN_ID, true, DRIVE_DIR);
        s.addTime(RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0).enabled(true).build());
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.realizedGainsDocForOwner(ADMIN_ID)).thenReturn(doc());
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("rclone exit 3"));

        service.selfHealOnStartup();   // 不得擲例外——一列炸掉不能拖垮其餘 owner

        assertThat(expectedLocalFile(ADMIN_ID)).exists();
        assertThat(s.getLastRunStatus()).startsWith("xlsx 成功：");        // 本機確實成功，不得標記為失敗
        assertThat(s.getGdriveLastStatus()).contains("失敗：");             // 兩個狀態欄必須可分辨
    }

    @Test
    void 本機產檔失敗時完全不上傳但仍寫狀態欄() throws IOException {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        RealizedGainExportSchedule s = setting(ADMIN_ID, true, DRIVE_DIR);
        s.addTime(RealizedGainExportScheduleTime.builder().id(com.steven.assets.service.ExportScheduleUnitHarness.nextId()).runHour(0).runMinute(0).enabled(true).build());
        when(settingRepo.findAll()).thenReturn(List.of(s));
        when(excelExportService.realizedGainsDocForOwner(ADMIN_ID))
                .thenThrow(new RuntimeException("產檔失敗"));

        service.selfHealOnStartup();

        assertThat(s.getLastRunStatus()).startsWith("失敗：");
        // 絕不上傳前一次的舊檔；狀態欄也不得停留在上一次的成功
        assertThat(s.getGdriveLastStatus()).startsWith("跳過：");
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void 非主要管理者啟用Drive回403() {
        givenCurrentUser(OTHER_ID);
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        when(settingRepo.findByOwnerUserId(OTHER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(true, "input", true, DRIVE_DIR,
                        List.of(new RealizedGainExportDto.TimeRequest(8, 0, true)))))
                .isInstanceOf(com.steven.assets.security.AdminRequiredException.class);
        // This in-memory fixture has no rollback evidence; the real PostgreSQL suite proves no setting remains.
    }

    @Test
    void 非主要管理者仍可設定本機路徑與多個排程時間() {
        givenCurrentUser(OTHER_ID);
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        when(settingRepo.findByOwnerUserId(OTHER_ID)).thenReturn(Optional.empty());

        RealizedGainExportDto.SettingResponse resp = service.updateForCurrentUser(
                new RealizedGainExportDto.SettingRequest(true, "Project/SRPP/data/input", null, null,
                        List.of(new RealizedGainExportDto.TimeRequest(9, 30, true),
                                new RealizedGainExportDto.TimeRequest(18, 0, true))));

        assertThat(resp.outputSubpath()).isEqualTo("Project/SRPP/data/input");
        assertThat(resp.times()).extracting(RealizedGainExportDto.TimeResponse::runHour).containsExactly(9, 18);
        assertThat(resp.gdriveEnabled()).isFalse();
    }
}
