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

        void download(String folder, String filename, Path target);

        /** Plaintext ISO date directory names directly below the pinned crypt root. */
        String listDateDirectoriesJson();

        String listJson(String dateFolder);

        /** Exact decrypted archive header; only PGDMP proves a dynamic deletion anchor. */
        String readHeader(String folder, String filename);

        /** Return true only when an independent exact-path remote probe proves absence. */
        boolean proveAbsent(String folder, String filename);

        DeleteResult delete(String folder, String filename);
    }

    enum DeleteResult {
        DELETED,
        ALREADY_MISSING
    }
}
