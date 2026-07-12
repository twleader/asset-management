package com.steven.assets.dto;

import lombok.Builder;

import java.time.LocalDateTime;

public class BackupDto {

    public record RestoreRequest(
            String folder,          // manual / daily / weekly / monthly
            String filename,
            String confirmation     // 必須等於「確認還原」
    ) {}

    @Builder
    public record BackupItem(
            String folder,
            String filename,
            long sizeBytes,
            LocalDateTime modifiedAt,
            boolean autoPreRestore
    ) {}

    @Builder
    public record CreateResponse(
            String filename,
            long sizeBytes,
            LocalDateTime uploadedAt
    ) {}

    @Builder
    public record SettingResponse(
            Integer manualRetention,
            Integer dailyRetention,
            Integer weeklyRetention,
            Boolean backupEnabled
    ) {}

    public record SettingRequest(
            Integer manualRetention,
            Integer dailyRetention,
            Integer weeklyRetention,
            Boolean backupEnabled
    ) {}

    @Builder
    public record SyncResponse(
            int inserted,       // 從 GDrive 補進 DB 的筆數
            int deleted,        // DB 有但 GDrive 已不存在被清除的筆數
            int total           // GDrive 上目前的總檔案數
    ) {}

    @Builder
    public record RestoreResponse(
            String status,                  // success
            String preRestoreBackup,        // 自救點檔名
            String restoredFrom             // 還原來源描述
    ) {}
}
