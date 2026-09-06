package com.steven.assets.apierrorlog;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ApiErrorLogRepository extends JpaRepository<ApiErrorLog, Long> {
    @Query(value = "DELETE FROM api_error_log WHERE occurred_at < current_timestamp - interval '30 days'", nativeQuery = true)
    @Modifying int deleteExpired();
    @Query("select l from ApiErrorLog l where (:source = 'ALL' or l.source = :source) and (:operationKey is null or l.operationKey = :operationKey) order by l.occurredAt desc, l.id desc")
    List<ApiErrorLog> newest(@Param("source") String source, @Param("operationKey") String operationKey);
    @Query("select l from ApiErrorLog l where (:source = 'ALL' or l.source = :source) and (:operationKey is null or l.operationKey = :operationKey) order by l.occurredAt asc, l.id asc")
    List<ApiErrorLog> oldest(@Param("source") String source, @Param("operationKey") String operationKey);
}
