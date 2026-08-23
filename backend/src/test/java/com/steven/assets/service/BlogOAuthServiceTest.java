package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.BlogPublishCredential;
import com.steven.assets.repository.BlogPublishCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BlogOAuthService} 單元測試（Requirement 102 / Task 366）。
 *
 * <p>全部用可替換的 {@link GoogleHttpClient} 假實作驗證，不打真的 Google 網域。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlogOAuthServiceTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Mock private BlogPublishCredentialRepository credentialRepo;

    private FakeHttpClient httpClient;
    private BlogOAuthService service;

    @BeforeEach
    void setup() {
        httpClient = new FakeHttpClient();
        service = new BlogOAuthService(credentialRepo, httpClient, new ObjectMapper(),
                "test-client-id", "test-client-secret", "https://app.example.com");
    }

    private static String extractState(String url) {
        for (String part : url.substring(url.indexOf('?') + 1).split("&")) {
            if (part.startsWith("state=")) {
                return URLDecoder.decode(part.substring("state=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("找不到 state 參數：" + url);
    }

    private void givenSuccessfulTokenExchangeAndLookup() {
        httpClient.stub("https://oauth2.googleapis.com/token",
                new GoogleHttpClient.Response(200, "{\"access_token\":\"AT1\",\"expires_in\":3600,\"refresh_token\":\"RT1\"}"));
        httpClient.stub("https://www.googleapis.com/blogger/v3/blogs/byurl",
                new GoogleHttpClient.Response(200, "{\"id\":\"BLOG123\"}"));
        httpClient.stub("https://www.googleapis.com/blogger/v3/users/self",
                new GoogleHttpClient.Response(200, "{\"displayName\":\"Chihung Shi\"}"));
    }

    @SuppressWarnings("unchecked")
    private void putExpiredState(String state) throws Exception {
        Field f = BlogOAuthService.class.getDeclaredField("pendingStates");
        f.setAccessible(true);
        Map<String, Instant> map = (Map<String, Instant>) f.get(service);
        map.put(state, Instant.now().minusSeconds(10));
    }

    // ===== buildAuthorizeUrl =====

    @Test
    void buildAuthorizeUrl含必要參數() {
        String url = service.buildAuthorizeUrl();

        assertThat(url).contains("scope=" + URLEncoder.encode(
                "https://www.googleapis.com/auth/blogger", StandardCharsets.UTF_8));
        assertThat(url).contains("access_type=offline");
        assertThat(url).contains("prompt=consent");
        assertThat(url).contains("state=");
    }

    @Test
    void 產生的state可被同一個handleCallback消費一次() {
        String url = service.buildAuthorizeUrl();
        String state = extractState(url);
        givenSuccessfulTokenExchangeAndLookup();
        when(credentialRepo.findById(1L)).thenReturn(Optional.empty());

        BlogOAuthService.CallbackResult first = service.handleCallback("auth-code", state);
        assertThat(first.success()).isTrue();

        BlogOAuthService.CallbackResult second = service.handleCallback("auth-code", state);
        assertThat(second.success()).isFalse();
        assertThat(second.reason()).isEqualTo("state_expired");
    }

    // ===== handleCallback：state 驗證 =====

    @Test
    void state不存在回state_expired且不呼叫任何HTTP() {
        BlogOAuthService.CallbackResult result = service.handleCallback("code", "never-issued-state");

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo("state_expired");
        assertThat(httpClient.calls).isEmpty();
    }

    @Test
    void state已過期回state_expired且不呼叫任何HTTP() throws Exception {
        putExpiredState("expired-state");

        BlogOAuthService.CallbackResult result = service.handleCallback("code", "expired-state");

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo("state_expired");
        assertThat(httpClient.calls).isEmpty();
    }

    // ===== handleCallback：正常換取 =====

    @Test
    void 正常換取寫入BlogPublishCredential四個關鍵欄位() {
        String url = service.buildAuthorizeUrl();
        String state = extractState(url);
        givenSuccessfulTokenExchangeAndLookup();
        when(credentialRepo.findById(1L)).thenReturn(Optional.empty());
        ArgumentCaptor<BlogPublishCredential> cap = ArgumentCaptor.forClass(BlogPublishCredential.class);

        BlogOAuthService.CallbackResult result = service.handleCallback("auth-code", state);

        assertThat(result.success()).isTrue();
        verify(credentialRepo).save(cap.capture());
        BlogPublishCredential saved = cap.getValue();
        assertThat(saved.getBlogId()).isEqualTo("BLOG123");
        assertThat(saved.getAccessToken()).isEqualTo("AT1");
        assertThat(saved.getRefreshToken()).isEqualTo("RT1");
        assertThat(saved.getAccountLabel()).isEqualTo("Chihung Shi");
    }

    @Test
    void token回應缺refreshToken時回missing_refresh_token且不寫入任何credential列() {
        String url = service.buildAuthorizeUrl();
        String state = extractState(url);
        httpClient.stub("https://oauth2.googleapis.com/token",
                new GoogleHttpClient.Response(200, "{\"access_token\":\"AT1\",\"expires_in\":3600}"));

        BlogOAuthService.CallbackResult result = service.handleCallback("auth-code", state);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo("missing_refresh_token");
        verify(credentialRepo, never()).save(any());
    }

    // ===== ensureAccessToken =====

    @Test
    void accessToken未到期時不觸發任何HTTP呼叫() {
        BlogPublishCredential cred = BlogPublishCredential.builder()
                .id(1L).refreshToken("RT1").accessToken("AT-old")
                .accessTokenExpiresAt(LocalDateTime.now(TAIPEI).plusMinutes(30))
                .needsReconnect(false)
                .build();
        when(credentialRepo.findById(1L)).thenReturn(Optional.of(cred));

        String token = service.ensureAccessToken();

        assertThat(token).isEqualTo("AT-old");
        assertThat(httpClient.calls).isEmpty();
    }

    @Test
    void 到期時觸發refresh且保留原refreshToken() {
        BlogPublishCredential cred = BlogPublishCredential.builder()
                .id(1L).refreshToken("RT-original").accessToken("AT-old")
                .accessTokenExpiresAt(LocalDateTime.now(TAIPEI).minusSeconds(1))
                .needsReconnect(false)
                .build();
        when(credentialRepo.findById(1L)).thenReturn(Optional.of(cred));
        // 假 refresh 回應不含 refresh_token 欄位（Google 常態行為）
        httpClient.stub("https://oauth2.googleapis.com/token",
                new GoogleHttpClient.Response(200, "{\"access_token\":\"AT-new\",\"expires_in\":3600}"));

        String token = service.ensureAccessToken();

        assertThat(token).isEqualTo("AT-new");
        assertThat(cred.getRefreshToken()).isEqualTo("RT-original");
        verify(credentialRepo).save(cred);
    }

    @Test
    void invalidGrant回應觸發needsReconnect並擲例外() {
        BlogPublishCredential cred = BlogPublishCredential.builder()
                .id(1L).refreshToken("RT1")
                .accessTokenExpiresAt(LocalDateTime.now(TAIPEI).minusSeconds(1))
                .needsReconnect(false)
                .build();
        when(credentialRepo.findById(1L)).thenReturn(Optional.of(cred));
        httpClient.stub("https://oauth2.googleapis.com/token",
                new GoogleHttpClient.Response(400, "{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> service.ensureAccessToken()).isInstanceOf(IllegalStateException.class);

        assertThat(cred.isNeedsReconnect()).isTrue();
        verify(credentialRepo).save(cred);
    }

    @Test
    void 一般網路例外時refreshToken與needsReconnect皆維持原值() {
        BlogPublishCredential cred = BlogPublishCredential.builder()
                .id(1L).refreshToken("RT1")
                .accessTokenExpiresAt(LocalDateTime.now(TAIPEI).minusSeconds(1))
                .needsReconnect(false)
                .build();
        when(credentialRepo.findById(1L)).thenReturn(Optional.of(cred));
        httpClient.throwOnNext(new IOException("network down"));

        assertThatThrownBy(() -> service.ensureAccessToken()).isInstanceOf(RuntimeException.class);

        assertThat(cred.getRefreshToken()).isEqualTo("RT1");
        assertThat(cred.isNeedsReconnect()).isFalse();
        verify(credentialRepo, never()).save(any());
    }

    /** 記錄所有呼叫、依 URL 前綴回傳預先設定的假回應。 */
    static class FakeHttpClient implements GoogleHttpClient {
        final List<String> calls = new ArrayList<>();
        final Map<String, Response> stubs = new LinkedHashMap<>();
        private Exception pendingThrow;

        void stub(String urlPrefix, Response response) {
            stubs.put(urlPrefix, response);
        }

        void throwOnNext(Exception e) {
            this.pendingThrow = e;
        }

        private Response resolve(String url) throws IOException {
            if (pendingThrow != null) {
                Exception e = pendingThrow;
                pendingThrow = null;
                if (e instanceof IOException io) throw io;
                throw new RuntimeException(e);
            }
            for (Map.Entry<String, Response> e : stubs.entrySet()) {
                if (url.startsWith(e.getKey())) return e.getValue();
            }
            throw new IllegalStateException("沒有為此 URL 設定假回應：" + url);
        }

        @Override
        public Response post(String url, String contentType, String body, String bearerToken) throws IOException {
            calls.add("POST " + url);
            return resolve(url);
        }

        @Override
        public Response get(String url, String bearerToken) throws IOException {
            calls.add("GET " + url);
            return resolve(url);
        }

        @Override
        public Response put(String url, String contentType, String body, String bearerToken) throws IOException {
            calls.add("PUT " + url);
            return resolve(url);
        }
    }
}
