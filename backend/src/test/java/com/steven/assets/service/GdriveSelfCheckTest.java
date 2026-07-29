package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLSyntaxErrorException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Google Drive 輸出自檢 {@link GdriveSelfCheck}（Requirement 52 / Task 247）。
 *
 * <p>rclone 一律以 {@link RcloneClient} 替身注入，<b>不實際連網</b>；config 一律用 {@code @TempDir}
 * 的暫存檔，<b>測資不含任何真實 token</b>。
 *
 * <p>這一組測試守的是三條「錯了也不會有人立刻發現」的性質：
 * <ul>
 *   <li>L2 的五種輸入<b>全部不得擲例外</b>——自檢是純觀測功能，逸出的例外會變成啟動失敗或儲存 5xx；</li>
 *   <li>沒人啟用 Drive 時<b>零次</b> rclone 呼叫——Requirement 50／51「既有部署不要求 rclone remote 存在」；</li>
 *   <li>DB 前置查詢炸掉、來源 config 讀不到時只留一則 WARN，服務照常啟動。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GdriveSelfCheckTest {

    private static final String REMOTE = "GDriveOutput";

    @Mock private RcloneClient rcloneClient;
    @Mock private JdbcTemplate jdbc;

    @TempDir Path tmp;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path configSource;
    private Path configWritable;
    private GdriveSelfCheck selfCheck;

    @BeforeEach
    void setUp() throws IOException {
        configSource = tmp.resolve("rclone.conf");
        configWritable = tmp.resolve("rclone-output.conf");
        selfCheck = new GdriveSelfCheck(rcloneClient, jdbc, objectMapper, configSource, configWritable);
    }

    // ===== 247.6.1 L2：token 解析的五種輸入 =====

    @Test
    void L2_含非空refresh_token時靜默通過() throws IOException {
        writeConfig(configWritable, token("\"refresh_token\":\"dummy-not-a-real-token\","
                + "\"access_token\":\"dummy\",\"expiry\":\"2026-07-28T14:37:15+08:00\""));

        assertThat(selfCheck.checkTokenRefreshable(configWritable, REMOTE)).isNull();
    }

    @Test
    void L2_缺refresh_token時警告並附帶prompt_consent的修法() throws IOException {
        // 事故當天 [GDriveOutput] 的 token 就只有這四個鍵——access_token 一過期（約一小時）全部上傳開始失敗，
        // 而在那之前任何探測都會給假的綠燈，故這一層是唯一抓得到它的辦法。
        writeConfig(configWritable, token("\"access_token\":\"dummy\",\"token_type\":\"Bearer\","
                + "\"expiry\":\"2026-07-28T14:37:15+08:00\",\"expires_in\":3599"));

        String warning = selfCheck.checkTokenRefreshable(configWritable, REMOTE);

        assertThat(warning).contains("refresh_token").contains(REMOTE);
        // 裸的 reconnect 實測連續兩次都拿不到 refresh token，訊息少了 prompt=consent 等於沒給修法
        assertThat(warning).contains("rclone config reconnect " + REMOTE + ":")
                .contains("prompt=consent");
        // 絕不輸出 token 的值（Task 247.5.5）
        assertThat(warning).doesNotContain("dummy");
    }

    @Test
    void L2_token值非合法JSON時只警告不擲例外() throws IOException {
        writeConfig(configWritable, "token = {this-is-not-json");

        String warning = selfCheck.checkTokenRefreshable(configWritable, REMOTE);

        assertThat(warning).isNotNull().contains("無法判定");
        assertThat(warning).doesNotContain("this-is-not-json"); // 不回吐內容（可能是 access_token 本體）
    }

    @Test
    void L2_config副本不存在時只警告不擲例外() {
        Path missing = tmp.resolve("no-such-file.conf");

        assertThatCode(() -> selfCheck.checkTokenRefreshable(missing, REMOTE)).doesNotThrowAnyException();
        assertThat(selfCheck.checkTokenRefreshable(missing, REMOTE)).isNotNull().contains("無法判定");
    }

    @Test
    void L2_檔內無該section時只警告不擲例外() throws IOException {
        Files.writeString(configWritable, """
                [GoogleDriver]
                type = drive
                token = {"refresh_token":"dummy","access_token":"dummy"}
                """, StandardCharsets.UTF_8);

        String warning = selfCheck.checkTokenRefreshable(configWritable, REMOTE);

        // 別的 section 有 refresh_token 不算數——事故當天正是 [GoogleDriver] 好好的、[GDriveOutput] 壞掉
        assertThat(warning).isNotNull().contains(REMOTE).contains("無法判定");
    }

    @Test
    void L2_remote名稱由呼叫端決定不得寫死() throws IOException {
        Files.writeString(configWritable, """
                [MyOwnRemote]
                type = drive
                token = {"refresh_token":"dummy","access_token":"dummy"}
                """, StandardCharsets.UTF_8);

        assertThat(selfCheck.checkTokenRefreshable(configWritable, "MyOwnRemote")).isNull();
        assertThat(selfCheck.checkTokenRefreshable(configWritable, REMOTE)).isNotNull();
    }

    // ===== 247.6.2 前置 DB 查詢擲例外時自檢靜默結束 =====

    /**
     * 前置查詢必須涵蓋<b>全部九張</b>支援 Drive 的設定表（Task 254 加入第九張
     * {@code stock_alert_export_setting}）。
     *
     * <p>少查一張就是<b>假綠燈</b>：只在該頁啟用 Drive 的部署，遇到 rclone token 失效或 remote 被改名時，
     * 重啟會判定「全庫無人啟用」而整個跳過 L3 探測、不噴任何 WARN——正是 Requirement 52 要消除的
     * 「明天早上才發現全掛」。這條測試存在的唯一理由，就是讓「日後新增第十張表卻忘了加進 UNION」變紅。
     */
    @Test
    void 前置查詢涵蓋全部九張支援Drive的設定表() throws IOException {
        writeConfig(configSource, token("\"refresh_token\":\"dummy\""));
        writeConfig(configWritable, token("\"refresh_token\":\"dummy\""));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Boolean>>any()))
                .thenReturn(List.of(false));

        selfCheck.runStartupCheck(REMOTE);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowMapper<Boolean>>any());
        assertThat(sql.getValue()).contains(
                "export_schedule_setting",
                "trading_calendar_export_schedule",
                "index_export_schedule",
                "exchange_rate_export_schedule",
                "trading_radar_export_setting",
                "commodity_export_schedule",
                "realized_gain_export_schedule",
                "asset_transaction_export_schedule",
                "stock_alert_export_setting");
        // 九張表 = 八次 UNION ALL；數量對不上代表有人加了表卻沒接上，或接錯成別的運算子
        assertThat(sql.getValue().split("UNION ALL", -1)).hasSize(9);
    }

    @Test
    void 前置查詢擲BadSqlGrammar時不擲出且不呼叫rclone() {
        // 全新安裝的首次啟動可能還沒建這九張表；自檢若讓例外逸出，純觀測功能就變成啟動失敗
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Boolean>>any()))
                .thenThrow(new BadSqlGrammarException("query", "SELECT EXISTS (...)",
                        new SQLSyntaxErrorException("relation \"export_schedule_setting\" does not exist")));

        assertThatCode(() -> selfCheck.runStartupCheck(REMOTE)).doesNotThrowAnyException();
        verifyNoInteractions(rcloneClient);
    }

    // ===== 247.6.3 全庫無任何列啟用時整個啟動自檢跳過 =====

    @Test
    void 全庫無人啟用時零次rclone呼叫() throws IOException {
        // config 一切正常，唯一該讓自檢住手的就是前置條件——否則這條會因為錯的理由通過
        writeConfig(configSource, token("\"refresh_token\":\"dummy\""));
        writeConfig(configWritable, token("\"refresh_token\":\"dummy\""));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Boolean>>any()))
                .thenReturn(List.of(false));

        selfCheck.runStartupCheck(REMOTE);

        verifyNoInteractions(rcloneClient);
    }

    @Test
    void 有人啟用且設定正常時才會跑L3探測() throws IOException {
        writeConfig(configSource, token("\"refresh_token\":\"dummy\""));
        writeConfig(configWritable, token("\"refresh_token\":\"dummy\""));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Boolean>>any()))
                .thenReturn(List.of(true));

        selfCheck.runStartupCheck(REMOTE);

        verify(rcloneClient, times(1)).listDirs(REMOTE, "");
    }

    // ===== 247.6.7 L1 失敗時只 warn =====

    @Test
    void L1_來源讀不到時回警告而不擲例外() {
        // dangling inode 下 Files.exists() 仍回 true，故判準必須是實際讀取
        String warning = selfCheck.checkConfigSource(tmp.resolve("gone.conf"));

        assertThat(warning).isNotNull()
                .contains("本次生命週期")        // 使用者真正需要知道的後果
                .contains("--force-recreate");  // 以及唯一的修法
    }

    @Test
    void L1_失敗時啟動自檢不擲例外也不打網路() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Boolean>>any()))
                .thenReturn(List.of(true));
        // configSource 未建立＝來源讀不到

        assertThatCode(() -> selfCheck.runStartupCheck(REMOTE)).doesNotThrowAnyException();
        // L1 失敗時 rclone 的 configReady 必為 false，再探測只會得到同一個原因，故刻意不探
        verifyNoInteractions(rcloneClient);
    }

    @Test
    void L1_失敗時checkLocal回的是根因而非下游的副本不存在() {
        String warning = selfCheck.checkLocal(REMOTE);

        assertThat(warning).isNotNull().contains(configSource.toString());
    }

    // ===== L3：四種例外全部要攔下 =====

    @Test
    void L3_四種例外全部不得逸出() throws IOException {
        writeConfig(configSource, token("\"refresh_token\":\"dummy\""));
        writeConfig(configWritable, token("\"refresh_token\":\"dummy\""));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Boolean>>any()))
                .thenReturn(List.of(true));

        List<RuntimeException> all = List.of(
                new RcloneClient.RcloneUnavailableException("remote 未設定"),
                new RcloneClient.RcloneTimeoutException("逾時", 20),
                new RcloneClient.RcloneRateLimitedException("每分鐘查詢上限"),
                // 403 不含速率限制特徵字，會落到這一條——而它的 stderr 正是使用者最需要的那一行
                new RuntimeException("googleapi: Error 403: Google Drive API has not been used in project"));

        for (RuntimeException e : all) {
            // 用 doThrow 而非 when(...).thenThrow：mock 已被上一輪 stub 過，when() 內的呼叫會當場把例外丟出來
            doThrow(e).when(rcloneClient).listDirs(anyString(), anyString());

            assertThat(selfCheck.checkReachable(REMOTE)).isNotNull();
            assertThatCode(() -> selfCheck.runStartupCheck(REMOTE)).doesNotThrowAnyException();
        }
    }

    @Test
    void L3_403的原始stderr要原樣帶出來() {
        String stderr = "googleapi: Error 403: Google Drive API has not been used in project 1098468643583 "
                + "before or it is disabled.";
        doThrow(new RuntimeException(stderr)).when(rcloneClient).listDirs(anyString(), anyString());

        // 那段訊息本身就含「去這個連結啟用 Drive API」的 console 網址，換成罐頭訊息等於把修法丟掉
        assertThat(selfCheck.checkReachable(REMOTE)).contains("Google Drive API has not been used");
    }

    // ===== 測資工具（不含任何真實 token） =====

    private static void writeConfig(Path path, String body) throws IOException {
        Files.writeString(path, "[" + REMOTE + "]\ntype = drive\nscope = drive\n" + body + "\n",
                StandardCharsets.UTF_8);
    }

    private static String token(String jsonBody) {
        return "token = {" + jsonBody + "}";
    }
}
