package com.steven.assets.bff.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SecurityConfig} 的自訂 {@code ServerOAuth2AuthorizationRequestResolver}
 * （Requirement 122 / Task 387）：{@code /oauth2/authorization/google} 產生的
 * Authorization Request 必須固定帶 {@code prompt=select_account}，強制 Google 每次都顯示
 * 帳號選擇器，同時既有的 {@code response_type}／{@code client_id}／{@code scope}／
 * {@code redirect_uri}（含 Requirement 28 的 {@code X-Forwarded-*} 動態 https 計算）不受影響。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.com"
})
class OAuth2LoginAccountChooserSecurityTest {

    @LocalServerPort
    private int port;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** 測試一：觸發 Google 登入一律 3xx 重導，且 Location 帶 prompt=select_account。 */
    @Test
    void 觸發Google登入時Location帶promptSelectAccount() {
        client.get().uri("/oauth2/authorization/google")
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION,
                        location -> assertThat(location).contains("prompt=select_account"));
    }

    /** 測試二：既有 Authorization Request 參數不因自訂 resolver 而被覆寫（預設無 X-Forwarded-* 時 redirect_uri 為 http）。 */
    @Test
    void 既有AuthorizationRequest參數不受影響() {
        client.get().uri("/oauth2/authorization/google")
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> {
                    assertThat(location).contains("response_type=code");
                    assertThat(location).contains("client_id=");
                    assertThat(location).contains("scope=");
                    assertThat(location).contains("redirect_uri=http://");
                });
    }

    /** 測試三：帶 X-Forwarded-Proto/Host 時，redirect_uri 正確算成 https 版本，且同樣帶 prompt=select_account。 */
    @Test
    void https場景下redirectUri正確且仍帶promptSelectAccount() {
        client.get().uri("/oauth2/authorization/google")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "localhost")
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> {
                    assertThat(location).contains("prompt=select_account");
                    assertThat(location).containsAnyOf(
                            "redirect_uri=https%3A%2F%2Flocalhost%2Flogin%2Foauth2%2Fcode%2Fgoogle",
                            "redirect_uri=https://localhost/login/oauth2/code/google");
                });
    }
}
