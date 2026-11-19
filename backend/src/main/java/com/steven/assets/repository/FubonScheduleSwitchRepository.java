package com.steven.assets.repository;

import com.steven.assets.model.FubonScheduleSwitch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 富邦唯讀排程執行開關（Requirement 218／Task 612）。全域設定，無租戶過濾。 */
public interface FubonScheduleSwitchRepository extends JpaRepository<FubonScheduleSwitch, String> {

    List<FubonScheduleSwitch> findAllByOrderByScheduleKeyAsc();
}
