package com.steven.assets.dto.aiflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Task 492：閉合觀測協定。nullable key 仍完整輸出，不包含金融內容或私人推理。 */
public final class AiFlowDtos {
    private AiFlowDtos() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Run(String runId, String consumer, @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate reportDate, String reportSlot,
                      String sourceKind, String sourceRunId, String automationId, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt,
                      String workflowVersion) {}
    public record Operation(String method, String path) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(String role, String id, String hash) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ToolCall(String name, List<String> innerNames, String parameters, int parametersChars,
                           boolean truncated, int redactedCount,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Boolean sourceTruncated) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Event(String eventId, int sequence, String stepId, String eventKind, String status,
                        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt, String title, String summary, Operation operation,
                        String reasonCode, int attemptNo, List<String> parentEventIds, String relation,
                        List<Evidence> evidenceRefs, String reportedOutcome,
                        @JsonInclude(JsonInclude.Include.NON_NULL) ToolCall toolCall) {}
    public record Batch(int schemaVersion, Run run, List<Event> events) {}
    public record Ack(String runId, int inserted, int replayed, List<String> eventIds) {}
    public record AppendResult(int status, Ack ack) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunView(String runId, long ownerUserId, String consumer, @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate reportDate,
                          String reportSlot, String sourceKind, String sourceRunId, String automationId,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt, String workflowVersion, String executionState,
                          String reportedOutcome, String verifiedReportOutcome, String traceCoverage,
                          int eventCount, Long durationMillis, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant endedAt, String reasonCode,
                          String slotLabel, String coverageLabel, String executionStateLabel,
                          String outcomeLabel) {}
    public record RunList(@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate date, String consumer, String period, String timezone,
                          int retentionDays, @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate earliestDate, List<RunView> runs) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Node(String eventId, int sequence, String stepId, String eventKind, String status,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt, String title, String summary, Operation operation,
                       String reasonCode, int attemptNo, List<String> parentEventIds, String relation,
                       List<Evidence> evidenceRefs, String reportedOutcome, String statusLabel,
                       String label, String categoryLabel, String timeLabel, Long durationMillis,
                       int x, int y, String color, ToolCall toolCall) {}
    public record Edge(String id, String source, String target, String relation, String label,
                       double curveness) {}
    public record SlowStep(String stepId, String nodeId, String title, Operation operation,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt,
                           @JsonFormat(shape = JsonFormat.Shape.STRING) Instant endedAt, long durationMillis, String status,
                           String statusLabel) {}
    public record Detail(RunView run, List<Node> nodes, List<Edge> edges, List<SlowStep> slowSteps) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ToolCallDetail(String runId, String eventId, ToolCall toolCall) {}
    public record Problem(String type, String title, int status, String detail, String instance,
                          String code, boolean retryable) {}
}
