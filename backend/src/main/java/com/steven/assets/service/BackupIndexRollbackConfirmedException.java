package com.steven.assets.service;

/** The index transaction completed rollback; no commit acknowledgement is ambiguous. */
public final class BackupIndexRollbackConfirmedException extends RuntimeException {
    private final boolean beforeTransaction;

    private BackupIndexRollbackConfirmedException(String message, boolean beforeTransaction) {
        super(message, null, false, false);
        this.beforeTransaction = beforeTransaction;
    }

    public static BackupIndexRollbackConfirmedException forFilename(String filename) {
        return new BackupIndexRollbackConfirmedException("備份已上傳，但索引交易已確認回滾（" + filename
                + "）；已保留遠端 exact 檔案與本地來源供唯讀對帳，不會自動重試或刪除。", false);
    }

    public static BackupIndexRollbackConfirmedException forFilenameBeforeTransaction(String filename) {
        return new BackupIndexRollbackConfirmedException("備份已上傳，但索引交易尚未執行（" + filename
                + "）；已保留遠端 exact 檔案與本地來源供唯讀對帳，不會自動重試或刪除。", true);
    }

    public static BackupIndexRollbackConfirmedException forOperation(String operation) {
        return new BackupIndexRollbackConfirmedException("備份" + operation
                + "交易已確認回滾；不會自動重試，請檢查索引與遠端狀態。", false);
    }

    public static BackupIndexRollbackConfirmedException beforeTransaction(String operation) {
        return new BackupIndexRollbackConfirmedException("備份" + operation
                + "交易尚未執行；未變更索引，請確認服務狀態後重試。", true);
    }

    public boolean beforeTransaction() { return beforeTransaction; }
}
