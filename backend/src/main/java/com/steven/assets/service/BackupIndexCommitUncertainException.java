package com.steven.assets.service;

/** The upload was verified, but the durable index commit could not be confirmed. */
public final class BackupIndexCommitUncertainException extends RuntimeException {

    private BackupIndexCommitUncertainException(String subject, boolean uploaded) {
        super("備份索引提交結果未定（" + subject + "）；"
                + (uploaded ? "已保留遠端 exact 檔案與本地來源，" : "已保留提交線索與隔離標記，")
                + "不會自動重試、刪除或輪替。請先唯讀核對 exact 檔案及 backup_record 索引。",
                null, false, false);
    }

    public static BackupIndexCommitUncertainException forFilename(String filename) {
        return new BackupIndexCommitUncertainException(filename, true);
    }

    public static BackupIndexCommitUncertainException forOperation(String operation) {
        return new BackupIndexCommitUncertainException("備份" + operation, false);
    }
}
