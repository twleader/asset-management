package com.steven.assets.repository;

import com.steven.assets.model.ExportScheduleSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/** Owner-scoped access to independent asset and gain export schedules. */
public interface ExportScheduleSettingRepository extends JpaRepository<ExportScheduleSetting, Long> {

    List<ExportScheduleSetting> findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(Long ownerUserId);

    long countByOwnerUserId(Long ownerUserId);

    Optional<ExportScheduleSetting> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /** Lock only the parent row; the eager market collection is loaded separately by Hibernate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExportScheduleSetting s where s.id = :id and s.ownerUserId = :owner")
    Optional<ExportScheduleSetting> findLockedByIdAndOwnerUserId(@Param("id") Long id,
                                                                @Param("owner") Long ownerUserId);
}
