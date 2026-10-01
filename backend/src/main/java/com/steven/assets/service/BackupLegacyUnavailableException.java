package com.steven.assets.service;

/** The requested backup has no verified object in the date based destination. */
public final class BackupLegacyUnavailableException extends RuntimeException {
    public BackupLegacyUnavailableException() {
        super("這份備份尚未搬移或未列入可還原索引；未下載、未執行 pg_restore。", null, false, false);
    }
}
