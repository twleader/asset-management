package com.steven.assets.service;

import java.nio.file.Path;
import java.util.List;

/**
 * Google Drive 輸出用的 rclone 操作（Requirement 50 / Task 241；上傳於 Requirement 51 / Task 242 加入）。
 *
 * <p><b>刻意只有「列目錄」與「上傳」兩個方法，不實作任何刪除既有 Drive 檔案的程式路徑。</b>
 * 本功能取得的是使用者 Drive 的完整讀寫權（{@code scope = drive}，這是能寫進使用者<b>手動建立</b>的
 * 既有目錄所必要的——{@code drive.file} 下 rclone 連該目錄都列不出來）。為把完整權限的實際使用面
 * 縮到最小，backend 端<b>只實作 {@code lsjson --dirs-only} 與 {@code copyto}</b>。
 *
 * <p>抽成介面是為了讓測試能替換掉、不實際連網。
 */
public interface RcloneClient {

    /**
     * 列出 {@code <remote>:<subpath>} 下的子目錄名稱（唯讀，不建立／不刪除／不修改 Drive 內容）。
     *
     * @param remote  rclone remote 名稱（不含冒號）
     * @param subpath 相對子路徑，可為空字串（＝remote 根）
     * @return 子目錄名稱清單（未排序，由呼叫端決定順序）
     * @throws RcloneUnavailableException remote 未設定／授權失效／設定檔不可用——呼叫端須轉為可讀訊息，
     *                                    <b>不得</b>回空清單（空樹會被使用者誤讀為「Drive 裡沒有資料夾」）
     * @throws RcloneTimeoutException     未在時限內結束
     * @throws RuntimeException           其他執行失敗（rclone 非零退出）
     */
    List<String> listDirs(String remote, String subpath);

    /**
     * 上傳單一檔案並覆寫同名檔，回傳實際落點（{@code <remote>:<subpath>/<destFileName>}）。
     *
     * <p>用 {@code copyto}（而非 {@code copy}）以明確指定目的檔名。<b>Drive 側刻意不做本機那套
     * 「tmp ＋ atomic rename」</b>：Drive API 未完成的上傳不會產生可見檔案，且 tmp＋改名會需要
     * delete 權限的程式路徑，與本介面「不實作刪除」的自我約束衝突。
     *
     * @throws RcloneUnavailableException remote 未設定／授權失效／設定檔不可用
     * @throws RcloneTimeoutException     未在時限內結束——<b>注意這不等於「檔案沒上去」</b>，
     *                                    判準是行程是否 exit；呼叫端的狀態措辭須與確定性失敗分開
     * @throws RuntimeException           其他執行失敗（rclone 非零退出）
     */
    String copyTo(String remote, Path localFile, String subpath, String destFileName);

    /** remote 未設定或授權失效——與「該目錄下真的沒有子目錄」語意不同，必須讓使用者分辨得出來。 */
    class RcloneUnavailableException extends RuntimeException {
        public RcloneUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Google Drive API 的每分鐘查詢配額被打滿（{@code rateLimitExceeded}）。
     *
     * <p><b>第三類狀態，與「逾時」及「確定性失敗」都不同</b>：設定、授權、路徑全都正確，只是這一刻
     * 額度用完了；<b>稍候重試即會成功</b>，使用者不必做任何排查。若混進「失敗：」那一類，狀態欄會出現
     * 一整段 Google API 的 JSON，讀的人無從判斷該不該去修設定。
     *
     * <p><b>為什麼會發生：</b>實測 {@code [GDriveOutput]} 未設 {@code client_id}，即使用 rclone 內建的
     * 共用 OAuth client（錯誤訊息中的 {@code project_number:202264815644}）——全球所有沒設定自己
     * client_id 的 rclone 使用者共用同一個 Google Cloud 專案的配額，故會間歇性撞到限制，與本專案的
     * 呼叫量無關。<b>根治方式是建立專屬的 OAuth client_id 並寫入 rclone 設定</b>（需使用者在 GCP Console
     * 操作，程式不持有也不建立 client secret）。
     *
     * <p><b>刻意不靠放寬重試來掩蓋</b>：{@code RCLONE_LIMITS} 的低重試次數是 Task 241 實測的結論
     * （重試放大會讓單次上傳膨脹到數分鐘），為了這個暫時性錯誤放寬，代價是每一次真實故障都變慢。
     */
    class RcloneRateLimitedException extends RuntimeException {
        public RcloneRateLimitedException(String message) {
            super(message);
        }
    }

    /**
     * 行程未在時限內結束。
     *
     * <p><b>與「操作失敗」刻意分開的型別</b>：判準是行程是否 exit，<b>不是</b>檔案有沒有上去。
     * 實測發生過假失敗——狀態記「逾時（45 秒）」但 Drive 端檔案完整、與本機檔逐 byte 相同。
     * 呼叫端據此寫出「Drive 端可能已完成」而非「失敗」的狀態字串。
     */
    class RcloneTimeoutException extends RuntimeException {
        private final long timeoutSec;

        public RcloneTimeoutException(String message, long timeoutSec) {
            super(message);
            this.timeoutSec = timeoutSec;
        }

        public long getTimeoutSec() {
            return timeoutSec;
        }
    }
}
