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
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
    // Task 260：產檔前重算 ＋ 休市日判斷新增的三個建構子依賴。
    @Mock private TradingRadarService radarService;
    @Mock private PriceQueryService priceQueryService;
    @Mock private MarketDataService marketDataService;
    // Requirement 102 / Task 366：排程整合「發布到 Blog」新增的建構子依賴。
    @Mock private BlogPublishService blogPublishService;

    @TempDir Path baseDir;

    private TradingRadarExportScheduleService service;

    @BeforeEach
    void setup() {
        GdriveOutputSupport gdrive =
                new GdriveOutputSupport(rcloneClient, appUserRepo, userAdminService, selfCheck, "GDriveOutput");
        service = new TradingRadarExportScheduleService(
                timeRepo, settingRepo, exportService, snapshotStore, currentUserProvider, gdrive,
                new com.steven.assets.service.export.ExcelDocRenderer(),
                new com.steven.assets.service.export.JsonDocRenderer(new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.steven.assets.service.export.DualFormatExportWriter(gdrive),
                baseDir.toString(), radarService, priceQueryService, marketDataService, blogPublishService);
        // 預設交易日；休市日分支由專屬測試覆寫。類別已標 @MockitoSettings(LENIENT)，
        // 不需要 HTTP-only 測試裡額外呼叫 lenient()。
        when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
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
        when(exportService.radarDoc(eq(owner), anyLong(), anyLong())).thenReturn(radarDoc());
    }

    /** 最小合法 doc：本測試驗的是落檔與狀態，內容只需能被兩個 renderer 產出。 */
    private static com.steven.assets.service.export.ExportDoc radarDoc() {
        var table = new com.steven.assets.service.export.ExportDoc.Table(
                null, null, List.of("代碼"), true, false, false, null, List.of(List.of("2330")));
        return new com.steven.assets.service.export.ExportDoc("交易雷達",
                List.of(new com.steven.assets.service.export.ExportDoc.Sheet("快照索引", List.of(table), 1)));
    }

    /** 同一主檔名的 .json 那一份（Requirement 55：兩份主檔名相同、只差副檔名）。 */
    private static Path jsonOf(Path xlsx) {
        String n = xlsx.getFileName().toString();
        return xlsx.resolveSibling(n.substring(0, n.length() - ".xlsx".length()) + ".json");
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

        verify(exportService, never()).radarDoc(anyLong(), anyLong(), anyLong());
    }

    @Test
    void 停用的時間點不跑() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        t.setEnabled(false);
        when(timeRepo.findAll()).thenReturn(List.of(t));

        service.tick();

        verify(exportService, never()).radarDoc(anyLong(), anyLong(), anyLong());
        assertThat(t.getLastRunDate()).isEqualTo(yesterday());   // 未被動到
    }

    @Test
    void 尚未到執行時間不跑() throws Exception {
        // 23:59 幾乎必然晚於測試執行當下
        when(timeRepo.findAll()).thenReturn(List.of(time(1L, 23, 59, yesterday())));

        service.tick();

        verify(exportService, never()).radarDoc(anyLong(), anyLong(), anyLong());
    }

    /**
     * Task 260 回歸錨點：舊行為（當日零快照 → 完全不寫檔）已被推翻——排程現在會先觸發背景重算，
     * 重算「補上」了當日快照後仍會正常產檔。用 doAnswer 讓 mock 的 recomputeAndStoreForOwner 呼叫
     * 產生 givenSnapshots 的副作用，等價於背景真的算出了東西。
     */
    @Test
    void 當日零快照時背景重算後仍會產檔() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        doAnswer(inv -> { givenSnapshots(1L); return null; })
                .when(radarService).recomputeAndStoreForOwner(1L);

        service.tick();

        verify(radarService).recomputeAndStoreForOwner(1L);
        assertThat(expectedFile(1L, "out")).exists();
        ArgumentCaptor<TradingRadarExportSetting> cap = ArgumentCaptor.forClass(TradingRadarExportSetting.class);
        verify(settingRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getLastRunStatus()).startsWith("xlsx 成功：").contains("／json 成功：");
    }

    /** 不是只在查無快照時才補算：當日已有快照時，排程仍會重算並把新快照 append 進去。 */
    @Test
    void 當日已有快照時仍重算並append() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        givenSnapshots(1L);

        service.tick();

        verify(radarService).recomputeAndStoreForOwner(1L);
        assertThat(expectedFile(1L, "out")).exists();
    }

    @Test
    void 回補在重算之前且一輪只回補一次() throws Exception {
        TradingRadarExportTime t1 = time(1L, 0, 0, yesterday());
        TradingRadarExportTime t2 = time(2L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t1, t2));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out1")));
        when(settingRepo.findByOwnerUserId(2L)).thenReturn(Optional.of(setting(2L, "out2")));
        givenSnapshots(1L);
        givenSnapshots(2L);

        service.tick();

        verify(priceQueryService, times(1)).refreshTradingRadarPrices();
        InOrder order = inOrder(priceQueryService, radarService);
        order.verify(priceQueryService).refreshTradingRadarPrices();
        order.verify(radarService, times(2)).recomputeAndStoreForOwner(anyLong());
    }

    /** due 為空時完全不呼叫回補，否則每分鐘都在打 external。 */
    @Test
    void due為空時完全不呼叫回補() throws Exception {
        when(timeRepo.findAll()).thenReturn(List.of());

        service.tick();

        verify(priceQueryService, never()).refreshTradingRadarPrices();
        verify(radarService, never()).recomputeAndStoreForOwner(anyLong());
    }

    /** 外部服務不可用不得使當日缺檔——那是用一個新的失敗模式換掉舊的。 */
    @Test
    void 回補失敗仍產檔() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        givenSnapshots(1L);
        when(priceQueryService.refreshTradingRadarPrices())
                .thenThrow(new IllegalStateException("Timeout on blocking read"));

        service.tick();

        verify(radarService).recomputeAndStoreForOwner(1L);
        assertThat(expectedFile(1L, "out")).exists();
    }

    @Test
    void 休市日不產檔且仍設guard且不回補不重算() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(false);

        service.tick();

        assertThat(Files.exists(baseDir.resolve("out"))).isFalse();
        assertThat(t.getLastRunDate()).isEqualTo(today());
        verify(priceQueryService, never()).refreshTradingRadarPrices();
        verify(radarService, never()).recomputeAndStoreForOwner(anyLong());
        verify(exportService, never()).radarDoc(anyLong(), anyLong(), anyLong());

        ArgumentCaptor<TradingRadarExportSetting> cap = ArgumentCaptor.forClass(TradingRadarExportSetting.class);
        verify(settingRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getLastRunStatus())
                .isEqualTo(TradingRadarExportScheduleService.NON_TRADING_DAY_STATUS);
    }

    /** 重算是「盡力讓檔更新」，不是產檔的新前提：重算失敗時回退用當日既有快照產檔。 */
    @Test
    void 重算擲例外時回退用當日既有快照產檔() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        givenSnapshots(1L);
        doThrow(new RuntimeException("重算失敗")).when(radarService).recomputeAndStoreForOwner(1L);

        service.tick();

        assertThat(expectedFile(1L, "out")).exists();
        ArgumentCaptor<TradingRadarExportSetting> cap = ArgumentCaptor.forClass(TradingRadarExportSetting.class);
        verify(settingRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getLastRunStatus()).startsWith("xlsx 成功：").contains("／json 成功：");
    }

    /** 重算擲例外且當日確實零快照 → 維持既有「不寫檔、不上傳」的降級終點，不得回歸為總是產檔。 */
    @Test
    void 重算擲例外且當日零快照維持既有不寫檔行為() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        when(snapshotStore.range(eq(1L), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));
        doThrow(new RuntimeException("重算失敗")).when(radarService).recomputeAndStoreForOwner(1L);

        service.tick();

        verify(exportService, never()).radarDoc(anyLong(), anyLong(), anyLong());
        assertThat(Files.exists(baseDir.resolve("out"))).isFalse();
        verify(rcloneClient, never()).copyTo(any(), any(), any(), any());
        ArgumentCaptor<TradingRadarExportSetting> cap = ArgumentCaptor.forClass(TradingRadarExportSetting.class);
        verify(settingRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getLastRunStatus())
                .isEqualTo(TradingRadarExportScheduleService.NO_SNAPSHOT_STATUS);
    }

    @Test
    void 單一owner重算失敗不影響其他owner() throws Exception {
        TradingRadarExportTime bad = time(1L, 0, 0, yesterday());
        TradingRadarExportTime good = time(2L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(bad, good));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out1")));
        when(settingRepo.findByOwnerUserId(2L)).thenReturn(Optional.of(setting(2L, "out2")));
        when(snapshotStore.range(eq(1L), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));
        doThrow(new RuntimeException("owner1 重算失敗")).when(radarService).recomputeAndStoreForOwner(1L);
        givenSnapshots(2L);

        service.tick();

        assertThat(expectedFile(2L, "out2")).exists();                // 另一位使用者不受影響
        assertThat(Files.exists(baseDir.resolve("out1"))).isFalse();  // owner1 重算失敗＋零快照 → 不產檔
        assertThat(bad.getLastRunDate()).isEqualTo(today());          // 失敗列仍設 guard
        assertThat(good.getLastRunDate()).isEqualTo(today());
    }

    /** run-now 亦重算（260.4.5.1）：先回補行情、再重算，才走既有寫檔邏輯。 */
    @Test
    void runNow也會回補行情並重算() throws Exception {
        givenCurrentUser(1L);
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        givenSnapshots(1L);

        service.runNow();

        verify(priceQueryService).refreshTradingRadarPrices();
        verify(radarService).recomputeAndStoreForOwner(1L);
        assertThat(expectedFile(1L, "out")).exists();
    }

    /** run-now 用途就是驗證落點，休市日必須仍可用——不受交易日限制，否則週末無法驗證部署。 */
    @Test
    void runNow不受休市日限制() throws Exception {
        givenCurrentUser(1L);
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        givenSnapshots(1L);
        when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(false);

        service.runNow();

        verify(priceQueryService).refreshTradingRadarPrices();
        verify(radarService).recomputeAndStoreForOwner(1L);
        assertThat(expectedFile(1L, "out")).exists();
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
        when(exportService.radarDoc(eq(1L), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("磁碟壞了"));
        when(exportService.radarDoc(eq(2L), anyLong(), anyLong())).thenReturn(radarDoc());

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

    // ===== Blog 發布整合（Requirement 102 / Task 366）=====

    @Test
    void blogEnabled為真時排程完成既有產出後會呼叫BlogPublishService() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        TradingRadarExportSetting s = setting(1L, "out");
        s.setBlogEnabled(true);
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(s));
        givenSnapshots(1L);

        service.tick();

        verify(blogPublishService).publish(eq(1L), any());
    }

    @Test
    void blogEnabled為假時完全不呼叫BlogPublishService() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(setting(1L, "out")));
        givenSnapshots(1L);

        service.tick();

        verify(blogPublishService, never()).publish(anyLong(), any());
    }

    /**
     * blog 發布失敗不得回滾既有本機／Drive 狀態寫入——三個輸出通道各自成敗，
     * {@code publishToBlogQuietly} 的例外處理不得影響 {@code runScheduled} 既有的 finally 收尾。
     */
    @Test
    void blog發布擲例外不影響既有本機與狀態寫入且仍設guard() throws Exception {
        TradingRadarExportTime t = time(1L, 0, 0, yesterday());
        when(timeRepo.findAll()).thenReturn(List.of(t));
        TradingRadarExportSetting s = setting(1L, "out");
        s.setBlogEnabled(true);
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(s));
        givenSnapshots(1L);
        when(blogPublishService.publish(eq(1L), any())).thenThrow(new RuntimeException("blog 發布炸了"));

        service.tick();

        assertThat(expectedFile(1L, "out")).exists();
        assertThat(s.getLastRunStatus()).startsWith("xlsx 成功：").contains("／json 成功：");
        assertThat(s.getBlogLastStatus()).contains("失敗").contains("blog 發布炸了");
        assertThat(s.getBlogLastRunAt()).isNotNull();
        assertThat(t.getLastRunDate()).isEqualTo(today());
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
