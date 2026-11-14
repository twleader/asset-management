package com.steven.assets.dto;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/** Immutable transport snapshots. Dynamic settings trees are copied on construction and access. */
public final class SrppSettingsDto {
    private SrppSettingsDto() {}
    public sealed interface Response permits CenterResponse,DraftResponse,RunResponse,ReleaseResponse,PayloadResponse,ProblemResponse {}
    private static JsonNode copy(JsonNode n){return n==null?null:n.deepCopy();}
    private static <T> List<T> list(List<T> values){return values==null?List.of():List.copyOf(values);}
    private static List<JsonNode> trees(List<JsonNode> values){return values==null?List.of():values.stream().map(SrppSettingsDto::copy).toList();}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CurrentResponse(String baseBundleSha256,String modelInputsSha256,String decision,String policyVersion) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CatalogResponse(int schemaVersion,String baseBundleSha256,String modelInputsSha256,String catalogVersion,
            String catalogSha256,List<JsonNode> fields,JsonNode currentValues) {
        public CatalogResponse {fields=trees(fields);currentValues=copy(currentValues);}
        @Override public List<JsonNode> fields(){return trees(fields);}
        @Override public JsonNode currentValues(){return copy(currentValues);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CenterResponse(CatalogResponse catalog,CurrentResponse current,List<DraftResponse> drafts,
            List<RunResponse> simulations,List<ReleaseResponse> releases,boolean canPublish,boolean aiAvailable,
            List<FieldGroup> fieldGroups) implements Response {
        public CenterResponse {drafts=list(drafts);simulations=list(simulations);releases=list(releases);fieldGroups=list(fieldGroups);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BlockReason(String code,String message) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChangeResponse(String fieldId,String label,JsonNode before,JsonNode after,boolean formalSupported,String retirementImpact) {
        public ChangeResponse {before=copy(before);after=copy(after);}
        @Override public JsonNode before(){return copy(before);}
        @Override public JsonNode after(){return copy(after);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftResponse(String id,String name,long revision,String baseBundleSha256,String modelInputsSha256,
            String catalogSha256,String catalogVersion,String configSha256,String createdAt,String updatedAt,
            JsonNode values,List<ChangeResponse> changes,boolean publishEligible,List<BlockReason> blockedReasons) implements Response {
        public DraftResponse {values=copy(values);changes=list(changes);blockedReasons=list(blockedReasons);}
        @Override public JsonNode values(){return copy(values);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunResponse(String id,String draftId,long draftRevision,String mode,String status,String configSha256,
            String inputSha256,String resultSha256,String aiStatus,String aiInterpretation,String errorCode,String createdAt,
            String completedAt,SimulationEnvelope deterministicResult,List<ChangeResponse> changes,boolean publishEligible,
            List<BlockReason> blockedReasons,Presentation presentation) implements Response {
        public RunResponse {changes=list(changes);blockedReasons=list(blockedReasons);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReleaseResponse(String id,String draftId,String simulationId,String requestSha256,String configSha256,
            String baseBundleSha256,String createdAt,String status) implements Response {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SimulationReceipt(String id,String resultSha256) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PayloadResponse(int schemaVersion,String requestSha256,String baseBundleSha256,String modelInputsSha256,
            String catalogSha256,String configSha256,JsonNode values,SimulationReceipt simulation) implements Response {
        public PayloadResponse {values=copy(values);}
        @Override public JsonNode values(){return copy(values);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProblemResponse(String code,String message) implements Response {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SimulationEnvelope(int schemaVersion,String baseBundleSha256,String configSha256,String inputSha256,
            String engineVersion,SimulationResult result) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SimulationResult(ScenarioResponse baseline,ScenarioResponse scenario,Differences differences,
            List<WorkerChange> changes,List<String> warnings) {
        public SimulationResult {changes=list(changes);warnings=list(warnings);}
    }
    /** These five fields are the worker's immutable wire; no presentation metadata enters its hash. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record WorkerChange(String fieldId,String label,JsonNode before,JsonNode after,String retirementImpact) {
        public WorkerChange {before=copy(before);after=copy(after);}
        @Override public JsonNode before(){return copy(before);}
        @Override public JsonNode after(){return copy(after);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ScenarioResponse(String scope,double successProbability,ProbabilityInterval successProbabilityCi95,
            List<PathPoint> percentilePaths,MoneyQuantiles retirementAssets,MoneyQuantiles terminalAssets,
            AgeQuantiles depletionAge,List<String> modeledFields,List<String> notModeledFields,Assumptions assumptions) {
        public ScenarioResponse {percentilePaths=list(percentilePaths);modeledFields=list(modeledFields);notModeledFields=list(notModeledFields);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProbabilityInterval(double low,double high) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MoneyQuantiles(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal p10,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal p50,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal p90) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AgeQuantiles(double p10,double p50,double p90) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PathPoint(String date,double age,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal p10,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal p50,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal p90) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Differences(double successProbability,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal retirementAssetsP50,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal terminalAssetsP50) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Assumptions(String baseCurrency,String purchasingPowerBaseDate,String asOfDate,String assetSnapshotDate,
            Boolean targetPriceComplete,String retirementDate,int planningAge,int simulations,int seed,boolean reserveExcluded,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal protectedReserveRealTwd,double referenceInflation,
            ProbabilityInterval scenarioInflationBounds,double equityRealCompoundPlanningTarget,double equityArithmeticPlanningMean,
            double bondReferenceRealArithmeticMean,double cashReferenceRealArithmeticMean,double equityVolatility,
            double bondVolatility,double fxVolatility,String fxVolatilitySource,double bondNominalPlanningYield,
            String returnMeanSource,String equityMeanConversion,List<String> approximationNotes) {
        public Assumptions {approximationNotes=list(approximationNotes);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FieldGroup(String name,List<JsonNode> fields) {
        public FieldGroup {fields=trees(fields);}
        @Override public List<JsonNode> fields(){return trees(fields);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AssumptionEntry(String key,String label,JsonNode value,String unit) {
        public AssumptionEntry {value=copy(value);}
        @Override public JsonNode value(){return copy(value);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ScenarioPresentation(List<AssumptionEntry> assumptions,List<String> modeledFields,List<String> notModeledFields) {
        public ScenarioPresentation {assumptions=list(assumptions);modeledFields=list(modeledFields);notModeledFields=list(notModeledFields);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Presentation(ScenarioPresentation baseline,ScenarioPresentation scenario) {}
}
