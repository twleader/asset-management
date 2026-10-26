package com.steven.assets.service;

import com.steven.assets.model.ExchangeRateExportSchedule;
import com.steven.assets.repository.ExchangeRateExportScheduleRepository;
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
public class ExchangeRateExportScheduleExecutionStore {
    private final ExchangeRateExportScheduleRepository scheduleRepository;
    private final com.steven.assets.repository.ExportScheduleFreshRead freshRead;

    @PersistenceContext
    private EntityManager entityManager;

    public ExchangeRateExportScheduleExecutionStore(ExchangeRateExportScheduleRepository scheduleRepository, com.steven.assets.repository.ExportScheduleFreshRead freshRead) {
        this.freshRead = freshRead;
        this.scheduleRepository = scheduleRepository;
    }

    /**
     * Serialize all owner writes, including first-time writes for owners with no schedule rows.
     * The lock is transaction scoped and namespaced independently from every other advisory lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T write(Long ownerId, Function<List<ExchangeRateExportSchedule>, T> operation) {
        acquireOwnerLock(ownerId);
        List<ExchangeRateExportSchedule> owned = new ArrayList<>(
                scheduleRepository.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(ownerId));
        owned.replaceAll(row -> scheduleRepository.findLockedByIdAndOwnerUserId(row.getId(), ownerId)
                .map(freshRead::refresh).orElseThrow());
        return operation.apply(owned);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<ExchangeRateExportSchedule> listForOwner(Long ownerId) {
        return scheduleRepository.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(ownerId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ExchangeRateExportScheduleCapture> manual(Long scheduleId, Long ownerId) {
        return scheduleRepository.findLockedByIdAndOwnerUserId(scheduleId, ownerId)
                .map(freshRead::refresh).map(schedule -> capture(schedule, null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ExchangeRateExportScheduleCapture> due(Long scheduleId, Long ownerId,
                                                LocalDate date, LocalTime now) {
        ExchangeRateExportSchedule schedule = scheduleRepository.findLockedByIdAndOwnerUserId(scheduleId, ownerId)
                .map(freshRead::refresh).orElse(null);
        if (schedule == null || !Boolean.TRUE.equals(schedule.getEnabled())
                || schedule.getRunHour() == null || schedule.getRunMinute() == null
                || schedule.getLastRunDate() != null && !schedule.getLastRunDate().isBefore(date)
                || now.isBefore(LocalTime.of(schedule.getRunHour(), schedule.getRunMinute()))) {
            return List.of();
        }
        return List.of(capture(schedule, date));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(ExchangeRateExportScheduleCapture input, LocalDateTime completedAt,
                         String localStatus, String driveStatus) {
        if (input == null || input.scheduleId() == null) return;
        ExchangeRateExportSchedule schedule = scheduleRepository.findLockedByIdAndOwnerUserId(
                input.scheduleId(), input.ownerId()).map(freshRead::refresh).orElse(null);
        if (!input.isSameSettings(schedule)) return;

        if (input.attemptDate() != null
                && (schedule.getLastRunDate() == null || schedule.getLastRunDate().isBefore(input.attemptDate()))) {
            schedule.setLastRunDate(input.attemptDate());
        }
        if (ExchangeRateExportScheduleCapture.notOlder(completedAt, schedule.getLastRunAt())) {
            schedule.setLastRunAt(completedAt);
            schedule.setLastRunStatus(ExchangeRateExportScheduleCapture.truncate(localStatus, 500));
            if (ExchangeRateExportScheduleCapture.notOlder(completedAt, schedule.getUpdatedAt())) {
                schedule.setUpdatedAt(completedAt);
            }
        }
        if (driveStatus != null && input.gdriveEnabled() && schedule.isGdriveEnabled()
                && ExchangeRateExportScheduleCapture.notOlder(completedAt, schedule.getGdriveLastRunAt())) {
            schedule.setGdriveLastRunAt(completedAt);
            schedule.setGdriveLastStatus(ExchangeRateExportScheduleCapture.truncate(driveStatus, 512));
        }
    }

    private ExchangeRateExportScheduleCapture capture(ExchangeRateExportSchedule schedule, LocalDate attemptDate) {
        return new ExchangeRateExportScheduleCapture(schedule.getId(), schedule.getOwnerUserId(), schedule.getName(),
                Boolean.TRUE.equals(schedule.getEnabled()), schedule.getRunHour(), schedule.getRunMinute(),
                attemptDate, schedule.getOutputSubpath(), schedule.getRangeMonths(), schedule.isGdriveEnabled(), schedule.getGdriveSubpath());
    }

    private void acquireOwnerLock(Long ownerId) {
        entityManager.createNativeQuery("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended('exchange_rate_export_schedule:' || CAST(:ownerId AS text), 0))
                """).setParameter("ownerId", ownerId).getSingleResult();
    }
}
