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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 公開觸發「重新搜尋」（Requirement 71 / Task 329）：{@link NewsPoller#publicRescan()} 與既有
 * {@link NewsPoller#fetchAndExportNow()} 的唯一差異只有 {@code trigger} 標籤，其餘（{@code enabled}
 * 檢查、{@link NewsPoller} 共用的互斥鎖）逐字相同——故本檔只驗兩件事：（1）鎖互斥對兩個入口都生效，
 * 且被擋下的一輪確實沒有真正呼叫抓取 client；（2）產出的 JSON／xlsx 的 {@code trigger} 欄位為
 * {@code public-rescan}。其餘（DISABLED／BUSY／雙格式／Drive 同步等）已由
 * {@link NewsPollerManualExportTest} 涵蓋，兩個入口共用同一段程式碼，不重複測。
 */
class NewsPollerPublicRescanTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");

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

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private final PublicInfoXlsxWriter realXlsxWriter = new PublicInfoXlsxWriter(mapper());

    private NewsPoller poller;

    private Path outDir;

    private static final LocalDate TODAY = LocalDate.now(TW);
    private static final String JSON_NAME = "public_info_" + TODAY + ".json";
    private static final String XLSX_NAME = "public_info_" + TODAY + ".xlsx";

    @BeforeEach
    void setUp() throws IOException {
        poller = new NewsPoller(newsClient, twseClient, snapshotClient, krClient, maCrossClient,
                source, stockFilter, calendar, mock(CrawlerScheduleQuery.class),
                exportPathQuery, uploader, mapper(), xlsxWriter);
        ReflectionTestUtils.setField(poller, "enabled", true);
        ReflectionTestUtils.setField(poller, "exportEnabled", true);
        ReflectionTestUtils.setField(poller, "retentionDays", 30);

        when(source.lastTwseTradingDate()).thenReturn(TODAY);
        when(source.loadTodayPublicInfoForExport(any(), any())).thenReturn(List.of(row("標題A")));
        when(stockFilter.retain(any())).thenAnswer(inv -> inv.getArgument(0));
        when(xlsxWriter.build(any())).thenReturn(new byte[]{1, 2, 3});
        when(exportPathQuery.gdriveConfig(anyString()))
                .thenReturn(CrawlerExportPathQuery.GdriveConfig.disabled());
    }

    private static NewsRow row(String title) {
        return new NewsRow(title, "twse", "https://example.test/" + title,
                "twse-turnover", "TW", "摘要", Instant.parse("2026-08-02T01:00:00Z"));
    }

    private void outputTo(Path dir) {
        ReflectionTestUtils.setField(poller, "exportBaseDir", dir.toString());
        when(exportPathQuery.outputSubpath(anyString())).thenReturn("out");
        outDir = dir.resolve("out");
    }

    // ===== trigger 標籤 =====

    /** publicRescan() 完整跑一輪，mode 仍為 FETCH_AND_EXPORT（既有語意），但 trigger 標為 public-rescan。 */
    @Test
    void publicRescan完整跑一輪且mode為FETCH_AND_EXPORT(@TempDir Path dir) {
        outputTo(dir);
        when(newsClient.fetchAll()).thenReturn(List.of(row("新聞")));

        NewsPoller.ManualRunResult r = poller.publicRescan();

        verify(newsClient).fetchAll();
        verify(twseClient).fetchAllTyped();
        verify(snapshotClient).fetchAll();
        verify(krClient).fetchAll();
        verify(maCrossClient).fetchAll();
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.mode()).isEqualTo("FETCH_AND_EXPORT");
        assertThat(r.upserted()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(0);
    }

    /** JSON 與 xlsx 的 metadata 區的 trigger 欄位皆為 public-rescan，且與 manual／manual-export 不同。 */
    @Test
    void publicRescan產出的JSON與xlsx的trigger欄位為publicRescan(@TempDir Path dir) throws IOException {
        outputTo(dir);
        when(xlsxWriter.build(any())).thenAnswer(inv -> realXlsxWriter.build(inv.getArgument(0)));

        poller.publicRescan();

        assertThat(triggerInJson()).isEqualTo("public-rescan");
        assertThat(triggerInXlsx()).isEqualTo("public-rescan");
    }

    private String triggerInJson() throws IOException {
        JsonNode root = new ObjectMapper().readTree(outDir.resolve(JSON_NAME).toFile());
        return root.get("trigger").asText();
    }

    private String triggerInXlsx() throws IOException {
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

    // ===== 互斥（與既有兩顆按鈕共用同一把鎖）=====

    /**
     * {@code running} 已被 {@link NewsPoller#fetchAndExportNow()} 持有時，{@link NewsPoller#publicRescan()}
     * 也不啟動第二輪；反向（{@code publicRescan} 持有時 {@code fetchAndExportNow} 被擋）同樣驗證，
     * 確認三者（既有兩顆按鈕＋本公開入口）共用的是同一個 {@code running} 欄位而非各自獨立的鎖。
     * 只斷言回傳 {@code BUSY} 是假綠燈——在「根本沒有互斥」的實作下也會偶然通過，故以真正的併發佈局：
     * 讓第一輪卡在抓取替身裡，期間再從主執行緒觸發第二輪，並斷言抓取 client 完全沒被第二輪呼叫。
     */
    @Test
    void publicRescan與fetchAndExportNow共用running鎖互相排擠(@TempDir Path dir) throws Exception {
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

        NewsPoller.ManualRunResult busyPublicRescan = poller.publicRescan();

        assertThat(busyPublicRescan.status()).isEqualTo("BUSY");
        // 被擋下的那一次沒有真正呼叫任何抓取來源——第一輪還卡在 newsClient.fetchAll() 裡，
        // 若第二輪其實跑了，這裡會多出一次呼叫（Mockito verify 的次數即會超過 1）。
        verify(newsClient, org.mockito.Mockito.times(1)).fetchAll();
        verify(source, never()).loadTodayPublicInfoForExport(any(), any());
        assertThat(outDir.resolve(JSON_NAME)).doesNotExist();

        release.countDown();
        first.join(5000);
    }

    /** 反向：publicRescan() 持有鎖時，既有「立即抓取並匯出」按鈕也被擋下，且未真正呼叫抓取 client。 */
    @Test
    void fetchAndExportNow在publicRescan持有鎖時被擋下且不呼叫抓取(@TempDir Path dir) throws Exception {
        outputTo(dir);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(newsClient.fetchAll()).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        Thread first = new Thread(() -> poller.publicRescan(), "first-round");
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        NewsPoller.ManualRunResult busyFetch = poller.fetchAndExportNow();

        assertThat(busyFetch.status()).isEqualTo("BUSY");
        verify(newsClient, org.mockito.Mockito.times(1)).fetchAll();
        verifyNoInteractions(twseClient, snapshotClient, krClient, maCrossClient);

        release.countDown();
        first.join(5000);
    }

    // ===== enabled=false =====

    @Test
    void 爬蟲整體停用時publicRescan回DISABLED且不呼叫抓取(@TempDir Path dir) {
        outputTo(dir);
        ReflectionTestUtils.setField(poller, "enabled", false);

        NewsPoller.ManualRunResult r = poller.publicRescan();

        assertThat(r.status()).isEqualTo("DISABLED");
        assertThat(r.message()).contains("news-scraper.enabled=false");
        verifyNoInteractions(newsClient);
    }
}
