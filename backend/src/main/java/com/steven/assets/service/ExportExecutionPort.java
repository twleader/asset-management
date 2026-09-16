package com.steven.assets.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Function;

/** Short transaction boundary for current settings and immutable export execution inputs. */
public interface ExportExecutionPort<M> {
    <T> T update(Long owner, Function<M, T> change);
    CapturedExport manual(Long owner);
    List<CapturedExport> due(Long id, Long owner, LocalDate date, LocalTime now);
    void complete(CapturedExport input, LocalDateTime completedAt, String localStatus, String driveStatus);
}
