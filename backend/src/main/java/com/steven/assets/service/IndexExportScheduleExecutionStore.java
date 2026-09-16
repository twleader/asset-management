package com.steven.assets.service;

import com.steven.assets.repository.IndexExportScheduleRepository;
import com.steven.assets.repository.ExportScheduleFreshRead;

import com.steven.assets.service.CapturedExport;
import com.steven.assets.service.ExportExecutionPort;

import com.steven.assets.model.IndexExportSchedule;
import com.steven.assets.model.IndexExportScheduleTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Parent lock is shared by UI updates, capture and completion; no export I/O is done here. */
@Service
public class IndexExportScheduleExecutionStore implements ExportExecutionPort<IndexExportSchedule> {
    private final IndexExportScheduleRepository settingRepo;
    private final ExportScheduleFreshRead freshRead;
    public IndexExportScheduleExecutionStore(IndexExportScheduleRepository settingRepo, ExportScheduleFreshRead freshRead) {
        this.settingRepo = settingRepo; this.freshRead = freshRead;
    }
    private IndexExportSchedule lockOwner(Long owner) {
        return settingRepo.findLockedByOwnerUserId(owner).map(freshRead::refresh).orElse(null);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T update(Long owner, Function<IndexExportSchedule, T> change) {
        IndexExportSchedule s = lockOwner(owner);
        if (s == null) { s = IndexExportSchedule.builder().ownerUserId(owner).build(); settingRepo.saveAndFlush(s); }
        s.getTimes().size();
        return change.apply(s);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CapturedExport manual(Long owner) {
        IndexExportSchedule s = lockOwner(owner);
        if (s == null) return new CapturedExport(null, owner, null, null, null, null, "input", false, null, null,
                List.of("TWSE"));
        return new CapturedExport(s.getId(), owner, null, null, null, null, s.getOutputSubpath(),
            s.isGdriveEnabled(), s.getGdriveSubpath(), s.getRangeMonths(), s.getTimes().stream().flatMap(t -> t.getMarkets().stream()).distinct().toList());
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CapturedExport> due(Long id, Long owner, LocalDate date, LocalTime now) {
        IndexExportSchedule s = lockOwner(owner);
        if (s == null || !Objects.equals(s.getId(), id) || !Boolean.TRUE.equals(s.getEnabled())) return List.of();

        List<CapturedExport> captures = new ArrayList<>();
        for (IndexExportScheduleTime t : s.getTimes()) {
            if (!Boolean.TRUE.equals(t.getEnabled()) || (t.getLastRunDate() != null && !t.getLastRunDate().isBefore(date))
                    || now.isBefore(LocalTime.of(t.getRunHour(), t.getRunMinute()))) continue;
            captures.add(new CapturedExport(s.getId(), owner, t.getId(), t.getRunHour(), t.getRunMinute(), date,
                s.getOutputSubpath(), s.isGdriveEnabled(), s.getGdriveSubpath(), s.getRangeMonths(), t.getMarkets().stream().toList()));
        }
        return List.copyOf(captures);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(CapturedExport input, LocalDateTime completedAt, String localStatus, String driveStatus) {
        if (input.scheduleId() == null) return;
        IndexExportSchedule s = lockOwner(input.ownerId());
        if (s == null || !Objects.equals(s.getId(), input.scheduleId())) return;
        IndexExportScheduleTime child = null;
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

        if (driveStatus != null && s.isGdriveEnabled() == input.gdriveEnabled()
                && Objects.equals(s.getOutputSubpath(), input.outputSubpath())
                && Objects.equals(s.getGdriveSubpath(), input.gdriveSubpath()) && Objects.equals(s.getRangeMonths(), input.rangeMonths()) && (input.childId() == null ? s.getTimes().stream().flatMap(t -> t.getMarkets().stream()).collect(java.util.stream.Collectors.toSet()).equals(new java.util.HashSet<>(input.markets())) : child.getMarkets().equals(new java.util.HashSet<>(input.markets())))
                && CapturedExport.notOlder(completedAt, s.getGdriveLastRunAt())) {
            s.setGdriveLastRunAt(completedAt); s.setGdriveLastStatus(CapturedExport.truncate(driveStatus, 512));
        }
        if (CapturedExport.notOlder(completedAt, s.getUpdatedAt())) s.setUpdatedAt(completedAt);
    }
}
