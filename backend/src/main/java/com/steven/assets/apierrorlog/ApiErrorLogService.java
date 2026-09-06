package com.steven.assets.apierrorlog;

import com.steven.assets.security.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ApiErrorLogService {
    private final ApiErrorLogRepository repository; private final CurrentUserContext currentUser;
    public ApiErrorLogService(ApiErrorLogRepository repository, CurrentUserContext currentUser) { this.repository=repository; this.currentUser=currentUser; }
    public List<ListItem> list(String source, String operationKey, String sort) {
        requireAdmin(); validate(source, sort); validateOperation(source, operationKey);
        List<ApiErrorLog> rows = "OLDEST".equals(sort) ? repository.oldest(source, operationKey) : repository.newest(source, operationKey);
        return rows.stream().map(ListItem::from).toList();
    }
    public List<ApiErrorLogOperationCatalog.Operation> operations(String source) {
        requireAdmin(); validate(source, "NEWEST");
        return ApiErrorLogOperationCatalog.OPERATIONS.stream().filter(o -> "ALL".equals(source) || o.source().equals(source))
                .sorted(Comparator.comparingInt((ApiErrorLogOperationCatalog.Operation o) ->
                        ApiErrorLogOperationCatalog.OPEN_API.equals(o.source()) ? 0 : 1)
                        .thenComparingInt(ApiErrorLogOperationCatalog.Operation::displayOrder)
                        .thenComparing(ApiErrorLogOperationCatalog.Operation::operationKey)).toList();
    }
    public Detail detail(Long id) { requireAdmin(); ApiErrorLog row=repository.findById(id).orElseThrow(() -> new NoSuchElementException("API error log not found")); return Detail.from(row); }
    private void requireAdmin() { if (!currentUser.hasUser()) throw new UnauthenticatedException(); if (!currentUser.isAdmin()) throw new AdminRequiredException(); }
    private static void validate(String source, String sort) { if (!Set.of("ALL","OPEN_API","FUBON_API").contains(source) || !Set.of("NEWEST","OLDEST").contains(sort)) throw new IllegalArgumentException("Invalid API error log filter"); }
    private static void validateOperation(String source, String key) { if (key != null && ApiErrorLogOperationCatalog.OPERATIONS.stream().noneMatch(o -> o.operationKey().equals(key) && ("ALL".equals(source) || o.source().equals(source)))) throw new IllegalArgumentException("Invalid API error log operation"); }
    public record ListItem(Long id,String source,String operationKey,String apiName,String messageHeader,java.time.Instant occurredAt){ static ListItem from(ApiErrorLog r){return new ListItem(r.getId(),r.getSource(),r.getOperationKey(),r.getApiName(),r.getMessageHeader(),r.getOccurredAt());} }
    public record Detail(Long id,String source,String operationKey,String apiName,String messageHeader,String stackTrace,java.time.Instant occurredAt){ static Detail from(ApiErrorLog r){return new Detail(r.getId(),r.getSource(),r.getOperationKey(),r.getApiName(),r.getMessageHeader(),r.getStackTrace(),r.getOccurredAt());} }
}
