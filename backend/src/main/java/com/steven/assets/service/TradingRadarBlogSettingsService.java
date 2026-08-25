package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarBlogDto;
import com.steven.assets.model.TradingRadarExportSetting;
import com.steven.assets.repository.TradingRadarExportSettingRepository;
import com.steven.assets.security.AdminRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 交易雷達 Blog 排程發布設定的業務入口（Requirement 102 / Task 374）。
 * Controller 只能委派本類別，不得自行讀寫 credential 或 export-setting repository。
 */
@Service
@RequiredArgsConstructor
public class TradingRadarBlogSettingsService {

    private static final String ADMIN_REQUIRED_MESSAGE = "交易雷達發布到 Blog 僅限主要管理者啟用";
    private static final String DESTINATION_NOT_CONNECTED_MESSAGE =
            "尚未完成目前 Blogger 發布目的地的有效連接，無法啟用同步發布";

    private final TradingRadarExportSettingRepository settingRepo;
    private final BlogOAuthService blogOAuthService;
    private final BlogPublishOutputSupport blogOutputSupport;

    /** 聚合安全的固定目的地連接狀態與 owner 的排程發布設定。 */
    public TradingRadarBlogDto.StatusResponse status(Long ownerId) {
        BlogOAuthService.DestinationStatus destination = blogOAuthService.currentDestinationStatus();
        TradingRadarExportSetting setting = ownerId == null
                ? null
                : settingRepo.findByOwnerUserId(ownerId).orElse(null);
        return new TradingRadarBlogDto.StatusResponse(
                destination.connected(),
                destination.accountLabel(),
                destination.blogUrl(),
                setting != null && setting.isBlogEnabled(),
                setting == null || setting.getBlogLastRunAt() == null ? null : setting.getBlogLastRunAt().toString(),
                setting == null ? null : setting.getBlogLastStatus(),
                setting == null ? null : setting.getBlogLastPostUrl());
    }

    /**
     * 主要管理者設定排程發布開關。啟用前先驗證固定新站 credential，失敗時完全不建立或儲存 setting。
     */
    public TradingRadarBlogDto.StatusResponse setEnabled(long ownerId, boolean enabled) {
        requireAllowed(ownerId);
        if (enabled && !blogOAuthService.isCurrentDestinationConnected()) {
            throw new IllegalArgumentException(DESTINATION_NOT_CONNECTED_MESSAGE);
        }

        TradingRadarExportSetting setting = settingRepo.findByOwnerUserId(ownerId)
                .orElseGet(() -> TradingRadarExportSetting.builder().ownerUserId(ownerId).build());
        setting.setBlogEnabled(enabled);
        settingRepo.save(setting);
        return status(ownerId);
    }

    private void requireAllowed(long ownerId) {
        if (!blogOutputSupport.isBlogAllowedFor(ownerId)) {
            throw new AdminRequiredException(ADMIN_REQUIRED_MESSAGE);
        }
    }
}
