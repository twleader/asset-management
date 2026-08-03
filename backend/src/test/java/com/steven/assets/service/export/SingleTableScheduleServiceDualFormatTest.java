package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.CommodityExportSchedule;
import com.steven.assets.model.ExchangeRateExportSchedule;
import com.steven.assets.model.IndexExportSchedule;
import com.steven.assets.model.IndexExportScheduleTime;
import com.steven.assets.model.RealizedGainExportSchedule;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.CommodityExportScheduleRepository;
import com.steven.assets.repository.ExchangeRateExportScheduleRepository;
import com.steven.assets.repository.IndexExportScheduleRepository;
import com.steven.assets.repository.RealizedGainExportScheduleRepository;
import com.steven.assets.service.CommodityExportScheduleService;
import com.steven.assets.service.ExchangeRateExportScheduleService;
import com.steven.assets.service.ExcelExportService;
import com.steven.assets.service.GdriveOutputSupport;
import com.steven.assets.service.GdriveSelfCheck;
import com.steven.assets.service.IndexExportScheduleService;
import com.steven.assets.service.RcloneClient;
import com.steven.assets.service.RealizedGainExportScheduleService;
import com.steven.assets.service.UserAdminService;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 四支「單表」排程服務的雙格式落檔驗證（Requirement 55 / Task 270）——
 * 已實現損益／油價金價／台幣兌美元匯率／大盤指數日線。
 *
 * <p><b>這四支在 main 上完全沒有測試類</b>（交易紀錄那支另有既有的
 * {@code AssetTransactionExportScheduleServiceTest}），故 270.4 的 (a) 主檔名一致性、
 * (b) 單次查詢、(h) Drive 兩份、(j) owner 隔離對它們是新建。
 *
 * <p>兩個 renderer 與 {@link DualFormatExportWriter} 一律注入<b>真實實例</b>：
 * 換成 mock 就驗不到「兩份檔真的被寫出來」。rclone 以介面替身注入、不實際連網。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SingleTableScheduleServiceDualFormatTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Mock private RealizedGainExportScheduleRepository gainRepo;
    @Mock private CommodityExportScheduleRepository commodityRepo;
    @Mock private ExchangeRateExportScheduleRepository rateRepo;
    @Mock private IndexExportScheduleRepository indexRepo;
    @Mock private ExcelExportService excelExportService;
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private RcloneClient rcloneClient;
    @Mock private AppUserRepository appUserRepo;
    @Mock private UserAdminService userAdminService;
    @Mock private GdriveSelfCheck selfCheck;

    @TempDir Path baseDir;

    private GdriveOutputSupport gdrive;
    private RealizedGainExportScheduleService gainService;
    private CommodityExportScheduleService commodityService;
    private ExchangeRateExportScheduleService rateService;
    private IndexExportScheduleService indexService;

    @BeforeEach
    void setUp() {
        gdrive = new GdriveOutputSupport(rcloneClient, appUserRepo, userAdminService, selfCheck, "GDriveOutput");
        var excel = new ExcelDocRenderer();
        var json = new JsonDocRenderer(new ObjectMapper());
        var dual = new DualFormatExportWriter(gdrive);

        gainService = new RealizedGainExportScheduleService(
                gainRepo, excelExportService, currentUserProvider, gdrive, excel, json, dual, baseDir.toString());
        commodityService = new CommodityExportScheduleService(
                commodityRepo, excelExportService, currentUserProvider, gdrive, excel, json, dual, baseDir.toString());
        rateService = new ExchangeRateExportScheduleService(
                rateRepo, excelExportService, currentUserProvider, gdrive, excel, json, dual, baseDir.toString());
        indexService = new IndexExportScheduleService(
                indexRepo, excelExportService, currentUserProvider, gdrive, excel, json, dual, baseDir.toString());

        when(gainRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(commodityRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(rateRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(indexRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        when(excelExportService.realizedGainsDocForOwner(anyLong())).thenReturn(doc("已實現損益"));
        when(excelExportService.commodityPricesDoc(any(), any())).thenReturn(doc("油價金價"));
        when(excelExportService.exchangeRatesDoc(anyString(), any(), any())).thenReturn(doc("台幣兌美元"));
        when(excelExportService.indexDailyDoc(anyString(), any(), any())).thenReturn(doc("台股大盤"));
    }

    private static ExportDoc doc(String title) {
        var table = new ExportDoc.Table(null, null, List.of("日期", "值"), true, false, false,
                List.of(ExportDoc.Format.DATE, ExportDoc.Format.NUM4),
                List.of(List.of(LocalDate.of(2026, 7, 30), new java.math.BigDecimal("1.2345"))));
        return new ExportDoc(title, List.of(new ExportDoc.Sheet(title, List.of(table), 2)));
    }

    private static String today() { return LocalDate.now(TW).format(FILE_DATE); }

    /** 同一主檔名的兩份檔都存在，且去掉副檔名後逐字元相同。 */
    private void assertBothFormats(String subpath, String baseName) throws Exception {
        Path dir = baseDir.resolve(subpath);
        Path xlsx = dir.resolve(baseName + ".xlsx");
        Path json = dir.resolve(baseName + ".json");
        assertThat(xlsx).as("xlsx 落點").exists();
        assertThat(json).as("json 落點").exists();
        String x = xlsx.getFileName().toString();
        String j = json.getFileName().toString();
        assertThat(x.substring(0, x.lastIndexOf('.')))
                .as("兩份主檔名必須逐字元相同")
                .isEqualTo(j.substring(0, j.lastIndexOf('.')));
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    // ===== (a) 主檔名一致性 ＋ (b) 單次查詢 =====

    @Test
    @DisplayName("已實現損益：兩份落檔、主檔名一致，且 doc 只取一次、不碰既有的 byte[] 方法")
    void 已實現損益() throws Exception {
        var s = RealizedGainExportSchedule.builder().ownerUserId(1L).enabled(true)
                .runHour(0).runMinute(0).outputSubpath("out").lastRunDate(LocalDate.now(TW).minusDays(1)).build();
        when(gainRepo.findAll()).thenReturn(List.of(s));

        gainService.tick();

        assertBothFormats("out", "已實現損益_1_" + today());
        verify(excelExportService, times(1)).realizedGainsDocForOwner(1L);
        // 只驗 doc 被叫一次擋不住「xlsx 走舊的 byte[] 方法、json 另呼一次 doc」這種查兩次資料的寫法
        verify(excelExportService, never()).exportRealizedGains();
        verify(excelExportService, never()).exportRealizedGainsForOwner(anyLong());
        assertThat(s.getLastRunStatus()).startsWith("xlsx 成功：").contains("／json 成功：");
    }

    @Test
    @DisplayName("油價金價：兩份落檔、主檔名一致，且 doc 只取一次")
    void 油價金價() throws Exception {
        var s = CommodityExportSchedule.builder().ownerUserId(1L).enabled(true)
                .runHour(0).runMinute(0).outputSubpath("out").lastRunDate(LocalDate.now(TW).minusDays(1)).build();
        when(commodityRepo.findAll()).thenReturn(List.of(s));

        commodityService.tick();

        assertBothFormats("out", "油價金價_1_" + today());
        verify(excelExportService, times(1)).commodityPricesDoc(any(), any());
        verify(excelExportService, never()).exportCommodityPrices(any(), any());
    }

    @Test
    @DisplayName("台幣兌美元匯率：兩份落檔、主檔名一致，且 doc 只取一次")
    void 匯率() throws Exception {
        var s = ExchangeRateExportSchedule.builder().ownerUserId(1L).enabled(true)
                .runHour(0).runMinute(0).outputSubpath("out").lastRunDate(LocalDate.now(TW).minusDays(1)).build();
        when(rateRepo.findAll()).thenReturn(List.of(s));

        rateService.tick();

        assertBothFormats("out", "台幣兌美元_1_" + today());
        verify(excelExportService, times(1)).exchangeRatesDoc(anyString(), any(), any());
        verify(excelExportService, never()).exportExchangeRates(anyString(), any(), any());
    }

    @Test
    @DisplayName("大盤指數日線：兩份落檔、主檔名一致，且 doc 只取一次")
    void 指數() throws Exception {
        var s = IndexExportSchedule.builder().ownerUserId(1L).enabled(true).outputSubpath("out").build();
        s.addTime(IndexExportScheduleTime.builder().runHour(0).runMinute(0)
                .lastRunDate(LocalDate.now(TW).minusDays(1)).markets(new java.util.LinkedHashSet<>(java.util.Set.of("TWSE"))).build());
        when(indexRepo.findAll()).thenReturn(List.of(s));

        indexService.tick();

        assertBothFormats("out", "台股大盤_1_" + today());
        verify(excelExportService, times(1)).indexDailyDoc(anyString(), any(), any());
        verify(excelExportService, never()).exportIndexDaily(anyString(), any(), any());
    }

    // ===== (h) Drive 兩份 =====

    @Test
    @DisplayName("啟用 Drive 時上傳兩份、子路徑相同、副檔名各一，狀態能分辨是哪一份")
    void drive上傳兩份() throws Exception {
        when(appUserRepo.findById(1L)).thenReturn(Optional.of(adminUser()));
        when(userAdminService.isConfiguredAdmin("admin@example.com")).thenReturn(true);

        var s = RealizedGainExportSchedule.builder().ownerUserId(1L).enabled(true)
                .runHour(0).runMinute(0).outputSubpath("out")
                .gdriveEnabled(true).gdriveSubpath("資產管理")
                .lastRunDate(LocalDate.now(TW).minusDays(1)).build();
        when(gainRepo.findAll()).thenReturn(List.of(s));

        gainService.tick();

        var files = org.mockito.ArgumentCaptor.forClass(Path.class);
        var subs = org.mockito.ArgumentCaptor.forClass(String.class);
        var names = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rcloneClient, times(2))
                .copyTo(anyString(), files.capture(), subs.capture(), names.capture());
        assertThat(subs.getAllValues()).containsExactly("資產管理", "資產管理");
        assertThat(names.getAllValues()).anyMatch(n -> n.endsWith(".xlsx")).anyMatch(n -> n.endsWith(".json"));
        assertThat(files.getAllValues().stream().map(f -> f.getFileName().toString()))
                .anyMatch(n -> n.endsWith(".xlsx")).anyMatch(n -> n.endsWith(".json"));
        assertThat(s.getGdriveLastStatus()).startsWith("xlsx ").contains("／json ");
    }

    @Test
    @DisplayName("未啟用 Drive 時零上傳，且兩個 Drive 狀態欄一律不碰")
    void 未啟用drive不上傳() throws Exception {
        var s = RealizedGainExportSchedule.builder().ownerUserId(1L).enabled(true)
                .runHour(0).runMinute(0).outputSubpath("out")
                .lastRunDate(LocalDate.now(TW).minusDays(1)).build();
        when(gainRepo.findAll()).thenReturn(List.of(s));

        gainService.tick();

        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
        assertThat(s.getGdriveLastStatus()).isNull();
        assertThat(s.getGdriveLastRunAt()).isNull();
    }

    // ===== (j) owner 隔離 =====

    @Test
    @DisplayName("已實現損益背景排程逐列走 owner-scoped 版，且各 owner 落到自己的檔名")
    void owner隔離() throws Exception {
        var a = RealizedGainExportSchedule.builder().ownerUserId(1L).enabled(true)
                .runHour(0).runMinute(0).outputSubpath("out").lastRunDate(LocalDate.now(TW).minusDays(1)).build();
        var b = RealizedGainExportSchedule.builder().ownerUserId(2L).enabled(true)
                .runHour(0).runMinute(0).outputSubpath("out").lastRunDate(LocalDate.now(TW).minusDays(1)).build();
        when(gainRepo.findAll()).thenReturn(List.of(a, b));

        gainService.tick();

        assertBothFormats("out", "已實現損益_1_" + today());
        assertBothFormats("out", "已實現損益_2_" + today());
        verify(excelExportService, times(1)).realizedGainsDocForOwner(1L);
        verify(excelExportService, times(1)).realizedGainsDocForOwner(2L);
        // 背景無 request context，走 HTTP 版會把所有人的損益寫進每個人的檔案
        verify(excelExportService, never()).exportRealizedGains();
    }

    // ===== 失敗語意 =====

    @Test
    @DisplayName("單一 owner 產檔失敗只記錄，不影響其他 owner，且仍設當日 guard")
    void 單一owner失敗不影響他人() throws Exception {
        var bad = RealizedGainExportSchedule.builder().ownerUserId(1L).enabled(true)
                .runHour(0).runMinute(0).outputSubpath("out").lastRunDate(LocalDate.now(TW).minusDays(1)).build();
        var good = RealizedGainExportSchedule.builder().ownerUserId(2L).enabled(true)
                .runHour(0).runMinute(0).outputSubpath("out").lastRunDate(LocalDate.now(TW).minusDays(1)).build();
        when(gainRepo.findAll()).thenReturn(List.of(bad, good));
        when(excelExportService.realizedGainsDocForOwner(1L)).thenThrow(new RuntimeException("查詢炸了"));

        gainService.tick();

        assertThat(bad.getLastRunStatus()).startsWith("失敗：");
        assertThat(bad.getLastRunDate()).isEqualTo(LocalDate.now(TW));
        assertBothFormats("out", "已實現損益_2_" + today());
        assertThat(good.getLastRunStatus()).startsWith("xlsx 成功：");
    }

    private static com.steven.assets.model.AppUser adminUser() {
        var u = new com.steven.assets.model.AppUser();
        u.setId(1L);
        u.setEmail("admin@example.com");
        return u;
    }
}
