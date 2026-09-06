package com.steven.assets.integration.fubon;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

final class FubonAccountingFixtures {
    static final LocalDate DATE = LocalDate.of(2026, 8, 28);
    static final Instant NOW = Instant.parse("2026-08-28T00:00:30Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String FINGERPRINT = "0123456789abcdef01234567";
    static final String HEADER = "\"queryDate\":\"2026-08-28\",\"observedAt\":\"2026-08-28T00:00:01Z\","
            + "\"accountFingerprint\":\"" + FINGERPRINT + "\",";
    static final String ACCOUNT_BOUND_HEADER = HEADER + "\"accountBindingExplicit\":true,";
    static final String SETTLEMENT_ROW = """
            {"status":"AVAILABLE","sourceQueryDate":"2026-08-28","settlementDate":"2026-09-01","currency":"TWD",
             "buyValue":"1000","buyFee":"2","buySettlement":"-1002","buyTax":"0",
             "sellValue":"0","sellFee":"0","sellSettlement":"0","sellTax":"0",
             "totalBsValue":"1000","totalFee":"2","totalTax":"0","totalSettlementAmount":"-1002"}
            """;
    static final String NO_DATA_ROW = """
            {"status":"NO_DATA_OBSERVED","sourceQueryDate":"2026-08-27","settlementDate":null,"currency":null,
             "buyValue":null,"buyFee":null,"buySettlement":null,"buyTax":null,
             "sellValue":null,"sellFee":null,"sellSettlement":null,"sellTax":null,
             "totalBsValue":null,"totalFee":null,"totalTax":null,"totalSettlementAmount":null}
            """;
    static final String REALIZED_ROW = """
            {"stockNo":"2330","buySell":"Sell","orderType":"Stock","filledQty":1000,"filledPrice":"123.5",
             "realizedProfit":"0","realizedLoss":"20","sourceDate":"2026-08-27"}
            """;

    static String bankJson(String amount) {
        return "{" + HEADER + "\"currency\":\"TWD\",\"balance\":\"" + amount + "\",\"availableBalance\":\"0\"}";
    }
    static String settlementJson(String rows) {
        return "{" + ACCOUNT_BOUND_HEADER + "\"coverageStatus\":\"SDK_RANGE_3D_RETURNED_ROWS\",\"reason\":null,\"details\":[" + rows + "]}";
    }
    static String realizedJson(String rows) { return "{" + ACCOUNT_BOUND_HEADER + "\"rows\":[" + rows + "]}"; }

    static FubonDtos.BankBalance bank(String amount) {
        return new FubonDtos.BankBalance(DATE, NOW.minusSeconds(29), FINGERPRINT, "TWD",
                CanonicalFubonDecimal.parseNonNegative(amount), CanonicalFubonDecimal.parseNonNegative("0"));
    }
    static FubonDtos.SettlementBatch settlement() { return read(settlementJson(SETTLEMENT_ROW), FubonDtos.SettlementBatch.class); }
    static FubonDtos.RealizedGainBatch realized() { return read(realizedJson(REALIZED_ROW), FubonDtos.RealizedGainBatch.class); }
    static <T> T read(String json, Class<T> type) {
        try { return FubonAccountingJson.mapper().readValue(json, type); }
        catch (Exception failure) { throw new AssertionError("Invalid synthetic accounting fixture", failure); }
    }
}
