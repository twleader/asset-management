package com.steven.assets.externalmaterials.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    private final String remote;

    /** config 來源是否就緒；啟動時判定一次，缺檔不阻止服務啟動。 */
    private volatile boolean configReady;

    public ProcessGdriveUploader(@Value("${GDRIVE_OUTPUT_REMOTE:GDriveOutput}") String remote) {
        this.remote = remote;
    }

    /**
     * 啟動時把唯讀掛入的 config 複製到可寫位置。
     *
     * <p>來源不存在時<b>只 warn、不擲例外</b>：使用者可能根本沒啟用 Drive 輸出，這個附加功能絕不該
     * 讓爬蟲服務起不來——本機輸出與 {@code news_headline} 入庫才是本服務的主要職責。
     */
    @PostConstruct
    void initConfig() {
        try {
            if (!Files.exists(CONFIG_SOURCE)) {
                log.warn("找不到 rclone 設定：{}（Google Drive 同步將被跳過；"
                        + "請確認 docker-compose 已把 host 的 ~/.config/rclone/rclone.conf 掛入該路徑，"
                        + "且其中含 [{}] section）", CONFIG_SOURCE, remote);
                return;
            }
            Files.copy(CONFIG_SOURCE, CONFIG_WRITABLE, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(CONFIG_WRITABLE,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException e) {
                log.debug("設定 {} 權限失敗：{}", CONFIG_WRITABLE, e.getMessage());
            }
            configReady = true;
            log.info("Drive 輸出用 rclone 設定已複製至可寫路徑：{}（remote={}）", CONFIG_WRITABLE, remote);
        } catch (IOException e) {
            log.error("初始化 Drive 輸出用 rclone 設定失敗：{}", e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
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
        if (!configReady) {
            throw new IllegalStateException("rclone 設定不可用（" + CONFIG_SOURCE + " 不存在）");
        }
        String dest = remote + ":" + (subpath == null || subpath.isBlank() ? "" : subpath + "/") + destFileName;
        List<String> cmd = new ArrayList<>(List.of("rclone", "copyto", localFile.toString(), dest));
        cmd.addAll(RCLONE_LIMITS);
        exec(cmd);
        return dest;
    }

    private void exec(List<String> cmd) {
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
                throw new RuntimeException("rclone 上傳逾時（" + UPLOAD_TIMEOUT_SEC + " 秒）");
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String err = Files.readString(errFile).strip();
                if (err.contains(NO_SECTION)) {
                    throw new RuntimeException("Drive remote「" + remote + "」尚未設定或授權失效");
                }
                throw new RuntimeException("rclone 上傳失敗 (exit=" + exit + "): " + err);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("rclone 上傳執行錯誤: " + e.getMessage(), e);
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
}
