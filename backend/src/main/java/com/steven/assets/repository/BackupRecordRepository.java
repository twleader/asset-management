package com.steven.assets.repository;

import com.steven.assets.model.BackupRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BackupRecordRepository extends JpaRepository<BackupRecord, Long> {

    List<BackupRecord> findAllByOrderByModifiedAtDesc();

    Optional<BackupRecord> findByFolderAndFilename(String folder, String filename);

    /**
     * 用於 rotation：取得指定資料夾下、排除「自救點」（auto_pre_restore=true）後依 modifiedAt 由新到舊排序。
     * 自救點需永久保留（還原前的最後一根稻草），不參與輪替。
     */
    List<BackupRecord> findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc(String folder);
}
