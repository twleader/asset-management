package com.steven.assets.service;

import com.steven.assets.repository.*;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** In-memory plumbing for existing renderer/Drive tests. It supplies no transaction or lock evidence. */
public final class ExportScheduleUnitHarness {
    private static final AtomicLong IDS = new AtomicLong(10000);
    private ExportScheduleUnitHarness() { }
    public static long nextId() { return IDS.incrementAndGet(); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void attach(Object service, JpaRepository repo) {
        ExportScheduleFreshRead fresh = mock(ExportScheduleFreshRead.class);
        when(fresh.refresh(any())).thenAnswer(i -> i.getArgument(0));
        ExportExecutionPort delegate;
        if (repo instanceof ExportScheduleSettingRepository r) {
            doAnswer(i -> locked(r, i.getArgument(0))).when(r).findLockedByOwnerUserId(anyLong());
            delegate = new ExportScheduleExecutionStore(r, fresh);
        } else if (repo instanceof CommodityExportScheduleRepository r) {
            doAnswer(i -> locked(r, i.getArgument(0))).when(r).findLockedByOwnerUserId(anyLong());
            delegate = new CommodityExportScheduleExecutionStore(r, fresh);
        } else if (repo instanceof RealizedGainExportScheduleRepository r) {
            doAnswer(i -> locked(r, i.getArgument(0))).when(r).findLockedByOwnerUserId(anyLong());
            delegate = new RealizedGainExportScheduleExecutionStore(r, fresh);
        } else if (repo instanceof IndexExportScheduleRepository r) {
            doAnswer(i -> locked(r, i.getArgument(0))).when(r).findLockedByOwnerUserId(anyLong());
            delegate = new IndexExportScheduleExecutionStore(r, fresh);
        } else throw new IllegalArgumentException("Unsupported fixture repository");
        doAnswer(i -> {
            Object p = i.getArgument(0); assignIds(p); return repo.save(p);
        }).when(repo).saveAndFlush(any());
        ExportExecutionPort<Object> port = delegate;
        ExportExecutionPort<Object> harness = new ExportExecutionPort<>() {
            @Override public <T> T update(Long owner, Function<Object, T> change) {
                T result = port.update(owner, change); locked(repo, owner).ifPresent(repo::save); return result;
            }
            @Override public CapturedExport manual(Long owner) { return port.manual(owner); }
            @Override public List<CapturedExport> due(Long id, Long owner, LocalDate date, LocalTime now) { return port.due(id, owner, date, now); }
            @Override public void complete(CapturedExport input, LocalDateTime at, String status, String driveStatus) {
                port.complete(input, at, status, driveStatus);
                if (input.scheduleId() != null) locked(repo, input.ownerId()).ifPresent(repo::save);
            }
        };
        ReflectionTestUtils.setField(service, "executionStore", harness);
    }
    @SuppressWarnings("unchecked") private static Optional<Object> locked(JpaRepository<?, ?> repo, Long owner) {
        Optional<Object> result = ReflectionTestUtils.invokeMethod(repo, "findByOwnerUserId", owner);
        if (result == null || result.isEmpty()) result = (Optional<Object>) repo.findAll().stream()
            .filter(p -> Objects.equals(new BeanWrapperImpl(p).getPropertyValue("ownerUserId"), owner)).findFirst();
        result.ifPresent(ExportScheduleUnitHarness::assignIds); return result;
    }
    private static void assignIds(Object parent) {
        BeanWrapperImpl p = new BeanWrapperImpl(parent);
        if (p.getPropertyValue("id") == null) p.setPropertyValue("id", nextId());
        for (Object time : (List<?>) p.getPropertyValue("times")) {
            BeanWrapperImpl t = new BeanWrapperImpl(time);
            if (t.getPropertyValue("id") == null) t.setPropertyValue("id", nextId());
        }
    }
}
