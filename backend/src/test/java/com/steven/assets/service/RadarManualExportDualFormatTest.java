package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.TradingRadarExportSetting;
import com.steven.assets.repository.TradingRadarExportSettingRepository;
import com.steven.assets.repository.TradingRadarExportTimeRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.export.DualFormatExportWriter;
import com.steven.assets.service.export.ExcelDocRenderer;
import com.steven.assets.service.export.JsonDocRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 頁首「匯出 Excel」在下載之外同時落一份 JSON ＋ Excel 到伺服器目錄（Requirement 48 追加 / Task 283）。
 *
 * <p><b>刻意不用 {@code @InjectMocks}</b>：{@link TradingRadarExportScheduleService} 是顯式 13 參數建構子，
 * 其中 {@code baseDir} 是 {@code String}（{@code @Value}），{@code @InjectMocks} 會塞 null →
 * {@code resolveDir} 的 {@code Path.of(null)} NPE → 被落檔的 try/catch 吞成 {@code "failed"}，
 * 「ok」那條會以看不出原因的方式紅燈。</p>
 *
 * <p><b>{@code exportService} 必須是真實實例</b>：R55 的「只查一次 Redis」判準靠
 * {@code verify(store, times(1)).range(...)}，而 {@code store} 是 {@code TradingRadarExportService}
 * 的欄位、{@code snapshotStore} 是本服務的欄位。若把 {@code exportService} 也 mock 掉，
 * 正確實作會得到 0 次（紅燈）、而「多查一次做 guard」的錯誤實作剛好 1 次（綠燈）——
 * 那等於獎勵違反 R55 的寫法。故兩者共用同一個 {@code store} mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RadarManualExportDualFormatTest {

    @Mock private TradingRadarExportTimeRepository timeRepo;
    @Mock private TradingRadarExportSettingRepository settingRepo;
    @Mock private TradingRadarSnapshotStore store;
    @Mock private CurrentUserContext ctx;
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private GdriveOutputSupport gdrive;
    @Mock private DualFormatExportWriter dualWriter;
    @Mock private TradingRadarService radarService;
    @Mock private PriceQueryService priceQueryService;
    @Mock private MarketDataService marketDataService;
    @Mock private BlogPublishService blogPublishService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExcelDocRenderer excel = new ExcelDocRenderer();
    private JsonDocRenderer json = new JsonDocRenderer(new ObjectMapper());

    @TempDir Path tmp;
    private TradingRadarExportScheduleService service;

    private static final long OWNER = 1L;
    private static final String FROM = "2026-07-20T00:00:00";
    private static final String TO = "2026-07-25T23:59:59";

    @BeforeEach
    void setUp() {
        when(currentUserProvider.getObject()).thenReturn(ctx);
        when(ctx.getEffectiveUserId()).thenReturn(OWNER);
        when(ctx.hasUser()).thenReturn(true);
        when(settingRepo.findByOwnerUserId(OWNER)).thenReturn(Optional.of(
                TradingRadarExportSetting.builder()
                        .ownerUserId(OWNER).outputSubpath("out").gdriveEnabled(false).build()));
        rebuild();
    }

    /** exportService 用真實實例並與本服務共用同一個 store mock，見類別 javadoc。 */
    private void rebuild() {
        TradingRadarExportService exportService =
                new TradingRadarExportService(store, ctx, excel);
        service = new TradingRadarExportScheduleService(
                timeRepo, settingRepo, exportService, store, currentUserProvider, gdrive,
                excel, json, dualWriter, tmp.toString(),
                radarService, priceQueryService, marketDataService, blogPublishService);
    }

    private void givenSnapshots(int count) throws Exception {
        java.util.List<com.fasterxml.jackson.databind.JsonNode> snaps = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            snaps.add(mapper.readTree("{\"ruleVersion\":\"TW_RULES_V9\",\"generatedAt\":\"2026-07-2"
                    + i + "T10:00:00\",\"market\":{\"regime\":\"NEUTRAL\"},\"stocks\":[],"
                    + "\"skippedNonTwStocks\":0}"));
        }
        when(store.range(anyLong(), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(snaps, count, 0));
    }

    private void givenWriterOk() throws IOException {
        when(dualWriter.write(any(), any(), anyString(), any(), any(), anyBoolean(), any()))
                .thenReturn(new DualFormatExportWriter.DualResult(
                        tmp.resolve("a.json"), tmp.resolve("a.xlsx"), "ok", null, null, null));
    }

    @Test
    @DisplayName("只查一次 Redis：下載那份與落檔兩份出自同一次 store.range（Requirement 55）")
    void 只查一次Redis() throws Exception {
        givenSnapshots(2);
        givenWriterOk();

        service.exportAndWriteManual(FROM, TO);

        // 在 exportAndWriteManual 內另呼叫 snapshotStore.range 做零快照 guard 會變成 2 次
        verify(store, times(1)).range(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("落檔失敗仍回 200 的 xlsx，標頭為 failed")
    void 落檔失敗仍回xlsx() throws Exception {
        givenSnapshots(2);
        when(dualWriter.write(any(), any(), anyString(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IOException("disk full"));

        var r = service.exportAndWriteManual(FROM, TO);

        assertThat(r.xlsx()).isNotEmpty();
        assertThat(r.dirOutcome()).isEqualTo("failed");
    }

    @Test
    @DisplayName("該區間零快照時只下載、一律不落檔（否則會用只有表頭的檔覆寫當日排程的好檔）")
    void 零快照不落檔() throws Exception {
        givenSnapshots(0);

        var r = service.exportAndWriteManual(FROM, TO);

        assertThat(r.dirOutcome()).isEqualTo("skipped");
        assertThat(r.xlsx()).isNotEmpty();
        verify(dualWriter, never()).write(any(), any(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("ownerId 為 null 時 skipped，下載仍為含表頭的合法 xlsx、writer 零呼叫")
    void 無使用者身分時skipped() throws Exception {
        when(ctx.getEffectiveUserId()).thenReturn(null);
        when(ctx.hasUser()).thenReturn(false);
        rebuild();

        var r = service.exportAndWriteManual(FROM, TO);

        assertThat(r.dirOutcome()).isEqualTo("skipped");
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(r.xlsx()))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(4);
        }
        verify(dualWriter, never()).write(any(), any(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("落檔檔名取 to 的日期，不是牆鐘今日")
    void 檔名取to的日期() throws Exception {
        givenSnapshots(2);
        givenWriterOk();

        service.exportAndWriteManual(FROM, TO);

        ArgumentCaptor<String> baseName = ArgumentCaptor.forClass(String.class);
        verify(dualWriter).write(any(), any(), baseName.capture(), any(), any(), anyBoolean(), any());
        assertThat(baseName.getValue()).isEqualTo("交易雷達_1_20260725");
    }

    @Test
    @DisplayName("不寫四個排程狀態欄——那四欄的語意是「排程最後一次的結果」")
    void 不寫排程狀態欄() throws Exception {
        givenSnapshots(2);
        givenWriterOk();

        service.exportAndWriteManual(FROM, TO);

        verify(settingRepo, never()).save(any());
    }

    @Test
    @DisplayName("json render 失敗不影響下載：仍回 xlsx，writer 收到的 jsonBytes 為 null")
    void json_render失敗不影響下載() throws Exception {
        givenSnapshots(2);
        json = new JsonDocRenderer(new ObjectMapper()) {
            @Override
            public byte[] render(com.steven.assets.service.export.ExportDoc doc) throws IOException {
                throw new IOException("boom");
            }
        };
        rebuild();
        when(dualWriter.write(any(), any(), anyString(), any(), any(), anyBoolean(), any()))
                .thenReturn(new DualFormatExportWriter.DualResult(
                        null, tmp.resolve("a.xlsx"), "json render 失敗", null, null, null));

        var r = service.exportAndWriteManual(FROM, TO);

        assertThat(r.xlsx()).isNotEmpty();
        assertThat(r.dirOutcome()).isEqualTo("failed");
        ArgumentCaptor<byte[]> jsonBytes = ArgumentCaptor.forClass(byte[].class);
        verify(dualWriter).write(any(), any(), anyString(), jsonBytes.capture(), any(), anyBoolean(), any());
        assertThat(jsonBytes.getValue()).isNull();
    }

    @Test
    @DisplayName("格式錯誤與 from>to 仍由 manualDoc 轉成 IllegalArgumentException（→ 400），不是 500")
    void 參數錯誤仍回400() {
        assertThatCode(() -> service.exportAndWriteManual("not-a-date", TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> service.exportAndWriteManual(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
