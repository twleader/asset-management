package com.steven.assets.service;

/**
 * DB 備份專用的 remote 暫時不可用錯誤。
 *
 * <p>這個例外刻意只保存已清洗、固定格式的操作指引，不保留 rclone stderr 或原始 cause，
 * 避免 OAuth token、client secret 與 crypt password 經 stack trace 或 ProblemDetail 外洩。</p>
 */
public final class BackupRemoteUnavailableException extends RuntimeException {

    private final boolean resultUncertain;

    private static final String RETRY_GUIDANCE =
            "系統會依 source fingerprint 自動 reload，DB 備份不需要 recreate business-services；修正後直接重試即可。";

    private BackupRemoteUnavailableException(String message) {
        this(message, false);
    }

    private BackupRemoteUnavailableException(String message, boolean resultUncertain) {
        super(message, null, false, false);
        this.resultUncertain = resultUncertain;
    }

    public boolean resultUncertain() {
        return resultUncertain;
    }

    public static BackupRemoteUnavailableException uncertainUpload(String filename) {
        return new BackupRemoteUnavailableException(
                "Google Drive 備份上傳結果未定（" + filename + "）；已停止自動重試並保留本地來源。"
                        + "請先唯讀對帳 exact 檔名與索引，再決定後續處理。", true);
    }

    public static BackupRemoteUnavailableException configUnavailable() {
        return new BackupRemoteUnavailableException(
                "DB 備份的 host rclone 設定或獨立部署釘選不可用。請確認 GoogleDriver: 的完整 drive scope、" +
                        "gdrive-crypt: 的加密設定與 My Drive 根層 asset-management-backup 資料夾 ID，並於 host 核實帳號身分。" + RETRY_GUIDANCE);
    }

    public static BackupRemoteUnavailableException authenticationUnavailable() {
        return new BackupRemoteUnavailableException(
                "Google Drive 備份授權目前不可用。請在 host 執行 rclone config reconnect GoogleDriver:，" +
                "選擇已核實的正確帳號並授予完整 Drive scope，再確認 My Drive 根層 asset-management-backup 目錄。" + RETRY_GUIDANCE);
    }

    public static BackupRemoteUnavailableException rootMissing() {
        return new BackupRemoteUnavailableException(
                "找不到 My Drive 根層唯一的 asset-management-backup，或目的地資料夾身分與獨立釘選不符；" +
                        "系統不會自動建立這個 root。請在 host 核實帳號、完整 Drive scope 與 provider ID。" +
                        RETRY_GUIDANCE);
    }

    public static BackupRemoteUnavailableException remoteUnavailable() {
        return new BackupRemoteUnavailableException(
                "Google Drive 備份服務目前不可用。請確認 GoogleDriver: 授權與 My Drive 根層 " +
                        "asset-management-backup 的 provider ID 與加密備份可讀後直接重試。" + RETRY_GUIDANCE);
    }
}
