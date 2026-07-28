package com.steven.assets.service;

import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.security.AdminRequiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Google Drive 輸出的共用支援元件（Requirement 51 / Task 242）。
 *
 * <p><b>為什麼要抽這一支：</b>Requirement 51 要把「本機照寫＋Drive 附加副本」推廣到八個匯出頁。
 * 子路徑驗證、上傳呼叫、狀態字串組裝、remote 名稱、權限判定這五件事若各頁自己實作，就會有八份
 * 會各自演化的規則——而它們寫進的是<b>同一個</b> Drive。故一律走本元件。
 *
 * <p><b>與 Task 241 的「不為兩處 rclone 呼叫建共用 module」不衝突：</b>那條講的是跨越三個獨立
 * Maven 專案（backend／bff／external-materials-service，無父 pom）為兩處數十行程式碼建 module；
 * 這裡是<b>同一個 backend module 內</b>八個 service 共用同一段邏輯。
 *
 * <p>本元件同時收斂了 Task 241 留下的重複：{@code CrawlerExportPathService} 的 private static 驗證方法
 * 與散在兩處的 {@code GDRIVE_OUTPUT_REMOTE} 注入，皆已遷入此處。
 */
@Slf4j
@Component
public class GdriveOutputSupport {

    /** 狀態欄長度上限（{@code gdrive_last_status VARCHAR(512)}）；rclone stderr 可能很長。 */
    private static final int MAX_STATUS_LEN = 512;

    private final RcloneClient rcloneClient;
    private final AppUserRepository userRepo;
    private final UserAdminService userAdminService;

    /**
     * 啟用當下的可用性自檢（Requirement 52 / Task 247）。
     *
     * <p><b>相依方向只能是這一邊</b>：{@link GdriveSelfCheck} 是葉節點、<b>絕不反向注入本元件</b>——
     * 反向注入會形成建構子循環依賴，Spring Boot 2.6+ 預設禁止循環參照，結果是
     * {@code BeanCurrentlyInCreationException}、business-services 整個起不來（Task 247.2.1）。
     * 故本元件把自己持有的 remote 名稱<b>傳參</b>給它。
     */
    private final GdriveSelfCheck selfCheck;

    /** Drive 輸出用的 rclone remote 名稱。<b>全 backend 唯一的注入點</b>（Task 242.1.4）。 */
    private final String remote;

    public GdriveOutputSupport(RcloneClient rcloneClient,
                               AppUserRepository userRepo,
                               UserAdminService userAdminService,
                               GdriveSelfCheck selfCheck,
                               @Value("${GDRIVE_OUTPUT_REMOTE:GDriveOutput}") String remote) {
        this.rcloneClient = rcloneClient;
        this.userRepo = userRepo;
        this.userAdminService = userAdminService;
        this.selfCheck = selfCheck;
        this.remote = remote;
    }

    /** 供 DTO 回傳給前端的衍生顯示值（<b>不入庫</b>），讓錯誤訊息能指名 remote。 */
    public String remoteName() {
        return remote;
    }

    // ===== 1. 子路徑正規化與驗證 =====

    /**
     * 正規化 Drive 子路徑：去頭尾空白、<b>只剝結尾</b> {@code /}；空字串 → {@code null}（未設定）。
     *
     * <p><b>不套用任何預設值</b>——Drive 沒有「合理的預設目錄」，猜錯的代價是把檔案倒進使用者
     * 雲端硬碟的非預期位置。開頭的 {@code /} 刻意不在這裡剝除，由 {@link #validateSubpath} 擋成 400。
     */
    public String normalizeSubpath(String subpath) {
        if (subpath == null) return null;
        String sub = subpath.trim();
        while (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);
        return sub.isEmpty() ? null : sub;
    }

    /**
     * 驗證 Drive 子路徑；違反一律擲 {@link IllegalArgumentException}（{@code GlobalExceptionHandler} → 400）。
     *
     * <p>三條規則的理由各不相同，不可混為一談：
     * <ul>
     *   <li><b>開頭 {@code /} 回 400 而非默默剝掉</b>——讓使用者知道只能填相對子路徑，勝過默默改寫語意。
     *       注意：<b>先剝再檢查會讓這個分支永遠不可達</b>。</li>
     *   <li><b>擋 {@code ..} 不是在防目錄跳脫</b>——實測 rclone 對 Drive remote 不做路徑正規化，
     *       {@code ..} 被當字面目錄名（{@code rclone lsd "remote:x/.."} 回 {@code directory not found}、exit 3）。
     *       擋它是為了避免在使用者 Drive 上建出字面名為 {@code ..} 的怪目錄。<b>必須逐段比對</b>，
     *       不可用 {@code contains("..")}——那會誤擋合法目錄名如 {@code a..b}。</li>
     *   <li><b>擋 {@code :} 是防 remote 切換</b>——rclone 取第一個 {@code :} 之前為 remote 名。
     *       雖然「remote 前綴與子路徑拼在同一個 argv 元素」已使注入在結構上被擋住，
     *       但該保證依賴實作細節，日後重構拆開即失效，故多擋一層。</li>
     * </ul>
     *
     * <p><b>不得</b>用 {@code Path.of(...).normalize().startsWith(base)}：Drive 路徑不是本機檔案系統路徑，
     * 在容器內 resolve 沒有意義，且結果會隨容器 OS 的路徑分隔符改變。
     */
    public void validateSubpath(String subpath) {
        if (subpath == null || subpath.isBlank()) return; // 未設定；是否必填由呼叫端依 enabled 判斷
        if (subpath.startsWith("/")) {
            throw new IllegalArgumentException("Drive 目標資料夾須為相對路徑，不可以 / 開頭：" + subpath);
        }
        if (subpath.contains(":")) {
            throw new IllegalArgumentException("Drive 目標資料夾不可含冒號（會被 rclone 解讀為切換 remote）：" + subpath);
        }
        for (String seg : subpath.split("/")) {
            if ("..".equals(seg)) {
                throw new IllegalArgumentException("Drive 目標資料夾不可含 .. 路徑段：" + subpath);
            }
        }
    }

    /**
     * 一次做完「正規化 ＋ 驗證 ＋ 啟用時必填」，回傳可直接入庫的值。
     *
     * @param enabled 使用者要求的啟用狀態（已由呼叫端解析 null＝不變更）
     */
    public String normalizeAndValidate(String subpath, boolean enabled) {
        String sub = normalizeSubpath(subpath);
        validateSubpath(sub);
        if (enabled && (sub == null || sub.isBlank())) {
            throw new IllegalArgumentException("已啟用 Google Drive 同步時，必須指定 Drive 目標資料夾");
        }
        return sub;
    }

    // ===== 2. 權限判定 =====

    /**
     * 該 owner 是否可使用 Drive 同步。<b>唯一的權限判定入口，且 fail-closed。</b>
     *
     * <p><b>判準是 {@code isConfiguredAdmin(email)}，不是 {@code role == ADMIN}。</b>
     * {@code role} 是 DB 欄位、可以有多列 ADMIN；第二位若被升為 ADMIN，其報表照樣會進到 rclone remote
     * 擁有者的 Drive——外流語意不變、只是母體變小。{@code isConfiguredAdmin} 比對 {@code ADMIN_EMAIL}，
     * 全庫唯一一人。
     *
     * <p><b>查不到使用者、email 為空，一律回 {@code false}</b>：找不到 owner 卻照傳，等於把資料送去一個
     * 無法歸屬的帳號。
     *
     * <p>背景排程也用這一支（無 request context 時 {@code TenantFilterAspect} 直接 return，
     * 且 {@code AppUser} 未套 {@code @Filter}，故 {@code findById} 不會被 fail-closed 成空）。
     */
    public boolean isDriveAllowedFor(Long ownerUserId) {
        if (ownerUserId == null) return false;
        AppUser user = userRepo.findById(ownerUserId).orElse(null);
        if (user == null) {
            log.warn("Drive 權限判定：查無 owner={}，一律視為不允許（fail-closed）", ownerUserId);
            return false;
        }
        String email = user.getEmail();
        if (email == null || email.isBlank()) return false;
        return userAdminService.isConfiguredAdmin(email);
    }

    // ===== 3. 設定更新：解析未送出的欄位、驗證、權限 =====

    /**
     * {@link #resolveUpdate} 的結果：{@code enabled}／{@code subpath} 可直接寫回設定列的兩個欄位，
     * {@code selfCheckWarning} <b>刻意不入庫</b>。
     *
     * <p><b>警告字串為什麼不寫 {@code gdrive_last_status}</b>（Task 247.3.4）：那一欄的語意是「上次<b>上傳</b>
     * 的結果」，九個前端頁面都把它直接標成「上次上傳」顯示。把自檢結果寫進去有兩個實害——會永久覆蓋昨晚
     * 真正上傳成功的落點與大小，且 {@code gdrive_last_run_at} 會被寫成一個根本沒發生過上傳的時刻。
     * 故只走當次回應的 {@code gdriveSelfCheckWarning} 欄位。
     */
    public record DriveSettings(boolean enabled, String subpath, String selfCheckWarning) {}

    /**
     * 把「請求送來的 Drive 兩欄」與「設定列現值」合成可入庫的值，一次做完 null 解析、正規化、驗證、
     * 啟用時必填與權限判定。<b>八個匯出頁的 {@code PUT} 一律走這一支</b>，不得各自實作第二份規則。
     *
     * <p><b>{@code requestedEnabled} 為包裝型別的意義</b>：{@code null}＝「整個欄位沒送」＝不變更，
     * 與「明確送 false」語意不同。舊版前端、或只想改本機路徑／排程時間的呼叫端，不該把使用者已開啟的
     * Drive 開關靜默關掉。{@code requestedSubpath} 為 {@code null} 時同理保留既有值——關掉再開回來不必重填。
     *
     * <p><b>權限只在「明確要求啟用」時檢查</b>（403 早於 400，權限問題比輸入錯誤更根本）。既有值已是
     * {@code true} 而本次請求沒送該欄時<b>刻意不檢查</b>：否則 {@code ADMIN_EMAIL} 換人後，該使用者連
     * 本機輸出路徑與排程時間都會被 403 鎖死——而那兩項本來就開放給所有使用者（Requirement 39／49）。
     * 這不是漏洞：真正決定「會不會上傳」的是每一輪產檔前的 {@link #isDriveAllowedFor} 複驗。
     *
     * <p><b>「本次把開關從 false 翻成 true」時另做一次本地自檢</b>（Task 247.3.1(a)）：只做這一處，
     * 八個匯出頁就全部有——見 {@link #selfCheckWarningOnEnable}。
     */
    public DriveSettings resolveUpdate(Long ownerUserId, Boolean requestedEnabled, String requestedSubpath,
                                       boolean currentEnabled, String currentSubpath) {
        if (Boolean.TRUE.equals(requestedEnabled) && !isDriveAllowedFor(ownerUserId)) {
            throw new AdminRequiredException("Google Drive 同步僅限主要管理者啟用");
        }
        boolean enabled = requestedEnabled == null ? currentEnabled : requestedEnabled;
        String sub = requestedSubpath == null ? currentSubpath : normalizeSubpath(requestedSubpath);
        validateSubpath(sub);
        if (enabled && (sub == null || sub.isBlank())) {
            throw new IllegalArgumentException("已啟用 Google Drive 同步時，必須指定 Drive 目標資料夾");
        }
        // 自檢排在驗證之後：輸入本身就不合法時該回 400，不必也不該讓使用者同時收到兩種訊息。
        return new DriveSettings(enabled, sub, selfCheckWarningOnEnable(currentEnabled, enabled));
    }

    /**
     * 「使用者在這次請求把 Drive 開關從 false 翻成 true」時做一次<b>純本地</b>可用性自檢，
     * 回傳可直接放進當次回應 {@code gdriveSelfCheckWarning} 的警告字串；一切正常回 {@code null}。
     *
     * <p><b>為什麼一定要有「啟用當下」這一處</b>（Task 247.3.2）：Drive 輸出上線 recreate 的當下，九張表的
     * {@code gdrive_enabled} 全為 false，啟動自檢的前置條件會判定「全庫無人啟用」而整個跳過；使用者接著在 UI
     * 打開開關，<b>不需要也不會 recreate 容器</b>。實測九列的 {@code updated_at} 全部晚於上線那次 recreate——
     * 只做啟動自檢的話，2026-07-28 那次事故從頭到尾不會有任何一次自檢執行。
     *
     * <p><b>true→true 不重複觸發</b>：那是「只改資料夾」或「只改排程時間」的儲存，每次都跑等於白付成本。
     *
     * <p><b>只做 L1＋L2，不做 L3</b>（Task 247.3.3）：L3 走 {@code rclone lsd}、逾時上限 20 秒，同步做會讓
     * 使用者按下儲存後乾等，而「探測慢」恰恰等於「Drive 有問題」，體感就是儲存卡死；且
     * {@code TradingRadarExportScheduleService.saveSetting} 與 {@code CrawlerExportPathService.update}
     * 在 {@code @Transactional} 內，會把 20 秒的外部行程呼叫包進交易、佔住連線。
     * L3 涵蓋的 403／remote 打錯會在該頁第一次實際上傳時寫進 {@code gdrive_last_status}（既有機制），
     * 每次服務啟動時也會再探一次。
     *
     * <p><b>自檢結果絕不讓儲存變成非 2xx</b>：使用者必須能先把設定存起來再去修授權，否則唯一的修正入口
     * 被自己鎖死（同 {@code absolutePathOrNull} 的理由）。{@link GdriveSelfCheck#checkLocal} 契約上不擲例外，
     * 這裡也不另包 try/catch 假裝有防護。
     *
     * @param currentEnabled 設定列的現值（本次請求寫入<b>之前</b>）
     * @param newEnabled     本次請求解析後要寫入的值
     */
    public String selfCheckWarningOnEnable(boolean currentEnabled, boolean newEnabled) {
        if (!newEnabled || currentEnabled) return null;
        // 沿用狀態欄的截斷機制：措辭風格與 skipped(...) 一致，長度上限也一致，前端顯示區塊才不會被撐爆。
        return truncate(selfCheck.checkLocal(remote));
    }

    // ===== 4. 同步（best-effort，回傳狀態字串） =====

    /**
     * 同步結果。{@code status} 一律有值（成功／逾時／失敗／跳過皆須寫入狀態欄）；
     * {@code path} 只有實際上傳成功時才有值，供 run-now 回報落點。
     */
    public record SyncResult(String status, String path) {}

    /**
     * 本機檔已寫好之後的 Drive 同步：<b>絕不擲例外</b>，回傳可直接寫進 {@code gdrive_last_status} 的字串。
     *
     * <p>呼叫端只需在 {@code gdriveEnabled} 為真時呼叫，並把 {@code status} 與 {@code gdrive_last_run_at}
     * 寫回設定列——<b>連「跳過」也要寫</b>，否則狀態欄會停在上一次的成功、顯示過期的好消息（這兩個欄位
     * 存在的唯一理由就是「上傳目的地不在使用者眼前，不回報就是靜默失敗」）。
     *
     * <p><b>每一輪都重驗 owner 權限</b>：背景排程是背景執行緒、逐列跑 {@code findAll()}，沒有
     * {@code CurrentUserContext}，{@code PUT} 當下的檢查在此完全不適用。若某列在啟用後 owner 被改、
     * DB 值被 psql 直改繞過 API、或 {@code ADMIN_EMAIL} 換人，沒有這一步就會照樣上傳。
     *
     * <p><b>逾時與確定性失敗的措辭刻意不同</b>：底層判準是「行程未在時限內 exit」而<b>不是</b>
     * 「檔案沒上去」。實測發生過假失敗——狀態記「逾時」但 Drive 端檔案完整、與本機逐 byte 相同。
     * 故逾時寫「Drive 端可能已完成」，只有 rclone 非零退出才寫「失敗」。
     *
     * @param localFile 本機已寫成功的檔案；{@code null}＝本輪沒產檔（例：當日無快照），寫成跳過而非上傳舊檔
     */
    public SyncResult syncQuietly(Long ownerUserId, String subpath, Path localFile) {
        if (localFile == null) {
            return new SyncResult(skipped("本輪未產生本機檔案"), null);
        }
        if (!isDriveAllowedFor(ownerUserId)) {
            log.warn("Drive 同步跳過：owner={} 非主要管理者", ownerUserId);
            return new SyncResult(skipped("owner 非主要管理者"), null);
        }
        String sub;
        try {
            sub = normalizeSubpath(subpath);
            validateSubpath(sub);
        } catch (IllegalArgumentException e) {
            return new SyncResult(skipped("Drive 目標資料夾不合法：" + e.getMessage()), null);
        }
        if (sub == null) {
            return new SyncResult(skipped("未設定 Drive 目標資料夾"), null);
        }

        String destFileName = localFile.getFileName().toString();
        try {
            String dest = rcloneClient.copyTo(remote, localFile, sub, destFileName);
            long size = localFile.toFile().length();
            return new SyncResult(truncate("成功：" + dest + "（" + size + " bytes）"), dest);
        } catch (RcloneClient.RcloneTimeoutException e) {
            log.error("Drive 上傳逾時：{} → {}:{}", localFile, remote, sub, e);
            return new SyncResult(
                    truncate("逾時（" + e.getTimeoutSec() + " 秒）：Drive 端可能已完成，請於下一輪確認"), null);
        } catch (RcloneClient.RcloneRateLimitedException e) {
            // 第三類狀態：設定與授權都正確，只是這一刻 Drive API 額度滿了，下一輪排程會自動重試成功。
            // 寫成「失敗」會讓使用者去排查一個自己會好的狀況（本機那一份已經寫成功了）。
            log.warn("Drive 上傳遇 API 速率限制，下一輪重試：{} → {}:{}", localFile, remote, sub);
            return new SyncResult(truncate("暫時未上傳：Drive API 達每分鐘查詢上限，下一輪排程會自動重試"), null);
        } catch (Exception e) {
            log.error("Drive 上傳失敗：{} → {}:{}", localFile, remote, sub, e);
            return new SyncResult(truncate("失敗：" + e.getMessage()), null);
        }
    }

    /** 已啟用但本輪未實際上傳時的狀態字串（例：本機寫檔失敗、子路徑不合法、owner 非主要管理者）。 */
    public String skipped(String reason) {
        return truncate("跳過：" + reason);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_STATUS_LEN ? s : s.substring(0, MAX_STATUS_LEN - 1) + "…";
    }

    // ===== 5. Drive 目錄列舉 =====

    /**
     * 列出 Drive 上該子路徑的子目錄（唯讀）。由 {@code ExportScheduleService.browseGdrive} 遷入。
     *
     * <p><b>全庫唯一的 Drive 目錄列舉實作</b>——八個匯出頁的 BFF 各有自己的 passthrough（一頁一 BFF），
     * 但都指向同一支 business 端點、同一份實作。
     *
     * <p>remote 未設定／授權失效時讓 {@link RcloneClient.RcloneUnavailableException} 往上拋
     * （由 {@code GlobalExceptionHandler} 轉 503 ＋ 可讀訊息），<b>刻意不吞成空清單</b>——
     * 空樹會被使用者誤讀為「Drive 裡沒有資料夾」而以為自己選錯位置。
     */
    public List<String> listDirsSorted(String subpath) {
        return rcloneClient.listDirs(remote, subpath == null ? "" : subpath).stream()
                .filter(n -> !n.startsWith(".")) // 與本機一致：隱藏 dotfiles
                .sorted(Comparator.comparing(String::toLowerCase))
                .toList();
    }

    /**
     * 目錄瀏覽用的子路徑正規化（<b>比 {@link #normalizeSubpath} 寬鬆</b>）。
     *
     * <p>瀏覽會<b>剝除開頭</b>的 {@code /}，而儲存設定時開頭 {@code /} 直接回 400——這個不對稱沿用
     * 本專案本機選擇器的既有設計：瀏覽是唯讀導覽、對輸入寬容；儲存是寫入設定、要求明確。
     * {@code ..} 段與 {@code :} 兩條規則兩邊相同。
     */
    public String normalizeBrowseSubpath(String subpath) {
        String sub = subpath == null ? "" : subpath.trim();
        while (sub.startsWith("/")) sub = sub.substring(1);
        while (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);
        if (!sub.isEmpty()) {
            if (sub.contains(":")) {
                throw new IllegalArgumentException("瀏覽路徑不可含冒號（會被 rclone 解讀為切換 remote）：" + subpath);
            }
            for (String seg : sub.split("/")) {
                if ("..".equals(seg)) {
                    throw new IllegalArgumentException("瀏覽路徑不可含 .. 路徑段：" + subpath);
                }
            }
        }
        return sub;
    }
}
