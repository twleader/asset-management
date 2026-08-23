package com.steven.assets.service;

import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 交易雷達「發布到 Blog」的權限判定（Requirement 102 / Task 366）。
 *
 * <p>與 {@link GdriveOutputSupport#isDriveAllowedFor} 邏輯完全平行但<b>刻意獨立存在，不合併成
 * 同一支</b>——兩者未來可能各自獨立開放對象，合併會讓其中一方要開放時被迫連動另一方。
 *
 * <p>判準是 {@code isConfiguredAdmin(email)}（主要管理者本人），不是 {@code role == ADMIN}：
 * Blogger 帳號全機只有一份、綁定 {@code shi.chihung@gmail.com}，若允許非主要管理者的其他
 * {@code role=ADMIN} 使用者啟用，其交易雷達結果會被發布到與自己無關的公開部落格。
 */
@Slf4j
@Component
public class BlogPublishOutputSupport {

    private final AppUserRepository userRepo;
    private final UserAdminService userAdminService;

    public BlogPublishOutputSupport(AppUserRepository userRepo, UserAdminService userAdminService) {
        this.userRepo = userRepo;
        this.userAdminService = userAdminService;
    }

    /**
     * 該 ownerUserId 是否可使用「發布到 Blog」。<b>唯一的權限判定入口，且 fail-closed。</b>
     * {@code ownerUserId} 為 {@code null}、查無使用者、email 為空皆回 {@code false}。
     */
    public boolean isBlogAllowedFor(Long ownerUserId) {
        if (ownerUserId == null) return false;
        AppUser user = userRepo.findById(ownerUserId).orElse(null);
        if (user == null) {
            log.warn("Blog 發布權限判定：查無 owner={}，一律視為不允許（fail-closed）", ownerUserId);
            return false;
        }
        String email = user.getEmail();
        if (email == null || email.isBlank()) return false;
        return userAdminService.isConfiguredAdmin(email);
    }
}
