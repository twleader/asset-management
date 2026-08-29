package com.steven.assets.service;

import java.nio.file.Path;

/** DB 備份 crypt remote 的單一安全入口；每個 workflow 只取得一個 verified session。 */
interface BackupRemoteClient {

    <T> T withVerifiedSession(SessionWork<T> work);

    @FunctionalInterface
    interface SessionWork<T> {
        T apply(Session session);
    }

    interface Session {
        void upload(Path localFile, String folder);

        void download(String folder, String filename);

        String listJson(String folder);

        DeleteResult delete(String folder, String filename);
    }

    enum DeleteResult {
        DELETED,
        ALREADY_MISSING
    }
}
