package com.steven.assets.externalmaterials.service;

import java.nio.file.Path;

/**
 * 把已寫好的本機檔案上傳一份副本到 Google Drive（Requirement 50 / Task 241）。
 *
 * <p><b>只有「上傳」一個動作。</b>本功能取得的是使用者 Drive 的完整讀寫權（{@code scope = drive}，
 * 這是能寫進使用者<b>手動建立</b>的既有目錄所必要的）。為把完整權限的實際使用面縮到最小，
 * 這裡<b>只實作 {@code copyto}</b>，<b>不實作任何刪除既有 Drive 檔案的程式路徑</b>。
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

    /** rclone 設定是否就緒；未就緒時呼叫端應跳過上傳並記錄原因，而非反覆嘗試。 */
    boolean isAvailable();

    /** 目前使用的 remote 名稱（供記錄與錯誤訊息指名）。 */
    String remoteName();
}
