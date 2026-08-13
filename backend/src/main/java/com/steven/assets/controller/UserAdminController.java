package com.steven.assets.controller;

import com.steven.assets.dto.UserDto;
import com.steven.assets.model.AppUser;
import com.steven.assets.service.UserAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 使用者主檔內部端點（Requirement 28）。
 *
 * <p>路徑前綴 {@code /internal/users}（business-services 內網）。只有精確的登入 bootstrap、本人查詢與
 * configured-admin bootstrap 依各自 HTTP method 免 ADMIN；其餘皆由 {@code AdminGateInterceptor} 限 ADMIN。
 * BFF 經 {@code /api/bff/user-management/**} passthrough。
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    /** 登入 upsert（BFF OIDC user service 呼叫），回傳目前 role/status。免 ADMIN。 */
    @PostMapping("/login-upsert")
    public UserDto.UserResponse loginUpsert(@RequestBody UserDto.LoginUpsertRequest req) {
        AppUser user = userAdminService.loginUpsert(req.email(), req.name(), req.picture());
        return toResponse(user);
    }

    /** 依 email 查目前使用者（BFF /api/me 即時取 status 用）。 */
    @GetMapping("/by-email")
    public UserDto.UserResponse byEmail(@RequestParam String email) {
        AppUser user = userAdminService.getByEmail(email);
        return user == null ? null : toResponse(user);
    }

    /** Requirement 68 bootstrap：BFF 不持有 ADMIN_EMAIL，由 business 唯一權威解析主要管理者。 */
    @GetMapping("/configured-admin")
    public UserDto.UserResponse configuredAdmin() {
        return userAdminService.configuredAdmin()
                .map(this::toResponse)
                .orElse(null);
    }

    @GetMapping
    public List<UserDto.UserResponse> list() {
        return userAdminService.listAll().stream().map(this::toResponse).toList();
    }

    @PatchMapping("/{id}/status")
    public UserDto.UserResponse updateStatus(@PathVariable Long id, @RequestBody UserDto.StatusRequest req) {
        return toResponse(userAdminService.updateStatus(id, req.status()));
    }

    @PatchMapping("/{id}/role")
    public UserDto.UserResponse updateRole(@PathVariable Long id, @RequestBody UserDto.RoleRequest req) {
        return toResponse(userAdminService.updateRole(id, req.role()));
    }

    private UserDto.UserResponse toResponse(AppUser user) {
        return UserDto.UserResponse.from(user, userAdminService.isConfiguredAdmin(user.getEmail()));
    }
}
