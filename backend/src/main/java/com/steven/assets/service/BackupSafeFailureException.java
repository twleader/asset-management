package com.steven.assets.service;

/**
 * 備份子行程或流程期限的失敗，訊息必為固定文字、不含 stderr／路徑／DB 內容，可安全寫入日誌。
 *
 * <p>仍是 {@link IllegalStateException} 的子類，既有只攔 {@code IllegalStateException} 或
 * {@code RuntimeException} 的路徑行為不變；只有排程備份入口額外辨識本型別，
 * 以便把固定原因帶進日誌。任何來源不明的例外都不得改用此型別。</p>
 */
final class BackupSafeFailureException extends IllegalStateException {

    BackupSafeFailureException(String message) {
        super(message);
    }
}
