package com.steven.assets.externalmaterials.service;

import java.nio.file.Path;

/**
 * 把已寫好的本機檔案上傳一份副本到 Google Drive（Requirement 50 / Task 241）。
 *
 * <p><b>只有兩種操作：{@code copyto}（寫入指定子路徑）與 {@code lsd}（唯讀列目錄）。</b>
 * 本功能取得的是使用者 Drive 的完整讀寫權（{@code scope = drive}，這是能寫進使用者<b>手動建立</b>的
 * 既有目錄所必要的）。為把完整權限的實際使用面縮到最小，這裡<b>仍不實作任何刪除既有 Drive 檔案的
 * 程式路徑</b>。
 *
 * <p>{@code lsd} 是 Requirement 52 / Task 247.4.2 明示放寬的：啟動自檢的 L3 需要一個唯讀探測來抓出
 * 「Drive API 未啟用（403）／remote 名稱打錯／授權已撤銷」這三種只有真的連線才知道的狀況。
 * 探測刻意走本介面而非在自檢元件內另跑一份 {@code ProcessBuilder}——否則逾時、stderr 解析與
 * {@code RCLONE_LIMITS} 會變成兩份各自漂移的邏輯。
 *
 * <p>抽成介面是為了讓測試能注入替身、不實際連網（見 Task 241.10.3）。
 */
public interface GdriveUploader {

    /**
     * 上傳（覆寫）單一檔案至 {@code <remote>:<subpath>/<destFileName>}。
     *
     * @param localFile    已寫成功的本機檔案絕對路徑
     * @param subpath      Drive 上的相對子路徑（呼叫端須已驗過合法性）
     * @param destFileName 目的檔名（本專案固定 {@code public_info_<date>.json}，不開放設定）
     * @return 成功時的目的地描述，供寫入 {@code gdrive_last_status}
     * @throws RuntimeException 上傳失敗、逾時、rclone 不可用——一律由呼叫端 catch 成 best-effort，
     *                          <b>絕不</b>讓它影響本機檔案或 {@code news_headline} 入庫
     */
    String upload(Path localFile, String subpath, String destFileName);

    /**
     * 唯讀探測：對 {@code <remote>:} 跑一次 {@code rclone lsd}，確認這條路徑當下真的走得通
     * （Requirement 52 / Task 247.4.2）。
     *
     * <p><b>只列 remote 根目錄、不碰任何檔案</b>，故不受「只實作 copyto」那條自我約束限制。
     * 成功時無回傳值——自檢只在乎「有沒有失敗」，列到什麼目錄不是它要判斷的事，也不該寫進 log。
     *
     * @throws RuntimeException 探測失敗（Drive API 未啟用的 403／remote 不存在／授權已撤銷／逾時）；
     *                          訊息盡量原樣帶 rclone 的 stderr——403 那段本身就含「去哪個 console
     *                          啟用 Drive API」的連結，是使用者最需要的一行
     */
    void probe();

    /** rclone 設定是否就緒；未就緒時呼叫端應跳過上傳並記錄原因，而非反覆嘗試。 */
    boolean isAvailable();

    /** 目前使用的 remote 名稱（供記錄與錯誤訊息指名）。 */
    String remoteName();
}
