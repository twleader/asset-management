package com.steven.assets.repository;

import com.steven.assets.model.BackupRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BackupRecordRepository extends JpaRepository<BackupRecord, Long> {

    List<BackupRecord> findAllByOrderByModifiedAtDesc();

    Optional<BackupRecord> findByFolderAndFilename(String folder, String filename);

    /**
     * 台股 daily 的完成判定只接受同一台北日、同一類型的 durable index row。
     * 不以同日美股、手動或 weekly 記錄代替，避免排程與開機自癒重複建立 snapshot。
     */
    boolean existsByFolderAndFilenameStartingWith(String folder, String filenamePrefix);

    /**
     * 用於 rotation：取得指定資料夾下、排除「自救點」（auto_pre_restore=true）後依 modifiedAt 由新到舊排序。
     * 自救點需永久保留（還原前的最後一根稻草），不參與輪替。
     */
    List<BackupRecord> findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc(String folder);
}
