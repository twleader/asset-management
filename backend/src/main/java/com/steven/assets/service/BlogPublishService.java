package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.BlogPublishCredential;
import com.steven.assets.model.TradingRadarExportSetting;
import com.steven.assets.repository.BlogPublishCredentialRepository;
import com.steven.assets.repository.TradingRadarExportSettingRepository;
import com.steven.assets.service.export.BlogContentRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 把交易雷達快照的精簡摘要版發布/更新到 {@code twleader.blogspot.com}（Requirement 102 / Task 366）。
 *
 * <p>{@link #publishLatest} 供手動發布（{@code POST /blog-publish}）呼叫，查快照與「查無快照」的
 * 業務判斷封裝在這裡——controller 不得直接呼叫 {@link TradingRadarSnapshotStore} 自己判斷。
 * {@link #publish} 是核心方法，排程路徑（{@code TradingRadarExportScheduleService}）直接呼叫這支、
 * 傳入該輪已查好的快照，不為此再查一次 Redis。<b>本類別對外的兩支方法皆永不擲出例外</b>——
 * 排程呼叫端需要這個保證才能安全地「失敗只記 log，不影響既有本機／Drive 產出流程」。
 */
@Slf4j
@Service
public class BlogPublishService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String POST_TITLE = "交易雷達每日結果";
    private static final String NO_SNAPSHOT_STATUS = "當日尚無交易雷達快照，請先重新整理";
    /** {@code trading_radar_export_setting.blog_last_status} 欄位上限（varchar(512)）。 */
    private static final int STATUS_MAX = 512;

    private final BlogOAuthService blogOAuthService;
    private final BlogPublishCredentialRepository credentialRepo;
    private final TradingRadarExportSettingRepository settingRepo;
    private final TradingRadarSnapshotStore snapshotStore;
    private final ObjectMapper objectMapper;
    private final GoogleHttpClient httpClient;

    public BlogPublishService(BlogOAuthService blogOAuthService,
                               BlogPublishCredentialRepository credentialRepo,
                               TradingRadarExportSettingRepository settingRepo,
                               TradingRadarSnapshotStore snapshotStore,
                               ObjectMapper objectMapper,
                               GoogleHttpClient httpClient) {
        this.blogOAuthService = blogOAuthService;
        this.credentialRepo = credentialRepo;
        this.settingRepo = settingRepo;
        this.snapshotStore = snapshotStore;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public record PublishResult(boolean success, String status, String postUrl) {}

    /**
     * 手動發布：查該 owner 當日（00:00～現在）最新一筆快照並發布。查無快照時<b>不寫入</b>
     * {@code blogLastRunAt}／{@code blogLastStatus}（沒有內容可發，不算一次「執行」），也不呼叫
     * {@link BlogOAuthService#ensureAccessToken()}。
     */
    public PublishResult publishLatest(long ownerId) {
        ZonedDateTime now = ZonedDateTime.now(TAIPEI);
        long fromEpoch = now.toLocalDate().atStartOfDay(TAIPEI).toInstant().toEpochMilli();
        long toEpoch = now.toInstant().toEpochMilli();
        TradingRadarSnapshotStore.SnapshotRange range = snapshotStore.range(ownerId, fromEpoch, toEpoch);
        if (range.snapshots().isEmpty()) {
            return new PublishResult(false, NO_SNAPSHOT_STATUS, null);
        }
        JsonNode snapshot = range.snapshots().get(range.snapshots().size() - 1);
        return publish(ownerId, snapshot);
    }

    /**
     * 核心發布邏輯：建立或更新 Blogger 文章。<b>永不擲出例外</b>——任何失敗一律轉成
     * {@code PublishResult(false, ...)} 並盡力寫入狀態欄（供設定頁顯示失敗原因）。
     */
    public PublishResult publish(long ownerId, JsonNode snapshot) {
        String accessToken;
        try {
            accessToken = blogOAuthService.ensureAccessToken();
        } catch (Exception e) {
            return recordFailureByOwner(ownerId, "尚未連接 Blogger 帳號或授權已失效：" + e.getMessage());
        }

        String blogId = credentialRepo.findById(1L).map(BlogPublishCredential::getBlogId).orElse(null);
        if (blogId == null || blogId.isBlank()) {
            return recordFailureByOwner(ownerId, "尚未解析出 blogId，請重新連接");
        }

        TradingRadarExportSetting setting = settingRepo.findByOwnerUserId(ownerId).orElse(null);
        if (setting == null) {
            // 防禦性分支：理論上不會發生——啟用 blogEnabled 前必先有一列設定。無列可寫，直接回失敗。
            return new PublishResult(false, "查無交易雷達匯出設定，請先於設定頁儲存一次", null);
        }

        try {
            String html = BlogContentRenderer.render(snapshot);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("kind", "blogger#post");
            body.put("title", POST_TITLE);
            body.put("content", html);
            String bodyJson = objectMapper.writeValueAsString(body);

            String postId = setting.getBlogLastPostId();
            return postId == null
                    ? createPost(setting, blogId, accessToken, bodyJson)
                    : updatePost(setting, blogId, postId, accessToken, bodyJson);
        } catch (Exception e) {
            log.warn("交易雷達 blog 發布失敗 owner={}：{}", ownerId, e.getMessage(), e);
            return recordFailure(setting, "發布失敗：" + e.getMessage());
        }
    }

    // ===== Blogger API 呼叫 =====

    private PublishResult createPost(TradingRadarExportSetting setting, String blogId,
                                      String accessToken, String bodyJson)
            throws java.io.IOException, InterruptedException {
        String url = "https://www.googleapis.com/blogger/v3/blogs/" + blogId + "/posts?isDraft=false";
        GoogleHttpClient.Response resp = httpClient.post(url, "application/json", bodyJson, accessToken);
        if (!isSuccess(resp)) {
            return recordFailure(setting, "建立文章失敗：HTTP " + resp.statusCode());
        }
        return applyCreateSuccess(setting, resp);
    }

    private PublishResult updatePost(TradingRadarExportSetting setting, String blogId, String postId,
                                      String accessToken, String bodyJson)
            throws java.io.IOException, InterruptedException {
        String url = "https://www.googleapis.com/blogger/v3/blogs/" + blogId + "/posts/" + postId;
        GoogleHttpClient.Response resp = httpClient.put(url, "application/json", bodyJson, accessToken);
        if (isSuccess(resp)) {
            JsonNode json = parseQuietly(resp.body());
            String postUrl = json == null ? null : json.path("url").asText(null);
            setting.setBlogLastPostUrl(postUrl);
            return succeedAndRecord(setting, postUrl);
        }
        if (resp.statusCode() == 404) {
            // 視為文章已被手動刪除，回退成建立；成功後覆寫 postId／postUrl。
            String createUrl = "https://www.googleapis.com/blogger/v3/blogs/" + blogId + "/posts?isDraft=false";
            GoogleHttpClient.Response createResp =
                    httpClient.post(createUrl, "application/json", bodyJson, accessToken);
            if (isSuccess(createResp)) {
                return applyCreateSuccess(setting, createResp);
            }
            return recordFailure(setting, "更新文章找不到既有貼文，重新建立亦失敗：HTTP " + createResp.statusCode());
        }
        return recordFailure(setting, "更新文章失敗：HTTP " + resp.statusCode());
    }

    private PublishResult applyCreateSuccess(TradingRadarExportSetting setting, GoogleHttpClient.Response resp) {
        JsonNode json = parseQuietly(resp.body());
        String newPostId = json == null ? null : json.path("id").asText(null);
        String postUrl = json == null ? null : json.path("url").asText(null);
        setting.setBlogLastPostId(newPostId);
        setting.setBlogLastPostUrl(postUrl);
        return succeedAndRecord(setting, postUrl);
    }

    private JsonNode parseQuietly(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSuccess(GoogleHttpClient.Response resp) {
        return resp.statusCode() >= 200 && resp.statusCode() < 300;
    }

    // ===== 狀態欄寫入 =====

    private PublishResult succeedAndRecord(TradingRadarExportSetting setting, String postUrl) {
        String status = "成功：" + postUrl;
        setting.setBlogLastRunAt(LocalDateTime.now(TAIPEI));
        setting.setBlogLastStatus(truncate(status));
        settingRepo.save(setting);
        return new PublishResult(true, status, postUrl);
    }

    private PublishResult recordFailure(TradingRadarExportSetting setting, String message) {
        setting.setBlogLastRunAt(LocalDateTime.now(TAIPEI));
        setting.setBlogLastStatus(truncate(message));
        settingRepo.save(setting);
        return new PublishResult(false, message, null);
    }

    /** 步驟 1／2（ensureAccessToken／blogId 缺漏）失敗時，setting 尚未載入，改以 ownerId 查找後寫入。 */
    private PublishResult recordFailureByOwner(long ownerId, String message) {
        TradingRadarExportSetting setting = settingRepo.findByOwnerUserId(ownerId).orElse(null);
        return setting == null ? new PublishResult(false, message, null) : recordFailure(setting, message);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= STATUS_MAX ? s : s.substring(0, STATUS_MAX - 1) + "…";
    }
}
