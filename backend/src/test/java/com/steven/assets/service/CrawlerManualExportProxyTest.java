package com.steven.assets.service;

import com.steven.assets.controller.CrawlerExportPathController;
import com.steven.assets.dto.CrawlerExportPathDto;
import com.steven.assets.repository.CrawlerExportSettingRepository;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手動匯出 proxy 的權限、參數驗證與逾時語意（Requirement 63 / Task 280）。
 *
 * <p>三件事在這裡守門：
 * <ul>
 *   <li><b>非 ADMIN 回 403，且完全沒有呼叫 ext</b>——權限檢查必須在 proxy 之前；</li>
 *   <li><b>{@code crawler} 非 news-poller 回 400，且完全沒有呼叫 ext</b>——這兩支的 proxy 目標是
 *       news-poller 專屬端點、{@code crawlerKey} 不會被帶下去，不驗就等於
 *       {@code ?crawler=whatever} 也會觸發爬蟲；</li>
 *   <li><b>逾時回 {@code RUNNING} 而非 {@code ERROR}</b>——這是「逾時分支必須寫在 reactive chain 內」的
 *       唯一探針：{@code Mono.timeout} 送出的是 checked {@code TimeoutException}，{@code block()} 會把它
 *       包成 {@code RuntimeException}，寫成 {@code catch (Exception)} 的實作會把逾時誤判為失敗。</li>
 * </ul>
 *
 * <p><b>逾時以裸 {@code ServerSocket}（只 bind、不 accept）重現，刻意不引入 HTTP stub</b>：
 * 全樹沒有 MockWebServer／WireMock，且正式建構子沿用 backend 既有 6 處的靜態 {@code WebClient.builder()}
 * （不注入 {@code WebClient.Builder}），沒有替身接縫。TCP 三次握手由核心完成、請求送得出去卻永遠等不到回應，
 * 正是「連得上但不回應」的真實情境。
 */
class CrawlerManualExportProxyTest {

    private final CrawlerExportSettingRepository repo = mock(CrawlerExportSettingRepository.class);
    private final GdriveOutputSupport gdrive = mock(GdriveOutputSupport.class);

    private ServerSocket silentServer;

    @AfterEach
    void tearDown() throws IOException {
        if (silentServer != null) silentServer.close();
    }

    private CrawlerExportPathService serviceAt(String baseUrl, long timeoutSeconds) {
        return new CrawlerExportPathService(repo, "/home/steven", gdrive, baseUrl, timeoutSeconds);
    }

    /** 未被監聽的埠：連線一定失敗（不是逾時）。 */
    private static String deadUrl() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }   // close 之後那個埠就沒人聽了
    }

    // ===== 權限（controller 層縱深防禦）=====

    @Test
    void 非ADMIN呼叫立即匯出擲AdminRequired且完全不呼叫service() {
        CrawlerExportPathService service = mock(CrawlerExportPathService.class);
        CurrentUserContext user = mock(CurrentUserContext.class);
        when(user.isAdmin()).thenReturn(false);
        CrawlerExportPathController controller = new CrawlerExportPathController(service, user);

        assertThatThrownBy(() -> controller.runNow("news-poller"))
                .isInstanceOf(AdminRequiredException.class);

        verify(service, never()).runNow(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 非ADMIN呼叫立即抓取並匯出擲AdminRequired且完全不呼叫service() {
        CrawlerExportPathService service = mock(CrawlerExportPathService.class);
        CurrentUserContext user = mock(CurrentUserContext.class);
        when(user.isAdmin()).thenReturn(false);
        CrawlerExportPathController controller = new CrawlerExportPathController(service, user);

        assertThatThrownBy(() -> controller.fetchAndRunNow("news-poller"))
                .isInstanceOf(AdminRequiredException.class);

        verify(service, never()).fetchAndRunNow(org.mockito.ArgumentMatchers.anyString());
    }

    // ===== 參數驗證 =====

    /**
     * crawler 非 news-poller 擲 {@link IllegalArgumentException}（→ 400）。
     *
     * <p>ext base-url 指向一個沒人監聽的埠：若驗證漏掉而真的送出請求，會落進 catch 回
     * {@code status=ERROR} 而不是擲出——故「擲出」本身即證明沒有呼叫 ext。
     */
    @Test
    void crawler非newsPoller一律擲IllegalArgument且不呼叫ext() throws IOException {
        CrawlerExportPathService service = serviceAt(deadUrl(), 1);

        assertThatThrownBy(() -> service.runNow("whatever"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("news-poller");
        assertThatThrownBy(() -> service.fetchAndRunNow("whatever"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("news-poller");
    }

    // ===== 逾時 vs. 連線失敗 =====

    /** 連得上但不回應 → RUNNING（<b>不是失敗</b>：ext 那一輪會跑完、檔案照寫）。 */
    @Test
    void 等待逾時回RUNNING而非ERROR() throws IOException {
        silentServer = new ServerSocket(0);   // 只 bind、不 accept：握手完成但永遠沒有回應
        CrawlerExportPathService service = serviceAt("http://127.0.0.1:" + silentServer.getLocalPort(), 1);

        CrawlerExportPathDto.RunNowResponse r = service.runNow("news-poller");

        assertThat(r.status()).isEqualTo("RUNNING");
        assertThat(r.message()).contains("背景");
    }

    /** 連線根本不通 → ERROR（＝根本沒跑到 ext，與 FAILED「跑到了但檔案沒寫成」是不同的事）。 */
    @Test
    void 連線失敗回ERROR() throws IOException {
        CrawlerExportPathService service = serviceAt(deadUrl(), 5);

        CrawlerExportPathDto.RunNowResponse r = service.fetchAndRunNow("news-poller");

        assertThat(r.status()).isEqualTo("ERROR");
        assertThat(r.message()).contains("呼叫爬蟲服務失敗");
    }
}
