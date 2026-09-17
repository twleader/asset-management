package com.steven.assets.externalmaterials.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GdriveSelfCheck} 的三層自檢（Requirement 52 / Task 247.4、247.6.9）。
 *
 * <p>本檔驗的核心是「自檢<b>絕不</b>把服務搞掛」：五種 L2 輸入、L1 讀不到來源、前置查詢爆掉、L3 探測失敗，
 * 全部只能產生警告字串或 WARN log，一律不得擲例外。
 *
 * <p>另驗兩條容易被實作漏掉的：<b>沒人啟用時零 rclone 呼叫</b>（Requirement 50／51 的「既有部署不要求
 * rclone remote 存在」直接回歸），以及<b>警告訊息不得含 token 值</b>（Task 247.5.5）。
 *
 * <p>測資的 token 一律是假字串，且 L1／L2 指向 {@code @TempDir}——本檔不碰 {@code /etc}、不連網。
 */
class GdriveSelfCheckTest {

    private static final String REMOTE = "GDriveOutput";

    /** 假 token：值本身是測試哨兵，用來驗證它<b>不會</b>出現在任何警告訊息裡。 */
    private static final String FAKE_ACCESS = "fake-access-value";
    private static final String FAKE_REFRESH = "fake-refresh-value";

    private final GdriveUploader uploader = mock(GdriveUploader.class);
    private final CrawlerExportPathQuery exportPathQuery = mock(CrawlerExportPathQuery.class);

    @TempDir
    Path dir;

    private Path source;
    private Path writable;

    /** 寫好 L1 的來源檔（內容不重要，L1 只看讀不讀得到）與 L2 的副本，回傳建好的自檢元件。 */
    private GdriveSelfCheck withConfig(String writableContent) throws IOException {
        source = dir.resolve("rclone.conf");
        Files.writeString(source, "[" + REMOTE + "]\ntype = drive\n");
        writable = dir.resolve("rclone-output.conf");
        if (writableContent != null) Files.writeString(writable, writableContent);
        return new GdriveSelfCheck(uploader, exportPathQuery, new ObjectMapper(), source, writable);
    }

    private static String config(String tokenJson) {
        return "[GoogleDriver]\ntype = drive\n\n"
                + "[" + REMOTE + "]\ntype = drive\nscope = drive\n"
                + (tokenJson == null ? "" : "token = " + tokenJson + "\n");
    }

    // ===== L2 的五種輸入（Task 247.6.1／247.6.9）=====

    @Test
    void L2_含refresh_token時靜默通過() throws IOException {
        GdriveSelfCheck check = withConfig(config(
                "{\"access_token\":\"" + FAKE_ACCESS + "\",\"token_type\":\"Bearer\","
                + "\"refresh_token\":\"" + FAKE_REFRESH + "\",\"expiry\":\"2026-07-28T14:37:15+08:00\"}"));

        assertThat(check.localWarnings(REMOTE)).isEmpty();
    }

    @Test
    void L2_缺refresh_token時warn並附prompt_consent修法() throws IOException {
        GdriveSelfCheck check = withConfig(config(
                "{\"access_token\":\"" + FAKE_ACCESS + "\",\"token_type\":\"Bearer\","
                + "\"expiry\":\"2026-07-28T14:37:15+08:00\"}"));

        List<String> warnings = check.localWarnings(REMOTE);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("refresh_token")
                // 裸的 reconnect 實測拿不到 refresh_token，訊息一定要帶 prompt=consent
                .contains("prompt=consent")
                .doesNotContain(FAKE_ACCESS);
    }

    @Test
    void L2_token非合法JSON時warn且不擲例外也不外洩內容() throws IOException {
        GdriveSelfCheck check = withConfig(config("這不是JSON-" + FAKE_ACCESS));

        List<String> warnings = check.localWarnings(REMOTE);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("無法判定")
                // Jackson 的解析錯誤訊息會夾帶來源片段（＝token 本身），不得轉述
                .doesNotContain(FAKE_ACCESS);
    }

    @Test
    void L2_config副本不存在時warn無法判定() throws IOException {
        GdriveSelfCheck check = withConfig(null);

        List<String> warnings = check.localWarnings(REMOTE);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("無法判定").contains("rclone-output.conf");
    }

    @Test
    void L2_檔內無該section時warn無法判定() throws IOException {
        GdriveSelfCheck check = withConfig("[GoogleDriver]\ntype = drive\ntoken = {\"refresh_token\":\"x\"}\n");

        List<String> warnings = check.localWarnings(REMOTE);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("無法判定").contains("[" + REMOTE + "]");
    }

    // ===== L1（Task 247.6.7）=====

    /**
     * 來源讀不到＝掛載已 dangling；只能 warn。Task 443 之後不再斷言「本容器整個生命週期都不會再同步」——
     * isAvailable()／ensureConfigCurrent() 每次呼叫都會重試，讀不到只代表這一刻讀不到。
     */
    @Test
    void L1_來源讀不到時只warn不擲例外() {
        GdriveSelfCheck check = new GdriveSelfCheck(uploader, exportPathQuery, new ObjectMapper(),
                dir.resolve("不存在.conf"), dir.resolve("也不存在.conf"));

        List<String> warnings = check.localWarnings(REMOTE);

        // L1 與 L2 各報一則：修法不同（等下一次操作自動重新載入 vs 重新授權），不能合成一句
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).contains("L1").contains("force-recreate")
                .contains("下一次上傳或自檢操作會自動重試");
    }

    @Test
    void L1_來源是空檔時也要warn() throws IOException {
        Path emptySource = dir.resolve("empty.conf");
        Files.writeString(emptySource, "");
        GdriveSelfCheck check = new GdriveSelfCheck(uploader, exportPathQuery, new ObjectMapper(),
                emptySource, dir.resolve("nope.conf"));

        assertThat(check.localWarnings(REMOTE).get(0)).contains("L1");
    }

    // ===== 前置條件與 L3（Task 247.6.2／247.6.3／247.6.8）=====

    /** Requirement 50／51「既有部署不要求 rclone remote 存在」的機械保證。 */
    @Test
    void 無人啟用時整個自檢跳過且零rclone呼叫() {
        when(exportPathQuery.anyGdriveEnabled()).thenReturn(false);
        GdriveSelfCheck check = new GdriveSelfCheck(uploader, exportPathQuery, new ObjectMapper(),
                dir.resolve("不存在.conf"), dir.resolve("也不存在.conf"));

        check.runStartupCheck();

        verifyNoInteractions(uploader);
    }

    /** 前置查詢自身爆掉（表還沒建、DB 尚未就緒）時，自檢靜默結束而非讓 daemon 執行緒噴 stack trace。 */
    @Test
    void 前置查詢擲例外時自檢靜默結束() {
        when(exportPathQuery.anyGdriveEnabled()).thenThrow(new RuntimeException("relation does not exist"));
        GdriveSelfCheck check = new GdriveSelfCheck(uploader, exportPathQuery, new ObjectMapper(),
                dir.resolve("不存在.conf"), dir.resolve("也不存在.conf"));

        assertThatCode(check::runStartupCheck).doesNotThrowAnyException();
        verifyNoInteractions(uploader);
    }

    @Test
    void 有人啟用且config就緒時會做一次唯讀探測() throws IOException {
        GdriveSelfCheck check = withConfig(config(
                "{\"access_token\":\"" + FAKE_ACCESS + "\",\"refresh_token\":\"" + FAKE_REFRESH + "\"}"));
        when(exportPathQuery.anyGdriveEnabled()).thenReturn(true);
        when(uploader.remoteName()).thenReturn(REMOTE);
        when(uploader.isAvailable()).thenReturn(true);

        check.runStartupCheck();

        verify(uploader).probe();
    }

    /** 探測失敗是本功能存在的理由（403／remote 打錯／授權撤銷），但一樣不得外擴例外。 */
    @Test
    void 探測失敗時不外擴例外() throws IOException {
        GdriveSelfCheck check = withConfig(config(
                "{\"access_token\":\"" + FAKE_ACCESS + "\",\"refresh_token\":\"" + FAKE_REFRESH + "\"}"));
        when(exportPathQuery.anyGdriveEnabled()).thenReturn(true);
        when(uploader.remoteName()).thenReturn(REMOTE);
        when(uploader.isAvailable()).thenReturn(true);
        doThrow(new RuntimeException("Error 403: Google Drive API has not been used in project"))
                .when(uploader).probe();

        assertThatCode(check::runStartupCheck).doesNotThrowAnyException();
    }

    /**
     * L3 失敗時的 WARN 訊息須含第四個成因（Task 443／Requirement 160）：client_secret 與其他共用服務
     * （如 app 登入）不同步——這是 2026-09-17 事故的真正根因，舊版三個成因（Drive API 未啟用／remote
     * 名稱設錯／授權已撤銷）都不涵蓋，會誤導排查方向。
     */
    @Test
    void L3失敗時WARN訊息含client_secret不同步這個成因() throws IOException {
        GdriveSelfCheck check = withConfig(config(
                "{\"access_token\":\"" + FAKE_ACCESS + "\",\"refresh_token\":\"" + FAKE_REFRESH + "\"}"));
        when(exportPathQuery.anyGdriveEnabled()).thenReturn(true);
        when(uploader.remoteName()).thenReturn(REMOTE);
        when(uploader.isAvailable()).thenReturn(true);
        doThrow(new RuntimeException("couldn't fetch token: invalid_client")).when(uploader).probe();

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GdriveSelfCheck.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            check.runStartupCheck();
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(msg -> assertThat(msg).contains("client_secret 與其他共用服務"));
    }

    /** config 未就緒時 L3 必然失敗，且原因 L1 已經講完；再打一次網路只是噪音。 */
    @Test
    void config未就緒時不做探測() throws IOException {
        GdriveSelfCheck check = withConfig(null);
        when(exportPathQuery.anyGdriveEnabled()).thenReturn(true);
        when(uploader.remoteName()).thenReturn(REMOTE);
        when(uploader.isAvailable()).thenReturn(false);

        check.runStartupCheck();

        verify(uploader, never()).probe();
    }
}
