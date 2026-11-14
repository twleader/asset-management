package com.steven.assets.dto;

import com.fasterxml.jackson.annotation.*;
import java.util.List;

/** Immutable page-owned observations. No price or action in this contract authorizes a trade. */
public final class SrppShortTermDto {
    private SrppShortTermDto() {}
    private static <T> List<T> list(List<T> v){return v==null?List.of():List.copyOf(v);}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ListEnvelope(String configurationStatus,boolean thresholdsAppliedToRadar,String policyBundleSha256,
            String modelInputsSha256,String ruleVersion,String actionPolicyVersion,String generatedAt,List<Market> markets,
            List<Policy> policies,List<Row> rows,List<MissingSymbol> missingSymbols,List<String> limitations,boolean tradeAuthorization) {
        public ListEnvelope {if(thresholdsAppliedToRadar||tradeAuthorization)throw new IllegalArgumentException("SHORT_OBSERVATION_ONLY");markets=list(markets);policies=list(policies);rows=list(rows);missingSymbols=list(missingSymbols);limitations=list(limitations);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Market(String market,boolean stale,boolean dataComplete,String asOfDate,String quoteStatus) {}
    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME,include=JsonTypeInfo.As.EXISTING_PROPERTY,property="group",visible=true)
    @JsonSubTypes({@JsonSubTypes.Type(value=TaiwanPolicy.class,name="TW_EQUITY"),
            @JsonSubTypes.Type(value=TaiwanPolicy.class,name="TW_BOND"),@JsonSubTypes.Type(value=UsPolicy.class,name="US")})
    public sealed interface Policy permits TaiwanPolicy,UsPolicy {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SixPolicy(List<String> symbols,double reboundPct,double netRewardRisk,double sleeveCap,double positionCap,
            int maxHoldingSessions) {public SixPolicy {symbols=list(symbols);}}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsValues(double netUpsidePct,double batchCap,double companyCap,double sleeveCap) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TaiwanPolicy(String group,String label,SixPolicy formalValues,SixPolicy draftValues,String entryRule,
            String exitRule,String retirementImpact,String entrySummary,String exitSummary) implements Policy {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsPolicy(String group,String label,UsValues formalValues,UsValues draftValues,String entryRule,
            String exitRule,String retirementImpact,String entrySummary,String exitSummary) implements Policy {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(String stockCode,String stockName,String market,String group,boolean held,String shortAction,
            String shortActionLabel,Integer shortScore,String observationType,String status,String price,String quoteStatus,
            String priceUpdatedAt,String dailyCandleAsOfDate,boolean horizonConflict) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MissingSymbol(String stockCode,String market,String group,String status) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PriceReference(String buyLower,String buyUpper,String sellLower,String sellUpper,String asOfDate,String applicableSide) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DetailEnvelope(String policyBundleSha256,String modelInputsSha256,String ruleVersion,String actionPolicyVersion,
            String generatedAt,String stockCode,String stockName,String market,String group,boolean held,String observationType,
            String status,boolean dataComplete,String shortAction,String shortActionLabel,Integer shortScore,List<String> shortReasons,
            List<String> shortRisks,Integer shortEvidenceConfidence,Integer shortDownsideRisk,Double shortRiskCoverage,
            boolean horizonConflict,String price,String quoteStatus,String priceUpdatedAt,String dailyCandleAsOfDate,
            PriceReference priceReference,List<String> limitations,boolean tradeAuthorization) {
        public DetailEnvelope {if(tradeAuthorization)throw new IllegalArgumentException("SHORT_OBSERVATION_ONLY");shortReasons=list(shortReasons);shortRisks=list(shortRisks);limitations=list(limitations);}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Problem(String code,String message) {}
}
