package com.steven.assets.service;

import com.steven.assets.dto.CrawlerExportPathDto;
import com.steven.assets.repository.CrawlerExportSettingRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 公開觸發「重新搜尋」（Requirement 71 / Task 329）：{@link CrawlerExportPathService#publicRescan()} 的
 * 全域 30 秒 Redis 冷卻節流，比照 {@link TradingRadarRefreshServiceTest} 的冷卻鍵測法。
 *
 * <p>ext 一律以裸 {@link HttpServer}（無 MockWebServer／WireMock，同 {@code DividendHistoryServiceTest}
 * 既有慣例）替身，可精確控制回應內容與延遲，藉此驗證「冷卻不計入等待耗時」這個關鍵行為。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrawlerExportPathServicePublicRescanTest {

    private static final String COOLDOWN_KEY = "crawler:news-poller:public-rescan:cooldown";

    @Mock private CrawlerExportSettingRepository repo;
    @Mock private GdriveOutputSupport gdrive;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private HttpServer extServer;
    private AtomicInteger extCalls;
    private String extLastPath;

    @AfterEach
    void tearDown() {
        if (extServer != null) extServer.stop(0);
    }

    private CrawlerExportPathService serviceWithExtBody(String status) throws IOException {
        return serviceWithExtBody(status, 0);
    }

    /** @param delayMs 回應前的人工延遲（模擬 ext 耗時），用於驗證冷卻 TTL 不因等待而縮短。 */
    private CrawlerExportPathService serviceWithExtBody(String status, long delayMs) throws IOException {
        extCalls = new AtomicInteger();
        extServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        extServer.createContext("/internal/news-poller/public-rescan", exchange -> {
            extCalls.incrementAndGet();
            extLastPath = exchange.getRequestURI().getPath();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = runNowJson(status).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        extServer.start();
        return new CrawlerExportPathService(repo, "/home/steven", gdrive,
                "http://127.0.0.1:" + extServer.getAddress().getPort(), 50, redis);
    }

    private static String runNowJson(String status) {
        return "{\"status\":\"" + status + "\",\"mode\":\"FETCH_AND_EXPORT\","
                + "\"jsonPath\":\"/x/public_info_2026-08-15.json\",\"jsonSizeBytes\":100,"
                + "\"xlsxPath\":\"/x/public_info_2026-08-15.xlsx\",\"xlsxSizeBytes\":200,"
                + "\"upserted\":3,\"failed\":0,\"exported\":3,"
                + "\"jsonGdrivePath\":null,\"xlsxGdrivePath\":null,\"gdriveStatus\":null,"
                + "\"message\":\"完成\"}";
    }

    /** 未被監聽的埠：連線一定失敗（ERROR），不是逾時。 */
    private static String deadUrl() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }
    }

    private void cooldownAvailable() {
        when(valueOps.setIfAbsent(eq(COOLDOWN_KEY), eq("1"), any(Duration.class))).thenReturn(true);
    }

    // ===== 冷卻中：不呼叫 ext =====

    @Test
    void 冷卻鍵已被持有時第二次呼叫不呼叫ext且回傳COOLDOWN() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(COOLDOWN_KEY), eq("1"), any(Duration.class))).thenReturn(false);
        CrawlerExportPathService service = serviceWithExtBody("OK");

        CrawlerExportPathDto.RunNowResponse r = service.publicRescan();

        assertThat(r.status()).isEqualTo("COOLDOWN");
        assertThat(extCalls).hasValue(0);
        verify(redis, never()).delete(anyString());
    }

    // ===== 冷卻可用：正確轉送並逐欄位透傳 =====

    @Test
    void 冷卻可用時正確轉送至publicRescan端點且逐欄位透傳() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        cooldownAvailable();
        CrawlerExportPathService service = serviceWithExtBody("OK");

        CrawlerExportPathDto.RunNowResponse r = service.publicRescan();

        assertThat(extCalls).hasValue(1);
        assertThat(extLastPath).isEqualTo("/internal/news-poller/public-rescan");
        assertThat(r.status()).isEqualTo("OK");
        assertThat(r.mode()).isEqualTo("FETCH_AND_EXPORT");
        assertThat(r.jsonPath()).isEqualTo("/x/public_info_2026-08-15.json");
        assertThat(r.jsonSizeBytes()).isEqualTo(100L);
        assertThat(r.xlsxPath()).isEqualTo("/x/public_info_2026-08-15.xlsx");
        assertThat(r.xlsxSizeBytes()).isEqualTo(200L);
        assertThat(r.upserted()).isEqualTo(3);
        assertThat(r.failed()).isEqualTo(0);
        assertThat(r.exported()).isEqualTo(3);
        assertThat(r.message()).isEqualTo("完成");
    }

    // ===== BUSY／DISABLED／ERROR：立即刪除冷卻鍵 =====

    @Test
    void BUSY結果立即刪除冷卻鍵() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        cooldownAvailable();
        CrawlerExportPathService service = serviceWithExtBody("BUSY");

        CrawlerExportPathDto.RunNowResponse r = service.publicRescan();

        assertThat(r.status()).isEqualTo("BUSY");
        verify(redis).delete(COOLDOWN_KEY);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void DISABLED結果立即刪除冷卻鍵() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        cooldownAvailable();
        CrawlerExportPathService service = serviceWithExtBody("DISABLED");

        CrawlerExportPathDto.RunNowResponse r = service.publicRescan();

        assertThat(r.status()).isEqualTo("DISABLED");
        verify(redis).delete(COOLDOWN_KEY);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void ERROR結果立即刪除冷卻鍵() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        cooldownAvailable();
        CrawlerExportPathService service = new CrawlerExportPathService(
                repo, "/home/steven", gdrive, deadUrl(), 5, redis);

        CrawlerExportPathDto.RunNowResponse r = service.publicRescan();

        assertThat(r.status()).isEqualTo("ERROR");
        verify(redis).delete(COOLDOWN_KEY);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // ===== OK／FAILED／RUNNING：以 SET 重新起算全新 30 秒 TTL =====

    /**
     * 讓 ext 延遲數秒才回應，驗證重新起算的 TTL 仍是完整 30 秒（{@code Duration.ofSeconds(30)}），
     * 而非「30 秒減去這幾秒等待耗時」——實作必須用 {@code SET} 從呼叫返回的當下重新起算，
     * 而不是沿用呼叫前設下、可能已在等待期間自然到期的舊 TTL。
     */
    @Test
    void OK結果以SET重新起算完整30秒TTL不因ext耗時而縮短() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        cooldownAvailable();
        CrawlerExportPathService service = serviceWithExtBody("OK", 2000);

        CrawlerExportPathDto.RunNowResponse r = service.publicRescan();

        assertThat(r.status()).isEqualTo("OK");
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(COOLDOWN_KEY), eq("1"), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(30));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void FAILED結果以SET重新起算完整30秒TTL() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        cooldownAvailable();
        CrawlerExportPathService service = serviceWithExtBody("FAILED");

        CrawlerExportPathDto.RunNowResponse r = service.publicRescan();

        assertThat(r.status()).isEqualTo("FAILED");
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(COOLDOWN_KEY), eq("1"), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(30));
    }

    /** RUNNING：等待逾時（business 端 timeout 設 1 秒、ext 延遲 3 秒才回應）合成，同樣重新起算完整 30 秒。 */
    @Test
    void RUNNING結果以SET重新起算完整30秒TTL() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        cooldownAvailable();
        extCalls = new AtomicInteger();
        extServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        extServer.createContext("/internal/news-poller/public-rescan", exchange -> {
            extCalls.incrementAndGet();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = runNowJson("OK").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        extServer.start();
        CrawlerExportPathService service = new CrawlerExportPathService(repo, "/home/steven", gdrive,
                "http://127.0.0.1:" + extServer.getAddress().getPort(), 1, redis);

        CrawlerExportPathDto.RunNowResponse r = service.publicRescan();

        assertThat(r.status()).isEqualTo("RUNNING");
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(COOLDOWN_KEY), eq("1"), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(30));
    }

    // ===== Redis 例外時 fail-open =====

    /** 分支 (1)：acquirePublicRescanCooldown() 本身的 setIfAbsent 拋例外時放行並呼叫 ext。 */
    @Test
    void 冷卻閘門讀寫失敗時放行並呼叫ext() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).setIfAbsent(anyString(), anyString(), any(Duration.class));
        CrawlerExportPathService service = serviceWithExtBody("OK");

        CrawlerExportPathDto.RunNowResponse r = service.publicRescan();

        assertThat(extCalls).hasValue(1);
        assertThat(r.status()).isEqualTo("OK");
    }

    /**
     * 分支 (2)：renewal 的 {@code redis.opsForValue().set(...)}（OK／FAILED／RUNNING 分支）拋例外時，
     * 本次呼叫仍正常回應原本的 {@code result}（不拋例外、不影響本次結果），僅 log warn。
     * 與分支 (1) 是不同的失敗點：閘門本身沒壞（still 取得鎖並呼叫了 ext），壞的是「呼叫已返回後」
     * 重新起算 TTL 這一步。
     */
    @Test
    void 冷卻鍵重新起算失敗時仍正常回應本次結果不拋例外() throws IOException {
        when(redis.opsForValue()).thenReturn(valueOps);
        cooldownAvailable();
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(eq(COOLDOWN_KEY), eq("1"), any(Duration.class));
        CrawlerExportPathService service = serviceWithExtBody("OK");

        var ref = new java.util.concurrent.atomic.AtomicReference<CrawlerExportPathDto.RunNowResponse>();
        assertThatCode(() -> ref.set(service.publicRescan())).doesNotThrowAnyException();

        assertThat(ref.get().status()).isEqualTo("OK");
        assertThat(extCalls).hasValue(1);
    }
}
