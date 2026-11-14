package com.steven.assets.bff.portfolioadvice;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import java.util.List;
import java.util.Map;

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
            AgeQuantiles depletionAge,List<String> modeledFields,List<String> notModeledFields,Assumptions assumptions,
            @JsonInclude(JsonInclude.Include.NON_NULL) ReserveStrategyAudit reserveStrategyAudit) {
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
            String returnMeanSource,String equityMeanConversion,List<String> approximationNotes,
            @JsonInclude(JsonInclude.Include.NON_NULL) String birthDate,
            @JsonInclude(JsonInclude.Include.NON_NULL) CashflowSchedule cashflowSchedule,
            @JsonInclude(JsonInclude.Include.NON_NULL) ReserveStrategy reserveStrategy) {
        public Assumptions {approximationNotes=list(approximationNotes);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CashflowSchedule(LaborPension laborPension,LaborInsurance laborInsurance,
            RecurringExpense homeRepair,RecurringExpense appliance,List<MajorExpense> majorExpenses,Payroll payroll) {
        public CashflowSchedule {majorExpenses=list(majorExpenses);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LaborPension(String mode,String receiptDate,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amountNominalTwd,boolean included) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LaborInsurance(String mode,String startDate,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal monthlyNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal asOfMonthlyNominalTwd,String indexationBasis) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecurringExpense(int firstYear,String eventMonthDay,int intervalYears,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amountRealTwd) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MajorExpense(String date,String name,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amountRealTwd,
            boolean included,String exclusionReason) {}
    /** Source configuration preserves optional members exactly; it is not an untyped JSON escape hatch. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DatedBonus(String name,@JsonProperty("pay_date") String payDate,double months,
            @JsonProperty("festival_date") String festivalDate) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecurringBonus(String name,double months,int month,@JsonProperty("month_by_year") Map<String,Integer> monthByYear) {
        public RecurringBonus {monthByYear=monthByYear==null?null:Map.copyOf(monthByYear);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Payroll(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal monthlySalaryNominalTwd,int payday,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal annualRaiseAmountNominalTwd,String annualRaiseFirstDate,
            double withholdingRate,double selfPensionRate,int nhiDependents,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal monthlyExpenseRealTwd,
            List<DatedBonus> bonusEvents,List<RecurringBonus> recurringBonuses,List<PayrollEntry> ledger,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal receivedByRetirementNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal receivableAfterRetirementNominalTwd,
            @JsonInclude(JsonInclude.Include.NON_NULL) AnnualIncomeTax annualIncomeTax) {
        public Payroll {bonusEvents=list(bonusEvents);recurringBonuses=list(recurringBonuses);ledger=list(ledger);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AnnualIncomeTax(String scope,int ruleYear,String sourceUrl,String futureYearPolicy,
            String historicalBasis,List<AnnualIncomeTaxYear> years,List<String> notes) {
        public AnnualIncomeTax {years=list(years);notes=list(notes);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AnnualIncomeTaxYear(int taxYear,
            @JsonFormat(shape=JsonFormat.Shape.STRING,pattern="yyyy-MM-dd")
            @JsonSerialize(using=LocalDateSerializer.class) @JsonDeserialize(using=LocalDateDeserializer.class) LocalDate settlementDate,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal grossSalaryNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal selfPensionNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal taxableSalaryNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal personalExemptionNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal standardDeductionNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal salaryDeductionNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal taxableIncomeNominalTwd,
            double marginalRate,double effectiveRate,List<IncomeTaxBracket> brackets,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal liabilityNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal withheldNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal settlementNominalTwd,IncomeTaxTreatment treatment) {
        public AnnualIncomeTaxYear {brackets=list(brackets);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record IncomeTaxBracket(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal lowerNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal upperNominalTwd,double rate,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal taxableSliceNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal taxNominalTwd) {}
    /** Closed wire treatment codes, not a user-managed business classification. */
    public enum IncomeTaxTreatment { SETTLED_BEFORE_AS_OF,CASH_SETTLEMENT,TERMINAL_ACCRUAL }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PayrollEntry(String payDate,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal contractualMonthlySalaryNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal regularSalaryNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal bonusNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal grossPayNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal laborInsuranceNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal healthInsuranceNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal selfPensionNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal withholdingNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal supplementalHealthNominalTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal netCashNominalTwd,
            boolean paymentAfterRetirement,boolean includedAtRetirement,String yearRuleStatus,List<PayrollBonus> bonusEvents) {
        public PayrollEntry {bonusEvents=list(bonusEvents);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PayrollBonus(String name,double months,@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amountNominalTwd,
            String festivalDate,String timingStatus) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BearRung(@JsonProperty("drawdown") double drawdown,
            @JsonProperty("share_of_window_budget") double shareOfWindowBudget) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BullRung(@JsonProperty("gain_above_anchor") double gainAboveAnchor,
            @JsonProperty("pullback_from_high_watermark") double pullbackFromHighWatermark,
            @JsonProperty("share_of_window_budget") double shareOfWindowBudget) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReserveStrategy(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal emergencyDepositReserveRealTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal emergencyEtfReserveRealTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal protectedReserveRealTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal livingReserveCeilingRealTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal livingReserveFloorRealTwd,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal bearLivingDeploymentCapRealTwd,
            boolean bullStrategyEnabled,boolean bearStrategyEnabled,int tacticalWaitMonths,double bearBondSaleMaxShare,
            double minimumBondWeight,double bearEquityCap,double bullEquityFloor,
            @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal marketSpendingAdjustmentRealTwd,
            List<BearRung> bearLadder,List<BullRung> bullLadder,String signalBasis,boolean liquidityBranchesEnabled) {
        public ReserveStrategy {bearLadder=list(bearLadder);bullLadder=list(bullLadder);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReserveStrategyAudit(MoneyQuantiles openingCashTransferRealTwd,MoneyQuantiles openingLivingShortfallRealTwd,
            int monthUpdateCount,int bearFillCount,int bullFillCount,int bearMaximumWindowFills,int bullMaximumWindowFills,
            double bearMaximumFirstBatchShare,double bullMaximumFirstBatchShare,List<MonthlyStrategyAudit> monthlyAudit) {
        public ReserveStrategyAudit {monthlyAudit=list(monthlyAudit);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MonthlyStrategyAudit(String date,int bullSignalPathCount,int bearSignalPathCount,int bullFillPathCount,
            int bearFillPathCount,MoneyQuantiles bullEquitySoldRealTwd,MoneyQuantiles bearCashDeployedRealTwd,
            MoneyQuantiles bearBondsSoldRealTwd,MoneyQuantiles livingRefillRealTwd,MoneyQuantiles livingShortfallRealTwd) {}
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
