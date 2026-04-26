package com.steven.assets.repository;

import com.steven.assets.model.BackupSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupSettingRepository extends JpaRepository<BackupSetting, Integer> {
}
