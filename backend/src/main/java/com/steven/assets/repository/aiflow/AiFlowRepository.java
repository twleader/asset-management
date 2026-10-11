package com.steven.assets.repository.aiflow;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 只保存兩份不可變歷史 snapshot；衍生狀態與圖形不進資料庫。 */
public interface AiFlowRepository {
    record StoredRun(long owner, UUID runId, String consumer, LocalDate date, String slot,
                     String sourceKind, String sourceRunId, String metadata, String hash) {}
    record StoredEvent(UUID eventId, int sequence, String payload, String hash) {}
    void insertRun(StoredRun run);
    void writeDeadline();
    Optional<StoredRun> lockRun(long owner, UUID runId);
    Optional<StoredRun> sourceRun(long owner, String consumer, String sourceKind, String sourceRunId);
    List<StoredRun> list(long owner, LocalDate date, String consumer, String period);
    LocalDate databaseDate();
    Optional<StoredRun> detail(long owner, UUID runId, LocalDate date, String consumer);
    List<StoredEvent> events(long owner, UUID runId);
    void insertEvent(long owner, UUID runId, StoredEvent event);
    int purgeExpired(int limit);
    boolean expiredRemain();
}
