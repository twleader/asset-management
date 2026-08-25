package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.BlogPublishCredential;
import com.steven.assets.model.TradingRadarExportSetting;
import com.steven.assets.repository.BlogPublishCredentialRepository;
import com.steven.assets.repository.TradingRadarExportSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BlogPublishService} 單元測試（Requirement 102 / Task 366）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlogPublishServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode EMPTY_SNAPSHOT = MAPPER.createObjectNode();

    @Mock private BlogOAuthService blogOAuthService;
    @Mock private BlogPublishCredentialRepository credentialRepo;
    @Mock private TradingRadarExportSettingRepository settingRepo;
    @Mock private TradingRadarSnapshotStore snapshotStore;

    private StubHttpClient httpClient;
    private BlogPublishService service;

    @BeforeEach
    void setup() {
        httpClient = new StubHttpClient();
        service = new BlogPublishService(blogOAuthService, credentialRepo, settingRepo, snapshotStore,
                MAPPER, httpClient);
    }

    private void givenBlogId(String blogId) {
        when(credentialRepo.findById(1L))
                .thenReturn(Optional.of(BlogPublishCredential.builder().id(1L).blogId(blogId).build()));
    }

    private static TradingRadarExportSetting setting(long owner, String postId) {
        return TradingRadarExportSetting.builder()
                .ownerUserId(owner).outputSubpath("out").blogLastPostId(postId).build();
    }

    // ===== publish：建立 vs 更新 =====

    @Test
    void blogLastPostId為null時走建立並存下回應postId與url() {
        when(blogOAuthService.ensureAccessToken()).thenReturn("AT1");
        givenBlogId("BLOG1");
        TradingRadarExportSetting s = setting(1L, null);
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(s));
        httpClient.stub("POST", "https://www.googleapis.com/blogger/v3/blogs/BLOG1/posts",
                new GoogleHttpClient.Response(200, "{\"id\":\"POST1\",\"url\":\"https://myrader.blogspot.com/p1.html\"}"));

        BlogPublishService.PublishResult result = service.publish(1L, EMPTY_SNAPSHOT);

        assertThat(result.success()).isTrue();
        assertThat(result.postUrl()).isEqualTo("https://myrader.blogspot.com/p1.html");
        assertThat(s.getBlogLastPostId()).isEqualTo("POST1");
        assertThat(s.getBlogLastPostUrl()).isEqualTo("https://myrader.blogspot.com/p1.html");
        assertThat(s.getBlogLastRunAt()).isNotNull();
        assertThat(httpClient.calls).containsExactly("POST https://www.googleapis.com/blogger/v3/blogs/BLOG1/posts?isDraft=false");
    }

    @Test
    void 已有blogLastPostId時走更新() {
        when(blogOAuthService.ensureAccessToken()).thenReturn("AT1");
        givenBlogId("BLOG1");
        TradingRadarExportSetting s = setting(1L, "POST1");
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(s));
        httpClient.stub("PUT", "https://www.googleapis.com/blogger/v3/blogs/BLOG1/posts/POST1",
                new GoogleHttpClient.Response(200, "{\"url\":\"https://myrader.blogspot.com/p1-updated.html\"}"));

        BlogPublishService.PublishResult result = service.publish(1L, EMPTY_SNAPSHOT);

        assertThat(result.success()).isTrue();
        assertThat(s.getBlogLastPostId()).isEqualTo("POST1"); // 不變
        assertThat(s.getBlogLastPostUrl()).isEqualTo("https://myrader.blogspot.com/p1-updated.html");
        assertThat(httpClient.calls).containsExactly("PUT https://www.googleapis.com/blogger/v3/blogs/BLOG1/posts/POST1");
    }

    @Test
    void 更新回404時回退為建立並覆寫postId() {
        when(blogOAuthService.ensureAccessToken()).thenReturn("AT1");
        givenBlogId("BLOG1");
        TradingRadarExportSetting s = setting(1L, "STALE-POST");
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(s));
        httpClient.stub("PUT", "https://www.googleapis.com/blogger/v3/blogs/BLOG1/posts/STALE-POST",
                new GoogleHttpClient.Response(404, "{}"));
        httpClient.stub("POST", "https://www.googleapis.com/blogger/v3/blogs/BLOG1/posts",
                new GoogleHttpClient.Response(200, "{\"id\":\"POST2\",\"url\":\"https://myrader.blogspot.com/p2.html\"}"));

        BlogPublishService.PublishResult result = service.publish(1L, EMPTY_SNAPSHOT);

        assertThat(result.success()).isTrue();
        assertThat(s.getBlogLastPostId()).isEqualTo("POST2");
        assertThat(s.getBlogLastPostUrl()).isEqualTo("https://myrader.blogspot.com/p2.html");
    }

    // ===== publish：ensureAccessToken 失敗 =====

    @Test
    void ensureAccessToken擲例外時publish回失敗而非讓例外逸出且仍寫入狀態欄() {
        when(blogOAuthService.ensureAccessToken()).thenThrow(new IllegalStateException("尚未連接 Blogger 帳號"));
        TradingRadarExportSetting s = setting(1L, null);
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(s));

        BlogPublishService.PublishResult result = service.publish(1L, EMPTY_SNAPSHOT);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).contains("尚未連接 Blogger 帳號");
        assertThat(s.getBlogLastRunAt()).isNotNull();
        assertThat(s.getBlogLastStatus()).contains("尚未連接 Blogger 帳號");
    }

    // ===== publishLatest =====

    @Test
    void publishLatest查無快照時回失敗且不寫入狀態欄也不呼叫ensureAccessToken() {
        when(snapshotStore.range(anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));

        BlogPublishService.PublishResult result = service.publishLatest(1L);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("當日尚無交易雷達快照，請先重新整理");
        verify(settingRepo, never()).findByOwnerUserId(anyLong());
        verify(settingRepo, never()).save(org.mockito.ArgumentMatchers.any());
        verify(blogOAuthService, never()).ensureAccessToken();
    }

    @Test
    void publishLatest查得快照時委派給publish並回傳同一結果() {
        when(snapshotStore.range(anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(EMPTY_SNAPSHOT), 1, 0));
        when(blogOAuthService.ensureAccessToken()).thenReturn("AT1");
        givenBlogId("BLOG1");
        TradingRadarExportSetting s = setting(1L, null);
        when(settingRepo.findByOwnerUserId(1L)).thenReturn(Optional.of(s));
        httpClient.stub("POST", "https://www.googleapis.com/blogger/v3/blogs/BLOG1/posts",
                new GoogleHttpClient.Response(200, "{\"id\":\"POST1\",\"url\":\"https://myrader.blogspot.com/p1.html\"}"));

        BlogPublishService.PublishResult result = service.publishLatest(1L);

        assertThat(result.success()).isTrue();
        assertThat(result.postUrl()).isEqualTo("https://myrader.blogspot.com/p1.html");
    }

    /** 依 method + URL 前綴回傳假回應；未設定則回 500，避免測試誤打到未預期端點卻無感通過。 */
    static class StubHttpClient implements GoogleHttpClient {
        final List<String> calls = new ArrayList<>();
        final Map<String, Response> stubs = new LinkedHashMap<>();

        void stub(String method, String urlPrefix, Response response) {
            stubs.put(method + " " + urlPrefix, response);
        }

        private Response resolve(String method, String url) {
            for (Map.Entry<String, Response> e : stubs.entrySet()) {
                String key = e.getKey();
                int sp = key.indexOf(' ');
                String m = key.substring(0, sp);
                String prefix = key.substring(sp + 1);
                if (m.equals(method) && url.startsWith(prefix)) return e.getValue();
            }
            return new Response(500, "{\"error\":\"no stub for " + method + " " + url + "\"}");
        }

        @Override
        public Response post(String url, String contentType, String body, String bearerToken) throws IOException {
            calls.add("POST " + url);
            return resolve("POST", url);
        }

        @Override
        public Response get(String url, String bearerToken) throws IOException {
            calls.add("GET " + url);
            return resolve("GET", url);
        }

        @Override
        public Response put(String url, String contentType, String body, String bearerToken) throws IOException {
            calls.add("PUT " + url);
            return resolve("PUT", url);
        }
    }
}
