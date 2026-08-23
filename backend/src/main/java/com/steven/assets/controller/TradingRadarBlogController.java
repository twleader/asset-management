package com.steven.assets.controller;

import com.steven.assets.dto.TradingRadarBlogDto;
import com.steven.assets.model.BlogPublishCredential;
import com.steven.assets.model.TradingRadarExportSetting;
import com.steven.assets.repository.BlogPublishCredentialRepository;
import com.steven.assets.repository.TradingRadarExportSettingRepository;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.BlogOAuthService;
import com.steven.assets.service.BlogPublishOutputSupport;
import com.steven.assets.service.BlogPublishService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * 交易雷達「發布到 Blog」business API（Requirement 102 / Task 366）。與既有
 * {@code TradingRadarController} 平行的獨立類別，避免既有檔案繼續膨脹。
 *
 * <p>Controller 只做 HTTP 收發與委派：查快照與「查無快照」的業務判斷封裝在
 * {@link BlogPublishService#publishLatest}，本類別不直接注入或呼叫
 * {@code TradingRadarSnapshotStore}。
 *
 * <p><b>六支端點皆先覆核 {@link BlogPublishOutputSupport#isBlogAllowedFor}</b>——不得只靠 BFF
 * 的 {@code hasAuthority} 單一防線，business 層必須獨立覆核一次，尤其是
 * {@code blog-oauth/callback}：這是唯一真正把 Google token 寫入全域憑證（有副作用）的端點。
 */
@RestController
@RequestMapping("/api/trading-radar")
@RequiredArgsConstructor
public class TradingRadarBlogController {

    private static final String ADMIN_REQUIRED_MESSAGE = "交易雷達發布到 Blog 僅限主要管理者啟用";

    private final BlogPublishOutputSupport blogOutputSupport;
    private final BlogOAuthService blogOAuthService;
    private final BlogPublishService blogPublishService;
    private final BlogPublishCredentialRepository credentialRepo;
    private final TradingRadarExportSettingRepository settingRepo;
    private final CurrentUserContext currentUser;

    @GetMapping("/blog-oauth/authorize-url")
    public Map<String, String> authorizeUrl() {
        requireAllowed(currentUser.getEffectiveUserId());
        return Map.of("url", blogOAuthService.buildAuthorizeUrl());
    }

    /**
     * Google 同意畫面導回。權限不通過時<b>直接</b>回 302 導回錯誤頁，不呼叫
     * {@link BlogOAuthService#handleCallback}——即不進行任何 token 換取。
     */
    @GetMapping("/blog-oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                          @RequestParam(required = false) String state) {
        if (!blogOutputSupport.isBlogAllowedFor(currentUser.getEffectiveUserId())) {
            return redirect("/trading-radar?blogOauth=error&reason=forbidden");
        }
        BlogOAuthService.CallbackResult result = blogOAuthService.handleCallback(code, state);
        String target = result.success()
                ? "/trading-radar?blogOauth=connected"
                : "/trading-radar?blogOauth=error&reason=" + result.reason();
        return redirect(target);
    }

    @PostMapping("/blog-oauth/disconnect")
    public ResponseEntity<Void> disconnect() {
        requireAllowed(currentUser.getEffectiveUserId());
        blogOAuthService.disconnect();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/blog-status")
    public TradingRadarBlogDto.StatusResponse status() {
        Long ownerId = currentUser.hasUser() ? currentUser.getEffectiveUserId() : null;
        return buildStatus(ownerId);
    }

    @PutMapping("/blog-enabled")
    public TradingRadarBlogDto.StatusResponse setEnabled(@RequestBody TradingRadarBlogDto.EnabledRequest req) {
        long ownerId = requireOwnerId();
        boolean enabled = req != null && req.enabled();
        if (enabled) {
            requireAllowed(ownerId);
        }
        TradingRadarExportSetting setting = settingRepo.findByOwnerUserId(ownerId)
                .orElseGet(() -> TradingRadarExportSetting.builder().ownerUserId(ownerId).build());
        setting.setBlogEnabled(enabled);
        settingRepo.save(setting);
        return buildStatus(ownerId);
    }

    @PostMapping("/blog-publish")
    public ResponseEntity<BlogPublishService.PublishResult> publish() {
        long ownerId = requireOwnerId();
        requireAllowed(ownerId);
        BlogPublishService.PublishResult result = blogPublishService.publishLatest(ownerId);
        if (!result.success() && "當日尚無交易雷達快照，請先重新整理".equals(result.status())) {
            return ResponseEntity.badRequest().body(result);
        }
        if (!result.success()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(result);
        }
        return ResponseEntity.ok(result);
    }

    // ===== 共用小工具 =====

    private TradingRadarBlogDto.StatusResponse buildStatus(Long ownerId) {
        BlogPublishCredential cred = credentialRepo.findById(1L).orElse(null);
        boolean connected = cred != null
                && cred.getRefreshToken() != null && !cred.getRefreshToken().isBlank()
                && !cred.isNeedsReconnect();
        TradingRadarExportSetting setting = ownerId == null ? null : settingRepo.findByOwnerUserId(ownerId).orElse(null);
        return new TradingRadarBlogDto.StatusResponse(
                connected,
                cred == null ? null : cred.getAccountLabel(),
                cred == null ? "https://twleader.blogspot.com/" : cred.getBlogUrl(),
                setting != null && setting.isBlogEnabled(),
                setting == null || setting.getBlogLastRunAt() == null ? null : setting.getBlogLastRunAt().toString(),
                setting == null ? null : setting.getBlogLastStatus(),
                setting == null ? null : setting.getBlogLastPostUrl());
    }

    private void requireAllowed(Long ownerId) {
        if (!blogOutputSupport.isBlogAllowedFor(ownerId)) {
            throw new AdminRequiredException(ADMIN_REQUIRED_MESSAGE);
        }
    }

    private long requireOwnerId() {
        if (!currentUser.hasUser()) {
            throw new IllegalArgumentException("未識別使用者，無法操作交易雷達 Blog 發布設定");
        }
        return currentUser.getEffectiveUserId();
    }

    private static ResponseEntity<Void> redirect(String relativePath) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(relativePath)).build();
    }
}
