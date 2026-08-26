package com.steven.assets.bff.publicapi;

import java.time.LocalDate;

/** Complete minimal documents that exercise the strict public BFF response contracts. */
public final class PublicContractJsonFixtures {

    private PublicContractJsonFixtures() {}

    public static final String TRANSACTION_HISTORY = """
            {"selection":{"mode":"ALL","year":null,"start":null,"end":null},
            "allTimeSummary":{"buyCount":0,"sellCount":0,"totalBuyAmountTwd":0,"totalSellAmountTwd":0},
            "summary":{"buyCount":0,"sellCount":0,"totalBuyAmountTwd":0,"totalSellAmountTwd":0},
            "yearSummaries":[],"records":[]}
            """.replaceAll("\\s+", "");

    public static final String TRADING_RADAR_LIST = """
            {"ruleVersion":"TW_RULES_V17","actionPolicyVersion":"ACTION_POLICY_V1","generatedAt":"2026-08-26T00:00:00Z",
            "market":%s,"usMarket":%s,"stocks":[],"skippedNonTwStocks":0,"publicInformation":[]}
            """.formatted(marketSummary(), marketSummary()).replaceAll("\\s+", "");

    public static final String TRADING_RADAR_LIST_WITH_NULLABLE_STOCK = TRADING_RADAR_LIST.replace(
            "\"stocks\":[]", "\"stocks\":[" + nullableNestedListStock() + "]");

    public static String tradingCalendar(int year) {
        StringBuilder days = new StringBuilder();
        LocalDate current = LocalDate.of(year, 1, 1);
        LocalDate end = current.withMonth(12).withDayOfMonth(31);
        while (!current.isAfter(end)) {
            if (!days.isEmpty()) {
                days.append(',');
            }
            days.append("{\"date\":\"").append(current).append("\",\"weekday\":\"")
                    .append(current.getDayOfWeek()).append("\",\"isWeekend\":")
                    .append(current.getDayOfWeek().getValue() >= 6)
                    .append(",\"twTrading\":true,\"usTrading\":true,\"ukTrading\":true,")
                    .append("\"twHoliday\":false,\"usHoliday\":false,\"ukHoliday\":false}");
            current = current.plusDays(1);
        }
        return """
                {"year":%d,"generatedAt":"2026-08-26T00:00:00Z","timezone":"Asia/Taipei",
                "availableYears":[%d,%d,%d],"minYear":%d,"maxYear":%d,
                "markets":[
                  {"code":"TW","displayName":"台股","exchange":"TWSE","timezone":"Asia/Taipei","regularTradingHours":"09:00-13:30","daylightSavingSupported":false},
                  {"code":"US","displayName":"美股","exchange":"NYSE","timezone":"America/New_York","regularTradingHours":"09:30-16:00","daylightSavingSupported":true},
                  {"code":"UK","displayName":"英股","exchange":"LSE","timezone":"Europe/London","regularTradingHours":"08:00-16:30","daylightSavingSupported":true}],
                "availability":{"tw":{"status":"AVAILABLE","source":"TWSE/DGPA","message":null},"us":{"status":"AVAILABLE","source":"NYSE","message":null},"uk":{"status":"AVAILABLE","source":"LSE","message":null}},
                "tradingDayCount":{"tw":%d,"us":%d,"uk":%d},"holidays":{"tw":[],"us":[],"uk":[]},
                "days":[%s],
                "marketStatus":{"tw":{"marketOpen":false,"localTime":null,"displayTradingDate":null,"timezone":"Asia/Taipei"},"us":{"marketOpen":false,"localTime":null,"displayTradingDate":null,"timezone":"America/New_York"},"uk":{"marketOpen":false,"localTime":null,"displayTradingDate":null,"timezone":"Europe/London"}}}
                """.formatted(year, year - 1, year, year + 1, year - 1, year + 1,
                        0, 0, 0, days).replaceAll("\\s+", "");
    }

    private static String marketSummary() {
        return """
                {"regime":null,"regimeLabel":null,"score":null,"dataComplete":false,"stale":false,
                "asOfDate":null,"price":null,"changePercent":null,"quoteStatus":null,"weeklyMa":null,
                "monthlyMa":null,"quarterlyMa":null,"annualMa":null,"kValue":null,"dValue":null,
                "quarterlyConfirmation":null,"annualConfirmation":null,"reasons":[],"risks":[],"intraday":false,
                "liveUpdatedAt":null,"extendedIndicators":null,"marketVolumeRatio":null,"marketTurnoverRatio":null,
                "marketVolumeAsOfDate":null,"nasdaqChangePercent":null,"soxChangePercent":null,
                "usTechCompositePercent":null,"usTechAsOfDate":null,"usTechAvailable":false,"weeklyIndicators":null}
                """.replaceAll("\\s+", "");
    }

    private static String nullableNestedListStock() {
        return """
                {"stockCode":"2330","stockName":"測試標的","market":"台股","assetClass":"STOCK",
                "distributionAdjusted":false,"held":false,"fxPercentile":null,"underlyingCurrency":null,
                "fundamental":null,"shortAction":null,"shortActionLabel":null,"shortScore":null,
                "swingAction":null,"swingActionLabel":null,"swingScore":null,"action":null,"actionLabel":null,
                "score":null,"horizonConflict":false,"timingState":null,"timingLabel":null,"counterTrendState":null,
                "counterTrendLabel":null,"price":null,"changePercent":null,"quoteStatus":null,"etfPremiumLivePct":null,
                "etfPremiumLiveNavAsOf":null,"weeklyMa":null,"monthlyMa":null,"quarterlyMa":null,"annualMa":null,
                "kValue":null,"dValue":null,"kdHeat":null,"weeklyIndicators":null,"asOfDate":null}
                """.replaceAll("\\s+", "");
    }
}
