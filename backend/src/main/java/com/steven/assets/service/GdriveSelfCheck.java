package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/**
 * Google Drive 輸出的可用性自檢（Requirement 52 / Task 247）。
 *
 * <p><b>為什麼需要這一支：</b>2026-07-28 的事故裡九張設定表都開了 Drive 同步、本機檔九份照常產生，
 * 而 Drive 端一份都沒上去，使用者是<b>隔天早上</b>才發現。三個彼此獨立的原因（GCP 專案未啟用 Drive API、
 * token 缺 {@code refresh_token}、config 單檔掛載 dangling）都不會讓本機輸出出錯，故沒有任何既有訊號
 * 會提早浮出來。本元件把這三種已知原因在<b>啟用當下</b>與<b>每次服務啟動時</b>主動探出來。
 *
 * <p><b>本元件刻意是葉節點</b>：只注入 {@link RcloneClient}、{@link JdbcTemplate}、{@link ObjectMapper}，
 * <b>絕不注入 {@code GdriveOutputSupport}</b>。理由是硬約束而非風格偏好——{@code GdriveOutputSupport}
 * 要在 {@code resolveUpdate} 裡呼叫本元件，本元件若反向注入它就形成建構子循環依賴，
 * Spring Boot 2.6+ 預設禁止循環參照（全樹無 {@code allow-circular-references}），
 * 結果是 {@code BeanCurrentlyInCreationException}、business-services 整個起不來。
 * 故 remote 名稱<b>一律由呼叫端傳參</b>，沿用 {@link ProcessRcloneClient} 既有模式
 * （其建構子同樣不吃 remote，{@code listDirs}／{@code copyTo} 都由呼叫端傳入）；
 * 全 backend 唯一的 {@code GDRIVE_OUTPUT_REMOTE} 注入點仍只有 {@code GdriveOutputSupport}（Task 242.1.4）。
 *
 * <p><b>自檢分三層</b>：L1 來源 config 實際讀得到內容、L2 token 有 {@code refresh_token}、
 * L3 實跑一次唯讀 {@code rclone lsd}。L1／L2 是純本地檔案讀取與 JSON 解析（毫秒級、不打網路），
 * 供「啟用當下」同步呼叫；L3 會打 Drive API（最長 20 秒），只在啟動自檢裡跑。
 *
 * <p><b>一律不擲例外、一律只寫 WARN</b>：這是純觀測功能，本機輸出完全正常，
 * 把它變成啟動失敗或讓儲存設定回 5xx 都是本需求明文禁止的結果。
 */
@Slf4j
@Component
public class GdriveSelfCheck {

    /**
     * 唯讀掛入的 rclone config 來源。<b>必須與 {@link ProcessRcloneClient} 的同名常數一致</b>——
     * 那兩個常數是 private，本任務不授權改動該類別（除速率限制的字串），故此處刻意複寫一份；
     * 日後若改路徑，兩處要一起改。
     */
    private static final Path CONFIG_SOURCE = Path.of("/etc/rclone/rclone.conf");

    /** rclone 實際使用的可寫副本（{@code ProcessRcloneClient.initConfig()} 於啟動時複製）。 */
    private static final Path CONFIG_WRITABLE = Path.of("/tmp/rclone-output.conf");

    /**
     * 「全庫是否有任一列啟用 Drive 輸出」。<b>九張表一次查完</b>，少查一張就會給假綠燈。
     *
     * <p><b>每新增一個支援 Drive 的設定表都必須加進這個 UNION</b>（最新一張是 Task 254 的
     * {@code stock_alert_export_setting}）。漏加的話，只在該頁啟用 Drive 的部署遇到 token 失效或
     * remote 被改名時，重啟會判定「全庫無人啟用」而整個跳過 L3 探測、不噴任何 WARN。
     *
     * <p>沒有這個前置條件，Requirement 50／51 明訂的「既有部署升級後行為與現況一致、不要求 rclone
     * remote 存在」就被推翻——沒人啟用 Drive 的部署會每次啟動都噴 WARN。
     *
     * <p><b>刻意走 {@link JdbcTemplate} 而非 JPA repository</b>：這八張表都掛 {@code @Filter(ownerFilter)}，
     * 而這裡問的是「全庫有沒有人啟用」而不是「我的設定」；背景執行緒也沒有 request context 可依賴。
     * JdbcTemplate 天然繞過 Hibernate filter，語意明確。
     */
    private static final String ANY_ENABLED_SQL = """
            SELECT EXISTS (
              SELECT 1 FROM export_schedule_setting            WHERE gdrive_enabled
              UNION ALL SELECT 1 FROM trading_calendar_export_schedule  WHERE gdrive_enabled
              UNION ALL SELECT 1 FROM index_export_schedule             WHERE gdrive_enabled
              UNION ALL SELECT 1 FROM exchange_rate_export_schedule     WHERE gdrive_enabled
              UNION ALL SELECT 1 FROM trading_radar_export_setting      WHERE gdrive_enabled
              UNION ALL SELECT 1 FROM commodity_export_schedule         WHERE gdrive_enabled
              UNION ALL SELECT 1 FROM realized_gain_export_schedule     WHERE gdrive_enabled
              UNION ALL SELECT 1 FROM asset_transaction_export_schedule WHERE gdrive_enabled
              UNION ALL SELECT 1 FROM stock_alert_export_setting        WHERE gdrive_enabled) AS any_enabled
            """;

    private final RcloneClient rcloneClient;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** 實際使用的來源／副本路徑；正式執行一律為上面兩個常數，只有測試會替換成暫存檔。 */
    private final Path configSource;
    private final Path configWritable;

    @Autowired
    public GdriveSelfCheck(RcloneClient rcloneClient, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(rcloneClient, jdbc, objectMapper, CONFIG_SOURCE, CONFIG_WRITABLE);
    }

    /**
     * 測試用：把兩個容器內固定路徑換成暫存檔。
     *
     * <p>沒有這一支，「無人啟用時整個自檢跳過」那條測試會<b>因為錯的理由通過</b>——測試機上
     * {@code /etc/rclone/rclone.conf} 本來就不存在，L1 一樣會擋掉 rclone 呼叫，
     * 於是「零次呼叫」證明不了前置條件真的有生效。
     */
    GdriveSelfCheck(RcloneClient rcloneClient, JdbcTemplate jdbc, ObjectMapper objectMapper,
                    Path configSource, Path configWritable) {
        this.rcloneClient = rcloneClient;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.configSource = configSource;
        this.configWritable = configWritable;
    }

    // ===== 對外入口 =====

    /**
     * L1 ＋ L2 的純本地自檢（毫秒級、不打網路、無副作用），供「使用者按下儲存、把開關從 false 翻成 true」
     * 的當下同步呼叫。
     *
     * <p><b>刻意不含 L3</b>：L3 走 {@code rclone lsd}，逾時上限 20 秒，同步做會讓使用者按下儲存後乾等，
     * 而「探測慢」恰恰等於「Drive 有問題」，體感就是儲存卡死；且有兩支設定 service 的更新在
     * {@code @Transactional} 內，會把 20 秒的外部行程呼叫包進交易、佔住連線。
     *
     * @param remote rclone remote 名稱（由呼叫端傳入，本元件不持有）
     * @return 一切正常回 {@code null}；有問題回可直接顯示給使用者的警告字串
     */
    public String checkLocal(String remote) {
        String l1 = checkConfigSource(configSource);
        // L1 失敗時 L2 必然是「副本不存在」的下游噪音（副本由啟動時的複製產生），回報根因即可。
        if (l1 != null) return l1;
        return checkTokenRefreshable(configWritable, remote);
    }

    /**
     * 啟動時的完整自檢（L1 ＋ L2 ＋ L3）。<b>只寫 WARN log，不回傳、不擲例外</b>，前置條件也自行判斷。
     *
     * <p><b>整段（含 DB 前置查詢）包在單一 catch-all 內</b>——這不是防禦性冗餘：八張表在全新安裝的
     * 首次啟動時可能尚未建立（{@code BadSqlGrammarException}），自檢若讓例外逸出，
     * 會把一個純觀測功能變成啟動失敗。
     */
    public void runStartupCheck(String remote) {
        try {
            if (!anyGdriveEnabled()) {
                // 沒人啟用時完全不出聲：既有部署升級後行為須與現況一致（Requirement 50／51）。
                log.debug("Google Drive 輸出自檢：全庫無任何列啟用，略過");
                return;
            }
            // **L1 失敗時仍跑 L2，但跳過 L3。** 這個組合是刻意的，兩半各有理由：
            //
            // 跑 L2：原本「L1 失敗就整個 return」的理由是「L1 失敗 ⇒ configReady 必為 false」，
            // 但那不成立——configReady 是 @PostConstruct 判定一次的旗標，L1 檢查的是「現在」讀不讀得到，
            // 兩者取樣時間不同。最常見的組合恰恰是「啟動時複製成功（/tmp 副本在、configReady=true）、
            // 之後 host 端原子替換使來源 dangling」；此時 L2 仍能從副本判定 token 狀態，而那正是使用者要的資訊。
            //
            // 跳過 L3：來源設定已經壞了，此時打網路得到的成敗無法區分「設定問題」與「授權／API 問題」，
            // 只會讓 log 出現兩個看似無關的錯誤。ext 端以 uploader.isAvailable() 達成同一效果，
            // backend 的 RcloneClient 介面沒有等價方法，故以 L1 結果直接守門。
            String l1 = checkConfigSource(configSource);
            String l2 = checkTokenRefreshable(configWritable, remote);
            if (l1 != null) {
                log.warn("Google Drive 輸出自檢：{}", l1);
            }
            if (l2 != null) {
                log.warn("Google Drive 輸出自檢：{}", l2);
            }
            if (l1 != null) {
                return; // 見上：來源壞掉時不打網路
            }
            // L2 有問題時 L3 仍要跑：兩者涵蓋的是不同故障（token 續期 vs Drive API 未啟用／remote 打錯），
            // 且 access_token 未過期的時間窗內 L3 會通過，正是 L2 存在的理由。
            String l3 = checkReachable(remote);
            if (l3 != null) {
                log.warn("Google Drive 輸出自檢：{}", l3);
            }
        } catch (Exception e) {
            log.warn("Google Drive 輸出自檢未能完成（不影響服務啟動，本機輸出照常）：{}", e.getMessage());
        }
    }

    // ===== 前置條件 =====

    /**
     * 全庫是否有任一列啟用 Drive 輸出。
     *
     * <p><b>callback 用雙參數 {@code (rs, rowNum) -> ...}</b>：寫成單參數 {@code (rs) -> ...} 會被解析成
     * {@code ResultSetExtractor}，Spring 只呼叫一次且 {@code rs} <b>未</b> {@code next()} 定位，
     * runtime 才炸（本專案既有踩坑，見 {@code CrawlerExportPathQuery.gdriveConfig} 的註解）。
     */
    boolean anyGdriveEnabled() {
        List<Boolean> rows = jdbc.query(ANY_ENABLED_SQL, (rs, rowNum) -> rs.getBoolean(1));
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    // ===== L1：來源 config 實際讀得到內容 =====

    /**
     * L1：來源 config <b>實際讀得到內容</b>。
     *
     * <p><b>判準是實際讀取而不是 {@code Files.exists()}</b>：host 端 {@code rclone config} 是「寫新檔＋rename」
     * 的原子替換，單檔掛載下容器內舊 inode 的 link count 會歸零，此時 {@code exists()} 走 stat 仍回 true，
     * 只有真的讀才會 {@code ENOENT}。
     *
     * <p>Task 443 之後，{@code ProcessRcloneClient.ensureConfigCurrent()} 每次呼叫都會重新嘗試讀取來源
     * （見 {@link ProcessRcloneClient#reloadIfSourceChanged}），這一層讀不到只代表「現在」讀不到，
     * 不再是「該容器整個生命週期的 Drive 同步都會被跳過」。2026-07-28 事故（另一個服務照常上傳、
     * 症狀偽裝成「Drive 好像還在收檔案」）保留作為歷史脈絡，但其「整個生命週期跳過」的後果已被本任務修正。
     *
     * @param source 來源 config 路徑（參數化只為了讓測試能給替身路徑）
     */
    String checkConfigSource(Path source) {
        try (InputStream in = Files.newInputStream(source)) {
            // 只讀第一個 byte 就夠判定 inode 還活著；刻意不讀全檔——該檔含 [gdrive-crypt] 的解密密碼，
            // 任何形式的內容留存都是不必要的風險（Task 247.5.5）。
            if (in.read() < 0) {
                // 0 byte 與「讀不到」是不同的後果：檔案讀得到，故 initConfig() 的 Files.exists()＋Files.copy()
                // 都會成功、configReady 仍為 true，只是每一次 rclone 呼叫都會因為找不到 section 而失敗。
                // 不要沿用「本次生命週期全部跳過」那句——那是 ENOENT／dangling 才成立的後果。
                return "rclone 設定 " + source + " 是 0 byte，找不到任何 remote section，"
                        + "所有 Drive 上傳與目錄列舉都會失敗（本機檔案照常產生）。";
            }
            return null;
        } catch (NoSuchFileException e) {
            return configBroken(source, "檔案不存在或掛載已失效（dangling inode）");
        } catch (IOException | RuntimeException e) {
            // **RuntimeException 也要攔**：本方法的回傳值會走到 (b) 啟用當下的路徑，
            // 而呼叫端（GdriveOutputSupport）刻意不另包 try/catch（契約是「本方法不擲例外」）。
            // 只攔 checked 的 IOException，遇上 InvalidPathException／SecurityException 之類
            // 就會讓使用者的設定儲存變成 500——直接違反 Task 247.3.6「自檢失敗不得讓儲存回非 2xx」。
            // ext 端的同一層本來就是 `catch (IOException | RuntimeException e)`，此處對齊。
            return configBroken(source, "讀取失敗：" + e.getMessage());
        }
    }

    /**
     * 讀不到來源 config 時的訊息。
     *
     * <p><b>兩個服務名都要列出，不能只寫 business-services</b>：這個字串也會經 (b) 的路徑回到
     * 「爬蟲資訊查詢」頁，而該頁的實際上傳者是 {@code external-materials-service}；
     * 只叫使用者重建 business 會讓爬蟲那條路徑永遠修不好。兩個容器掛的是<b>同一份</b> host 設定，
     * 來源壞掉時本來就兩邊都要重建。
     */
    private static String configBroken(Path source, String reason) {
        return "讀不到 rclone 設定 " + source + "（" + reason + "）。"
                + "本機檔案照常產生；下一次上傳或列目錄操作會自動重試讀取設定，讀得到時即自動恢復，"
                + "不需要重建容器。"
                + "若持續讀不到（例如 ~/.config/rclone 這個掛載目錄本身被整個替換），才需要 "
                + "docker compose -p asset-management up -d --force-recreate "
                + "business-services external-materials-service";
    }

    // ===== L2：token 有沒有 refresh_token =====

    /**
     * L2：解析 {@code [<remote>]} section 的 token JSON，檢查 {@code refresh_token} 存在且非空。
     *
     * <p><b>這是唯一能在「access_token 尚未過期」期間就抓出問題的辦法</b>——實測缺 refresh_token 的
     * token 在重新授權後的一小時內，任何探測、任何上傳都會成功，L3 那類「跑一次 rclone 看通不通」的檢查
     * 在那個時間窗內只會給出假的綠燈；一小時後才整批開始失敗。
     *
     * <p>解析對象是<b>已複製到 {@code /tmp} 的副本</b>（來源可能 dangling）。
     * <b>五種輸入全部不得擲例外</b>：含非空 refresh_token → 靜默通過；缺或為空 → 警告；
     * token 值非合法 JSON → 警告（無法判定）；副本不存在 → 警告（L1 失敗的下游狀態）；
     * 檔內無該 section → 警告（remote 名稱錯或尚未建立）。
     *
     * <p><b>絕不輸出 token 的值</b>（Task 247.5.5）：只看鍵名判斷存在與否。
     *
     * @param configCopy rclone 實際使用的可寫副本（參數化只為了讓測試能給替身路徑）
     * @param remote     rclone remote 名稱；<b>不得寫死</b>，也不得在本元件注入取得
     */
    String checkTokenRefreshable(Path configCopy, String remote) {
        String content;
        try {
            // 刻意用 byte→String 而非 Files.readString：後者遇到非 UTF-8 位元組會擲 MalformedInputException，
            // 而本方法的契約是「任何輸入都不擲例外」。
            content = new String(Files.readAllBytes(configCopy), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return "無法判定 Drive 授權狀態：找不到 rclone 設定副本 " + configCopy
                    + "（通常是啟動時讀不到來源設定造成的下游狀態）。";
        } catch (IOException e) {
            return "無法判定 Drive 授權狀態：讀取 rclone 設定副本 " + configCopy + " 失敗：" + e.getMessage();
        }

        // rclone config 是 INI：section 標頭 [name]，token 值是單行 JSON 字串。
        boolean sectionFound = false;
        String tokenJson = null;
        String current = null;
        for (String line : content.split("\\R")) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) continue;
            if (s.startsWith("[") && s.endsWith("]")) {
                current = s.substring(1, s.length() - 1).strip();
                if (current.equals(remote)) sectionFound = true;
                continue;
            }
            if (!remote.equals(current)) continue;
            int eq = s.indexOf('=');
            if (eq < 0) continue;
            if ("token".equals(s.substring(0, eq).strip())) {
                tokenJson = s.substring(eq + 1).strip();
            }
        }

        if (!sectionFound) {
            return "無法判定 Drive 授權狀態：rclone 設定中找不到 [" + remote + "] section"
                    + "（remote 名稱與 GDRIVE_OUTPUT_REMOTE 不符，或尚未完成 rclone 授權設定）。";
        }
        if (tokenJson == null || tokenJson.isBlank()) {
            return "無法判定 Drive 授權狀態：[" + remote + "] 尚無 OAuth token，請先完成授權。" + reconnectHint(remote);
        }
        JsonNode token;
        try {
            token = objectMapper.readTree(tokenJson);
        } catch (Exception e) {
            // 只說「不是合法 JSON」，不輸出其內容——那是 access_token 本體。
            return "無法判定 Drive 授權狀態：[" + remote + "] 的 token 不是合法 JSON。";
        }
        if (token == null || !token.isObject()) {
            // `readTree("123")`／`readTree("null")`／`readTree("\"x\"")` 都是**合法 JSON**、不擲例外，
            // 但回的是 IntNode／NullNode／TextNode，其 get("refresh_token") 一律為 null。
            // 少了這道防護就會把「token 格式壞掉」誤報成「缺 refresh_token」，
            // 使用者照著去跑 prompt=consent 重新授權，卻修不掉真正的問題。ext 端已有同一道防護，此處對齊。
            return "無法判定 Drive 授權狀態：[" + remote + "] 的 token 值不是 JSON 物件。";
        }
        JsonNode refresh = token.get("refresh_token");
        if (refresh != null && !refresh.asText("").isBlank()) {
            return null; // 正常：靜默通過
        }
        return "[" + remote + "] 的 token 缺 refresh_token，access_token 過期後將無法自動續期"
                + "（實測約一小時後所有上傳開始失敗，而在那之前一切看起來完全正常）。" + reconnectHint(remote);
    }

    /**
     * 重新授權的修法。
     *
     * <p><b>{@code prompt=consent} 不可省</b>：Google 對「該應用程式已被此帳號授權過」的重複授權不重發
     * refresh token，判斷指標是授權過程有沒有出現同意畫面。實測裸的 {@code rclone config reconnect}
     * 連續兩次都在 3 秒內就 {@code Got code}（瀏覽器一閃而過）、都沒拿到 refresh token；
     * <b>換一組新的 client id/secret 也無效</b>——Google 的授權記錄綁的是「應用程式」（＝同一個 GCP 專案的
     * 同意畫面），不是 client id，實測換新 client 後 reconnect 兩次仍然沒有。
     * 帶上 {@code prompt=consent} 是當天唯一一次就成功的方式。
     */
    private static String reconnectHint(String remote) {
        return "修法：rclone config reconnect " + remote
                + ": --drive-auth-url \"https://accounts.google.com/o/oauth2/auth?prompt=consent\""
                + "（授權過程若數秒內就完成、沒讓你按「繼續／允許」，就是沒拿到；"
                + "換一組新的 client id/secret 無效，必須帶 prompt=consent）。"
                // Task 443 後兩支 client（ProcessRcloneClient／ProcessGdriveUploader）都會在下一次操作時
                // 自動重新載入 source，此註解與其原本解釋的「仍須重建容器」文字一併作廢。
                + "在 host 重新授權後，下一次上傳或列目錄操作即會自動偵測設定變更並重新載入，不需要重建容器。";
    }

    // ===== L3：實跑一次唯讀探測 =====

    /**
     * L3：實跑一次 {@code rclone lsd <remote>:}（唯讀）。
     *
     * <p>涵蓋只有真的連線才知道的狀況：GCP 專案未啟用 Drive API（403）、remote 名稱打錯、授權已撤銷。
     * 走既有的 {@link RcloneClient#listDirs}，測試可用介面替身，不實際連網。
     *
     * <p><b>四種例外全部要攔</b>，任何一種逸出都會讓啟動自檢變成噪音或啟動失敗。
     * 403 的 stderr 本身就含「去這個連結啟用 Drive API」的 console 網址，是使用者最需要的那一行，
     * 故裸 {@code RuntimeException} 那條<b>原樣附上訊息</b>；另兩型的 stderr 已被 {@code exec(...)}
     * 換成罐頭訊息（找不到 section／速率限制），附其可讀訊息即可。
     */
    String checkReachable(String remote) {
        try {
            rcloneClient.listDirs(remote, "");
            return null;
        } catch (RcloneClient.RcloneUnavailableException e) {
            return "Drive 探測失敗（remote「" + remote + "」不可用）：" + e.getMessage();
        } catch (RcloneClient.RcloneTimeoutException e) {
            return "Drive 探測逾時（" + e.getTimeoutSec() + " 秒）：" + e.getMessage();
        } catch (RcloneClient.RcloneRateLimitedException e) {
            return "Drive 探測遇 API 速率限制（設定與授權皆正常，本身不代表故障）：" + e.getMessage();
        } catch (RuntimeException e) {
            return "Drive 探測失敗（remote「" + remote + "」）：" + e.getMessage();
        }
    }
}
