package com.steven.assets.service;

import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 使用者主檔管理（Requirement 28）。
 *
 * <p>登入 upsert 與管理者核准／停用／設角色。主要管理者 email 由 {@code ADMIN_EMAIL}
 * 環境變數提供，upsert 時一律維持 ADMIN/ACTIVE，不會被降級或退回待核准。
 */
@Service
@Slf4j
public class UserAdminService {

    private static final Set<String> VALID_STATUS =
            Set.of(AppUser.STATUS_PENDING, AppUser.STATUS_ACTIVE, AppUser.STATUS_DISABLED);
    private static final Set<String> VALID_ROLE =
            Set.of(AppUser.ROLE_ADMIN, AppUser.ROLE_USER);
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
            Pattern.CASE_INSENSITIVE);

    private final AppUserRepository userRepo;
    private final String adminEmail;

    public UserAdminService(AppUserRepository userRepo, @Value("${app.admin-email}") String adminEmail) {
        this.userRepo = userRepo;
        this.adminEmail = normalizeAndValidateAdminEmail(adminEmail);
        log.info("主要管理者帳號已由 ADMIN_EMAIL 載入: {}", this.adminEmail);
    }

    /**
     * 登入時 upsert：存在則更新 name/picture，不存在則建立（一般使用者 PENDING/USER）。
     * 管理者 email 一律強制 ADMIN/ACTIVE。
     */
    @Transactional
    public AppUser loginUpsert(String email, String name, String picture) {
        String normalized = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("email 不可為空");
        }
        boolean isAdmin = isConfiguredAdmin(normalized);
        AppUser user = userRepo.findByEmail(normalized).orElseGet(() -> AppUser.builder()
                .email(normalized)
                .role(isAdmin ? AppUser.ROLE_ADMIN : AppUser.ROLE_USER)
                .status(isAdmin ? AppUser.STATUS_ACTIVE : AppUser.STATUS_PENDING)
                .build());
        if (name != null && !name.isBlank()) user.setName(name);
        if (picture != null && !picture.isBlank()) user.setPicture(picture);
        if (isAdmin) {
            user.setRole(AppUser.ROLE_ADMIN);
            user.setStatus(AppUser.STATUS_ACTIVE);
        }
        AppUser saved = userRepo.save(user);
        log.info("使用者登入 upsert: {} (role={}, status={})", saved.getEmail(), saved.getRole(), saved.getStatus());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AppUser> listAll() {
        return userRepo.findAllByOrderByCreatedAtAsc();
    }

    @Transactional(readOnly = true)
    public AppUser getByEmail(String email) {
        String normalized = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank()) return null;
        return userRepo.findByEmail(normalized).orElse(null);
    }

    /** 供所有需要保護主要管理者的路徑共用，避免各層自行保存 email。 */
    public boolean isConfiguredAdmin(String email) {
        return email != null && adminEmail.equals(email.trim().toLowerCase(Locale.ROOT));
    }

    @Transactional
    public AppUser updateStatus(Long id, String status) {
        if (!VALID_STATUS.contains(status)) {
            throw new IllegalArgumentException("不合法的狀態: " + status);
        }
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("查無使用者: " + id));
        if (isConfiguredAdmin(user.getEmail()) && !AppUser.STATUS_ACTIVE.equals(status)) {
            throw new IllegalArgumentException("不可停用主要管理者帳號");
        }
        user.setStatus(status);
        return userRepo.save(user);
    }

    @Transactional
    public AppUser updateRole(Long id, String role) {
        if (!VALID_ROLE.contains(role)) {
            throw new IllegalArgumentException("不合法的角色: " + role);
        }
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("查無使用者: " + id));
        if (isConfiguredAdmin(user.getEmail()) && !AppUser.ROLE_ADMIN.equals(role)) {
            throw new IllegalArgumentException("不可調降主要管理者帳號角色");
        }
        user.setRole(role);
        return userRepo.save(user);
    }

    private static String normalizeAndValidateAdminEmail(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("ADMIN_EMAIL 為必填，請在 .env 設定主要管理者的 Google 帳號");
        }
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("ADMIN_EMAIL 格式不正確: " + raw);
        }
        return normalized;
    }
}
