package com.steven.assets.service;

/**
 * DB 備份專用的 remote 暫時不可用錯誤。
 *
 * <p>這個例外刻意只保存已清洗、固定格式的操作指引，不保留 rclone stderr 或原始 cause，
 * 避免 OAuth token、client secret 與 crypt password 經 stack trace 或 ProblemDetail 外洩。</p>
 */
public final class BackupRemoteUnavailableException extends RuntimeException {

    private static final String RETRY_GUIDANCE =
            "系統會依 source fingerprint 自動 reload，DB 備份不需要 recreate business-services；修正後直接重試即可。";

    private BackupRemoteUnavailableException(String message) {
        super(message, null, false, false);
    }

    public static BackupRemoteUnavailableException configUnavailable() {
        return new BackupRemoteUnavailableException(
                "DB 備份的 host rclone 設定目前不可讀或無法安全安裝。請確認 host 的 rclone config 可用，" +
                        "並確認 GoogleDriver: 指向含既有 GoogleDriver:asset-management-backup 的正確帳號。" + RETRY_GUIDANCE);
    }

    public static BackupRemoteUnavailableException authenticationUnavailable() {
        return new BackupRemoteUnavailableException(
                "Google Drive 備份授權目前不可用。請在 host 執行 rclone config reconnect GoogleDriver:，" +
                        "選擇含既有 GoogleDriver:asset-management-backup 的正確帳號並確認該 root 存在。" + RETRY_GUIDANCE);
    }

    public static BackupRemoteUnavailableException rootMissing() {
        return new BackupRemoteUnavailableException(
                "找不到 exact GoogleDriver:asset-management-backup，可能 reconnect 時選錯 Google 帳號；" +
                        "系統不會自動建立這個 root。請在 host 重新連線 GoogleDriver: 並選擇含既有備份樹的正確帳號。" +
                        RETRY_GUIDANCE);
    }

    public static BackupRemoteUnavailableException remoteUnavailable() {
        return new BackupRemoteUnavailableException(
                "Google Drive 備份服務目前不可用。請確認 GoogleDriver: 授權與 exact " +
                        "GoogleDriver:asset-management-backup root 後直接重試。" + RETRY_GUIDANCE);
    }
}
