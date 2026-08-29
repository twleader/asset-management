package com.steven.assets.service;

import java.nio.file.Path;

/** PostgreSQL dump/restore 子行程邊界；與 backup rclone 的鎖與授權狀態機刻意分離。 */
interface BackupDatabaseProcess {

    void dump(Path outputFile);

    void restore(Path inputFile);
}
