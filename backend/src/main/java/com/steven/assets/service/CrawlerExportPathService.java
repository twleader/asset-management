package com.steven.assets.service;

import com.steven.assets.dto.CrawlerExportPathDto;
import com.steven.assets.model.CrawlerExportSetting;
import com.steven.assets.model.CrawlerSchedule;
import com.steven.assets.repository.CrawlerExportSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeoutException;

/**
 * 公開資訊爬蟲輸出檔案路徑設定（Requirement 38 / Task 212）。全域設定，無租戶。
 *
 * <p><b>路徑模型</b>（沿用 Requirement 34／39／41／42）：DB 只存相對子路徑，實際目錄 = 容器內基底
 * {@code EXPORT_OUTPUT_DIR} resolve 之。本服務只負責「設定」；實際寫檔的是 {@code external-materials-service}
 * 的 {@code NewsPoller}——**兩個容器掛同一個 host 目錄到同一個容器路徑 {@code /home/steven}**，前端資料夾樹
 * （由 {@link ExportScheduleService#browse} 於本服務列舉）看得到的目錄才等於爬蟲寫得到的目錄。
 *
 * <p><b>驗證</b>：以「基底 resolve 子路徑後 normalize 必須仍在基底內」擋 {@code ..} 跳脫與絕對路徑
 * （{@link IllegalArgumentException} → 400）。ext 端寫檔前會再驗一次（縱深防禦：實際持有檔案系統寫入權的是
 * ext，不能只信上游驗過）。
 */
@Slf4j
@Service
public class CrawlerExportPathService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(TW_ZONE);

    /** 公開觸發「重新搜尋」的全域冷卻秒數（Requirement 71 / Task 329）。呼叫者匿名，無法做 per-owner 區分。 */
    private static final long PUBLIC_RESCAN_COOLDOWN_SECONDS = 30;

    private static final String PUBLIC_RESCAN_COOLDOWN_KEY = "crawler:news-poller:public-rescan:cooldown";

    private final CrawlerExportSettingRepository repo;

    /** 公開觸發「重新搜尋」的冷卻閘門；backend 既有 Bean，{@code TradingRadarRefreshService} 已注入同一顆。 */
    private final StringRedisTemplate redis;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host 家目錄（與 business 的排程匯出共用同一基底）。 */
    private final String baseDir;

    /**
     * Drive 子路徑驗證與 remote 名稱的<b>唯一</b>來源（Task 242）。
     *
     * <p>驗證規則原本是本類別的 private static 方法，Task 242 把它遷入共用元件——八個匯出頁與本頁
     * 寫進的是<b>同一個</b> Drive，規則不能各自演化。本類別改為注入使用，行為不變（純重構）。
     */
    private final GdriveOutputSupport gdrive;

    /**
     * 手動匯出的等待上限（秒，Requirement 63 / Task 280）。<b>不寫死成常數</b>：測試要能覆寫成 1
     * 而不必真的等 50 秒。預設 50 &lt; nginx {@code /api/} 的 {@code proxy_read_timeout 60s}，
     * 確保 business 一定先回應、由我們自己決定回什麼，而不是讓 nginx 回一個沒有語意的 504。
     */
    private final long runNowTimeoutSeconds;

    /** 手動匯出用；爬蟲跑在 ext，本服務只是 proxy（比照 {@code POST /api/fund-nav/refresh} 的既有模式）。 */
    private final WebClient externalClient;

    public CrawlerExportPathService(CrawlerExportSettingRepository repo,
                                    @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir,
                                    GdriveOutputSupport gdrive,
                                    @Value("${external-materials.base-url:http://external-materials-service:8080}")
                                    String externalUrl,
                                    @Value("${crawler.run-now.timeout-seconds:50}") long runNowTimeoutSeconds,
                                    StringRedisTemplate redis) {
        this.repo = repo;
        this.baseDir = baseDir;
        this.gdrive = gdrive;
        // 靜態 WebClient.builder()——與 backend 其餘 6 處呼叫 ext 的寫法一致（全樹零處注入 WebClient.Builder）
        this.externalClient = WebClient.builder().baseUrl(externalUrl).build();
        this.runNowTimeoutSeconds = runNowTimeoutSeconds;
        this.redis = redis;
    }

    /**
     * 手動「立即匯出」（Requirement 63 / Task 280）：<b>只重產檔案</b>，proxy 至 ext
     * {@code POST /internal/news-poller/export-now}。
     */
    public CrawlerExportPathDto.RunNowResponse runNow(String crawlerKey) {
        return proxyManualRun(crawlerKey, "/internal/news-poller/export-now", "匯出");
    }

    /**
     * 手動「立即抓取並匯出」（Requirement 63 / Task 280）：<b>完整跑一輪</b>，proxy 至 ext
     * {@code POST /internal/news-poller/fetch-and-export-now}。
     */
    public CrawlerExportPathDto.RunNowResponse fetchAndRunNow(String crawlerKey) {
        return proxyManualRun(crawlerKey, "/internal/news-poller/fetch-and-export-now", "抓取");
    }

    /**
     * 公開觸發「重新搜尋」（Requirement 71）：免登入版「立即抓取並匯出」，供 Nginx 9090 gateway
     * 對外／Tailscale 呼叫。全域 30 秒冷卻（單一鍵，呼叫者匿名無法做 per-owner 區分）；
     * proxy 目標固定為 news-poller，不接受 crawler 參數。
     */
    public CrawlerExportPathDto.RunNowResponse publicRescan() {
        if (!acquirePublicRescanCooldown()) {
            return new CrawlerExportPathDto.RunNowResponse(
                    "COOLDOWN", null, null, null, null, null, null, null, null, null, null, null,
                    "冷卻中，請 " + PUBLIC_RESCAN_COOLDOWN_SECONDS + " 秒後再試");
        }
        CrawlerExportPathDto.RunNowResponse result = proxyManualRun(
                CrawlerSchedule.CRAWLER_NEWS_POLLER, "/internal/news-poller/public-rescan", "重新搜尋");
        String status = result.status();
        if ("BUSY".equals(status) || "DISABLED".equals(status) || "ERROR".equals(status)) {
            // 這三種結果代表本次呼叫沒有真的促成一輪對外抓取（或根本沒連到 ext），
            // 不強迫下一個匿名呼叫端等滿 30 秒——比照 TradingRadarRefreshService「沒真的抓，不燒冷卻」。
            redis.delete(PUBLIC_RESCAN_COOLDOWN_KEY);
        } else {
            // OK／FAILED／RUNNING：proxyManualRun 可阻塞至 runNowTimeoutSeconds（預設 50 秒），
            // 呼叫前設下的舊 30 秒 TTL 可能已在等待期間自然到期——這裡必須用 SET（不是 EXPIRE）
            // 從「呼叫已返回」的當下重新起算一個全新 30 秒窗口，EXPIRE 對已過期、不存在的 key 無效，
            // 無法重建。詳見 Requirement 71 對應 AC 與 design.md 的完整理由（含明確接受的殘餘落差）。
            try {
                redis.opsForValue().set(PUBLIC_RESCAN_COOLDOWN_KEY, "1",
                        java.time.Duration.ofSeconds(PUBLIC_RESCAN_COOLDOWN_SECONDS));
            } catch (Exception e) {
                log.warn("爬蟲公開重新搜尋冷卻鍵重新起算失敗（不影響本次呼叫結果）：{}", e.toString());
            }
        }
        return result;
    }

    /** Redis 例外時 fail-open（視為取得鎖），不因 Redis 抖動就永遠擋住這個公開入口。 */
    private boolean acquirePublicRescanCooldown() {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    PUBLIC_RESCAN_COOLDOWN_KEY, "1", java.time.Duration.ofSeconds(PUBLIC_RESCAN_COOLDOWN_SECONDS)));
        } catch (Exception e) {
            log.warn("爬蟲公開重新搜尋冷卻閘門讀寫失敗，本次放行：{}", e.toString());
            return true;
        }
    }

    /**
     * 兩顆按鈕共用的 proxy——爬蟲跑在 {@code external-materials-service}，本服務不重新實作任何抓取或產檔邏輯。
     *
     * <p><b>逾時不等於失敗。</b> 完整跑一輪典型 3~5 秒，但十餘個來源序列抓取（各 15 秒 request timeout）
     * 最壞可達數分鐘；只重產檔案本身極快，但 Drive 啟用時兩份上傳各有 45 秒上限。等待上限到了就回
     * {@code RUNNING}：ext 是 servlet 容器，request 執行緒不因 client 斷線而中止，<b>那一輪會繼續跑完、
     * 檔案照寫</b>，謊報失敗只會讓使用者去做多餘的補救。
     *
     * <p><b>逾時分支必須寫在 reactive chain 內</b>：{@code Mono.timeout(Duration)} 送出的是 checked 的
     * {@link TimeoutException}，而 {@code block()} 會把它包成 {@code RuntimeException}——外層寫
     * {@code catch (TimeoutException)} 是編譯錯誤，寫 {@code catch (Exception)} 則會把逾時誤判為失敗。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：不能把數十秒的 HTTP 呼叫包進資料庫交易。
     */
    private CrawlerExportPathDto.RunNowResponse proxyManualRun(String crawlerKey, String path, String action) {
        // 這兩支的 proxy 目標是 news-poller 專屬端點、crawlerKey 不會被帶下去；
        // 不驗就等於 ?crawler=whatever 也會觸發爬蟲。
        if (!CrawlerSchedule.CRAWLER_NEWS_POLLER.equals(crawlerKey)) {
            throw new IllegalArgumentException("目前只支援 crawler=" + CrawlerSchedule.CRAWLER_NEWS_POLLER
                    + "，收到：" + crawlerKey);
        }
        CrawlerExportPathDto.RunNowResponse running = new CrawlerExportPathDto.RunNowResponse(
                "RUNNING", null, null, null, null, null, null, null, null, null, null, null,
                "爬蟲仍在背景執行（已超過 " + runNowTimeoutSeconds + " 秒），這一輪會跑完並照常寫檔；"
                        + "請稍後重新整理頁面查看結果");
        try {
            return externalClient.post()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(CrawlerExportPathDto.RunNowResponse.class)
                    .timeout(Duration.ofSeconds(runNowTimeoutSeconds))
                    .onErrorResume(TimeoutException.class, e -> Mono.just(running))
                    .block();
        } catch (Exception e) {
            // 逾時走不到這裡（已由上面 onErrorResume 攔下）；這裡只剩連線不通、5xx 等真正的失敗
            return new CrawlerExportPathDto.RunNowResponse(
                    "ERROR", null, null, null, null, null, null, null, null, null, null, null,
                    "呼叫爬蟲服務失敗（" + action + "）：" + e.getMessage());
        }
    }

    /** 取某爬蟲的輸出路徑設定；尚未設定時回預設值（不寫入 DB）。 */
    public CrawlerExportPathDto.Response get(String crawlerKey) {
        CrawlerExportSetting s = repo.findByCrawlerKey(crawlerKey).orElseGet(() -> {
            CrawlerExportSetting fallback = new CrawlerExportSetting();
            fallback.setCrawlerKey(crawlerKey);
            fallback.setOutputSubpath(CrawlerExportSetting.DEFAULT_SUBPATH);
            return fallback;
        });
        return toResponse(s);
    }

    /**
     * upsert 某爬蟲的輸出子路徑與 Drive 設定；跳脫基底或 Drive 子路徑不合法者擲
     * {@link IllegalArgumentException}（→ 400）。
     *
     * <p><b>{@code gdriveEnabled} 為包裝型別</b>：null＝「整個欄位沒送」＝不變更，與「明確送 false」
     * 語意不同。舊版前端或只想改本機路徑的呼叫端不該把使用者已開啟的 Drive 開關靜默關掉。
     * {@code gdriveSubpath} 為 null 時同理保留既有值。
     *
     * <p><b>本頁的 Drive 自檢必須自己掛一次</b>（Task 247.3.1(b)）：本方法<b>不走</b>
     * {@code GdriveOutputSupport.resolveUpdate}（它只用該元件的零件自行合成），故八個匯出頁那一處
     * 涵蓋不到這裡。漏掉就是漏掉最該被攔下的那一次——實測九列設定中，爬蟲頁是 {@code updated_at}
     * <b>最早</b>被打開的一列（2026-07-27 22:33）。
     */
    @Transactional
    public CrawlerExportPathDto.Response update(String crawlerKey, CrawlerExportPathDto.Request req) {
        String subpath = normalizeSubpath(req == null ? null : req.outputSubpath());
        resolveDir(subpath); // 驗證不跳脫基底（丟出即擋下）

        CrawlerExportSetting s = repo.findByCrawlerKey(crawlerKey).orElseGet(CrawlerExportSetting::new);

        boolean enabled = req == null || req.gdriveEnabled() == null
                ? s.isGdriveEnabled()            // 未送出＝不變更
                : req.gdriveEnabled();
        String gdriveSubpath = req == null || req.gdriveSubpath() == null
                ? s.getGdriveSubpath()           // 未送出＝保留既有值
                : gdrive.normalizeSubpath(req.gdriveSubpath());
        // 驗證與「啟用時必填」一律走共用元件（Task 242）：兩邊寫進同一個 Drive，規則不能各自演化。
        gdrive.validateSubpath(gdriveSubpath);
        if (enabled && (gdriveSubpath == null || gdriveSubpath.isBlank())) {
            throw new IllegalArgumentException("已啟用 Google Drive 同步時，必須指定 Drive 目標資料夾");
        }
        // 只在「本次把開關從 false 翻成 true」時做一次純本地自檢（L1＋L2，毫秒級、不打網路）。
        // 位置在驗證與必填判定之後、setter 之前：前者確保輸入不合法時回 400 而不是兩種訊息並陳，
        // 後者確保讀到的 s.isGdriveEnabled() 還是「寫入之前」的現值。
        // 本方法在 @Transactional 內，這也正是不在此做 L3（rclone lsd，逾時 20 秒）的理由之一。
        String selfCheckWarning = gdrive.selfCheckWarningOnEnable(s.isGdriveEnabled(), enabled);

        s.setCrawlerKey(crawlerKey);
        s.setOutputSubpath(subpath);
        s.setGdriveEnabled(enabled);
        s.setGdriveSubpath(gdriveSubpath);
        // 刻意不碰 gdriveLastRunAt／gdriveLastStatus：那是 ext 寫入的執行結果，不是使用者設定。
        // 自檢結果同樣不寫那兩欄（Task 247.3.4），只走當次回應。
        s.setUpdatedAt(Instant.now());
        return toResponse(repo.save(s), selfCheckWarning);
    }

    /** 去頭尾空白與結尾斜線（避免 {@code input} 與 {@code input/} 存成兩種值）；空字串 → 預設子路徑。 */
    private static String normalizeSubpath(String subpath) {
        String sub = subpath == null ? "" : subpath.trim();
        while (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);
        return sub.isEmpty() ? CrawlerExportSetting.DEFAULT_SUBPATH : sub;
    }

    /**
     * 基底 resolve 子路徑並驗證仍在基底內。開頭的 {@code /} 刻意**不**先剝除——絕對路徑一律回 400 讓使用者
     * 知道只能填相對子路徑，勝過默默改寫語意把 {@code /etc} 當成 {@code <base>/etc}。
     */
    private Path resolveDir(String subpath) {
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path target = base.resolve(subpath).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄，且須為相對路徑：" + subpath);
        }
        return target;
    }

    /**
     * 轉 response。<b>讀取路徑一律不驗證、不擲例外</b>（同 {@link #absolutePathOrNull} 的理由）：
     * DB 內可能存在繞過 API 寫入的不合法 Drive 子路徑，若讀取也失敗，設定頁會 500 而使用者
     * <b>沒有任何入口能把它改回正常值</b>——唯一的修正入口被自己鎖死。故原樣回傳供前端顯示，
     * 存檔時（{@link #update}）才驗。
     */
    private CrawlerExportPathDto.Response toResponse(CrawlerExportSetting s) {
        return toResponse(s, null);   // 讀取路徑不做自檢：自檢只在「使用者這次把開關打開」時才有意義
    }

    /** @param gdriveSelfCheckWarning 當次啟用自檢的警告；<b>不入庫</b>，正常時為 {@code null} */
    private CrawlerExportPathDto.Response toResponse(CrawlerExportSetting s, String gdriveSelfCheckWarning) {
        String subpath = normalizeSubpath(s.getOutputSubpath());
        return new CrawlerExportPathDto.Response(
                s.getCrawlerKey(),
                subpath,
                baseDir,
                absolutePathOrNull(subpath),
                s.getUpdatedAt() == null ? null : TS_FMT.format(s.getUpdatedAt()),
                s.isGdriveEnabled(),
                s.getGdriveSubpath(),
                gdrive.remoteName(),
                s.getGdriveLastRunAt() == null ? null : TS_FMT.format(s.getGdriveLastRunAt()),
                s.getGdriveLastStatus(),
                gdriveSelfCheckWarning);
    }

    /**
     * 顯示用的完整落點；**讀取時不因既有值不合法而失敗**。PUT 已擋下跳脫值，但 DB 內仍可能存在繞過 API 寫入的
     * 舊值（psql 直改、跨環境備份還原、日後改動 {@code EXPORT_OUTPUT_DIR} 基底）。若讀取也一併擲例外，設定頁會
     * 500 而無法載入，使用者反而**沒有辦法用 UI 把它改回正常值**（唯一的修正入口被自己鎖死）。故此處回 null，
     * 前端顯示「—」、欄位仍可重選並儲存（存檔時照樣驗證）。ext 端遇同樣情形則 fallback 至預設子路徑。
     */
    private String absolutePathOrNull(String subpath) {
        try {
            return resolveDir(subpath).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
