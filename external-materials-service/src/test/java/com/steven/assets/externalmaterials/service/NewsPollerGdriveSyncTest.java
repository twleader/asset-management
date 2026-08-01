package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.NewsFetchClient;
import com.steven.assets.externalmaterials.client.TwseInfoFetchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link NewsPoller#syncToGdrive} 的 best-effort 保證（Requirement 50 / Task 241）。
 *
 * <p>本檔驗的是三條「絕不」：Drive 同步失敗<b>絕不</b>動到本機檔案（那是 SRPP 退休規劃專案的資料來源）、
 * <b>絕不</b>向外擲例外中斷排程、連「回報結果」本身失敗也<b>絕不</b>反過來造成影響。
 *
 * <p>另驗一條容易被忽略的行為：<b>已啟用但沒實際上傳時也要寫狀態欄</b>，否則設定頁會停在上一次的
 * 「成功」，顯示過期的好消息——而這兩個欄位存在的唯一理由就是「上傳目的地不在使用者眼前，不回報就是靜默失敗」。
 */
class NewsPollerGdriveSyncTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 27);
    private static final String SUBPATH = "投資理財/資產管理";

    private final CrawlerExportPathQuery exportPathQuery = mock(CrawlerExportPathQuery.class);
    private final GdriveUploader uploader = mock(GdriveUploader.class);

    private final NewsPoller poller = new NewsPoller(
            mock(NewsFetchClient.class),
            mock(TwseInfoFetchClient.class),
            mock(MarketSnapshotFetchClient.class),
            mock(KrIntradayFetchClient.class),
            mock(MaCrossSnapshotClient.class),
            mock(StockSourceQuery.class),
            mock(PublicInfoStockFilter.class),
            mock(MarketCalendar.class),
            mock(CrawlerScheduleQuery.class),
            exportPathQuery,
            uploader,
            new ObjectMapper(),
            // Task 272：多一個 xlsx writer；本測試只驗 Drive 同步，注入真實實例即可
            new PublicInfoXlsxWriter(new ObjectMapper()));

    private Path writtenFile(Path dir) throws IOException {
        Path f = dir.resolve("public_info_" + TODAY + ".json");
        Files.writeString(f, "{\"count\":0}");
        return f;
    }

    private void enabled(String subpath) {
        when(exportPathQuery.gdriveConfig(anyString()))
                .thenReturn(new CrawlerExportPathQuery.GdriveConfig(true, subpath));
        when(uploader.isAvailable()).thenReturn(true);
        when(uploader.remoteName()).thenReturn("GDriveOutput");
    }

    // ===== 未啟用 =====

    @Test
    void 未啟用時完全不呼叫rclone也不動狀態欄(@TempDir Path dir) throws IOException {
        when(exportPathQuery.gdriveConfig(anyString()))
                .thenReturn(CrawlerExportPathQuery.GdriveConfig.disabled());

        poller.syncToGdrive(writtenFile(dir), null, TODAY, "test");

        verifyNoInteractions(uploader);
        verify(exportPathQuery, never()).recordGdriveResult(anyString(), anyString());
    }

    /** 讀不到設定就無從得知使用者是否啟用；此時不該寫狀態欄（避免在「其實沒啟用」時留誤導訊息）。 */
    @Test
    void 讀取設定失敗時跳過且不寫狀態欄(@TempDir Path dir) throws IOException {
        when(exportPathQuery.gdriveConfig(anyString())).thenThrow(new RuntimeException("DB down"));

        Path file = writtenFile(dir);
        assertThatCode(() -> poller.syncToGdrive(file, null, TODAY, "test")).doesNotThrowAnyException();

        verifyNoInteractions(uploader);
        verify(exportPathQuery, never()).recordGdriveResult(anyString(), anyString());
        assertThat(file).exists();   // 本機檔不受影響
    }

    // ===== 成功 =====

    @Test
    void 成功時以固定檔名上傳並記錄落點與大小(@TempDir Path dir) throws IOException {
        enabled(SUBPATH);
        Path file = writtenFile(dir);
        when(uploader.upload(eq(file), eq(SUBPATH), eq("public_info_" + TODAY + ".json")))
                .thenReturn("GDriveOutput:" + SUBPATH + "/public_info_" + TODAY + ".json");

        poller.syncToGdrive(file, null, TODAY, "test");

        verify(exportPathQuery).recordGdriveResult(anyString(), contains("成功："));
        assertThat(file).exists();
    }

    // ===== 失敗與跳過：本機一律不受影響，且都要寫狀態欄 =====

    @Test
    void 上傳擲例外時不外擴例外_本機檔仍存在_且記錄失敗(@TempDir Path dir) throws IOException {
        enabled(SUBPATH);
        Path file = writtenFile(dir);
        when(uploader.upload(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("rclone 上傳逾時（45 秒）"));

        assertThatCode(() -> poller.syncToGdrive(file, null, TODAY, "test")).doesNotThrowAnyException();

        verify(exportPathQuery).recordGdriveResult(anyString(), contains("失敗："));
        assertThat(file).exists();
    }

    /** 狀態欄寫入自身失敗（DB 短暫不可用）也必須被吞掉——回報結果不能成為新的失敗來源。 */
    @Test
    void 狀態欄寫入自身失敗也被吞掉(@TempDir Path dir) throws IOException {
        enabled(SUBPATH);
        Path file = writtenFile(dir);
        when(uploader.upload(any(), anyString(), anyString())).thenReturn("GDriveOutput:x/y.json");
        doThrow(new RuntimeException("DB down"))
                .when(exportPathQuery).recordGdriveResult(anyString(), anyString());

        assertThatCode(() -> poller.syncToGdrive(file, null, TODAY, "test")).doesNotThrowAnyException();

        assertThat(file).exists();
    }

    @Test
    void 本機寫檔失敗時跳過上傳但仍寫狀態欄() {
        enabled(SUBPATH);

        poller.syncToGdrive(null, null, TODAY, "test");

        verify(uploader, never()).upload(any(), anyString(), anyString());
        verify(exportPathQuery).recordGdriveResult(anyString(), contains("跳過"));
    }

    @Test
    void rclone設定不可用時跳過上傳但仍寫狀態欄(@TempDir Path dir) throws IOException {
        when(exportPathQuery.gdriveConfig(anyString()))
                .thenReturn(new CrawlerExportPathQuery.GdriveConfig(true, SUBPATH));
        when(uploader.isAvailable()).thenReturn(false);
        when(uploader.remoteName()).thenReturn("GDriveOutput");

        poller.syncToGdrive(writtenFile(dir), null, TODAY, "test");

        verify(uploader, never()).upload(any(), anyString(), anyString());
        verify(exportPathQuery).recordGdriveResult(anyString(), contains("跳過"));
    }

    /**
     * 縱深防禦：DB 值可能被 psql 直改或跨環境還原繞過 API 驗證。此時<b>跳過</b>而非 fallback 到某個
     * 預設 Drive 目錄——把檔案倒進使用者雲端硬碟的非預期位置，比不上傳更糟。
     */
    @Test
    void 子路徑不合法時跳過上傳_不fallback到預設目錄(@TempDir Path dir) throws IOException {
        Path file = writtenFile(dir);
        for (String bad : new String[]{"/絕對路徑", "投資理財/../etc", "other-remote:x", "", null}) {
            org.mockito.Mockito.reset(exportPathQuery, uploader);
            when(exportPathQuery.gdriveConfig(anyString()))
                    .thenReturn(new CrawlerExportPathQuery.GdriveConfig(true, bad));
            when(uploader.isAvailable()).thenReturn(true);
            when(uploader.remoteName()).thenReturn("GDriveOutput");

            poller.syncToGdrive(file, null, TODAY, "test");

            verify(uploader, never()).upload(any(), anyString(), anyString());
            verify(exportPathQuery).recordGdriveResult(anyString(), contains("跳過"));
        }
    }

    /** {@code a..b} 是合法目錄名，不得被誤擋（驗證是逐段比對而非 contains("..")）。 */
    @Test
    void 目錄名內含雙點但非路徑段_仍會上傳(@TempDir Path dir) throws IOException {
        enabled("投資理財/a..b");
        Path file = writtenFile(dir);
        when(uploader.upload(any(), anyString(), anyString())).thenReturn("GDriveOutput:投資理財/a..b/x.json");

        poller.syncToGdrive(file, null, TODAY, "test");

        verify(uploader).upload(eq(file), eq("投資理財/a..b"), eq("public_info_" + TODAY + ".json"));
    }

    // ===== 雙格式（Requirement 55 / Task 272）=====

    @Test
    void 兩份都存在時上傳兩份且狀態能分辨哪一份(@TempDir Path dir) throws IOException {
        when(exportPathQuery.gdriveConfig(anyString()))
                .thenReturn(new CrawlerExportPathQuery.GdriveConfig(true, SUBPATH));
        when(uploader.isAvailable()).thenReturn(true);
        when(uploader.upload(any(), anyString(), anyString())).thenReturn("GDriveOutput:x");

        Path json = writtenFile(dir);
        Path xlsx = dir.resolve("public_info_" + TODAY + ".xlsx");
        java.nio.file.Files.writeString(xlsx, "xlsx");

        poller.syncToGdrive(json, xlsx, TODAY, "test");

        // 兩份都上傳，且檔名分別為兩個副檔名
        org.mockito.ArgumentCaptor<String> names = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(uploader, org.mockito.Mockito.times(2))
                .upload(any(), org.mockito.ArgumentMatchers.eq(SUBPATH), names.capture());
        assertThat(names.getAllValues()).anyMatch(n -> n.endsWith(".json")).anyMatch(n -> n.endsWith(".xlsx"));
        verify(exportPathQuery).recordGdriveResult(anyString(),
                org.mockito.ArgumentMatchers.matches("(?s)xlsx 成功：.*／json 成功：.*"));
    }

    @Test
    void xlsx沒產出時JSON那一份仍照常上傳(@TempDir Path dir) throws IOException {
        // JSON 是 SRPP 的契約，不能因為 Excel 壞掉就不同步
        when(exportPathQuery.gdriveConfig(anyString()))
                .thenReturn(new CrawlerExportPathQuery.GdriveConfig(true, SUBPATH));
        when(uploader.isAvailable()).thenReturn(true);
        when(uploader.upload(any(), anyString(), anyString())).thenReturn("GDriveOutput:x");

        poller.syncToGdrive(writtenFile(dir), null, TODAY, "test");

        verify(uploader, org.mockito.Mockito.times(1)).upload(any(), anyString(), anyString());
        verify(exportPathQuery).recordGdriveResult(anyString(), contains("json 成功："));
        verify(exportPathQuery).recordGdriveResult(anyString(), contains("xlsx 跳過："));
    }
}
