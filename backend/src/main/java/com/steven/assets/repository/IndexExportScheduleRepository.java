package com.steven.assets.repository;

import com.steven.assets.model.IndexExportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/** Owner-scoped access to independent GDP/TWSE export schedules. */
public interface IndexExportScheduleRepository extends JpaRepository<IndexExportSchedule, Long> {

    List<IndexExportSchedule> findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(Long ownerUserId);

    long countByOwnerUserId(Long ownerUserId);

    Optional<IndexExportSchedule> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /** Lock only the parent row; the eager market collection is loaded separately by Hibernate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from IndexExportSchedule s where s.id = :id and s.ownerUserId = :owner")
    Optional<IndexExportSchedule> findLockedByIdAndOwnerUserId(@Param("id") Long id,
                                                                @Param("owner") Long ownerUserId);
}
