package com.steven.assets.repository;

import com.steven.assets.model.IndexExportScheduleTime;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IndexExportScheduleTimeRepository extends JpaRepository<IndexExportScheduleTime, Long> {
    List<IndexExportScheduleTime> findAllByScheduleIdOrderByRunHourAscRunMinuteAscIdAsc(Long scheduleId);
}
