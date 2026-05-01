package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Google Drive 上每一份備份檔的本地索引。
 * UI 列表 / 還原選單一律從這張表讀，避免每次都連 rclone。
 * 真正的備份檔仍存於 Google Drive，本表只是 metadata 快取。
 */
@Entity
@Table(name = "backup_record",
       uniqueConstraints = @UniqueConstraint(name = "backup_record_unique",
                                             columnNames = {"folder", "filename"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** manual / daily / weekly / monthly */
    @Column(nullable = false, length = 20)
    private String folder;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /** 備份完成時間（與 rclone ModTime 同義，皆轉為 Asia/Taipei） */
    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "auto_pre_restore", nullable = false)
    @Builder.Default
    private Boolean autoPreRestore = Boolean.FALSE;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
