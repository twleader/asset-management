package com.steven.assets.repository;

import com.steven.assets.model.CommodityExportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/** Owner-scoped access to independent asset and gain export schedules. */
public interface CommodityExportScheduleRepository extends JpaRepository<CommodityExportSchedule, Long> {

    List<CommodityExportSchedule> findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(Long ownerUserId);

    long countByOwnerUserId(Long ownerUserId);

    Optional<CommodityExportSchedule> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /** Lock the exact owner-scoped independent row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CommodityExportSchedule s where s.id = :id and s.ownerUserId = :owner")
    Optional<CommodityExportSchedule> findLockedByIdAndOwnerUserId(@Param("id") Long id,
                                                                @Param("owner") Long ownerUserId);
}
