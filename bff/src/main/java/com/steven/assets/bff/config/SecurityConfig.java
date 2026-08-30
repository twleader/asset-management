package com.steven.assets.bff.config;

import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BusinessUserClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/**
 * BFF reactive 安全層（Requirement 28）。
 *
 * <ul>
 *   <li>Gmail OAuth2 Login（authorization code）。登入成功 302 導回前端 {@code /}。</li>
 *   <li>未登入存取受保護資源回 {@code 401}（非 302），由前端攔截後整頁跳 {@code /oauth2/authorization/google}。</li>
 *   <li>備份/還原、使用者管理、代看 端點限 {@code ROLE_ADMIN}。</li>
 *   <li>CSRF：採 session cookie + {@code SameSite=Lax}（見 application.yml）作為防護，停用 token 型 CSRF 以簡化 SPA。</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * 代看 cookie 的 {@code Secure} 屬性開關（Requirement 30）：由 {@code SESSION_COOKIE_SECURE} 控制，預設 false。
     * 必須與 {@link com.steven.assets.bff.security.TenantWebFilter} 寫入代看 cookie 時同值，否則清除時屬性不符而清不掉。
     */
    @org.springframework.beans.factory.annotation.Value("${SESSION_COOKIE_SECURE:false}")
    private boolean cookieSecure;

    /**
     * 全域共用參考資料的「前端可觸及路徑」（Requirement 29）：這些路徑的寫入（POST/PUT/PATCH/DELETE）限 ADMIN，
     * GET 開放給已登入者（下拉選單需讀取）。backend {@code AdminGateInterceptor} 對 rewrite 後的
     * {@code /api/settings/**} 再擋一次（縱深防禦）。
     *
     * <p>刻意不含 per-user 路徑：{@code /api/bff/payment-account-settings/accounts/**}（代繳帳戶）與
     * {@code /api/bff/notification-settings/recipients/**}（通知收件人），一般使用者仍可管理自己的資料。
     */
    private static final String[] GLOBAL_SETTINGS_PATHS = {
            "/api/settings/**",
            "/api/funds",            // fund_master 主檔（passthrough，GET 開放、寫入限 ADMIN）
            "/api/funds/**",
            "/api/bff/bank-settings/**",
            "/api/bff/broker-settings/**",
            "/api/bff/deposit-type-settings/**",
            "/api/bff/market-type-settings/**",
            "/api/bff/asset-class-settings/**",
            "/api/bff/transit-fund-type-settings/**",
            "/api/bff/payment-account-settings/categories/**",
            "/api/bff/app-feature-settings/**"
    };

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            ServerOAuth2AuthorizationRequestResolver authorizationRequestResolver) {
        http
                .authorizeExchange(ex -> ex
                        .pathMatchers("/oauth2/**", "/login/**",
                                "/actuator/health", "/actuator/info").permitAll()
                        // Requirements 67/68/70/78/79/86/108/118：僅列出的 exact GET 可由 api-gateway 匿名讀取；
                        // quotes 是 Task 372 的 public market aggregation，不能放寬成 /api/quotes/**。
                        .pathMatchers(HttpMethod.GET,
                                "/api/quotes",
                                "/api/quotes/one",
                                "/api/public/market-index",
                                "/api/assets/latest",
                                "/api/public/exchange-rate/usd-twd",
                                "/api/public/market-analysis/today",
                                "/api/public/portfolio-advice/latest",
                                "/api/public/trading-radar/today",
                                "/api/public/trading-radar/stock",
                                "/api/public/transactions",
                                "/api/public/trading-calendar",
                                "/api/public/commodity-prices").permitAll()
                        // Requirement 71：公開觸發重新搜尋，第六條 Nginx 9090 路由，唯一有寫入副作用的匿名端點；
                        // 30 秒全域冷卻在 business 端（CrawlerExportPathService.publicRescan()），BFF 層不重複防護。
                        .pathMatchers(HttpMethod.POST, "/api/public/crawler-data/rescan").permitAll()
                        // 交易雷達「發布到 Blog」（Requirement 102 / Task 366）：比照既有 Google Drive
                        // 同步先例，限主要管理者（isConfiguredAdmin）。business 層另有獨立覆核，此為第一道防線。
                        .pathMatchers("/api/bff/trading-radar/blog-oauth/**")
                            .hasAuthority(AuthConstants.AUTHORITY_CONFIGURED_ADMIN)
                        .pathMatchers("/api/bff/trading-radar/blog-status")
                            .hasAuthority(AuthConstants.AUTHORITY_CONFIGURED_ADMIN)
                        .pathMatchers(HttpMethod.PUT, "/api/bff/trading-radar/blog-enabled")
                            .hasAuthority(AuthConstants.AUTHORITY_CONFIGURED_ADMIN)
                        .pathMatchers(HttpMethod.POST, "/api/bff/trading-radar/blog-publish")
                            .hasAuthority(AuthConstants.AUTHORITY_CONFIGURED_ADMIN)
                        .pathMatchers("/api/bff/backup-restore/**").hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers("/api/bff/user-management/**").hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers("/api/impersonate/**").hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        // 今日股市分析（Requirement 31）：GET 開放已登入者；重新分析 / 改模型限 ADMIN（涉 LLM 成本）
                        .pathMatchers(HttpMethod.POST, "/api/bff/today-market-analysis/generate")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers(HttpMethod.PUT, "/api/bff/today-market-analysis/settings")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        // 分析寄送時間（Task 191）：新增／刪除／切換啟用限 ADMIN（GET 併入聚合、開放已登入者）
                        .pathMatchers(HttpMethod.POST, "/api/bff/today-market-analysis/send-times")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers(HttpMethod.DELETE, "/api/bff/today-market-analysis/send-times/**")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers(HttpMethod.PATCH, "/api/bff/today-market-analysis/send-times/**")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        // 資產配置建議（Requirement 32）：成本控管設定（模型／思考深度／web 搜尋）為全域，改設定限 ADMIN；
                        // 產生建議 /generate 與儲存條件 /profile 為 per-user（owner-scoped）→ 落 anyExchange().authenticated()
                        .pathMatchers(HttpMethod.PUT, "/api/bff/portfolio-advice/settings")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        // 爬蟲資訊查詢（Requirement 38）：查詢與讀排程／輸出路徑開放已登入者；改爬蟲執行時間限 ADMIN（系統設定變更）
                        .pathMatchers(HttpMethod.PUT, "/api/bff/crawler-data/schedule")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        // 改爬蟲輸出檔案路徑限 ADMIN（Task 212）：此設定決定服務往主機檔案系統寫入的位置
                        .pathMatchers(HttpMethod.PUT, "/api/bff/crawler-data/export-path")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        // 手動匯出兩支限 ADMIN（Requirement 63 / Task 280）：都會寫主機檔案系統與使用者的
                        // Google 雲端硬碟，fetch-and- 那支另會對外部網站發請求並寫 news_headline。
                        // 不列出就會落到 anyExchange().authenticated()（一般使用者也能觸發）
                        .pathMatchers(HttpMethod.POST, "/api/bff/crawler-data/export/run-now")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers(HttpMethod.POST, "/api/bff/crawler-data/export/fetch-and-run-now")
                            .hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        // 全域共用參考資料：寫入限 ADMIN（GET 不列入 → 落到 anyExchange().authenticated()）
                        .pathMatchers(HttpMethod.POST, GLOBAL_SETTINGS_PATHS).hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers(HttpMethod.PUT, GLOBAL_SETTINGS_PATHS).hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers(HttpMethod.PATCH, GLOBAL_SETTINGS_PATHS).hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers(HttpMethod.DELETE, GLOBAL_SETTINGS_PATHS).hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .anyExchange().authenticated())
                .oauth2Login(o -> o.authenticationSuccessHandler(spaSuccessHandler())
                        .authorizationRequestResolver(authorizationRequestResolver))
                .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler(status200LogoutHandler()))
                .exceptionHandling(e -> e.authenticationEntryPoint(json401EntryPoint()))
                .csrf(ServerHttpSecurity.CsrfSpec::disable);
        return http.build();
    }

    /**
     * Google 登入固定顯示帳號選擇器（Requirement 122）：在 Spring Security 預設的
     * {@link DefaultServerOAuth2AuthorizationRequestResolver} 上疊加一個
     * {@code prompt=select_account} additional parameter，強制每次觸發
     * {@code /oauth2/authorization/google} 都導向 Google 的帳號選擇畫面，不因瀏覽器既有
     * Google session 而被跳過。只新增這一個參數，{@code redirect_uri}／{@code scope}／
     * {@code client_id} 等既有由 {@code ClientRegistration} 與 {@code X-Forwarded-*}
     * （Requirement 28 的 {@code forward-headers-strategy: framework}）動態算出的欄位
     * 完全沿用預設 resolver 的既有行為，不覆寫。
     */
    @Bean
    public ServerOAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ReactiveClientRegistrationRepository clientRegistrationRepository) {
        DefaultServerOAuth2AuthorizationRequestResolver resolver =
                new DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrationRepository);
        resolver.setAuthorizationRequestCustomizer(
                (java.util.function.Consumer<OAuth2AuthorizationRequest.Builder>) builder ->
                        builder.additionalParameters(params -> params.put("prompt", "select_account")));
        return resolver;
    }

    /**
     * OIDC user service：登入時呼叫 business {@code login-upsert} 建立/更新使用者並取回角色，
     * 把 {@code ROLE_ADMIN}/{@code ROLE_USER} 灌成 authority（供路徑授權規則使用）。
     * 即時狀態（核准/停用）由 {@link com.steven.assets.bff.security.TenantWebFilter} 每請求查 DB，不靠此處快照。
     */
    @Bean
    public ReactiveOAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(BusinessUserClient businessUserClient) {
        OidcReactiveOAuth2UserService delegate = new OidcReactiveOAuth2UserService();
        return userRequest -> delegate.loadUser(userRequest).flatMap(oidcUser ->
                businessUserClient.loginUpsert(oidcUser.getEmail(), oidcUser.getFullName(), oidcUser.getPicture())
                        .map(me -> {
                            Set<GrantedAuthority> authorities = new HashSet<>(oidcUser.getAuthorities());
                            authorities.add(new SimpleGrantedAuthority(
                                    me != null && me.isAdmin()
                                            ? AuthConstants.AUTHORITY_ADMIN
                                            : AuthConstants.AUTHORITY_USER));
                            // 把 appUserId / status 編進 authorities，後續請求直接從 principal 取得，不再每次查 business
                            if (me != null && me.id() != null) {
                                authorities.add(new SimpleGrantedAuthority(
                                        AuthConstants.AUTHORITY_UID_PREFIX + me.id()));
                            }
                            if (me != null && me.status() != null) {
                                authorities.add(new SimpleGrantedAuthority(
                                        AuthConstants.AUTHORITY_STATUS_PREFIX + me.status()));
                            }
                            // 主要管理者（ADMIN_EMAIL 本人）旗標：供前端決定是否顯示 Google Drive 同步
                            // 開關（Requirement 51 / Task 242）。與 ROLE_ADMIN 語意不同——role 可有多列
                            // ADMIN，而 Drive remote 全機只有一份、綁定特定帳號，故只有主要管理者能啟用。
                            // 真正的閘門在 business 端的 403，這裡只是不顯示。
                            if (me != null && me.configuredAdmin()) {
                                authorities.add(new SimpleGrantedAuthority(
                                        AuthConstants.AUTHORITY_CONFIGURED_ADMIN));
                            }
                            return (OidcUser) new DefaultOidcUser(authorities,
                                    oidcUser.getIdToken(), oidcUser.getUserInfo());
                        }));
    }

    /** 登入成功固定 302 → 前端 "/"（SPA 自行打 /api/me 決定去向）。 */
    private ServerAuthenticationSuccessHandler spaSuccessHandler() {
        return (webFilterExchange, authentication) -> {
            var resp = webFilterExchange.getExchange().getResponse();
            resp.setStatusCode(HttpStatus.FOUND);
            resp.getHeaders().setLocation(URI.create("/"));
            // 每次新登入都從「看自己」開始：清掉上次 session 殘留的代看 cookie，
            // 否則管理者重新登入會誤帶上次代看目標（看到別人的資料）。
            resp.getHeaders().add(HttpHeaders.SET_COOKIE, clearImpersonateCookie());
            return resp.setComplete();
        };
    }

    /** 未登入存取受保護資源 → 401（讓 SPA 的 XHR 攔截器處理，而非被 302 導去 Google 拿到 HTML）。 */
    private ServerAuthenticationEntryPoint json401EntryPoint() {
        return (exchange, ex) -> {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        };
    }

    /** 登出成功回 200（前端再 /api/me → 401 → 顯示登入）。 */
    private ServerLogoutSuccessHandler status200LogoutHandler() {
        return (exchange, authentication) -> {
            var resp = exchange.getExchange().getResponse();
            resp.setStatusCode(HttpStatus.OK);
            // 登出一併清代看 cookie，避免殘留到下次登入。
            resp.getHeaders().add(HttpHeaders.SET_COOKIE, clearImpersonateCookie());
            return resp.setComplete();
        };
    }

    /**
     * 產生「清除代看 cookie」的 Set-Cookie 值。屬性（path/httpOnly/secure/sameSite）需與
     * {@link com.steven.assets.bff.security.TenantWebFilter} 寫入代看 cookie 時一致，瀏覽器才會覆蓋／刪除。
     */
    private String clearImpersonateCookie() {
        return ResponseCookie.from(AuthConstants.COOKIE_IMPERSONATE, "")
                .path("/").httpOnly(true).secure(cookieSecure).sameSite("Lax").maxAge(0).build()
                .toString();
    }
}
