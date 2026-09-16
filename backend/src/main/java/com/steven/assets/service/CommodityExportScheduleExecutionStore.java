package com.steven.assets.service;

import com.steven.assets.repository.CommodityExportScheduleRepository;
import com.steven.assets.repository.ExportScheduleFreshRead;

import com.steven.assets.service.CapturedExport;
import com.steven.assets.service.ExportExecutionPort;

import com.steven.assets.model.CommodityExportSchedule;
import com.steven.assets.model.CommodityExportScheduleTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Parent lock is shared by UI updates, capture and completion; no export I/O is done here. */
@Service
public class CommodityExportScheduleExecutionStore implements ExportExecutionPort<CommodityExportSchedule> {
    private final CommodityExportScheduleRepository settingRepo;
    private final ExportScheduleFreshRead freshRead;
    public CommodityExportScheduleExecutionStore(CommodityExportScheduleRepository settingRepo, ExportScheduleFreshRead freshRead) {
        this.settingRepo = settingRepo; this.freshRead = freshRead;
    }
    private CommodityExportSchedule lockOwner(Long owner) {
        return settingRepo.findLockedByOwnerUserId(owner).map(freshRead::refresh).orElse(null);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T update(Long owner, Function<CommodityExportSchedule, T> change) {
        CommodityExportSchedule s = lockOwner(owner);
        if (s == null) { s = CommodityExportSchedule.builder().ownerUserId(owner).build(); settingRepo.saveAndFlush(s); }
        s.getTimes().size();
        return change.apply(s);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CapturedExport manual(Long owner) {
        CommodityExportSchedule s = lockOwner(owner);
        if (s == null) return new CapturedExport(null, owner, null, null, null, null, "input", false, null, null,
                List.of());
        return new CapturedExport(s.getId(), owner, null, null, null, null, s.getOutputSubpath(),
            s.isGdriveEnabled(), s.getGdriveSubpath(), s.getRangeMonths(), List.of());
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CapturedExport> due(Long id, Long owner, LocalDate date, LocalTime now) {
        CommodityExportSchedule s = lockOwner(owner);
        if (s == null || !Objects.equals(s.getId(), id) || !Boolean.TRUE.equals(s.getEnabled())) return List.of();
        if (s.getTimes().isEmpty()) {
            CommodityExportScheduleTime legacy = CommodityExportScheduleTime.builder().runHour(s.getRunHour()).runMinute(s.getRunMinute())
                .enabled(true).lastRunDate(s.getLastRunDate()).lastRunAt(s.getLastRunAt())
                .lastRunStatus(s.getLastRunStatus()).build();
            s.addTime(legacy); settingRepo.saveAndFlush(s);
        }
        List<CapturedExport> captures = new ArrayList<>();
        for (CommodityExportScheduleTime t : s.getTimes()) {
            if (!Boolean.TRUE.equals(t.getEnabled()) || (t.getLastRunDate() != null && !t.getLastRunDate().isBefore(date))
                    || now.isBefore(LocalTime.of(t.getRunHour(), t.getRunMinute()))) continue;
            captures.add(new CapturedExport(s.getId(), owner, t.getId(), t.getRunHour(), t.getRunMinute(), date,
                s.getOutputSubpath(), s.isGdriveEnabled(), s.getGdriveSubpath(), s.getRangeMonths(), List.of()));
        }
        return List.copyOf(captures);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(CapturedExport input, LocalDateTime completedAt, String localStatus, String driveStatus) {
        if (input.scheduleId() == null) return;
        CommodityExportSchedule s = lockOwner(input.ownerId());
        if (s == null || !Objects.equals(s.getId(), input.scheduleId())) return;
        CommodityExportScheduleTime child = null;
        if (input.childId() != null) {
            child = s.getTimes().stream().filter(t -> Objects.equals(t.getId(), input.childId())
                && Objects.equals(t.getRunHour(), input.hour()) && Objects.equals(t.getRunMinute(), input.minute()))
                .findFirst().orElse(null);
            if (child == null) return;
            if (child.getLastRunDate() == null || child.getLastRunDate().isBefore(input.attemptDate()))
                child.setLastRunDate(input.attemptDate());
            if (CapturedExport.notOlder(completedAt, child.getLastRunAt())) {
                child.setLastRunAt(completedAt); child.setLastRunStatus(CapturedExport.truncate(localStatus, 500));
                if (CapturedExport.notOlder(completedAt, child.getUpdatedAt())) child.setUpdatedAt(completedAt);
            }
        }
        if (CapturedExport.notOlder(completedAt, s.getLastRunAt())) {
            s.setLastRunAt(completedAt); s.setLastRunStatus(CapturedExport.truncate(localStatus, 500));
            CommodityExportScheduleTime representative = s.getTimes().stream().filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                .min(Comparator.comparing(CommodityExportScheduleTime::getRunHour).thenComparing(CommodityExportScheduleTime::getRunMinute)
                    .thenComparing(CommodityExportScheduleTime::getId)).orElseGet(() -> s.getTimes().stream()
                .min(Comparator.comparing(CommodityExportScheduleTime::getRunHour).thenComparing(CommodityExportScheduleTime::getRunMinute)
                    .thenComparing(CommodityExportScheduleTime::getId)).orElse(null));
            if (representative != null) { s.setRunHour(representative.getRunHour());
                s.setRunMinute(representative.getRunMinute()); s.setLastRunDate(representative.getLastRunDate()); }
        }
        if (driveStatus != null && s.isGdriveEnabled() == input.gdriveEnabled()
                && Objects.equals(s.getOutputSubpath(), input.outputSubpath())
                && Objects.equals(s.getGdriveSubpath(), input.gdriveSubpath()) && Objects.equals(s.getRangeMonths(), input.rangeMonths())
                && CapturedExport.notOlder(completedAt, s.getGdriveLastRunAt())) {
            s.setGdriveLastRunAt(completedAt); s.setGdriveLastStatus(CapturedExport.truncate(driveStatus, 512));
        }
        if (CapturedExport.notOlder(completedAt, s.getUpdatedAt())) s.setUpdatedAt(completedAt);
    }
}
