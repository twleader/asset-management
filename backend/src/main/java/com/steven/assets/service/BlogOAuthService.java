package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.BlogPublishCredential;
import com.steven.assets.repository.BlogPublishCredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blogger OAuth2 換取／續期／中斷連接（Requirement 102 / Task 366）。
 *
 * <p>本任務刻意不借用 BFF 既有的 {@code oauth2Login}——那條路徑語意是「登入本 App」，scope 只有
 * {@code openid}／{@code profile}／{@code email}、不會帶 {@code access_type=offline}，且是
 * reactive Spring Security filter chain 內建行為，business 是傳統 Spring MVC 無法直接沿用。
 * 本類別直接呼叫 Google 的 {@code /o/oauth2/v2/auth} 與 {@code /token} 端點，全部用
 * {@link GoogleHttpClient}（正式環境為 {@link JdkGoogleHttpClient}，即
 * {@code java.net.http.HttpClient}）。
 *
 * <p>沿用既有登入用的 {@code GOOGLE_CLIENT_ID}／{@code GOOGLE_CLIENT_SECRET}，不新建第二組
 * OAuth Client。{@code publicBaseUrl} 是刻意需要使用者手動維護的明確環境變數
 * （{@code APP_PUBLIC_BASE_URL}），不沿用 BFF {@code oauth2Login} 的
 * {@code forward-headers-strategy: framework} 動態推導機制。
 */
@Slf4j
@Service
public class BlogOAuthService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final java.time.Duration STATE_TTL = java.time.Duration.ofMinutes(10);
    private static final String AUTH_BASE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String BLOGGER_SCOPE = "https://www.googleapis.com/auth/blogger";
    /** twleader.blogspot.com 的公開網址，供 {@code blogs/byurl} 反解 blogId；固定值，非使用者輸入。 */
    private static final String BLOG_URL = "https://twleader.blogspot.com/";
    private static final String CALLBACK_PATH = "/api/bff/trading-radar/blog-oauth/callback";

    private final BlogPublishCredentialRepository credentialRepo;
    private final GoogleHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;
    private final String publicBaseUrl;

    /** 行程內記憶體單例：{@code state} → 過期時間。多執行個體部署下各自獨立，可接受（單一主要管理者使用）。 */
    private final Map<String, Instant> pendingStates = new ConcurrentHashMap<>();

    public BlogOAuthService(BlogPublishCredentialRepository credentialRepo,
                             GoogleHttpClient httpClient,
                             ObjectMapper objectMapper,
                             @Value("${GOOGLE_CLIENT_ID:placeholder-client-id}") String clientId,
                             @Value("${GOOGLE_CLIENT_SECRET:placeholder-secret}") String clientSecret,
                             @Value("${APP_PUBLIC_BASE_URL:}") String publicBaseUrl) {
        this.credentialRepo = credentialRepo;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** OAuth callback 處理結果；{@code reason} 只在 {@code success=false} 時有值。 */
    public record CallbackResult(boolean success, String reason) {}

    /**
     * 產生 Google 同意畫面導向網址，並記錄一個新 {@code state}（10 分鐘後過期，一次性消費）。
     *
     * @throws IllegalStateException {@code APP_PUBLIC_BASE_URL} 未設定時——刻意不猜測或拼湊網址。
     */
    public String buildAuthorizeUrl() {
        purgeExpiredStates();
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "尚未設定 APP_PUBLIC_BASE_URL 環境變數，無法組出 Blogger OAuth 的 redirect_uri，請先設定後重新部署再使用本功能");
        }
        String state = UUID.randomUUID().toString();
        pendingStates.put(state, Instant.now().plus(STATE_TTL));
        return AUTH_BASE_URL
                + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri())
                + "&response_type=code"
                + "&scope=" + urlEncode(BLOGGER_SCOPE)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + urlEncode(state);
    }

    /**
     * 處理 Google 導回的 {@code code}／{@code state}。任何一步失敗皆回傳
     * {@code CallbackResult(false, reason)}，不擲例外——呼叫端（Controller）依 reason 組導回 URL。
     */
    public CallbackResult handleCallback(String code, String state) {
        Instant expiry = state == null ? null : pendingStates.get(state);
        if (expiry == null || expiry.isBefore(Instant.now())) {
            return new CallbackResult(false, "state_expired");
        }
        pendingStates.remove(state); // 消費掉，一次性

        GoogleHttpClient.Response tokenResp;
        try {
            String body = "grant_type=authorization_code"
                    + "&code=" + urlEncode(code)
                    + "&client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&redirect_uri=" + urlEncode(redirectUri());
            tokenResp = httpClient.post(TOKEN_URL, "application/x-www-form-urlencoded", body, null);
        } catch (Exception e) {
            log.warn("Blogger OAuth token 換取呼叫失敗：{}", e.toString());
            return new CallbackResult(false, "token_exchange_failed");
        }
        if (!isSuccess(tokenResp)) {
            // 不得記錄 code／client_secret／回應中的 token 明碼，只記 HTTP 狀態碼。
            log.warn("Blogger OAuth token 換取失敗，HTTP {}", tokenResp.statusCode());
            return new CallbackResult(false, "token_exchange_failed");
        }

        JsonNode tokenJson;
        try {
            tokenJson = objectMapper.readTree(tokenResp.body());
        } catch (Exception e) {
            log.warn("Blogger OAuth token 回應解析失敗");
            return new CallbackResult(false, "token_exchange_failed");
        }
        String accessToken = tokenJson.path("access_token").isMissingNode() || tokenJson.path("access_token").isNull()
                ? null : tokenJson.path("access_token").asText();
        long expiresIn = tokenJson.path("expires_in").isMissingNode() ? 3600L : tokenJson.path("expires_in").asLong(3600L);
        String refreshToken = tokenJson.path("refresh_token").isMissingNode() || tokenJson.path("refresh_token").isNull()
                ? null : tokenJson.path("refresh_token").asText();
        if (refreshToken == null || refreshToken.isBlank()) {
            return new CallbackResult(false, "missing_refresh_token");
        }
        if (accessToken == null || accessToken.isBlank()) {
            return new CallbackResult(false, "token_exchange_failed");
        }

        String blogLookupUrl = "https://www.googleapis.com/blogger/v3/blogs/byurl?url=" + urlEncode(BLOG_URL);
        GoogleHttpClient.Response blogResp;
        try {
            blogResp = httpClient.get(blogLookupUrl, accessToken);
        } catch (Exception e) {
            log.warn("Blogger blogs/byurl 查詢失敗：{}", e.toString());
            return new CallbackResult(false, "blog_lookup_failed");
        }
        if (!isSuccess(blogResp)) {
            return new CallbackResult(false, "blog_lookup_failed");
        }
        String blogId;
        try {
            JsonNode blogJson = objectMapper.readTree(blogResp.body());
            blogId = blogJson.path("id").isMissingNode() || blogJson.path("id").isNull()
                    ? null : blogJson.path("id").asText();
        } catch (Exception e) {
            blogId = null;
        }
        if (blogId == null || blogId.isBlank()) {
            return new CallbackResult(false, "blog_lookup_failed");
        }

        String displayName = null;
        try {
            GoogleHttpClient.Response userResp =
                    httpClient.get("https://www.googleapis.com/blogger/v3/users/self", accessToken);
            if (isSuccess(userResp)) {
                JsonNode userJson = objectMapper.readTree(userResp.body());
                String dn = userJson.path("displayName").asText(null);
                if (dn != null && !dn.isBlank()) displayName = dn;
            }
        } catch (Exception e) {
            // 純供 UI 顯示，缺漏容忍為 null，不得因此整體失敗。
            log.warn("Blogger users/self 查詢失敗（忽略，不影響連接結果）：{}", e.toString());
        }

        BlogPublishCredential cred = credentialRepo.findById(1L)
                .orElseGet(() -> BlogPublishCredential.builder().id(1L).build());
        cred.setBlogId(blogId);
        cred.setAccessToken(accessToken);
        cred.setAccessTokenExpiresAt(LocalDateTime.now(TAIPEI).plusSeconds(expiresIn));
        cred.setRefreshToken(refreshToken);
        cred.setAccountLabel(displayName);
        cred.setNeedsReconnect(false);
        if (cred.getConnectedAt() == null) {
            cred.setConnectedAt(LocalDateTime.now(TAIPEI));
        }
        cred.setUpdatedAt(LocalDateTime.now(TAIPEI));
        credentialRepo.save(cred);

        return new CallbackResult(true, null);
    }

    /**
     * 取得可用的 access token，必要時自動用 refresh token 續期。
     *
     * @throws IllegalStateException 尚未連接／授權已失效——呼叫端（{@link BlogPublishService}）
     *                                catch 住轉成 {@code PublishResult(false, ...)}，不得往外逸出。
     */
    public String ensureAccessToken() {
        BlogPublishCredential cred = credentialRepo.findById(1L).orElse(null);
        if (cred == null || cred.getRefreshToken() == null || cred.getRefreshToken().isBlank()) {
            throw new IllegalStateException("尚未連接 Blogger 帳號");
        }
        if (cred.isNeedsReconnect()) {
            throw new IllegalStateException("Blogger 授權已失效，請於設定頁重新連接");
        }
        LocalDateTime expiresAt = cred.getAccessTokenExpiresAt();
        if (expiresAt != null && expiresAt.isAfter(LocalDateTime.now(TAIPEI).plusSeconds(60))) {
            return cred.getAccessToken();
        }

        GoogleHttpClient.Response resp;
        try {
            String body = "grant_type=refresh_token"
                    + "&refresh_token=" + urlEncode(cred.getRefreshToken())
                    + "&client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret);
            resp = httpClient.post(TOKEN_URL, "application/x-www-form-urlencoded", body, null);
        } catch (Exception e) {
            // 網路例外：不修改 refreshToken／needsReconnect，原樣往外擲，呼叫端視為本次發布失敗，下次重試即可。
            throw new IllegalStateException("Blogger 存取權杖續期失敗：" + e.getMessage(), e);
        }

        if (!isSuccess(resp)) {
            String body = resp.body() == null ? "" : resp.body();
            if (body.contains("invalid_grant")) {
                cred.setNeedsReconnect(true);
                credentialRepo.save(cred);
                throw new IllegalStateException("Blogger 授權已失效，請於設定頁重新連接");
            }
            // 其他非 2xx：不修改 refreshToken／needsReconnect，下次重試即可。
            throw new IllegalStateException("Blogger 存取權杖續期失敗：HTTP " + resp.statusCode());
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("Blogger 存取權杖續期回應解析失敗：" + e.getMessage(), e);
        }
        String newAccessToken = json.path("access_token").asText(null);
        long expiresIn = json.path("expires_in").isMissingNode() ? 3600L : json.path("expires_in").asLong(3600L);
        cred.setAccessToken(newAccessToken);
        cred.setAccessTokenExpiresAt(LocalDateTime.now(TAIPEI).plusSeconds(expiresIn));
        // Google 的 refresh grant 回應通常不含 refresh_token；缺漏時保留原值，不得整包覆寫成 null。
        String newRefreshToken = json.path("refresh_token").asText(null);
        if (newRefreshToken != null && !newRefreshToken.isBlank()) {
            cred.setRefreshToken(newRefreshToken);
        }
        credentialRepo.save(cred);
        return newAccessToken;
    }

    /** 中斷連接：整列刪除；不存在則 no-op（不擲例外）。 */
    public void disconnect() {
        credentialRepo.deleteAll();
    }

    private String redirectUri() {
        return publicBaseUrl + CALLBACK_PATH;
    }

    private void purgeExpiredStates() {
        Instant now = Instant.now();
        pendingStates.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }

    private static boolean isSuccess(GoogleHttpClient.Response resp) {
        return resp.statusCode() >= 200 && resp.statusCode() < 300;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
