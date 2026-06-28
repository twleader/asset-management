package com.steven.assets.bff.config;

import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BusinessUserClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
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

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .authorizeExchange(ex -> ex
                        .pathMatchers("/oauth2/**", "/login/**",
                                "/actuator/health", "/actuator/info").permitAll()
                        .pathMatchers("/api/bff/backup-restore/**").hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers("/api/bff/user-management/**").hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .pathMatchers("/api/impersonate/**").hasAuthority(AuthConstants.AUTHORITY_ADMIN)
                        .anyExchange().authenticated())
                .oauth2Login(o -> o.authenticationSuccessHandler(spaSuccessHandler()))
                .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler(status200LogoutHandler()))
                .exceptionHandling(e -> e.authenticationEntryPoint(json401EntryPoint()))
                .csrf(ServerHttpSecurity.CsrfSpec::disable);
        return http.build();
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
     * 產生「清除代看 cookie」的 Set-Cookie 值。屬性（path/httpOnly/sameSite）需與
     * {@link com.steven.assets.bff.security.TenantWebFilter} 寫入代看 cookie 時一致，瀏覽器才會覆蓋／刪除。
     */
    private String clearImpersonateCookie() {
        return ResponseCookie.from(AuthConstants.COOKIE_IMPERSONATE, "")
                .path("/").httpOnly(true).sameSite("Lax").maxAge(0).build()
                .toString();
    }
}
