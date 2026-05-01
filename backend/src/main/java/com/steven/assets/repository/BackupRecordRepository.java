package com.steven.assets.repository;

import com.steven.assets.model.BackupRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BackupRecordRepository extends JpaRepository<BackupRecord, Long> {

    List<BackupRecord> findAllByOrderByModifiedAtDesc();

    Optional<BackupRecord> findByFolderAndFilename(String folder, String filename);
}
