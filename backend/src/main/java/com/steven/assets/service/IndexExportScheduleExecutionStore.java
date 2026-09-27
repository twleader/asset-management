package com.steven.assets.service;

import com.steven.assets.model.IndexExportSchedule;
import com.steven.assets.repository.IndexExportScheduleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Short transaction boundary for owner-locked schedule writes, captures and writeback. */
@Service
public class IndexExportScheduleExecutionStore {
    private final IndexExportScheduleRepository scheduleRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public IndexExportScheduleExecutionStore(IndexExportScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    /**
     * Serialize all owner writes, including first-time writes for owners with no schedule rows.
     * The lock is transaction scoped and namespaced independently from every other advisory lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T write(Long ownerId, Function<List<IndexExportSchedule>, T> operation) {
        acquireOwnerLock(ownerId);
        List<IndexExportSchedule> owned = new ArrayList<>(
                scheduleRepository.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(ownerId));
        return operation.apply(owned);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<IndexExportSchedule> listForOwner(Long ownerId) {
        return scheduleRepository.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(ownerId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IndexExportScheduleCapture> manual(Long scheduleId, Long ownerId) {
        return scheduleRepository.findLockedByIdAndOwnerUserId(scheduleId, ownerId)
                .map(schedule -> capture(schedule, null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<IndexExportScheduleCapture> due(Long scheduleId, Long ownerId,
                                                LocalDate date, LocalTime now) {
        IndexExportSchedule schedule = scheduleRepository.findLockedByIdAndOwnerUserId(scheduleId, ownerId)
                .orElse(null);
        if (schedule == null || !Boolean.TRUE.equals(schedule.getEnabled())
                || schedule.getRunHour() == null || schedule.getRunMinute() == null
                || schedule.getLastRunDate() != null && !schedule.getLastRunDate().isBefore(date)
                || now.isBefore(LocalTime.of(schedule.getRunHour(), schedule.getRunMinute()))
                || schedule.getMarkets().isEmpty()) {
            return List.of();
        }
        return List.of(capture(schedule, date));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(IndexExportScheduleCapture input, LocalDateTime completedAt,
                         String localStatus, String driveStatus) {
        if (input == null || input.scheduleId() == null) return;
        IndexExportSchedule schedule = scheduleRepository.findLockedByIdAndOwnerUserId(
                input.scheduleId(), input.ownerId()).orElse(null);
        if (!input.isSameSettings(schedule)) return;

        if (input.attemptDate() != null
                && (schedule.getLastRunDate() == null || schedule.getLastRunDate().isBefore(input.attemptDate()))) {
            schedule.setLastRunDate(input.attemptDate());
        }
        if (IndexExportScheduleCapture.notOlder(completedAt, schedule.getLastRunAt())) {
            schedule.setLastRunAt(completedAt);
            schedule.setLastRunStatus(IndexExportScheduleCapture.truncate(localStatus, 500));
            if (IndexExportScheduleCapture.notOlder(completedAt, schedule.getUpdatedAt())) {
                schedule.setUpdatedAt(completedAt);
            }
        }
        if (driveStatus != null && input.gdriveEnabled() && schedule.isGdriveEnabled()
                && IndexExportScheduleCapture.notOlder(completedAt, schedule.getGdriveLastRunAt())) {
            schedule.setGdriveLastRunAt(completedAt);
            schedule.setGdriveLastStatus(IndexExportScheduleCapture.truncate(driveStatus, 512));
        }
    }

    private IndexExportScheduleCapture capture(IndexExportSchedule schedule, LocalDate attemptDate) {
        return new IndexExportScheduleCapture(schedule.getId(), schedule.getOwnerUserId(), schedule.getName(),
                Boolean.TRUE.equals(schedule.getEnabled()), schedule.getRunHour(), schedule.getRunMinute(),
                attemptDate, schedule.getOutputSubpath(), schedule.isGdriveEnabled(), schedule.getGdriveSubpath(),
                schedule.getRangeMonths(), schedule.getMarkets().stream().sorted().toList());
    }

    private void acquireOwnerLock(Long ownerId) {
        entityManager.createNativeQuery("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended('index-export-schedule:' || CAST(:ownerId AS text), 0))
                """).setParameter("ownerId", ownerId).getSingleResult();
    }
}
