package com.steven.assets.apierrorlog;

import com.steven.assets.security.*;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import java.util.*;

@Service
public class ApiErrorLogService {
    private static final int PAGE_SIZE = 100;
    private final ApiErrorLogRepository repository; private final CurrentUserContext currentUser;
    public ApiErrorLogService(ApiErrorLogRepository repository, CurrentUserContext currentUser) { this.repository=repository; this.currentUser=currentUser; }
    public PageResult list(String source, String operationKey, String sort, int page) {
        requireAdmin(); validate(source, sort); validateOperation(source, operationKey);
        if (page < 0) throw new IllegalArgumentException("Invalid API error log page");
        PageRequest pageable = PageRequest.of(page, PAGE_SIZE);
        Page<ApiErrorLog> rows = "OLDEST".equals(sort) ? repository.oldest(source, operationKey, pageable) : repository.newest(source, operationKey, pageable);
        return new PageResult(rows.getContent().stream().map(ListItem::from).toList(), rows.getTotalElements(),
                page, PAGE_SIZE, rows.getTotalPages());
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
    public record ListItem(Long id,String source,String operationKey,String apiName,String messageHeader,Integer httpStatus,java.time.Instant occurredAt){ static ListItem from(ApiErrorLog r){return new ListItem(r.getId(),r.getSource(),r.getOperationKey(),r.getApiName(),r.getMessageHeader(),r.getHttpStatus(),r.getOccurredAt());} }
    public record PageResult(List<ListItem> items, long totalElements, int page, int pageSize, int totalPages) {
        public PageResult { items = List.copyOf(items); }
    }
    public record Detail(Long id,String source,String operationKey,String apiName,String messageHeader,String stackTrace,Integer httpStatus,java.time.Instant occurredAt){ static Detail from(ApiErrorLog r){return new Detail(r.getId(),r.getSource(),r.getOperationKey(),r.getApiName(),r.getMessageHeader(),r.getStackTrace(),r.getHttpStatus(),r.getOccurredAt());} }
}
