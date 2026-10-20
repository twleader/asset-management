package com.steven.assets.bff.aiflow;

import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Task 492 閉合互通 DTO；nullable 欄位仍保留 JSON key。 */
public final class AiFlowDtos {
    private AiFlowDtos() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Operation(String method, String path) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(String role, String id, String hash) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ToolCall(String name, List<String> innerNames, String parameters, int parametersChars,
                           boolean truncated, int redactedCount,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Boolean sourceTruncated) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChoiceOption(String optionId, String label, String toolName, String parameters, int parametersChars,
                               boolean truncated, int redactedCount,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Boolean sourceTruncated) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChoiceSet(String title, String selectedOptionId, List<ChoiceOption> options) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChoiceOptionSummary(String optionId, String label, String toolName, boolean selected) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChoiceSetSummary(String title, String selectedOptionId, List<ChoiceOptionSummary> options) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunMetadata(String runId, String consumer, LocalDate reportDate, String reportSlot,
                              String sourceKind, String sourceRunId, String automationId,
                              Instant startedAt, String workflowVersion) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Event(String eventId, int sequence, String stepId, String eventKind, String status,
                        Instant occurredAt, String title, String summary, Operation operation,
                        String reasonCode, int attemptNo, List<String> parentEventIds, String relation,
                        List<Evidence> evidenceRefs, String reportedOutcome,
                        @JsonInclude(JsonInclude.Include.NON_NULL) ToolCall toolCall,
                        @JsonInclude(JsonInclude.Include.NON_NULL) ChoiceSet choiceSet) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Batch(int schemaVersion, RunMetadata run, List<Event> events) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Ack(String runId, int inserted, int replayed, List<String> eventIds) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunView(String runId, long ownerUserId, String consumer, LocalDate reportDate,
                          String reportSlot, String sourceKind, String sourceRunId, String automationId,
                          Instant startedAt, String workflowVersion, String executionState,
                          String reportedOutcome, String verifiedReportOutcome, String traceCoverage,
                          int eventCount, Long durationMillis, Instant endedAt, String reasonCode,
                          String slotLabel, String coverageLabel, String executionStateLabel, String outcomeLabel) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunList(LocalDate date, String consumer, String period, String timezone,
                          int retentionDays, LocalDate earliestDate, List<RunView> runs) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Node(String eventId, int sequence, String stepId, String eventKind, String status,
                       Instant occurredAt, String title, String summary, Operation operation,
                       String reasonCode, int attemptNo, List<String> parentEventIds, String relation,
                       List<Evidence> evidenceRefs, String reportedOutcome, String statusLabel,
                       String label, String categoryLabel, String timeLabel, Long durationMillis,
                       int x, int y, String color, ToolCall toolCall, ChoiceSetSummary choiceSetSummary) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Edge(String id, String source, String target, String relation, String label, double curveness) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SlowStep(String stepId, String nodeId, String title, Operation operation,
                           Instant startedAt, Instant endedAt, long durationMillis, String status, String statusLabel) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(RunView run, List<Node> nodes, List<Edge> edges, List<SlowStep> slowSteps) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ToolCallDetail(String runId, String eventId, ToolCall toolCall) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChoiceSetDetail(String runId, String eventId, ChoiceSet choiceSet) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Problem(String type, String title, int status, String detail, String instance, String code, boolean retryable) {}
}
