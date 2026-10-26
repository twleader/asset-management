package com.steven.assets.service;

import com.steven.assets.model.ExportScheduleSetting;
import com.steven.assets.repository.ExportScheduleSettingRepository;
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
public class ExportScheduleExecutionStore {
    private final ExportScheduleSettingRepository scheduleRepository;
    private final com.steven.assets.repository.ExportScheduleFreshRead freshRead;

    @PersistenceContext
    private EntityManager entityManager;

    public ExportScheduleExecutionStore(ExportScheduleSettingRepository scheduleRepository, com.steven.assets.repository.ExportScheduleFreshRead freshRead) {
        this.freshRead = freshRead;
        this.scheduleRepository = scheduleRepository;
    }

    /**
     * Serialize all owner writes, including first-time writes for owners with no schedule rows.
     * The lock is transaction scoped and namespaced independently from every other advisory lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T write(Long ownerId, Function<List<ExportScheduleSetting>, T> operation) {
        acquireOwnerLock(ownerId);
        List<ExportScheduleSetting> owned = new ArrayList<>(
                scheduleRepository.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(ownerId));
        owned.replaceAll(row -> scheduleRepository.findLockedByIdAndOwnerUserId(row.getId(), ownerId)
                .map(freshRead::refresh).orElseThrow());
        return operation.apply(owned);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<ExportScheduleSetting> listForOwner(Long ownerId) {
        return scheduleRepository.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(ownerId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AssetExportScheduleCapture> manual(Long scheduleId, Long ownerId) {
        return scheduleRepository.findLockedByIdAndOwnerUserId(scheduleId, ownerId)
                .map(freshRead::refresh).map(schedule -> capture(schedule, null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<AssetExportScheduleCapture> due(Long scheduleId, Long ownerId,
                                                LocalDate date, LocalTime now) {
        ExportScheduleSetting schedule = scheduleRepository.findLockedByIdAndOwnerUserId(scheduleId, ownerId)
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
    public void complete(AssetExportScheduleCapture input, LocalDateTime completedAt,
                         String localStatus, String driveStatus) {
        if (input == null || input.scheduleId() == null) return;
        ExportScheduleSetting schedule = scheduleRepository.findLockedByIdAndOwnerUserId(
                input.scheduleId(), input.ownerId()).map(freshRead::refresh).orElse(null);
        if (!input.isSameSettings(schedule)) return;

        if (input.attemptDate() != null
                && (schedule.getLastRunDate() == null || schedule.getLastRunDate().isBefore(input.attemptDate()))) {
            schedule.setLastRunDate(input.attemptDate());
        }
        if (AssetExportScheduleCapture.notOlder(completedAt, schedule.getLastRunAt())) {
            schedule.setLastRunAt(completedAt);
            schedule.setLastRunStatus(AssetExportScheduleCapture.truncate(localStatus, 500));
            if (AssetExportScheduleCapture.notOlder(completedAt, schedule.getUpdatedAt())) {
                schedule.setUpdatedAt(completedAt);
            }
        }
        if (driveStatus != null && input.gdriveEnabled() && schedule.isGdriveEnabled()
                && AssetExportScheduleCapture.notOlder(completedAt, schedule.getGdriveLastRunAt())) {
            schedule.setGdriveLastRunAt(completedAt);
            schedule.setGdriveLastStatus(AssetExportScheduleCapture.truncate(driveStatus, 512));
        }
    }

    private AssetExportScheduleCapture capture(ExportScheduleSetting schedule, LocalDate attemptDate) {
        return new AssetExportScheduleCapture(schedule.getId(), schedule.getOwnerUserId(), schedule.getName(),
                Boolean.TRUE.equals(schedule.getEnabled()), schedule.getRunHour(), schedule.getRunMinute(),
                attemptDate, schedule.getOutputSubpath(), schedule.isGdriveEnabled(), schedule.getGdriveSubpath());
    }

    private void acquireOwnerLock(Long ownerId) {
        entityManager.createNativeQuery("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended('export_schedule_setting:' || CAST(:ownerId AS text), 0))
                """).setParameter("ownerId", ownerId).getSingleResult();
    }
}
