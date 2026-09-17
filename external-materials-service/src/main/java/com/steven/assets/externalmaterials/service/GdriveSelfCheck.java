package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Drive 輸出的啟動自檢（Requirement 52 / Task 247.4）：在服務啟動時就把「Drive 其實上不去」講出來，
 * 而不是等使用者隔天早上發現雲端沒檔案。
 *
 * <p>2026-07-28 的事故裡本機檔案九份全部照常產生、Drive 一份都沒上去，事後追出<b>三個彼此獨立</b>的原因，
 * 三層檢查各對應一個：
 * <ul>
 *   <li><b>L1</b>：來源 config 真的<b>讀得到內容</b>（不是 {@code exists()}）。host 端 {@code rclone config}
 *       是「寫新檔＋rename」原子替換，單檔掛載下容器內的舊 inode 會 dangling，此時 {@code stat} 仍成功、
 *       只有實際讀取才 {@code ENOENT}。</li>
 *   <li><b>L2</b>：{@code [<remote>]} 的 token 有沒有 {@code refresh_token}。<b>這一層不可省</b>——缺它時
 *       access_token 到期前的那一個小時內，任何實際探測（含 L3）都會給出假的綠燈。</li>
 *   <li><b>L3</b>：真的跑一次 {@code rclone lsd}，抓 Drive API 未啟用（403）、remote 名稱打錯、授權已撤銷。</li>
 * </ul>
 *
 * <p><b>與 business 端各寫一份是刻意的</b>（Task 247.4.6）：backend 與本服務是兩個獨立 Maven 專案、無父 pom，
 * Requirement 50 已定案不為兩處 rclone 呼叫建跨服務共用 module——為數十行程式碼把三個服務的建置綁在一起，
 * 代價遠大於收益。
 *
 * <p><b>本元件不負責「啟用當下」的自檢</b>：爬蟲設定的 {@code PUT}（{@code /api/crawler-export-path}）在
 * business-services，不在 ext（Task 247.4.5）。
 *
 * <p><b>一律只寫 WARN，絕不擲例外</b>：這是純觀測功能，本機輸出與 {@code news_headline} 入庫才是本服務的
 * 主要職責，自檢沒有任何理由讓服務起不來。
 */
@Slf4j
@Component
public class GdriveSelfCheck {

    /**
     * 唯讀掛入的 rclone config 來源、以及 rclone 實際使用的可寫副本。
     *
     * <p>值必須與 {@link ProcessGdriveUploader} 的兩個常數相同——刻意複製而非把那兩個常數改成 public：
     * Task 247.4.2 只授權在 {@code GdriveUploader}／{@code ProcessGdriveUploader} 加一支唯讀探測，
     * 把私有常數的可見性放寬不在授權範圍內，且自檢本來就是「從外部看得到什麼」的角度。
     * 兩者若哪天要改路徑，改的是同一個部署決定（compose 的 {@code RCLONE_CONFIG}），會一起改。
     */
    private static final Path CONFIG_SOURCE = Path.of("/etc/rclone/rclone.conf");
    private static final Path CONFIG_WRITABLE = Path.of("/tmp/rclone-output.conf");

    /**
     * 缺 {@code refresh_token} 時附上的修法。<b>不得簡化成裸的 {@code rclone config reconnect}</b>——
     * 2026-07-28 實測那樣連續失敗兩次：Google 對「該帳號已授權過此應用程式」的重複授權不重發 refresh token，
     * 只有真的跳出同意畫面那次才會發，而 {@code prompt=consent} 是當天唯一能穩定逼出同意畫面的做法。
     * <b>換一組新的 client id/secret 也無效</b>：Google 的授權記錄綁「應用程式」（＝同一個同意畫面），
     * 不是綁 client id，同專案換 client 仍算同一個應用程式。
     */
    private static final String RECONNECT_HINT =
            "修法（2026-07-28 實測唯一有效）：rclone config reconnect %s: "
            + "--drive-auth-url \"https://accounts.google.com/o/oauth2/auth?prompt=consent\"；"
            + "裸的 rclone config reconnect %s: 會跳過同意畫面而再次拿不到 refresh_token，"
            + "換一組新的 client id/secret 同樣無效（Google 的授權記錄綁「應用程式」而非 client id）。"
            + "判斷指標：授權過程若數秒內就結束、沒讓你按「繼續／允許」，就是沒拿到。"
            // 本 client 現在會在下一次操作時自動重新載入（Task 443），故此提示不再需要特別強調
            // 「仍須重建容器」。
            + "在 host 重新授權後，下一次上傳或自檢操作即會自動偵測設定變更並重新載入，不需要重建容器。";

    /**
     * host 端改過 rclone 設定後的處置。純文件約束擋不住它再犯，故直接寫進 WARN 讓使用者照抄。
     *
     * <p><b>兩個服務名都列</b>：兩個容器掛的是同一份 host 設定，來源壞掉時本來就兩邊都要重建；
     * 只列自己會讓使用者修了一半。
     */
    private static final String RECREATE_HINT =
            "若確認 host 端 rclone 設定的『內容』已經修好，下一次操作會自動重新載入、不需要重建容器；"
            + "只有在 ~/.config/rclone 這個掛載目錄本身被整個替換（而非目錄內檔案內容變更）時，"
            + "才需要 docker compose -p asset-management up -d --force-recreate "
            + "business-services external-materials-service";

    private final GdriveUploader uploader;
    private final CrawlerExportPathQuery exportPathQuery;
    private final ObjectMapper objectMapper;
    private final Path configSource;
    private final Path configWritable;

    /**
     * {@code ObjectMapper} 由 Spring 注入（Boot 自動配置已提供）。
     * <b>不在方法內 {@code new ObjectMapper()}</b>：{@link ProcessGdriveUploader} 沒有 Jackson 相依，
     * 但那不是「本服務不該用 Jackson」的理由（{@code NewsPoller} 早就在用）。
     */
    @Autowired
    public GdriveSelfCheck(GdriveUploader uploader, CrawlerExportPathQuery exportPathQuery,
                           ObjectMapper objectMapper) {
        this(uploader, exportPathQuery, objectMapper, CONFIG_SOURCE, CONFIG_WRITABLE);
    }

    /** 測試用：把 L1／L2 指向暫存目錄的假 config，測試絕不碰 {@code /etc} 也絕不連網。 */
    GdriveSelfCheck(GdriveUploader uploader, CrawlerExportPathQuery exportPathQuery,
                    ObjectMapper objectMapper, Path configSource, Path configWritable) {
        this.uploader = uploader;
        this.exportPathQuery = exportPathQuery;
        this.objectMapper = objectMapper;
        this.configSource = configSource;
        this.configWritable = configWritable;
    }

    /**
     * 啟動後在<b>獨立 daemon 執行緒</b>上跑自檢。
     *
     * <p>用 {@link ApplicationReadyEvent} 而非 {@code @PostConstruct}：後者可能早於 backend 套用 Liquibase
     * migration，而前置條件要查 {@code crawler_export_setting}。
     *
     * <p><b>丟到獨立執行緒的理由不是 healthcheck</b>（事件發布時 web server 已在 listen），而是同一事件的
     * listener 全跑在主執行緒上、彼此沒有順序保證：L3 最壞會卡滿 45 秒，阻塞在這裡等於連帶延後
     * {@code SpringApplication.run()} 收尾與其他 listener（本服務光是 {@code ApplicationReadyEvent}
     * 就掛了七、八個 warmup）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread t = new Thread(this::runStartupCheck, "gdrive-selfcheck");
        t.setDaemon(true);
        t.start();
    }

    /**
     * L1＋L2＋L3 完整自檢。只寫 WARN、不回傳、不擲例外，前置條件自行判斷。
     *
     * <p>整段包在 catch-all 內：這不是防禦性冗餘，而是本方法會碰 DB、檔案系統與外部行程三種都可能在
     * 啟動時尚未就緒的東西，任何一個逸出的例外都會變成 daemon 執行緒的 uncaught exception stack trace，
     * 讓一則「Drive 上不去」的提示長得像服務故障。
     */
    void runStartupCheck() {
        try {
            if (!exportPathQuery.anyGdriveEnabled()) {
                // 沒人啟用 Drive 同步：這是預設狀態，不該每次啟動都留下 WARN。
                log.debug("crawler_export_setting 無任何列啟用 Drive 同步，跳過 Drive 輸出自檢");
                return;
            }
            String remote = uploader.remoteName();
            for (String warning : localWarnings(remote)) {
                log.warn("{}", warning);
            }
            if (!uploader.isAvailable()) {
                // Task 443 之後 isAvailable() 每次呼叫都會先嘗試 reloadIfSourceChanged()，本輪讀不到只代表
                // 「這一次呼叫的當下」讀不到，不再是「這個容器整個生命週期都不會同步」——下一次呼叫
                // （下一輪 warmup 或排程觸發的 upload/probe）會自動重試。啟動自檢仍值得記一筆 WARN：
                // 啟動當下讀不到，代表使用者這時候去看 Drive 會暫時看不到更新，但不代表要手動介入。
                // 2026-07-28 曾實測發生過（ext 16:02:26 讀 config 得 NoSuchFileException）：症狀偽裝成
                // 「Drive 還在收檔案」——各匯出頁的 xlsx 由 business 上傳、照常出現，只有爬蟲 JSON 停止更新；
                // 這段歷史脈絡仍保留，但「整個生命週期都不會同步」的後果已被本任務修正，不再成立。
                log.warn("Drive 輸出自檢：rclone 設定在本服務啟動當下未能就緒（configReady=false）。"
                        + "本次呼叫讀不到只代表這一刻讀不到，下一次上傳或自檢操作會自動重試"
                        + "（本機 JSON 照常產生，故症狀會偽裝成「Drive 還在收檔案」）。{}", RECREATE_HINT);
                return;
            }
            probe(remote);
        } catch (Exception e) {
            log.warn("Drive 輸出自檢未能完成（不影響服務啟動與本機檔案輸出）：{}", e.getMessage());
        }
    }

    /**
     * L1＋L2：純本地檔案讀取與 JSON 解析，毫秒級、無副作用、不打網路。
     *
     * <p>兩層各自回報而非「L1 失敗就不做 L2」：兩者的修法完全不同（一個是等下一次操作自動重新載入、
     * 極端情況才需重建容器；一個是重新授權），合成一句會讓使用者只看到其中一半。
     *
     * @return 0～2 則可直接顯示給使用者的警告；一切正常時為空清單
     */
    List<String> localWarnings(String remote) {
        List<String> warnings = new ArrayList<>();
        String l1 = checkConfigReadable();
        if (l1 != null) warnings.add(l1);
        String l2 = checkRefreshToken(remote);
        if (l2 != null) warnings.add(l2);
        return warnings;
    }

    /**
     * L1：來源 config <b>實際讀得到內容</b>。
     *
     * <p><b>判準刻意是實際讀取而非 {@code Files.exists()}</b>：host 端 {@code rclone config} 的原子替換
     * 會讓單檔掛載的 inode link count 歸零（2026-07-28 實測 {@code links=0}），此時 {@code stat} 照樣成功、
     * {@code cat} 才回 ENOENT。
     *
     * <p>Task 443 之後，{@code isAvailable()}／{@code ensureConfigCurrent()} 每次呼叫都會重新嘗試讀取
     * source（見 {@code ProcessGdriveUploader.reloadIfSourceChanged}），這一層讀不到只代表「這一刻」
     * 讀不到，不再是「這個容器整個生命週期都會跳過」。2026-07-28 16:02 曾發生過 ext 啟動時剛好撞上 host
     * 改寫 config、business 逃過而 ext 靜默失效的事故（症狀偽裝成「Drive 好像還在收檔案」，因為各匯出頁的
     * xlsx 由 business 上傳、照常出現，只有爬蟲 JSON 停止更新）——保留作為歷史脈絡，但該事故「整個生命
     * 週期不會同步」的後果已被本任務修正，不再成立。
     *
     * <p><b>絕不 dump 檔案內容</b>：該檔含 {@code [gdrive-crypt]} 的 crypt 解密密碼。
     */
    private String checkConfigReadable() {
        try (var in = Files.newInputStream(configSource)) {
            if (in.read() < 0) {
                // 0 byte 與「讀不到」是不同的後果：檔案讀得到，故 initConfig() 的
                // Files.exists()＋Files.copy() 都會成功、configReady 仍為 true，
                // 只是每次 rclone 呼叫都會因為找不到 section 而失敗。
                // 不要沿用「本次生命週期全部跳過」——那是 ENOENT／dangling 才成立的後果。
                return "Drive 輸出自檢 L1：rclone 設定 " + configSource + " 是空檔（0 byte），"
                        + "找不到任何 remote section，所有 Drive 上傳都會失敗（本機 JSON 照常產生）。";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "Drive 輸出自檢 L1 失敗：讀不到 rclone 設定 " + configSource + "（" + e.getClass().getSimpleName()
                    + "）。掛載點可能已 dangling——host 端 rclone 每次續期 OAuth token 都會原子替換這個檔案，"
                    + "單檔掛載的舊 inode 會就此失效（stat 仍成功、實際讀取才 ENOENT）。"
                    + "本次檢查當下讀不到；下一次上傳或自檢操作會自動重試。"
                    + RECREATE_HINT;
        }
    }

    /**
     * L2：解析 {@code [<remote>]} section 的 token JSON，確認 {@code refresh_token} 存在且非空。
     *
     * <p>解析對象是<b>已複製到 {@code /tmp} 的可寫副本</b>（來源可能已 dangling）。remote 名稱由呼叫端傳入，
     * 不寫死 {@code GDriveOutput}。
     *
     * <p><b>五種結果全部不擲例外</b>：含非空 refresh_token → 靜默通過；缺或為空 → WARN；token 非合法 JSON
     * → WARN（無法判定）；副本不存在 → WARN（無法判定，L1 失敗的下游狀態）；檔內無該 section → WARN
     * （無法判定，remote 名稱錯或尚未建立）。
     *
     * <p><b>log 只寫「有沒有」，絕不寫值</b>——包含不轉述 Jackson 的解析錯誤訊息，那種訊息會把來源片段
     * （也就是 token 本身）帶進去。
     */
    private String checkRefreshToken(String remote) {
        List<String> lines;
        try {
            lines = Files.readAllLines(configWritable, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return "Drive 輸出自檢 L2：無法判定 refresh_token——找不到 rclone 設定副本 " + configWritable
                    + "（L1 失敗的下游狀態，config 未能複製到可寫路徑）。";
        } catch (IOException | RuntimeException e) {
            return "Drive 輸出自檢 L2：無法判定 refresh_token——讀取 " + configWritable + " 失敗（"
                    + e.getClass().getSimpleName() + "）。";
        }

        boolean sectionFound = false;
        String token = null;
        boolean inSection = false;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("[") && line.endsWith("]")) {
                inSection = line.substring(1, line.length() - 1).strip().equals(remote);
                sectionFound |= inSection;
                continue;
            }
            if (!inSection) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            if (line.substring(0, eq).strip().equals("token")) {
                // rclone config 是 INI，token 的值是「單行」JSON 字串。
                token = line.substring(eq + 1).strip();
                break;
            }
        }

        if (!sectionFound) {
            return "Drive 輸出自檢 L2：無法判定 refresh_token——" + configWritable + " 內找不到 [" + remote
                    + "] section（GDRIVE_OUTPUT_REMOTE 設錯，或該 remote 尚未建立）。";
        }
        if (token == null || token.isBlank()) {
            return "Drive 輸出自檢 L2：[" + remote + "] 沒有 token 設定，尚未完成授權。"
                    + String.format(RECONNECT_HINT, remote, remote);
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(token);
        } catch (Exception e) {
            // 刻意不附 e.getMessage()：Jackson 的解析錯誤會把來源片段（＝token 本身）帶進訊息。
            return "Drive 輸出自檢 L2：無法判定 refresh_token——[" + remote + "] 的 token 值不是合法 JSON。";
        }
        if (node == null || !node.isObject()) {
            return "Drive 輸出自檢 L2：無法判定 refresh_token——[" + remote + "] 的 token 值不是 JSON 物件。";
        }
        JsonNode refresh = node.get("refresh_token");
        if (refresh == null || refresh.asText("").isBlank()) {
            return "Drive 輸出自檢 L2：[" + remote + "] 的 token 缺 refresh_token，"
                    + "access_token 過期後將無法自動續期（實測約 1 小時），屆時所有上傳會整批開始失敗。"
                    + String.format(RECONNECT_HINT, remote, remote);
        }
        return null;   // 有 refresh_token：靜默通過，不留任何 log
    }

    /**
     * L3：實跑一次唯讀探測 {@code rclone lsd <remote>:}。
     *
     * <p>涵蓋只有真的連線才知道的狀況：Drive API 未啟用（403）、remote 名稱打錯、授權已撤銷。
     * 失敗時盡可能原樣附上 rclone 的訊息——403 那段本身就含「去哪個 GCP console 啟用 Drive API」的連結。
     */
    private void probe(String remote) {
        try {
            uploader.probe();
        } catch (RuntimeException e) {
            log.warn("Drive 輸出自檢 L3 失敗：對 remote「{}」的唯讀探測（rclone lsd）不成功——{}。"
                    + "常見成因：該 OAuth client 所屬的 GCP 專案未啟用 Drive API（錯誤訊息內含啟用連結，"
                    + "照著開即可）、remote 名稱設錯、或授權已被撤銷、或此 client 的 client_secret 與其他共用服務"
                    + "（如 app 登入）不同步。"
                    + "注意 DB 備份走的是另一個 remote，備份正常不代表本 remote 正常。",
                    remote, e.getMessage());
        }
    }
}
