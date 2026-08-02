package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.NewsFetchClient;
import com.steven.assets.externalmaterials.client.NewsRow;
import com.steven.assets.externalmaterials.client.TwseInfoFetchClient;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 手動匯出兩個入口的行為（Requirement 63 / Task 280）。
 *
 * <p>本檔驗的是兩顆按鈕的<b>語意差異</b>與各種「沒跑完／沒跑成」必須被誠實回報：
 * <ul>
 *   <li>{@code exportNow()}（立即匯出）<b>絕不</b>抓取、<b>絕不</b>寫 {@code news_headline}，
 *       但兩份檔案仍照常產出——這是兩顆語意差異的唯一探針；</li>
 *   <li>{@code running} 已被持有時兩顆都<b>不啟動第二輪</b>，且<b>不產生任何檔案</b>
 *       （只斷言回傳 {@code BUSY} 在「根本沒有互斥」的實作下也會偶然通過，是假綠燈）；</li>
 *   <li>本機寫檔失敗回 {@code FAILED} 而非 {@code OK}——沒有這一條，一次什麼檔都沒產生的匯出
 *       會在前端顯示綠色成功，而驗證落點正是這兩顆按鈕最主要的用途；</li>
 *   <li>「xlsx 上傳失敗、json 上傳成功」時狀態字串<b>不以「失敗」開頭</b>但<b>包含</b>「失敗」——
 *       顯示層若以「開頭」當判準，最常見的那種部分失敗會顯示成完全成功。</li>
 * </ul>
 *
 * <p>rclone 一律以 {@link GdriveUploader} 替身注入，不實際連網；DB 一律以 {@link StockSourceQuery}
 * 替身注入，不碰真 DB。
 */
class NewsPollerManualExportTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final String SUBPATH = "投資理財/資產管理";

    private final NewsFetchClient newsClient = mock(NewsFetchClient.class);
    private final TwseInfoFetchClient twseClient = mock(TwseInfoFetchClient.class);
    private final MarketSnapshotFetchClient snapshotClient = mock(MarketSnapshotFetchClient.class);
    private final KrIntradayFetchClient krClient = mock(KrIntradayFetchClient.class);
    private final MaCrossSnapshotClient maCrossClient = mock(MaCrossSnapshotClient.class);
    private final StockSourceQuery source = mock(StockSourceQuery.class);
    private final PublicInfoStockFilter stockFilter = mock(PublicInfoStockFilter.class);
    private final MarketCalendar calendar = mock(MarketCalendar.class);
    private final CrawlerExportPathQuery exportPathQuery = mock(CrawlerExportPathQuery.class);
    private final GdriveUploader uploader = mock(GdriveUploader.class);
    private final PublicInfoXlsxWriter xlsxWriter = mock(PublicInfoXlsxWriter.class);

    /**
     * {@code findAndRegisterModules()} 不可省略：{@code NewsRow.publishedAt} 是 {@code Instant}，
     * 沒有 JSR-310 module 的裸 {@code ObjectMapper} 會在序列化時擲 {@code InvalidDefinitionException}，
     * 讓每一條測試都以「本機寫檔失敗」收場。production 的 mapper 由 Spring 提供、本來就註冊好了。
     */
    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private final PublicInfoXlsxWriter realXlsxWriter = new PublicInfoXlsxWriter(mapper());

    private NewsPoller poller;

    /** 本輪實際的輸出目錄（＝ exportBaseDir resolve 設定子路徑），由 {@link #outputTo} 設定。 */
    private Path outDir;

    private static final LocalDate TODAY = LocalDate.now(TW);
    private static final String JSON_NAME = "public_info_" + TODAY + ".json";
    private static final String XLSX_NAME = "public_info_" + TODAY + ".xlsx";

    @BeforeEach
    void setUp() throws IOException {
        poller = new NewsPoller(newsClient, twseClient, snapshotClient, krClient, maCrossClient,
                source, stockFilter, calendar, mock(CrawlerScheduleQuery.class),
                exportPathQuery, uploader, mapper(), xlsxWriter);
        // @Value 欄位在單元測試不會被 Spring 注入，預設值會是 false／null
        ReflectionTestUtils.setField(poller, "enabled", true);
        ReflectionTestUtils.setField(poller, "exportEnabled", true);
        ReflectionTestUtils.setField(poller, "retentionDays", 30);

        when(source.lastTwseTradingDate()).thenReturn(TODAY);
        when(source.loadTodayPublicInfoForExport(any(), any())).thenReturn(List.of(row("標題A"), row("標題B")));
        when(stockFilter.retain(any())).thenAnswer(inv -> inv.getArgument(0));
        // 預設 xlsx 產得出來；要測失敗的那幾條再各自覆寫
        when(xlsxWriter.build(any())).thenReturn(new byte[]{1, 2, 3});
        when(exportPathQuery.gdriveConfig(anyString()))
                .thenReturn(CrawlerExportPathQuery.GdriveConfig.disabled());
    }

    private static NewsRow row(String title) {
        return new NewsRow(title, "twse", "https://example.test/" + title,
                "twse-turnover", "TW", "摘要", Instant.parse("2026-08-02T01:00:00Z"));
    }

    /**
     * 把輸出目錄指向 tempDir 下的一個子目錄，並記錄實際落點供斷言使用。
     *
     * <p><b>子路徑不可留空</b>：`resolveExportDir` 對空值會 fallback 至常數
     * `Project/SRPP/data/input`，檔案會落在 `dir/Project/SRPP/data/input/` 而非 `dir/`。
     */
    private void outputTo(Path dir) {
        ReflectionTestUtils.setField(poller, "exportBaseDir", dir.toString());
        when(exportPathQuery.outputSubpath(anyString())).thenReturn("out");
        outDir = dir.resolve("out");
    }

    private void gdriveEnabled() {
        when(exportPathQuery.gdriveConfig(anyString()))
                .thenReturn(new CrawlerExportPathQuery.GdriveConfig(true, SUBPATH));
        when(uploader.isAvailable()).thenReturn(true);
        when(uploader.remoteName()).thenReturn("GDriveOutput");
    }

    // ===== 兩顆的語意差異 =====

    /**
     * 「立即匯出」＝只重產檔案：絕不抓取、絕不寫 news_headline，但兩份檔案仍照常產出。
     * 這是兩顆按鈕語意差異的唯一探針。
     */
    @Test
    void 立即匯出不抓取但兩份檔案仍照常產出(@TempDir Path dir) {
        outputTo(dir);

        NewsPoller.ManualRunResult r = poller.exportNow();

        verifyNoInteractions(newsClient, twseClient, snapshotClient, krClient, maCrossClient);
        verify(source, never()).upsertNews(any(), any(), any(), any(), any(), any(), any(), any());
        verify(source, never()).deleteNewsOlderThan(any());

        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.mode()).isEqualTo("EXPORT_ONLY");
        // 沒抓就是沒抓——填 0 會被讀成「抓了但一筆都沒進」
        assertThat(r.upserted()).isNull();
        assertThat(r.failed()).isNull();
        assertThat(r.exported()).isEqualTo(2);
        assertThat(outDir.resolve(JSON_NAME)).exists();
        assertThat(outDir.resolve(XLSX_NAME)).exists();
    }

    /** 「立即抓取並匯出」＝完整跑一輪：五個來源都被呼叫，且 upserted／failed 有值。 */
    @Test
    void 立即抓取並匯出會跑完整一輪(@TempDir Path dir) {
        outputTo(dir);
        when(newsClient.fetchAll()).thenReturn(List.of(row("新聞")));

        NewsPoller.ManualRunResult r = poller.fetchAndExportNow();

        verify(newsClient).fetchAll();
        verify(twseClient).fetchAll();
        verify(snapshotClient).fetchAll();
        verify(krClient).fetchAll();
        verify(maCrossClient).fetchAll();
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.mode()).isEqualTo("FETCH_AND_EXPORT");
        assertThat(r.upserted()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(0);
    }

    /** 兩顆的 trigger 標籤不同，且該值同時出現在 JSON 與 xlsx 的 metadata 區。 */
    @Test
    void 兩顆的trigger標籤分別為manual與manualExport(@TempDir Path dir) throws IOException {
        outputTo(dir);
        // 這條要驗 xlsx 內容，故改注入真的 writer
        when(xlsxWriter.build(any())).thenAnswer(inv -> realXlsxWriter.build(inv.getArgument(0)));

        poller.exportNow();
        assertThat(triggerInJson(dir)).isEqualTo("manual-export");
        assertThat(triggerInXlsx(dir)).isEqualTo("manual-export");

        poller.fetchAndExportNow();
        assertThat(triggerInJson(dir)).isEqualTo("manual");
        assertThat(triggerInXlsx(dir)).isEqualTo("manual");
    }

    private String triggerInJson(Path dir) throws IOException {
        JsonNode root = new ObjectMapper().readTree(outDir.resolve(JSON_NAME).toFile());
        return root.get("trigger").asText();
    }

    /** xlsx 的 metadata 區為「鍵在 A 欄、值在 B 欄」的鍵值列；找出 trigger 那一列的值。 */
    private String triggerInXlsx(Path dir) throws IOException {
        try (InputStream in = Files.newInputStream(outDir.resolve(XLSX_NAME));
             Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheet("公開資訊");
            for (Row r : sheet) {
                if (r.getCell(0) != null && "trigger".equals(r.getCell(0).getStringCellValue())) {
                    return r.getCell(1).getStringCellValue();
                }
            }
            return null;
        }
    }

    // ===== 互斥 =====

    /**
     * {@code running} 已被持有時兩顆都不啟動第二輪，且<b>不產生任何檔案</b>。
     *
     * <p>只斷言回傳 {@code BUSY} 是假綠燈——在「根本沒有互斥」的實作下也會偶然通過。故以真正的併發
     * 佈局：讓第一輪卡在抓取替身裡，期間再從主執行緒各按一次兩顆按鈕。
     */
    @Test
    void 上一輪未結束時兩顆都不啟動第二輪也不產生檔案(@TempDir Path dir) throws Exception {
        outputTo(dir);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(newsClient.fetchAll()).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        Thread first = new Thread(() -> poller.fetchAndExportNow(), "first-round");
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        NewsPoller.ManualRunResult busyExport = poller.exportNow();
        NewsPoller.ManualRunResult busyFetch = poller.fetchAndExportNow();

        assertThat(busyExport.status()).isEqualTo("BUSY");
        assertThat(busyFetch.status()).isEqualTo("BUSY");
        // 第一輪還卡在抓取、尚未走到產檔那一步；被擋下的兩次若真有跑，這裡就會出現檔案
        assertThat(outDir.resolve(JSON_NAME)).doesNotExist();
        assertThat(outDir.resolve(XLSX_NAME)).doesNotExist();
        // 被擋下的兩次也絕不該碰 DB
        verify(source, never()).loadTodayPublicInfoForExport(any(), any());

        release.countDown();
        first.join(5000);
    }

    // ===== 誠實回報 =====

    /**
     * 本機寫檔失敗回 {@code FAILED} 而非 {@code OK}。沒有這一條，一次什麼檔都沒產生的匯出會在前端
     * 顯示綠色成功、落點欄還是 null——而驗證落點正是這兩顆按鈕最主要的用途。
     */
    @Test
    void 本機寫檔失敗回FAILED而非OK(@TempDir Path dir) throws IOException {
        // 讓 resolveExportDir 求出的目錄名已被一個「檔案」占用 → createDirectories 擲 IOException
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        ReflectionTestUtils.setField(poller, "exportBaseDir", dir.toString());
        when(exportPathQuery.outputSubpath(anyString())).thenReturn("blocked");

        NewsPoller.ManualRunResult r = poller.exportNow();

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.jsonPath()).isNull();
        assertThat(r.xlsxPath()).isNull();
        assertThat(r.message()).contains("本機寫檔失敗");
    }

    /**
     * {@code export-enabled=false} 回 {@code DISABLED}（<b>不是</b> {@code FAILED}）——兩者的形狀相同
     * （路徑皆為 null ＋ 一段中文訊息），這條驗的是它們確實由結構化欄位分流、而非比對訊息字串。
     */
    @Test
    void 輸出被停用時回DISABLED而非FAILED(@TempDir Path dir) {
        outputTo(dir);
        ReflectionTestUtils.setField(poller, "exportEnabled", false);

        NewsPoller.ManualRunResult r = poller.exportNow();

        assertThat(r.status()).isEqualTo("DISABLED");
        assertThat(r.jsonPath()).isNull();
        assertThat(r.message()).contains("news-scraper.export-enabled=false");
        assertThat(outDir.resolve(JSON_NAME)).doesNotExist();
    }

    /**
     * {@code enabled=true} 但 {@code export-enabled=false} 時「完整跑一輪」<b>照跑</b>——
     * 抓取與 upsert 都完成、筆數有值，只有產檔那一步早退。
     */
    @Test
    void 抓取開著但輸出停用時完整跑一輪仍抓取只是不產檔(@TempDir Path dir) {
        outputTo(dir);
        ReflectionTestUtils.setField(poller, "exportEnabled", false);
        when(newsClient.fetchAll()).thenReturn(List.of(row("新聞")));

        NewsPoller.ManualRunResult r = poller.fetchAndExportNow();

        verify(newsClient).fetchAll();
        assertThat(r.status()).isEqualTo("DISABLED");
        assertThat(r.upserted()).isEqualTo(1);   // 抓了、也入庫了
        assertThat(r.jsonPath()).isNull();
        assertThat(r.message()).contains("抓取已完成 1 則");
    }

    /** 爬蟲整體停用時，「完整跑一輪」不啟動；但那不該擋住「只重產檔案」（見下一條）。 */
    @Test
    void 爬蟲整體停用時完整跑一輪回DISABLED(@TempDir Path dir) {
        outputTo(dir);
        ReflectionTestUtils.setField(poller, "enabled", false);

        NewsPoller.ManualRunResult r = poller.fetchAndExportNow();

        assertThat(r.status()).isEqualTo("DISABLED");
        assertThat(r.message()).contains("news-scraper.enabled=false");
        verifyNoInteractions(newsClient);
    }

    /**
     * 「只重產檔案」刻意不看 {@code news-scraper.enabled}——那是「要不要自動抓取」的開關，
     * 與重產檔案無關；看了會讓「爬蟲整體停用但仍想重產檔案」的情境被錯誤擋掉。
     */
    @Test
    void 爬蟲整體停用時立即匯出仍照常產檔(@TempDir Path dir) {
        outputTo(dir);
        ReflectionTestUtils.setField(poller, "enabled", false);

        NewsPoller.ManualRunResult r = poller.exportNow();

        assertThat(r.status()).isEqualTo("OK");
        assertThat(outDir.resolve(JSON_NAME)).exists();
        assertThat(outDir.resolve(XLSX_NAME)).exists();
    }

    // ===== 雙格式與順序守門 =====

    /** 兩份的主檔名去掉副檔名後必須逐字元相同。 */
    @Test
    void 兩份檔案主檔名相同(@TempDir Path dir) {
        outputTo(dir);

        NewsPoller.ManualRunResult r = poller.exportNow();

        assertThat(stripExt(r.jsonPath())).isEqualTo(stripExt(r.xlsxPath()));
    }

    private static String stripExt(String p) {
        return p.substring(0, p.lastIndexOf('.'));
    }

    /** xlsx 產檔失敗時 JSON 仍寫出、仍上傳 Drive，方法未擲例外（Requirement 55 的反向 graceful）。 */
    @Test
    void xlsx產檔失敗時JSON仍寫出且仍上傳Drive(@TempDir Path dir) throws IOException {
        outputTo(dir);
        gdriveEnabled();
        when(xlsxWriter.build(any())).thenThrow(new IOException("POI 爆了"));
        when(uploader.upload(any(), anyString(), anyString())).thenReturn("GDriveOutput:" + SUBPATH);

        AtomicReference<NewsPoller.ManualRunResult> ref = new AtomicReference<>();
        assertThatCode(() -> ref.set(poller.exportNow())).doesNotThrowAnyException();

        assertThat(ref.get().status()).isEqualTo("OK");   // 本機 JSON 那一份成功
        assertThat(ref.get().jsonPath()).isNotNull();
        assertThat(ref.get().xlsxPath()).isNull();
        assertThat(outDir.resolve(JSON_NAME)).exists();
        assertThat(outDir.resolve(XLSX_NAME)).doesNotExist();
        verify(uploader).upload(any(), eq(SUBPATH), eq(JSON_NAME));
        verify(uploader, never()).upload(any(), anyString(), eq(XLSX_NAME));
    }

    /** JSON 寫失敗時 xlsx 不寫（Requirement 55 明列的唯一具名例外：本機 JSON 是 SRPP 的權威來源）。 */
    @Test
    void JSON寫失敗時xlsx不寫(@TempDir Path dir) throws IOException {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        ReflectionTestUtils.setField(poller, "exportBaseDir", dir.toString());
        when(exportPathQuery.outputSubpath(anyString())).thenReturn("blocked");

        poller.exportNow();

        verifyNoInteractions(xlsxWriter);
    }

    // ===== Drive =====

    /** 兩份都上傳到同一個子路徑，檔名分別為兩個副檔名。 */
    @Test
    void Drive啟用時兩份都上傳(@TempDir Path dir) {
        outputTo(dir);
        gdriveEnabled();
        when(uploader.upload(any(), anyString(), anyString())).thenReturn("GDriveOutput:" + SUBPATH);

        NewsPoller.ManualRunResult r = poller.exportNow();

        verify(uploader).upload(any(), eq(SUBPATH), eq(JSON_NAME));
        verify(uploader).upload(any(), eq(SUBPATH), eq(XLSX_NAME));
        assertThat(r.gdriveStatus()).contains("xlsx 成功").contains("json 成功");
    }

    /**
     * 「xlsx 上傳失敗、json 上傳成功」時狀態字串為 {@code xlsx 失敗：…／json 成功：…}——
     * <b>不以「失敗」開頭</b>但<b>包含</b>「失敗」。
     *
     * <p>這是顯示層「判準必須是包含而非開頭」的探針：以「開頭」判斷的實作會把這種<b>最常見的
     * 部分失敗</b>顯示成完全成功，而 {@code xlsxPath} 此時又非 null（本機那份有產出、只是沒上傳成功），
     * 也不會落進「xlsx 未產出」那個分支。
     */
    @Test
    void xlsx上傳失敗時狀態字串包含失敗但不以失敗開頭(@TempDir Path dir) {
        outputTo(dir);
        gdriveEnabled();
        when(uploader.upload(any(), anyString(), eq(JSON_NAME))).thenReturn("GDriveOutput:" + SUBPATH);
        when(uploader.upload(any(), anyString(), eq(XLSX_NAME))).thenThrow(new RuntimeException("rclone 逾時"));

        NewsPoller.ManualRunResult r = poller.exportNow();

        assertThat(r.status()).isEqualTo("OK");        // 本機成功、Drive 失敗是可分辨的正常狀態
        assertThat(r.xlsxPath()).isNotNull();          // 本機 xlsx 有產出，只是沒上傳成功
        assertThat(r.gdriveStatus()).contains("失敗").doesNotStartWith("失敗");
        assertThat(r.gdriveStatus()).startsWith("xlsx ");
    }

    /**
     * Requirement 50 的三條「絕不」不因手動途徑而改變：Drive 整批失敗時本機兩份檔仍在、
     * 方法未擲例外、且仍回 {@code status=OK}（本機確實成功了，不得記成整體失敗）。
     */
    @Test
    void Drive整批失敗時本機兩份仍在且不擲例外(@TempDir Path dir) {
        outputTo(dir);
        gdriveEnabled();
        when(uploader.upload(any(), anyString(), anyString())).thenThrow(new RuntimeException("授權失效"));

        AtomicReference<NewsPoller.ManualRunResult> ref = new AtomicReference<>();
        assertThatCode(() -> ref.set(poller.exportNow())).doesNotThrowAnyException();

        assertThat(ref.get().status()).isEqualTo("OK");
        assertThat(outDir.resolve(JSON_NAME)).exists();
        assertThat(outDir.resolve(XLSX_NAME)).exists();
        assertThat(ref.get().gdriveStatus()).contains("失敗");
    }
}
