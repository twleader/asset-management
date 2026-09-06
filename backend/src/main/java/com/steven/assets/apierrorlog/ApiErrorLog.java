package com.steven.assets.apierrorlog;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "api_error_log")
public class ApiErrorLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String source;
    @Column(name = "operation_key", nullable = false) private String operationKey;
    @Column(name = "api_name", nullable = false) private String apiName;
    @Column(name = "message_header", nullable = false, columnDefinition = "TEXT") private String messageHeader;
    @Column(name = "stack_trace", nullable = false, columnDefinition = "TEXT") private String stackTrace;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    protected ApiErrorLog() {}
    public ApiErrorLog(String source, String operationKey, String apiName, String messageHeader, String stackTrace, Instant occurredAt) {
        this.source=source; this.operationKey=operationKey; this.apiName=apiName; this.messageHeader=messageHeader; this.stackTrace=stackTrace; this.occurredAt=occurredAt;
    }
    public Long getId(){return id;} public String getSource(){return source;} public String getOperationKey(){return operationKey;}
    public String getApiName(){return apiName;} public String getMessageHeader(){return messageHeader;} public String getStackTrace(){return stackTrace;} public Instant getOccurredAt(){return occurredAt;}
}
