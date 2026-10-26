package com.steven.assets.service;

import com.steven.assets.repository.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** In-memory locked reads for existing renderer fixtures; PostgreSQL transaction evidence lives in dedicated tests. */
public final class ExportScheduleUnitHarness {
    private static final AtomicLong IDS = new AtomicLong(10000);
    private ExportScheduleUnitHarness() {}
    public static long nextId() { return IDS.incrementAndGet(); }
    public static ExportScheduleExecutionStore assetStore(ExportScheduleSettingRepository repo) {
        var fresh = mock(ExportScheduleFreshRead.class);
        when(fresh.refresh(any())).thenAnswer(i -> i.getArgument(0));
        doAnswer(i -> repo.findAll().stream().filter(p -> Objects.equals(p.getId(), i.getArgument(0))
                && Objects.equals(p.getOwnerUserId(), i.getArgument(1))).findFirst())
                .when(repo).findLockedByIdAndOwnerUserId(anyLong(), anyLong());
        return new ExportScheduleExecutionStore(repo, fresh);
    }
    public static RealizedGainExportScheduleExecutionStore gainStore(RealizedGainExportScheduleRepository repo) {
        var fresh = mock(ExportScheduleFreshRead.class);
        when(fresh.refresh(any())).thenAnswer(i -> i.getArgument(0));
        doAnswer(i -> repo.findAll().stream().filter(p -> Objects.equals(p.getId(), i.getArgument(0))
                && Objects.equals(p.getOwnerUserId(), i.getArgument(1))).findFirst())
                .when(repo).findLockedByIdAndOwnerUserId(anyLong(), anyLong());
        return new RealizedGainExportScheduleExecutionStore(repo, fresh);
    }
    public static CommodityExportScheduleExecutionStore commodityStore(CommodityExportScheduleRepository repo) {
        var fresh = mock(ExportScheduleFreshRead.class);
        when(fresh.refresh(any())).thenAnswer(i -> i.getArgument(0));
        doAnswer(i -> repo.findAll().stream().filter(p -> Objects.equals(p.getId(), i.getArgument(0))
                && Objects.equals(p.getOwnerUserId(), i.getArgument(1))).findFirst())
                .when(repo).findLockedByIdAndOwnerUserId(anyLong(), anyLong());
        return new CommodityExportScheduleExecutionStore(repo, fresh);
    }
    public static ExchangeRateExportScheduleExecutionStore rateStore(ExchangeRateExportScheduleRepository repo) {
        var fresh = mock(ExportScheduleFreshRead.class);
        when(fresh.refresh(any())).thenAnswer(i -> i.getArgument(0));
        doAnswer(i -> repo.findAll().stream().filter(p -> Objects.equals(p.getId(), i.getArgument(0))
                && Objects.equals(p.getOwnerUserId(), i.getArgument(1))).findFirst())
                .when(repo).findLockedByIdAndOwnerUserId(anyLong(), anyLong());
        return new ExchangeRateExportScheduleExecutionStore(repo, fresh);
    }
}
