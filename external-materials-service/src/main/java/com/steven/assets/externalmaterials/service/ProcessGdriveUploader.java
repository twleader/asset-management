package com.steven.assets.externalmaterials.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@link GdriveUploader} 的 {@code ProcessBuilder} 實作（Requirement 50 / Task 241）。
 *
 * <p><b>本容器與 business-services 共用同一份 rclone config</b>（單一 {@code rclone.conf}，內含
 * 備份用的 {@code [GoogleDriver]}／{@code [gdrive-crypt]} 與輸出用的 {@code [GDriveOutput]}）。
 * 這是使用者明示的決定；<b>已知取捨</b>：本服務是全 stack 唯一對外打第三方的（TWSE／NASDAQ／
 * FinMind／新聞爬蟲）、攻擊面最大，共用一檔等於它也讀得到 {@code [gdrive-crypt]} 的 crypt 解密密碼、
 * 具備解密整庫財務備份的能力。原設計為分離兩份以避免此擴權，改為共用是為省下第二份檔案的維護與
 * 搬機成本（兩個 remote 實測為同一個 Google 帳號，分離的實際收益本就有限）。
 * 取捨全文與復原方式見 {@code spec/steering/tech.md} §4；掛載設定見 {@code docker-compose.yml}。
 * <b>本類別只使用 {@code [GDriveOutput]}。</b>
 *
 * <p><b>config 必須先複製到可寫路徑再用</b>：rclone 會在 OAuth token 到期時自動續期並寫回 config，
 * 對唯讀掛入的檔案寫回失敗會使 rclone exit non-zero——症狀是「原本好好的上傳在數十分鐘後開始整批失敗」。
 * 容器以非 root 的 {@code appuser} 執行，故副本落在 {@code /tmp}。
 */
@Slf4j
@Component
public class ProcessGdriveUploader implements GdriveUploader {

    /**
     * 唯讀掛入的 rclone config（docker-compose mount）。
     *
     * <p>單一檔含備份用的 {@code [GoogleDriver]}／{@code [gdrive-crypt]} 與輸出用的
     * {@code [GDriveOutput]}。共用一檔為使用者明示的決定；已知取捨是本服務（全 stack 唯一對外打
     * 第三方者）因此也讀得到備份的 crypt 解密密碼。本類別只使用 {@code [GDriveOutput]}。
     */
    private static final Path CONFIG_SOURCE = Path.of("/etc/rclone/rclone.conf");
    /** rclone 實際使用的 config 路徑（可寫，供 token 自動續期寫回）。 */
    private static final Path CONFIG_WRITABLE = Path.of("/tmp/rclone-output.conf");

    /**
     * 上傳逾時上限。<b>必須小於 {@code NewsPoller} ticker 的 60 秒週期。</b>
     *
     * <p>理由不是「避免阻塞排程執行緒」（抓取早已在獨立執行緒），而是 {@code runGuarded} 的
     * {@code AtomicBoolean running}（warmup 與排程輪共用）在持有期間會讓後續輪次被整個跳過。
     * 該旗標的持有時間主要由抓取決定，本上限的作用只是<b>不再額外拉長</b>它。
     *
     * <p><b>這個上限只是最後的保險，不是主要的時間控制手段</b>——見 {@link #RCLONE_LIMITS}。
     */
    private static final long UPLOAD_TIMEOUT_SEC = 45;

    /**
     * rclone 自身的重試與逾時上限。<b>沒有這組參數，外層的 {@link #UPLOAD_TIMEOUT_SEC} 會治錯地方。</b>
     *
     * <p>實測（2026-07-27）：容器內上傳 122 KB 到既有目錄只需 <b>2.4 秒</b>，但 rclone 的預設值是
     * {@code --low-level-retries 10} ＋ {@code --timeout 5m}——一旦遇到任何暫時性的 Drive API 延遲，
     * 單次上傳就會被重試放大到數十秒甚至數分鐘。實際後果：20:30 那輪排程以
     * 「rclone 上傳逾時（45 秒）」失敗，另一次無參數的手動測試跑超過 180 秒仍未結束。
     * 外層 process timeout 砍掉它只會讓上傳失敗，並不會讓它變快。
     *
     * <p>放大效應之所以容易觸發，有兩個實測到的固定成本：（1）<b>token 每次都過期</b>，每次呼叫都要
     * 先 refresh 並寫回 config；（2）<b>未設 {@code root_folder_id}</b>，rclone 每次都得從 Drive 根目錄
     * 逐層查找子路徑（本專案的目標是兩層）。兩者本身只花數秒，但都是會被重試乘上去的網路往返。
     */
    private static final List<String> RCLONE_LIMITS = List.of(
            "--retries", "1",              // 整體不重試：每輪爬蟲都會重新產檔重傳，那才是天然重試
            "--low-level-retries", "3",    // 單一 API 呼叫的重試（預設 10 → 3）
            "--contimeout", "10s",
            "--timeout", "30s");

    /** rclone 找不到 remote 時 stderr 的特徵字串（實測 v1.73）。 */
    private static final String NO_SECTION = "didn't find section in config file";

    /**
     * OAuth client_id/secret 組合被 Google 拒絕時 rclone stderr 的特徵字串（Task 443／Requirement 160；
     * 2026-09-17 實測樣本：{@code couldn't fetch token: invalid_client: if you're using your own client
     * id/secret, make sure they're properly set up following the docs}）。比照 {@code ProcessRcloneClient}
     * （backend 模組）的同名常數，成因是 {@code [GDriveOutput]} 的 {@code client_id} 與 app 登入／Blog 發布共用同一組
     * {@code GOOGLE_CLIENT_ID}，但 {@code client_secret} 與 {@code .env} 現行值不同步。
     */
    private static final String INVALID_CLIENT = "invalid_client";

    private final String remote;

    /**
     * config 來源目前是否就緒；由 {@link #reloadIfSourceChanged} 於每次呼叫時維護，非啟動時判定一次
     * （Task 443／Requirement 160；沿用 Task 388 {@code ProcessBackupRemoteClient} 已驗證的機制，
     * 範圍已依本類別需求縮減）。
     */
    private volatile boolean configReady;

    /** 最後一次成功安裝的 source SHA-256 雜湊；用於判斷 source 內容是否變動（Task 443／Requirement 160）。 */
    private volatile String lastLoadedSourceFingerprint;

    public ProcessGdriveUploader(@Value("${GDRIVE_OUTPUT_REMOTE:GDriveOutput}") String remote) {
        this.remote = remote;
    }

    /**
     * 啟動時先嘗試安裝一份 writable snapshot（見 {@link #reloadIfSourceChanged}），讓一開機就有可用設定。
     *
     * <p>來源不存在時<b>只 warn、不擲例外</b>：使用者可能根本沒啟用 Drive 輸出，這個附加功能絕不該
     * 讓爬蟲服務起不來——本機輸出與 {@code news_headline} 入庫才是本服務的主要職責。<b>Task 443 之後
     * 這不再是唯一機會</b>——{@link #isAvailable()}／{@link #ensureConfigCurrent()} 之後每次呼叫都會自動重試。
     */
    @PostConstruct
    void initConfig() {
        reloadIfSourceChanged(CONFIG_SOURCE, CONFIG_WRITABLE);
        if (!configReady) {
            log.warn("找不到 rclone 設定：{}（Google Drive 同步將被跳過，之後每次操作會自動重試；"
                    + "請確認 docker-compose 已把 host 的 ~/.config/rclone/rclone.conf 掛入該路徑，"
                    + "且其中含 [{}] section）", CONFIG_SOURCE, remote);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Task 443／Requirement 160 之後，本方法每次呼叫都會先觸發一次 reload 嘗試</b>：它是
     * {@code NewsPoller.syncToGdrive()} 與 {@link GdriveSelfCheck#runStartupCheck()} 在呼叫
     * {@link #upload}／{@link #probe} 之前的唯一前置閘門，若本方法不觸發 reload，「source 從未成功
     * 載入過、後續變成可讀」這個情境就永遠不會被撿到——見 {@code spec/tasks/t443_*.md} 443.2。
     * 成本與其他路徑一致（一次檔案讀取＋SHA-256，內容未變時不做任何安裝動作）。
     */
    @Override
    public boolean isAvailable() {
        reloadIfSourceChanged(CONFIG_SOURCE, CONFIG_WRITABLE);
        return configReady;
    }

    @Override
    public String remoteName() {
        return remote;
    }

    /**
     * 以 {@code rclone copyto} 上傳並覆寫同名檔。
     *
     * <p>用 {@code copyto}（而非 {@code copy}）以明確指定目的檔名。<b>Drive 側刻意不做本機那套
     * 「tmp ＋ atomic rename」</b>：Drive API 未完成的上傳不會產生可見檔案，逾時被強殺後最壞情況是
     * 該次上傳沒發生、而非留下半截檔；且 tmp＋改名會需要 delete 權限的程式路徑，與「只用 copyto」的
     * 自我約束衝突。殘餘風險由下一輪覆寫消除。
     */
    @Override
    public String upload(Path localFile, String subpath, String destFileName) {
        ensureConfigCurrent();
        String dest = remote + ":" + (subpath == null || subpath.isBlank() ? "" : subpath + "/") + destFileName;
        List<String> cmd = new ArrayList<>(List.of("rclone", "copyto", localFile.toString(), dest));
        cmd.addAll(RCLONE_LIMITS);
        exec(cmd, "上傳");
        return dest;
    }

    /**
     * 唯讀探測（Requirement 52 / Task 247.4.2）：{@code rclone lsd <remote>:}。
     *
     * <p>刻意<b>沿用 {@link #exec} 與 {@link #RCLONE_LIMITS}</b>，不另寫一份 {@code ProcessBuilder}：
     * 逾時、stderr 解析與「找不到 section」的判定只該有一份。逾時同樣用 {@link #UPLOAD_TIMEOUT_SEC}——
     * 探測只跑在啟動自檢的背景 daemon 執行緒上，沒有使用者在等，不需要另一組數字。
     */
    @Override
    public void probe() {
        ensureConfigCurrent();
        List<String> cmd = new ArrayList<>(List.of("rclone", "lsd", remote + ":"));
        cmd.addAll(RCLONE_LIMITS);
        exec(cmd, "探測");
    }

    /**
     * 呼叫前先觸發一次 reload 嘗試，再判斷目前是否可用（Task 443／Requirement 160）。
     *
     * <p>取代舊的「開頭直接檢查 {@code configReady}」寫法：舊版只讀取這個「啟動時判定一次」的旗標，
     * host 端修好設定後執行中的容器不會自動生效。現在每次呼叫都先經 {@link #reloadIfSourceChanged}
     * 嘗試重新讀取來源，讀到新內容才會實際重裝；讀不到或內容未變則直接沿用既有 snapshot。
     * 訊息字面維持不變（{@code IllegalStateException("rclone 設定不可用（" + CONFIG_SOURCE + " 不存在）")}）。
     */
    private void ensureConfigCurrent() {
        reloadIfSourceChanged(CONFIG_SOURCE, CONFIG_WRITABLE);
        if (!configReady) {
            throw new IllegalStateException("rclone 設定不可用（" + CONFIG_SOURCE + " 不存在）");
        }
    }

    /**
     * 讀取 {@code source}，與 {@link #lastLoadedSourceFingerprint} 比對 SHA-256；內容不同才原子安裝為
     * {@code writable}（Task 443／Requirement 160）。與 backend 模組 {@code ProcessRcloneClient} 的
     * {@code reloadIfSourceChanged} 邏輯相同，但本服務與 backend 的 reload 狀態（{@link #lastLoadedSourceFingerprint}、
     * {@link #configReady}）完全獨立，不共用任何欄位或鎖。
     *
     * <p>機制比照已於 Task 388 驗證過的 {@code ProcessBackupRemoteClient}，範圍刻意縮小：本類別
     * 只有「上傳（覆寫具名檔）」與「唯讀探測」兩種操作，不需要 raw-root 身分守門、逐筆 durable commit
     * 或涵蓋整個 rclone 子行程生命期的鎖——見 {@code spec/tasks/t443_*.md}「範圍控制」。
     *
     * <p>讀 {@code source} 失敗（含 {@link java.nio.file.NoSuchFileException}）時回傳 {@code false}，
     * <b>不清空</b>既有 {@link #lastLoadedSourceFingerprint} 或 {@link #configReady}——沿用既有 writable
     * snapshot 繼續可用。安裝失敗（含 {@link AtomicMoveNotSupportedException}）時，舊 fingerprint 與
     * 既有 {@code writable} 內容都不得變動，回傳 {@code false}。<b>刻意不比較 {@code writable} 的
     * 內容／mtime／inode</b>：判準只看 {@code source} 的 SHA-256 是否變動。
     *
     * <p>整段用 {@code synchronized} 保護，避免多個執行緒同時 install 導致 fingerprint 更新順序錯亂；
     * <b>不延伸</b>到 {@link #exec}——那段本身透過獨立的 temp 錯誤輸出檔已是執行緒安全的。
     *
     * @param source   唯讀掛入的來源路徑（正式路徑固定為 {@link #CONFIG_SOURCE}，測試可傳暫存路徑）
     * @param writable rclone 實際讀取的可寫路徑（正式路徑固定為 {@link #CONFIG_WRITABLE}，測試可傳暫存路徑）
     * @return 是否實際安裝了新內容；{@code false} 代表「讀不到來源」或「內容未變」，呼叫端不需分辨兩者
     */
    synchronized boolean reloadIfSourceChanged(Path source, Path writable) {
        byte[] sourceBytes;
        try {
            sourceBytes = Files.readAllBytes(source);
        } catch (IOException | RuntimeException e) {
            // 讀不到來源（含 NoSuchFileException）：沿用既有 writable snapshot，不清空狀態。
            return false;
        }
        String fingerprint;
        try {
            fingerprint = sha256Hex(sourceBytes);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 是每個 JVM 都必須支援的標準演算法，這個分支理論上不會發生；
            // 發生時比照「讀不到來源」的保守處置：不動既有狀態。
            return false;
        }
        if (fingerprint.equals(lastLoadedSourceFingerprint)) {
            return false; // 內容未變，不做任何檔案動作
        }

        Path parent = writable.toAbsolutePath().getParent();
        if (parent == null) {
            return false;
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(parent, "." + writable.getFileName() + "-", ".tmp");
            Files.write(temp, sourceBytes);
            // 0600 是憑證快照的必要條件；設定失敗即終止安裝，保留舊 snapshot。
            Files.setPosixFilePermissions(temp,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.move(temp, writable, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            lastLoadedSourceFingerprint = fingerprint;
            configReady = true;
            log.info("Drive 輸出用 rclone 設定已重新載入（來源內容變動）：{} -> {}（remote={}）",
                    source, writable, remote);
            return true;
        } catch (IOException | RuntimeException e) {
            // IOException 涵蓋 AtomicMoveNotSupportedException（其子類別）：atomic move 失敗一律視為
            // 安裝失敗，不動既有 lastLoadedSourceFingerprint／writable 內容。
            log.warn("安裝新版 rclone 設定失敗，繼續沿用既有 writable snapshot：{}", e.getMessage());
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 暫存檔清理失敗不影響結果
                }
            }
        }
    }

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * 執行 rclone；失敗一律轉成帶可讀訊息的 {@link RuntimeException}。
     *
     * <p><b>{@code opName} 必須由呼叫端傳入</b>：上傳失敗的訊息會原樣寫進使用者可見的
     * {@code gdrive_last_status}，而探測失敗只進 log，兩者硬編同一個字樣會讓其中一邊自稱錯誤的操作。
     * 上傳這條路徑的訊息字面刻意維持與 Task 241 當初一字不差。
     */
    private void exec(List<String> cmd, String opName) {
        Path errFile = null;
        try {
            errFile = Files.createTempFile("rclone-err-", ".log");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            // per-process 覆寫：確保用的是可寫副本，而非唯讀掛入的來源（token 續期需寫回）。
            pb.environment().put("RCLONE_CONFIG", CONFIG_WRITABLE.toString());
            pb.redirectErrorStream(false);
            pb.redirectError(errFile.toFile());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            Process p = pb.start();
            if (!p.waitFor(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("rclone " + opName + "逾時（" + UPLOAD_TIMEOUT_SEC + " 秒）");
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String err = Files.readString(errFile).strip();
                if (err.contains(NO_SECTION)) {
                    throw new RuntimeException("Drive remote「" + remote + "」尚未設定或授權失效");
                }
                if (isInvalidClient(err)) {
                    throw new RuntimeException(
                            "Drive remote「" + remote + "」的 OAuth client_id/secret 組合已被 Google 拒絕"
                            + "（invalid_client）。此 client 與 app 登入／Blog 發布共用同一組 "
                            + "GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET，常見成因是曾在別處（如 .env）輪替過 "
                            + "client_secret 但未同步更新 rclone 設定。請確認兩邊 client_secret 一致；修正後系統會"
                            + "自動偵測設定變更並重新載入，不需要重建容器。若該 OAuth client 已在 GCP Console 被刪除"
                            + "或停用，需重新建立並更新 client_id/secret 後執行 rclone config reconnect "
                            + remote + ":。");
                }
                throw new RuntimeException("rclone " + opName + "失敗 (exit=" + exit + "): " + err);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("rclone " + opName + "執行錯誤: " + e.getMessage(), e);
        } finally {
            if (errFile != null) {
                try {
                    Files.deleteIfExists(errFile);
                } catch (IOException ignored) {
                    // 暫存檔清理失敗不影響結果
                }
            }
        }
    }

    /**
     * stderr 是否為 {@link #INVALID_CLIENT}（OAuth client_id/secret 組合被拒絕）。
     *
     * <p>與 {@code ProcessRcloneClient.isRateLimited(String)} 同一種寫法：純字串判斷、不啟動真的
     * process，供測試以反射直接呼叫。
     */
    private static boolean isInvalidClient(String stderr) {
        if (stderr == null || stderr.isBlank()) return false;
        return stderr.toLowerCase(Locale.ROOT).contains(INVALID_CLIENT);
    }
}
