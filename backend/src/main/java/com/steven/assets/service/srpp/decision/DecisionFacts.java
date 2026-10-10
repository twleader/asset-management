package com.steven.assets.service.srpp.decision;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Frozen domain facts; no IO or Spring dependencies. Missing evidence remains explicit. */
public final class DecisionFacts {
    private DecisionFacts() {}
    public record Policy(String bundleHash,String modelHash,String manifestHash,String decision,String policyVersion,String modelVersion,
                         List<String> symbols,Map<String,BigDecimal> targets,int lookback,BigDecimal minDrawdown,BigDecimal maxPremium,
                         BigDecimal pausePremium,int dailyCap,int weeklyCap,int emergencyDailyCap,BigDecimal emergencyTwoDrawdown,
                         BigDecimal emergencyTarget,BigDecimal termFloor,BigDecimal totalFloor,BigDecimal inflation,LocalDate baseDate,
                         BigDecimal feeRate,BigDecimal fxMax,BigDecimal intermediateShareCap,BigDecimal totalBondCap,BigDecimal normalEquityFloor,Set<String> nonDistributing,
                         int acquisitionMinutes,int limitMinutes,int earlyVolumeBelow) {public Policy{symbols=List.copyOf(symbols);targets=Map.copyOf(targets);nonDistributing=Set.copyOf(nonDistributing);}}
    public record Quote(BigDecimal price,BigDecimal previousClose,Long volume,LocalDate date,Instant updatedAt,String source,String status,Boolean closed,
                        BigDecimal premium,LocalDate premiumDate) {}
    public record Level(BigDecimal bid,long bidLots,BigDecimal ask,long askLots) {}
    public record Book(String source,LocalDate date,Instant sourceTime,Instant fetchedAt,List<Level> levels,boolean valid) {public Book{levels=List.copyOf(levels);}}
    public record Close(LocalDate date,BigDecimal price,boolean trusted) {}
    public record Distribution(LocalDate exDate,LocalDate rightsDate,BigDecimal cash,BigDecimal stock,String source) {}
    public record Evidence(String status,String reason) {public boolean verified(){return "PASS".equals(status);}}
    public record Symbol(String code,Quote quote,Book book,List<Close> closes,List<Distribution> distributions,boolean distributionVerified,
                         BigDecimal currentValue,Evidence position,Evidence pending,Evidence radar,Evidence valuation,Evidence tradability,
                         String mediumAction,String mediumCandidateAction,Evidence intraday) {public Symbol{closes=List.copyOf(closes);distributions=List.copyOf(distributions);}}
    public record Budget(int strategyToday,int strategyWeek,int emergencyToday,boolean verified,BigDecimal reservedFunds,Map<String,BigDecimal> strategicReservedValues,BigDecimal emergencyReservedValue,BigDecimal reservedBondValue,BigDecimal reservedFees) {public Budget{strategicReservedValues=Map.copyOf(strategicReservedValues);}}
    public record Input(LocalDate date,String slot,Instant capturedAt,List<LocalDate> sessions,BigDecimal totalAssets,
                        BigDecimal totalDeposits,BigDecimal termDeposits,BigDecimal bondValue,BigDecimal fx,
                        Evidence fxEvidence,Evidence bearWindow,Map<String,Symbol> symbols,Budget budget,
                        Evidence subaccounts,BigDecimal emergencyValue) {public Input{sessions=List.copyOf(sessions);symbols=Map.copyOf(symbols);}}
    public record Receipt(String ruleId,String calculatorVersion,List<String> inputRefs,String status,String reason,Map<String,String> values) {public Receipt{inputRefs=List.copyOf(inputRefs);values=Map.copyOf(values);}}
    public record Candidate(String symbol,String strategy,String status,String action,int lots,BigDecimal limitPrice,BigDecimal amountTwd,
                            List<String> blockingReasons,List<Receipt> receipts) {public Candidate{blockingReasons=List.copyOf(blockingReasons);receipts=List.copyOf(receipts);}}
    public record Result(List<Candidate> candidates,List<String> ranking) {public Result{candidates=List.copyOf(candidates);ranking=List.copyOf(ranking);}}
}
