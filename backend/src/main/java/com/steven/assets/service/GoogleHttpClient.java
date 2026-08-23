package com.steven.assets.service;

import java.io.IOException;

/**
 * Blogger OAuth／Blogger API 呼叫用的最小 HTTP 抽象（Requirement 102 / Task 366）。
 *
 * <p>本專案 {@code backend/pom.xml} 未含任何 Google API client library 或 OkHttp 依賴，且刻意
 * 不新增——{@link JdkGoogleHttpClient} 是唯一實作，內部全部用 {@code java.net.http.HttpClient}
 * 手刻請求／解析（見該類別）。抽出這一層介面<b>只是為了測試替身</b>：{@link BlogOAuthService}／
 * {@link BlogPublishService} 建構子注入本介面，單元測試提供假實作回傳固定 JSON body，
 * 不需要真的打 Google 網域也不需要啟動內嵌 HTTP server。
 */
public interface GoogleHttpClient {

    /** 單次 HTTP 呼叫的結果：{@code statusCode} 為原始 HTTP 狀態碼，{@code body} 為原始回應內容。 */
    record Response(int statusCode, String body) {}

    /**
     * @param bearerToken 為 {@code null} 時不帶 {@code Authorization} header
     *                    （OAuth token/refresh 端點靠 body 內的 client_secret 驗證，不用 Bearer）。
     */
    Response post(String url, String contentType, String body, String bearerToken)
            throws IOException, InterruptedException;

    /** @param bearerToken 為 {@code null} 時不帶 {@code Authorization} header。 */
    Response get(String url, String bearerToken) throws IOException, InterruptedException;

    /** @param bearerToken 為 {@code null} 時不帶 {@code Authorization} header。 */
    Response put(String url, String contentType, String body, String bearerToken)
            throws IOException, InterruptedException;
}
