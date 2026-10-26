package com.steven.assets.repository;

import com.steven.assets.model.RealizedGainExportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/** Owner-scoped access to independent asset and gain export schedules. */
public interface RealizedGainExportScheduleRepository extends JpaRepository<RealizedGainExportSchedule, Long> {

    List<RealizedGainExportSchedule> findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(Long ownerUserId);

    long countByOwnerUserId(Long ownerUserId);

    Optional<RealizedGainExportSchedule> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /** Lock only the parent row; the eager market collection is loaded separately by Hibernate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RealizedGainExportSchedule s where s.id = :id and s.ownerUserId = :owner")
    Optional<RealizedGainExportSchedule> findLockedByIdAndOwnerUserId(@Param("id") Long id,
                                                                @Param("owner") Long ownerUserId);
}
