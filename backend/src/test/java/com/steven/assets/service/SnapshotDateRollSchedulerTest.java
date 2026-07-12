package com.steven.assets.service;

import com.steven.assets.repository.AssetSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SnapshotDateRollScheduler 單元測試（Requirement 35 / Task 174）
 * 覆蓋：逐 owner 呼叫、單一 owner 失敗不中斷其他 owner、無 owner 時不呼叫。
 */
@ExtendWith(MockitoExtension.class)
class SnapshotDateRollSchedulerTest {

    @Mock AssetSnapshotRepository snapshotRepo;
    @Mock AssetService assetService;

    @InjectMocks SnapshotDateRollScheduler scheduler;

    @Test
    void scheduledRoll_逐owner各自呼叫roll() {
        when(snapshotRepo.findDistinctOwnerUserIds()).thenReturn(List.of(1L, 2L, 3L));
        when(assetService.rollLatestSnapshotToTodayForOwner(anyLong(), any(LocalDate.class)))
                .thenReturn(true);

        scheduler.scheduledRoll();

        verify(assetService).rollLatestSnapshotToTodayForOwner(eq(1L), any(LocalDate.class));
        verify(assetService).rollLatestSnapshotToTodayForOwner(eq(2L), any(LocalDate.class));
        verify(assetService).rollLatestSnapshotToTodayForOwner(eq(3L), any(LocalDate.class));
    }

    @Test
    void scheduledRoll_單一owner失敗不中斷其他owner() {
        when(snapshotRepo.findDistinctOwnerUserIds()).thenReturn(List.of(1L, 2L, 3L));
        when(assetService.rollLatestSnapshotToTodayForOwner(eq(1L), any(LocalDate.class)))
                .thenReturn(true);
        when(assetService.rollLatestSnapshotToTodayForOwner(eq(2L), any(LocalDate.class)))
                .thenThrow(new RuntimeException("模擬 DB 錯誤"));
        when(assetService.rollLatestSnapshotToTodayForOwner(eq(3L), any(LocalDate.class)))
                .thenReturn(true);

        assertThatCode(() -> scheduler.scheduledRoll()).doesNotThrowAnyException();

        // owner 2 丟例外後，owner 3 仍被處理
        verify(assetService).rollLatestSnapshotToTodayForOwner(eq(1L), any(LocalDate.class));
        verify(assetService).rollLatestSnapshotToTodayForOwner(eq(2L), any(LocalDate.class));
        verify(assetService).rollLatestSnapshotToTodayForOwner(eq(3L), any(LocalDate.class));
    }

    @Test
    void scheduledRoll_無owner時不呼叫roll() {
        when(snapshotRepo.findDistinctOwnerUserIds()).thenReturn(List.of());

        scheduler.scheduledRoll();

        verify(assetService, never()).rollLatestSnapshotToTodayForOwner(anyLong(), any(LocalDate.class));
    }
}
