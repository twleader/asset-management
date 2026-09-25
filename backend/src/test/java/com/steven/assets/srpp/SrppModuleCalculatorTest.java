package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.service.SnapshotAggregateCalculator;
import com.steven.assets.service.StockPriceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 163／Task 452.7：五模組純函式計算。 */
class SrppModuleCalculatorTest {
    private static final SupportedPolicy PROPOSAL_POLICY = SrppTestData.policy(Map.of(
            "CASH:TWD:DEMAND", "0.4", "CASH:TWD:TERM", "0.4", "STOCK:台股:0050", "0.2"));

    private static ObjectNode ok(SrppTestData data, SupportedPolicy policy) {
        SrppModuleCalculator.Result result = SrppModuleCalculator.calculate(data.build(), policy, SrppTestData.DATE);
        assertThat(result.rejectReason()).isEmpty();
        return result.modules();
    }

    private static String reject(SrppTestData data) {
        SrppModuleCalculator.Result result = SrppModuleCalculator.calculate(data.build(), PROPOSAL_POLICY, SrppTestData.DATE);
        assertThat(result.modules()).isNull();
        return result.rejectReason().orElseThrow();
    }

    private static JsonNode check(ObjectNode modules, String name) {
        for (JsonNode check : modules.at("/assets/data/checks")) if (name.equals(check.path("name").asText())) return check;
        throw new AssertionError(name);
    }

    @Test
    void proposalSyntheticNumbersAndTolerances() {
        ObjectNode modules = ok(SrppTestData.proposal(), PROPOSAL_POLICY);
        JsonNode assets = modules.get("assets");
        assertThat(assets.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(assets.path("calculationId").asText()).isEqualTo("ASSET_MGMT_ASSETS_RECON_V1");
        List<String> names = new ArrayList<>();
        assets.at("/data/checks").forEach(c -> names.add(c.path("name").asText()));
        assertThat(names).containsExactlyElementsOf(SrppModuleCalculator.CHECK_NAMES);
        assertThat(check(modules, "snapshot_deposits").path("toleranceTwd").asText()).isEqualTo("300");
        assertThat(check(modules, "live_stocks").path("toleranceTwd").asText()).isEqualTo("200");
        assertThat(check(modules, "snapshot_total").path("toleranceTwd").asText()).isEqualTo("500");
        assertThat(check(modules, "snapshot_funds").path("toleranceTwd").asText()).isEqualTo("0.01");
        assertThat(check(modules, "live_total").path("differenceTwd").asText()).isEqualTo("0");
        assertThat(assets.at("/data/snapshotTotalAssets/value").asText()).isEqualTo("5000000");
        assertThat(assets.at("/data/rowCounts/deposits").asInt()).isEqualTo(2);
        JsonNode groups = assets.at("/data/depositGroups");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).path("depositType").asText()).isEqualTo("DEMAND");
        assertThat(groups.get(1).at("/estimatedAnnualInterest/value").asText()).isEqualTo("30000");
        assertThat(groups.get(1).at("/estimatedAnnualInterest/quality").asText()).isEqualTo("ESTIMATE");
        assertThat(groups.get(1).path("sourceRowIds").get(0).asText()).isEqualTo("DEPOSIT-1");
        assertThat(groups.get(1).path("bankId").asText()).isEqualTo("10");

        JsonNode allocation = modules.get("allocation");
        assertThat(allocation.path("status").asText()).isEqualTo("COMPLETE");
        JsonNode rows = allocation.at("/data/rows");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).path("assetKey").asText()).isEqualTo("CASH:TWD:DEMAND");
        assertThat(rows.get(0).at("/currentWeight/value").asText()).isEqualTo("0.2");
        assertThat(rows.get(0).at("/gapWeight/value").asText()).isEqualTo("0.2");
        assertThat(rows.get(0).at("/gapValueTwd/value").asText()).isEqualTo("1000000");
        assertThat(rows.get(2).path("market").asText()).isEqualTo("台股");
        assertThat(rows.get(2).path("symbol").asText()).isEqualTo("0050");
        assertThat(rows.get(2).at("/gapValueTwd/value").asText()).isEqualTo("-1000000");
        assertThat(rows.get(2).at("/gapValueTwd/sourceIds").toString()).isEqualTo("[\"assets\",\"policy\"]");

        JsonNode income = modules.get("cashIncome");
        assertThat(income.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(income.path("reasonCodes").toString()).isEqualTo("[\"NET_CALCULATION_NOT_VERIFIED\"]");
        assertThat(income.at("/data/sourceAccruedAnnualIncome/value").asText()).isEqualTo("90000");
        assertThat(income.at("/data/depositInterest/value").asText()).isEqualTo("30000");
        assertThat(income.at("/data/taxYear").asInt()).isEqualTo(2026);
        assertThat(income.at("/data/netCalculationStandard").isNull()).isTrue();
        for (String field : List.of("permanentTermInterestReinvested", "spendableAnnualGross", "taiwanIncomeTaxOrRefund",
                "usWithholding", "additionalBasicTax", "supplementaryNhi", "afterAllTaxAnnualCashIncome")) {
            JsonNode metric = income.at("/data/" + field);
            assertThat(metric.path("value").isNull()).as(field).isTrue();
            assertThat(metric.path("quality").asText()).isEqualTo("UNAVAILABLE");
            assertThat(metric.path("reasonCodes").toString()).isEqualTo("[\"NET_CALCULATION_NOT_VERIFIED\"]");
            assertThat(metric.path("sourceIds")).isEmpty();
        }
    }

    @Test
    void toleranceBoundaryEqualPassesAndOneCentMoreFails() {
        SrppTestData equal = SrppTestData.proposal();
        equal.totalDeposit = new BigDecimal("2999700");      // diff 300 == tolerance 300
        equal.totalAssets = new BigDecimal("4999700");
        equal.liveTotalDeposit = new BigDecimal("3000000");
        assertThat(SrppModuleCalculator.calculate(equal.build(), PROPOSAL_POLICY, SrppTestData.DATE).rejectReason()).isEmpty();

        SrppTestData over = SrppTestData.proposal();
        over.totalDeposit = new BigDecimal("2999699.99");   // diff 300.01 > tolerance 300
        over.liveTotalDeposit = new BigDecimal("3000000");
        assertThat(reject(over)).isEqualTo("RECONCILIATION_FAILED");

        SrppTestData zeroFunds = SrppTestData.proposal();
        zeroFunds.totalFundValue = new BigDecimal("0.01");
        zeroFunds.liveTotalFundValue = BigDecimal.ZERO;
        assertThat(SrppModuleCalculator.calculate(zeroFunds.build(), PROPOSAL_POLICY, SrppTestData.DATE).rejectReason()).isEmpty();
        zeroFunds.totalFundValue = new BigDecimal("0.02");
        assertThat(reject(zeroFunds)).isEqualTo("RECONCILIATION_FAILED");
    }

    @Test
    void identityMismatchesAreRejected() {
        SrppTestData wrongId = SrppTestData.proposal();
        wrongId.liveSnapshotId = 2L;
        assertThat(reject(wrongId)).isEqualTo("ASSET_IDENTITY_MISMATCH");

        SrppTestData wrongDate = SrppTestData.proposal();
        wrongDate.liveSnapshotDate = "2026-09-23";
        assertThat(reject(wrongDate)).isEqualTo("ASSET_IDENTITY_MISMATCH");

        SrppTestData missingHolding = SrppTestData.proposal();
        missingHolding.live.clear();
        missingHolding.liveStockValue = BigDecimal.ZERO;
        assertThat(reject(missingHolding)).isEqualTo("ASSET_IDENTITY_MISMATCH");

        SrppTestData wrongMarket = SrppTestData.proposal();
        StockPriceService.LiveStockItem item = wrongMarket.live.remove(0);
        wrongMarket.live.add(new StockPriceService.LiveStockItem(item.stockCode(), item.stockName(), "美股",
                item.shares(), item.currentPrice(), item.liveValue(), item.closed(), item.tradingDate(), null, null,
                null, item.quoteStatus(), item.holdingId(), item.source(), item.targetTradingDate(),
                item.valuationSource(), null));
        assertThat(reject(wrongMarket)).isEqualTo("ASSET_IDENTITY_MISMATCH");

        SrppTestData wrongShares = SrppTestData.proposal();
        item = wrongShares.live.remove(0);
        wrongShares.live.add(new StockPriceService.LiveStockItem(item.stockCode(), item.stockName(), item.market(),
                new BigDecimal("10001"), item.currentPrice(), item.liveValue(), item.closed(), item.tradingDate(), null,
                null, null, item.quoteStatus(), item.holdingId(), item.source(), item.targetTradingDate(),
                item.valuationSource(), null));
        assertThat(reject(wrongShares)).isEqualTo("ASSET_IDENTITY_MISMATCH");
    }

    @Test
    void sharesScaleDifferencesAreEqual() {
        SrppTestData data = new SrppTestData().deposit(1, 10L, "銀行", "DEMAND", "100", "100", "TWD", null)
                .stock(5, "台股", "2330", "2", "2000", "10");
        StockPriceService.LiveStockItem item = data.live.remove(0);
        data.live.add(new StockPriceService.LiveStockItem(item.stockCode(), item.stockName(), item.market(),
                new BigDecimal("2.00000"), item.currentPrice(), item.liveValue(), item.closed(), item.tradingDate(),
                null, null, null, item.quoteStatus(), item.holdingId(), item.source(), item.targetTradingDate(),
                item.valuationSource(), null));
        assertThat(SrppModuleCalculator.calculate(data.build(), PROPOSAL_POLICY, SrppTestData.DATE).rejectReason()).isEmpty();
    }

    @Test
    void negativePayableTransitNullBankAndUsdAreKeptWithoutReconversion() {
        SrppTestData data = new SrppTestData()
                .deposit(1, null, null, "買股待付款", "-31004", "-31004", "TRANSIT_TWD", "2")
                .deposit(2, 20L, "美元銀行", "活存", "325000", "10000", "USD", "4")
                .deposit(3, null, null, "現金", "5000", null, "TWD", null)
                .stock(1, "台股", "0050", "1000", "200000", "5000");
        ObjectNode modules = ok(data, SrppTestData.policy(Map.of()));
        JsonNode groups = modules.at("/assets/data/depositGroups");
        assertThat(groups).hasSize(3);
        // currency → depositType → bankId（null 最前）
        assertThat(groups.get(0).path("currency").asText()).isEqualTo("TRANSIT_TWD");
        assertThat(groups.get(0).path("bankId").isNull()).isTrue();
        assertThat(groups.get(0).path("bankName").isNull()).isTrue();
        assertThat(groups.get(0).path("amountTwd").asText()).isEqualTo("-31004");
        assertThat(groups.get(0).at("/estimatedAnnualInterest/value").asText()).isEqualTo("0");
        assertThat(groups.get(1).path("currency").asText()).isEqualTo("TWD");
        assertThat(groups.get(1).path("originalAmount").isNull()).isTrue();
        assertThat(groups.get(2).path("currency").asText()).isEqualTo("USD");
        assertThat(groups.get(2).path("amountTwd").asText()).isEqualTo("325000");      // 不再乘匯率
        assertThat(groups.get(2).path("originalAmount").asText()).isEqualTo("10000");
        assertThat(groups.get(2).at("/estimatedAnnualInterest/value").asText()).isEqualTo("13000");
        assertThat(modules.at("/assets/data/snapshotTotalDeposit/value").asText()).isEqualTo("298996");
        JsonNode rows = modules.at("/allocation/data/rows");
        assertThat(rows.get(0).path("assetKey").asText()).isEqualTo("CASH:TRANSIT_TWD:買股待付款");
        assertThat(rows.get(0).at("/exposureTwd/value").asText()).isEqualTo("-31004");
        assertThat(rows.get(0).path("sourceHoldingIds").toString()).isEqualTo("[\"DEPOSIT-1\"]");
    }

    @Test
    void nullOrUnsupportedCurrencyIsRejected() {
        assertThat(reject(new SrppTestData().deposit(1, 1L, "b", "活存", "100", null, null, null)))
                .isEqualTo("UNSUPPORTED_DEPOSIT_CURRENCY");
        assertThat(reject(new SrppTestData().deposit(1, 1L, "b", "活存", "100", null, "JPY", null)))
                .isEqualTo("UNSUPPORTED_DEPOSIT_CURRENCY");
    }

    @Test
    void nullAmountIsMissing() {
        SrppTestData data = new SrppTestData().deposit(1, 1L, "b", "活存", null, null, "TWD", null);
        data.totalDeposit = BigDecimal.ZERO;
        assertThat(reject(data)).isEqualTo("ASSET_AMOUNT_MISSING");
    }

    @Test
    void allocationUnionMarketsBrokersUnmappedTargetsAndFundsWithoutCode() {
        SrppTestData data = new SrppTestData()
                .deposit(1, 1L, "b", "活存", "500000", null, "TWD", null)
                .stock(3, "台股", "0050", "100", "100000", "1000")
                .stock(1, "台股", "0050", "200", "200000", "2000")            // 同市場同代號跨券商合併
                .stock(2, "美股", "0050", "10", "50000", "100")               // 不同市場同代號不合併
                .fund(1, "F001", "100000", "3000")
                .fund(2, " ", "50000", "500");                                // 無代號 → unmapped
        SupportedPolicy policy = SrppTestData.policy(Map.of("STOCK:台股:0050", "0.3", "FUND:NOT_HELD", "0.1"));
        ObjectNode modules = ok(data, policy);
        JsonNode allocation = modules.get("allocation");
        assertThat(allocation.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(allocation.path("reasonCodes").toString()).isEqualTo("[\"HOLDING_NOT_MAPPED\",\"TARGET_NOT_MAPPED\"]");
        assertThat(allocation.at("/data/unmappedHoldingIds").toString()).isEqualTo("[\"FUND-2\"]");
        List<String> keys = new ArrayList<>();
        allocation.at("/data/rows").forEach(r -> keys.add(r.path("assetKey").asText()));
        assertThat(keys).containsExactly("CASH:TWD:活存", "FUND:F001", "FUND:NOT_HELD", "STOCK:台股:0050", "STOCK:美股:0050");
        JsonNode tw = allocation.at("/data/rows/3");
        assertThat(tw.path("sourceHoldingIds").toString()).isEqualTo("[\"STOCK-1\",\"STOCK-3\"]");
        assertThat(tw.at("/exposureTwd/value").asText()).isEqualTo("300000");
        JsonNode notHeld = allocation.at("/data/rows/2");
        assertThat(notHeld.at("/exposureTwd/value").asText()).isEqualTo("0");
        assertThat(notHeld.path("sourceHoldingIds")).isEmpty();
        assertThat(notHeld.path("market").isNull()).isTrue();
        assertThat(notHeld.at("/gapValueTwd/value").asText()).isEqualTo("100000");
        JsonNode unmappedTarget = allocation.at("/data/rows/1");
        assertThat(unmappedTarget.at("/targetWeight/quality").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unmappedTarget.at("/targetWeight/value").isNull()).isTrue();
        assertThat(unmappedTarget.at("/gapWeight/reasonCodes").toString()).isEqualTo("[\"TARGET_NOT_MAPPED\"]");
        assertThat(unmappedTarget.at("/gapValueTwd/sourceIds")).isEmpty();
        // currentWeight = 100000 / 1000000 以 MathContext(34, HALF_EVEN)
        assertThat(unmappedTarget.at("/currentWeight/value").asText()).isEqualTo("0.1");
    }

    @Test
    void repeatingWeightUsesThirtyFourSignificantDigits() {
        SrppTestData data = new SrppTestData()
                .deposit(1, 1L, "b", "活存", "100", null, "TWD", null)
                .deposit(2, 1L, "b", "定存", "200", null, "TWD", null);
        ObjectNode modules = ok(data, SrppTestData.policy(Map.of()));
        assertThat(modules.at("/allocation/data/rows/0/currentWeight/value").asText())
                .isEqualTo("0.6666666666666666666666666666666667");
    }

    @Test
    void nonPositiveDenominatorMakesAllocationUnavailable() {
        SrppTestData data = new SrppTestData().deposit(1, 1L, "b", "買股待付款", "-100", null, "TRANSIT_TWD", null);
        ObjectNode modules = ok(data, PROPOSAL_POLICY);
        JsonNode allocation = modules.get("allocation");
        assertThat(allocation.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(allocation.path("data").isNull()).isTrue();
        assertThat(allocation.path("reasonCodes").toString()).isEqualTo("[\"DENOMINATOR_NOT_POSITIVE\"]");
        assertThat(allocation.path("sourceIds")).isEmpty();

        SrppTestData zero = new SrppTestData();
        assertThat(ok(zero, PROPOSAL_POLICY).at("/allocation/status").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void missingIncomeRowsAreLowerBoundAndMismatchIsFlagged() {
        SrppTestData data = new SrppTestData()
                .deposit(1, 1L, "b", "活存", "1000000", null, "TWD", "1")
                .stock(2, "台股", "0056", "100", "300000", null)
                .stock(1, "台股", "0050", "100", "200000", "4000")
                .fund(1, "F1", "100000", null);
        data.estimatedAnnualDividend = new BigDecimal("99999");
        ObjectNode modules = ok(data, SrppTestData.policy(Map.of()));
        JsonNode income = modules.get("cashIncome");
        assertThat(income.path("reasonCodes").toString())
                .isEqualTo("[\"INCOME_RECONCILIATION_MISMATCH\",\"INCOME_ROWS_MISSING\",\"NET_CALCULATION_NOT_VERIFIED\"]");
        assertThat(income.at("/data/missingIncomeRowIds").toString()).isEqualTo("[\"FUND-1\",\"STOCK-2\"]");
        assertThat(income.at("/data/stockAndEtfDistributions/quality").asText()).isEqualTo("LOWER_BOUND");
        assertThat(income.at("/data/stockAndEtfDistributions/value").asText()).isEqualTo("4000");
        assertThat(income.at("/data/stockAndEtfDistributions/reasonCodes").toString()).isEqualTo("[\"INCOME_ROWS_MISSING\"]");
        assertThat(income.at("/data/fundDistributions/quality").asText()).isEqualTo("LOWER_BOUND");
        assertThat(income.at("/data/depositInterest/quality").asText()).isEqualTo("ESTIMATE");
        assertThat(income.at("/data/depositInterest/value").asText()).isEqualTo("10000");
        // sourceAccrued 直接輸出快照值（非三項和 14000），缺列時品質為 LOWER_BOUND。
        assertThat(income.at("/data/sourceAccruedAnnualIncome/quality").asText()).isEqualTo("LOWER_BOUND");
        assertThat(income.at("/data/sourceAccruedAnnualIncome/value").asText()).isEqualTo("99999");
        assertThat(income.at("/data/sourceAccruedAnnualIncome/reasonCodes").toString()).isEqualTo("[\"INCOME_ROWS_MISSING\"]");
    }

    @Test
    void sourceAccruedIsSnapshotValueEvenWhenClassifiedSumDiffers() {
        SrppTestData data = SrppTestData.proposal();
        data.estimatedAnnualDividend = new BigDecimal("91234.56");
        JsonNode income = ok(data, PROPOSAL_POLICY).get("cashIncome");
        assertThat(income.at("/data/sourceAccruedAnnualIncome").toString()).isEqualTo(
                "{\"value\":\"91234.56\",\"unit\":\"TWD\",\"quality\":\"ESTIMATE\",\"reasonCodes\":[],\"sourceIds\":[\"assets\"]}");
        assertThat(income.path("reasonCodes").toString())
                .isEqualTo("[\"INCOME_RECONCILIATION_MISMATCH\",\"NET_CALCULATION_NOT_VERIFIED\"]");

        SrppTestData withinTolerance = SrppTestData.proposal();
        withinTolerance.estimatedAnnualDividend = new BigDecimal("90009");   // 容差 max(0.01, 90009×0.0001)=9.0009
        JsonNode ok = ok(withinTolerance, PROPOSAL_POLICY).get("cashIncome");
        assertThat(ok.at("/data/sourceAccruedAnnualIncome/value").asText()).isEqualTo("90009");
        assertThat(ok.path("reasonCodes").toString()).isEqualTo("[\"NET_CALCULATION_NOT_VERIFIED\"]");
    }

    @Test
    void nullSnapshotEstimatedDividendIsUnavailableAndNotMismatch() {
        SrppTestData data = new SrppTestData()
                .deposit(1, 1L, "b", "活存", "1000000", null, "TWD", "1")
                .stock(1, "台股", "0050", "100", "200000", null);
        data.estimatedAnnualDividendNull = true;
        JsonNode income = ok(data, SrppTestData.policy(Map.of())).get("cashIncome");
        assertThat(income.at("/data/sourceAccruedAnnualIncome").toString()).isEqualTo(
                "{\"value\":null,\"unit\":\"TWD\",\"quality\":\"UNAVAILABLE\","
                        + "\"reasonCodes\":[\"SNAPSHOT_ESTIMATED_DIVIDEND_MISSING\"],\"sourceIds\":[]}");
        assertThat(income.path("reasonCodes").toString()).isEqualTo(
                "[\"INCOME_ROWS_MISSING\",\"NET_CALCULATION_NOT_VERIFIED\",\"SNAPSHOT_ESTIMATED_DIVIDEND_MISSING\"]");
        assertThat(income.at("/data/stockAndEtfDistributions/quality").asText()).isEqualTo("LOWER_BOUND");
        assertThat(income.at("/data/depositInterest/value").asText()).isEqualTo("10000");
    }

    @Test
    void depositInterestDelegatesToSharedFormula() {
        for (String[] c : new String[][]{{"TWD", "1.5"}, {"USD", "4.25"}, {"TRANSIT_TWD", "2"}, {"TRANSIT_USD", "2"},
                {"TWD", null}, {"TWD", "0"}, {"TWD", "-1"}}) {
            SrppTestData data = new SrppTestData().deposit(1, 1L, "b", "D", "123457", null, c[0], c[1]);
            AssetSnapshotDto.DepositResponse deposit = data.snapshot().deposits().get(0);
            assertThat(SrppModuleCalculator.depositInterest(deposit)).as(c[0] + "/" + c[1]).isEqualTo(
                    SnapshotAggregateCalculator.depositEstimatedInterest(
                            deposit.amount(), deposit.annualInterestRate(), deposit.currency()));
        }
    }

    @Test
    void targetPriceIncompleteMakesAssetsPartial() {
        SrppTestData data = SrppTestData.proposal();
        data.targetPriceComplete = false;
        JsonNode assets = ok(data, PROPOSAL_POLICY).get("assets");
        assertThat(assets.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(assets.path("reasonCodes").toString()).isEqualTo("[\"TARGET_PRICE_INCOMPLETE\"]");
    }

    @Test
    void fundingAndTechnicalsAreFixedUnavailable() {
        ObjectNode modules = ok(SrppTestData.proposal(), PROPOSAL_POLICY);
        assertThat(modules.get("funding").toString()).isEqualTo("{\"status\":\"UNAVAILABLE\",\"reasonCodes\":"
                + "[\"CALCULATOR_NOT_VERIFIED\"],\"sourceIds\":[],\"calculationId\":\"ASSET_MGMT_FUNDING_UNAVAILABLE_V1\",\"data\":null}");
        assertThat(modules.get("completedTechnicals").toString()).isEqualTo("{\"status\":\"UNAVAILABLE\",\"reasonCodes\":"
                + "[\"CALCULATOR_NOT_VERIFIED\"],\"sourceIds\":[],\"calculationId\":\"ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1\",\"data\":null}");
        String all = modules.toString();
        for (String forbidden : List.of("headroom", "ma20", "CORE_SELL_READY", "kdLastThreeCompletedSessions", "buyable")) {
            assertThat(all).doesNotContain(forbidden);
        }
    }
}
