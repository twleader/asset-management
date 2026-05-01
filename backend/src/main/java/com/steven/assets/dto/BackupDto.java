package com.steven.assets.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class BackupDto {

    @Data
    @NoArgsConstructor
    public static class RestoreRequest {
        private String folder;          // manual / daily / weekly / monthly
        private String filename;
        private String confirmation;    // 必須等於「確認還原」
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BackupItem {
        private String folder;
        private String filename;
        private long sizeBytes;
        private LocalDateTime modifiedAt;
        private boolean autoPreRestore;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateResponse {
        private String filename;
        private long sizeBytes;
        private LocalDateTime uploadedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SettingResponse {
        private Integer manualRetention;
        private Integer dailyRetention;
        private Integer weeklyRetention;
        private Boolean backupEnabled;
    }

    @Data
    @NoArgsConstructor
    public static class SettingRequest {
        private Integer manualRetention;
        private Integer dailyRetention;
        private Integer weeklyRetention;
        private Boolean backupEnabled;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SyncResponse {
        private int inserted;       // 從 GDrive 補進 DB 的筆數
        private int deleted;        // DB 有但 GDrive 已不存在被清除的筆數
        private int total;          // GDrive 上目前的總檔案數
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RestoreResponse {
        private String status;                  // success
        private String preRestoreBackup;        // 自救點檔名
        private String restoredFrom;            // 還原來源描述
    }
}
