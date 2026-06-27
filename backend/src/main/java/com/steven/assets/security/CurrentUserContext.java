package com.steven.assets.security;

import com.steven.assets.model.AppUser;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * 目前 HTTP 請求的使用者情境（Requirement 28：多租戶）。
 *
 * <p>由 {@link CurrentUserFilter} 從 BFF 傳來的 {@code X-User-*} header 填入。值僅在「有 HTTP request」的請求執行緒
 * 中存在；背景 cron 取不到本 bean（request scope），故不會套用 owner 過濾、維持掃全體資料的既有行為。
 *
 * <ul>
 *   <li>{@code effectiveUserId}：實際要過濾/歸屬的使用者 id（一般使用者＝自己；管理者代看＝選定目標）。由 BFF 決定。</li>
 *   <li>{@code role}：{@link AppUser#ROLE_ADMIN} / {@link AppUser#ROLE_USER}。</li>
 *   <li>{@code status}：{@code ACTIVE} / {@code PENDING} / {@code DISABLED}。</li>
 * </ul>
 */
@Component
@RequestScope
@Getter
@Setter
public class CurrentUserContext {

    private Long effectiveUserId;
    private String role;
    private String status;

    public boolean hasUser() {
        return effectiveUserId != null;
    }

    public boolean isAdmin() {
        return AppUser.ROLE_ADMIN.equals(role);
    }
}
