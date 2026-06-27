package com.steven.assets.service;

import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 使用者主檔管理（Requirement 28）。
 *
 * <p>登入 upsert 與管理者核准／停用／設角色。管理者 email 固定為 {@link #ADMIN_EMAIL}，
 * upsert 時一律維持 ADMIN/ACTIVE，不會被降級或退回待核准。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserAdminService {

    public static final String ADMIN_EMAIL = "tw.leader@gmail.com";

    private static final Set<String> VALID_STATUS =
            Set.of(AppUser.STATUS_PENDING, AppUser.STATUS_ACTIVE, AppUser.STATUS_DISABLED);
    private static final Set<String> VALID_ROLE =
            Set.of(AppUser.ROLE_ADMIN, AppUser.ROLE_USER);

    private final AppUserRepository userRepo;

    /**
     * 登入時 upsert：存在則更新 name/picture，不存在則建立（一般使用者 PENDING/USER）。
     * 管理者 email 一律強制 ADMIN/ACTIVE。
     */
    @Transactional
    public AppUser loginUpsert(String email, String name, String picture) {
        String normalized = email == null ? null : email.trim().toLowerCase();
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("email 不可為空");
        }
        boolean isAdmin = ADMIN_EMAIL.equalsIgnoreCase(normalized);
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
        String normalized = email == null ? null : email.trim().toLowerCase();
        return userRepo.findByEmail(normalized).orElse(null);
    }

    @Transactional
    public AppUser updateStatus(Long id, String status) {
        if (!VALID_STATUS.contains(status)) {
            throw new IllegalArgumentException("不合法的狀態: " + status);
        }
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("查無使用者: " + id));
        if (ADMIN_EMAIL.equalsIgnoreCase(user.getEmail()) && !AppUser.STATUS_ACTIVE.equals(status)) {
            throw new IllegalArgumentException("不可停用管理者帳號");
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
        if (ADMIN_EMAIL.equalsIgnoreCase(user.getEmail()) && !AppUser.ROLE_ADMIN.equals(role)) {
            throw new IllegalArgumentException("不可調降管理者帳號角色");
        }
        user.setRole(role);
        return userRepo.save(user);
    }
}
