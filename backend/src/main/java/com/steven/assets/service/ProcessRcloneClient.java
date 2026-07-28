package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@link RcloneClient} 的 {@code ProcessBuilder} 實作（Requirement 50 / Task 241）。
 *
 * <p>本類別只使用 {@code [GDriveOutput]} section。它與 DB 備份／還原（{@code BackupService}，用
 * {@code [gdrive-crypt]}）**共用同一份 config 檔**——這是使用者明示的決定；已知取捨是所有掛到該檔的
 * 服務都讀得到備份的 OAuth refresh token 與 crypt 解密密碼。
 *
 * <p><b>config 必須先複製到可寫路徑再用</b>：rclone 會在 OAuth token 到期時自動續期並寫回 config，
 * 對唯讀掛入的檔案寫回失敗會使 rclone exit non-zero——症狀是「原本好好的功能在數十分鐘後開始整批失敗」。
 * 實測 token 幾乎每次呼叫都已過期、每次都會 refresh 並寫回，故可寫副本是必要條件而非保險。
 * 本類別的副本與 {@code BackupService} 的刻意分開（各自 {@code /tmp} 檔），兩者各自續期 access token
 * 互不干擾；作法與該 service 既有模式一致（compose 層的 {@code RCLONE_CONFIG} 只是預設值，
 * per-process 覆寫才是實際生效值）。
 */
@Slf4j
@Component
public class ProcessRcloneClient implements RcloneClient {

    /**
     * 唯讀掛入的 rclone config（docker-compose mount）。單一檔含備份用的 {@code [GoogleDriver]}／
     * {@code [gdrive-crypt]} 與輸出用的 {@code [GDriveOutput]}；本類別只使用後者。
     */
    private static final Path CONFIG_SOURCE = Path.of("/etc/rclone/rclone.conf");
    /** rclone 實際使用的 config 路徑（可寫，供 token 自動續期寫回）。 */
    private static final Path CONFIG_WRITABLE = Path.of("/tmp/rclone-output.conf");

    /**
     * 互動式端點的逾時上限。<b>刻意不沿用 {@code BackupService.PROCESS_TIMEOUT_SEC} 的 300 秒</b>——
     * 那是為 pg_dump／整庫上傳設的，而目錄列舉是使用者點樹節點的懶載入，300 秒等於點一下卡五分鐘。
     *
     * <p><b>這個上限只是最後的保險</b>，真正的時間控制在 {@link #RCLONE_LIMITS}。
     */
    private static final long LIST_TIMEOUT_SEC = 20;

    /**
     * 上傳逾時上限。比列目錄寬（要傳檔案），但仍遠小於 {@code BackupService} 的 300 秒。
     *
     * <p><b>這個上限只是最後的保險</b>，真正的時間控制在 {@link #RCLONE_LIMITS}——外層砍掉 rclone
     * 只會讓上傳失敗，不會讓它變快。
     */
    private static final long UPLOAD_TIMEOUT_SEC = 45;

    /**
     * rclone 自身的重試與逾時上限。<b>沒有這組參數，外層的 {@link #LIST_TIMEOUT_SEC} 會治錯地方。</b>
     *
     * <p>rclone 預設 {@code --low-level-retries 10} ＋ {@code --timeout 5m}：遇到任何暫時性的 Drive API
     * 延遲，單次呼叫就會被重試放大到數十秒甚至數分鐘，而外層 process timeout 砍掉它只會讓操作失敗、
     * 並不會讓它變快。實測（2026-07-27）ext 端上傳因此出現「45 秒逾時失敗」與「無參數時超過 180 秒
     * 未結束」，而帶上這組參數後同一個上傳只需 2.4 秒。目錄列舉走同一個 Drive API，風險相同。
     */
    private static final List<String> RCLONE_LIMITS = List.of(
            "--retries", "1",
            "--low-level-retries", "3",
            "--contimeout", "10s",
            "--timeout", "30s");

    /**
     * rclone 對「目錄不存在」的 stderr 特徵字串（實測 v1.72／v1.73）。
     *
     * <p>這與「remote 不可用」是<b>不同的情境</b>：目錄不存在只代表該層沒有內容，
     * 與本機 {@code browse} 遇到不存在目錄時回空清單的行為一致（{@code Files.isDirectory} 為 false）。
     * 若讓它落到裸 {@code RuntimeException} → {@code Exception} 兜底 → <b>500</b>，
     * 使用者展開一個剛被刪掉的資料夾就會看到「伺服器錯誤」。
     * {@code BackupService} 對 {@code lsjson} 也是同樣處理。
     */
    private static final String DIR_NOT_FOUND = "directory not found";

    /** rclone 找不到 remote 時 stderr 的特徵字串（實測 v1.73）。 */
    private static final String NO_SECTION = "didn't find section in config file";

    /**
     * Drive API 速率限制在 rclone stderr 中的特徵字串（實測樣本見
     * {@link RcloneClient.RcloneRateLimitedException}）。
     *
     * <p>三個並列而非只留一個：Google 對不同觸發途徑回不同字樣——專案層每分鐘上限回
     * {@code rateLimitExceeded} ＋ {@code RATE_LIMIT_EXCEEDED}，單一使用者層回
     * {@code userRateLimitExceeded}。<b>比對一律小寫化</b>，因 {@code reason} 欄與 {@code ErrorInfo}
     * 的大小寫不同。
     */
    private static final List<String> RATE_LIMIT_MARKERS = List.of(
            "ratelimitexceeded",
            "rate_limit_exceeded",
            "userratelimitexceeded");

    private final ObjectMapper objectMapper;

    /** config 來源是否就緒；啟動時判定一次，缺檔不阻止服務啟動（見 {@link #initConfig()}）。 */
    private volatile boolean configReady;

    public ProcessRcloneClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 啟動時把唯讀掛入的 config 複製到可寫位置。
     *
     * <p>來源不存在時<b>只 warn、不擲例外</b>：使用者可能根本沒啟用 Drive 輸出，這個新功能不該讓整個
     * business-services（含正在運作的 DB 備份）起不來。
     */
    @PostConstruct
    void initConfig() {
        try {
            if (!Files.exists(CONFIG_SOURCE)) {
                log.warn("找不到 rclone 設定：{}（Google Drive 目錄瀏覽將不可用；"
                        + "請確認 docker-compose 已把 host 的 ~/.config/rclone/rclone.conf 掛入該路徑）",
                        CONFIG_SOURCE);
                return;
            }
            Files.copy(CONFIG_SOURCE, CONFIG_WRITABLE, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(CONFIG_WRITABLE,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException e) {
                log.debug("設定 {} 權限失敗（非 POSIX 檔案系統？）：{}", CONFIG_WRITABLE, e.getMessage());
            }
            configReady = true;
            log.info("Drive 輸出用 rclone 設定已複製至可寫路徑：{}", CONFIG_WRITABLE);
        } catch (IOException e) {
            log.error("初始化 Drive 輸出用 rclone 設定失敗：{}", e.getMessage(), e);
        }
    }

    @Override
    public List<String> listDirs(String remote, String subpath) {
        requireConfig();
        String target = remote + ":" + (subpath == null ? "" : subpath);
        List<String> cmd = new ArrayList<>(List.of("rclone", "lsjson", "--dirs-only", target));
        cmd.addAll(RCLONE_LIMITS);
        String json;
        try {
            json = exec(cmd, remote, LIST_TIMEOUT_SEC, "目錄列舉");
        } catch (RcloneUnavailableException | RcloneTimeoutException e) {
            throw e; // remote 不可用／逾時要讓使用者看到原因，不可吞
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains(DIR_NOT_FOUND)) {
                // 目錄不存在＝該層沒有子目錄，與本機 browse 的行為一致；不是伺服器錯誤。
                return List.of();
            }
            throw e;
        }
        return parseDirNames(json);
    }

    /**
     * 上傳單一檔案（{@code rclone copyto}）。
     *
     * <p><b>逾時用 {@link #UPLOAD_TIMEOUT_SEC} 而非列目錄的 20 秒</b>，且錯誤訊息的操作名稱是「上傳」——
     * 這個字串會原樣寫進使用者可見的 {@code gdrive_last_status}，沿用「目錄列舉」會讓使用者一頭霧水。
     */
    @Override
    public String copyTo(String remote, Path localFile, String subpath, String destFileName) {
        requireConfig();
        String dest = remote + ":" + (subpath == null || subpath.isBlank() ? "" : subpath + "/") + destFileName;
        List<String> cmd = new ArrayList<>(List.of("rclone", "copyto", localFile.toString(), dest));
        cmd.addAll(RCLONE_LIMITS);
        exec(cmd, remote, UPLOAD_TIMEOUT_SEC, "上傳");
        return dest;
    }

    private void requireConfig() {
        if (!configReady) {
            throw new RcloneUnavailableException(
                    "Google Drive 尚未設定：找不到 " + CONFIG_SOURCE + "。請確認 rclone 設定檔已掛入並重新部署。");
        }
    }

    /**
     * 執行 rclone 並回 stdout；失敗一律轉成帶有可讀訊息的例外。
     *
     * <p><b>timeout 與操作名稱必須由呼叫端傳入</b>：列目錄是互動式端點（20 秒），上傳是背景寫入（45 秒），
     * 且失敗訊息會寫進使用者可見的狀態欄，硬編任一方都會讓另一方顯示錯誤的字樣。
     */
    private String exec(List<String> cmd, String remote, long timeoutSec, String opName) {
        Path outFile = null;
        Path errFile = null;
        try {
            outFile = Files.createTempFile("rclone-out-", ".json");
            errFile = Files.createTempFile("rclone-err-", ".log");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // per-process 覆寫：compose 層的 RCLONE_CONFIG 指向備份用主檔，這裡必須改指 output 專用可寫副本。
            pb.environment().put("RCLONE_CONFIG", CONFIG_WRITABLE.toString());
            pb.redirectOutput(outFile.toFile());
            pb.redirectError(errFile.toFile());

            Process p = pb.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                // 刻意用專屬型別：判準是「行程沒在時限內 exit」，不是「操作沒完成」。
                // 實測發生過 Drive 端檔案其實完整、狀態卻記失敗的假失敗，呼叫端據此改寫措辭。
                throw new RcloneTimeoutException(
                        "Google Drive " + opName + "逾時（" + timeoutSec + " 秒）", timeoutSec);
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String err = Files.readString(errFile).strip();
                if (err.contains(NO_SECTION)) {
                    throw new RcloneUnavailableException(
                            "Google Drive remote「" + remote + "」尚未設定或授權失效，請先完成 rclone 授權設定。");
                }
                if (isRateLimited(err)) {
                    // 設定與授權都正確，只是這一刻 Drive API 額度用完。原始 stderr 是一整段 Google API 的
                    // JSON，直接吐給使用者會讓人以為要去修設定，故換成一句可行動的說明。
                    // 建議「設專屬 client_id」時必須連帶講兩個前提，否則使用者照做就會重演 2026-07-28 的事故：
                    // 自訂 client 把配額與 API 啟用狀態綁到該 client 所屬的 GCP 專案（該專案未啟用 Drive API
                    // 就是 403），而重新授權若不帶 prompt=consent，Google 不會重發 refresh_token。
                    // 這句會原樣寫進使用者可見的 gdrive_last_status。
                    throw new RcloneRateLimitedException(
                            "Google Drive API 目前達到每分鐘查詢上限，請稍候幾秒再試（設定與授權皆正常）。"
                            + "此為 rclone 內建共用憑證的已知限制，根治方式是為 rclone 設定專屬的 OAuth client_id；"
                            + "設定後必須同時到該 GCP 專案啟用 Drive API，"
                            + "且授權時須帶 `prompt=consent` 才會取得 refresh_token。");
                }
                throw new RuntimeException("Google Drive " + opName + "失敗 (exit=" + exit + "): " + err);
            }
            return Files.readString(outFile);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Google Drive " + opName + "執行錯誤: " + e.getMessage(), e);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
        }
    }

    /**
     * stderr 是否為 Drive API 速率限制。
     *
     * <p><b>刻意只認速率限制的特徵字，不把所有含 403 的錯誤都算進來</b>：真正的權限不足
     * （remote 被撤權、目標資料夾無寫入權）同樣是 403，但那是使用者必須處理的確定性失敗，
     * 若一併寫成「稍候再試」，使用者會一直等一個永遠不會好的狀態。
     */
    private static boolean isRateLimited(String stderr) {
        if (stderr == null || stderr.isBlank()) return false;
        String lower = stderr.toLowerCase();
        return RATE_LIMIT_MARKERS.stream().anyMatch(lower::contains);
    }

    /** 從 {@code lsjson} 輸出取 {@code Name}（已加 {@code --dirs-only}，故不需再濾 {@code IsDir}）。 */
    private List<String> parseDirNames(String json) {
        List<String> names = new ArrayList<>();
        if (json == null || json.isBlank()) return names;
        try {
            for (JsonNode node : objectMapper.readTree(json)) {
                JsonNode name = node.get("Name");
                if (name != null && !name.asText().isBlank()) names.add(name.asText());
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 rclone lsjson 結果失敗: " + e.getMessage(), e);
        }
        return names;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 暫存檔清理失敗不影響結果
        }
    }
}
