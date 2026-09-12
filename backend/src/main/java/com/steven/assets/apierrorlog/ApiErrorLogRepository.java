package com.steven.assets.apierrorlog;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ApiErrorLogRepository extends JpaRepository<ApiErrorLog, Long> {
    /**
     * Narrow write path for stable gateway-log keys.  The named partial-index predicate is part
     * of the conflict target: unrelated constraints must still surface to the recorder.
     */
    @Modifying
    @Query(value = "INSERT INTO api_error_log (source, operation_key, api_name, message_header, stack_trace, "
            + "occurred_at, http_status, dedupe_key) VALUES (:source, :operationKey, :apiName, :messageHeader, "
            + ":stackTrace, :occurredAt, :httpStatus, :dedupeKey) "
            + "ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING", nativeQuery = true)
    int insertIgnoringDuplicateDedupeKey(
            @Param("source") String source,
            @Param("operationKey") String operationKey,
            @Param("apiName") String apiName,
            @Param("messageHeader") String messageHeader,
            @Param("stackTrace") String stackTrace,
            @Param("occurredAt") java.time.Instant occurredAt,
            @Param("httpStatus") Integer httpStatus,
            @Param("dedupeKey") String dedupeKey);

    @Query(value = "DELETE FROM api_error_log WHERE occurred_at < current_timestamp - interval '30 days'", nativeQuery = true)
    @Modifying int deleteExpired();
    @Query("select l from ApiErrorLog l where (:source = 'ALL' or l.source = :source) and (:operationKey is null or l.operationKey = :operationKey) order by l.occurredAt desc, l.id desc")
    List<ApiErrorLog> newest(@Param("source") String source, @Param("operationKey") String operationKey);
    @Query("select l from ApiErrorLog l where (:source = 'ALL' or l.source = :source) and (:operationKey is null or l.operationKey = :operationKey) order by l.occurredAt asc, l.id asc")
    List<ApiErrorLog> oldest(@Param("source") String source, @Param("operationKey") String operationKey);
}
