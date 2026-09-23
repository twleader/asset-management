package com.steven.assets.bff.tradingradar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Synthetic browser contract fixtures, containing no captured owner data. */
public final class TradingRadarPanelFixtures {
    public static final String JOB_ID = "4ca5af24-c4dc-48ee-b679-e1fcaaf26556";
    public static final String OTHER_JOB_ID = "d5ec8620-dce2-40f1-a711-639e748c1b42";
    private static final ObjectMapper JSON = new ObjectMapper();

    private TradingRadarPanelFixtures() {}

    public static ObjectNode panel(String name) {
        ObjectNode result = envelope();
        result.put("panel", name);
        ObjectNode data = result.putObject("data");
        if (name.equals("public-information")) {
            data.putArray("publicInformation").add(json("""
                    {"region":"TW","title":"合成新聞","source":"Fixture","url":"https://example.test/news",
                     "publishedAt":"2026-09-23T00:00:00Z","summary":null,"knownAt":"2026-09-23T00:01:00Z",
                     "availabilityBasis":"FETCHED_AT"}
                    """));
        } else {
            data.set("market", market());
            if (name.endsWith("-stocks")) {
                data.putArray("stocks").add(summary(name.startsWith("tw") ? "台股" : "美股",
                        name.startsWith("tw") ? "2330" : "AAPL"));
                data.put("skippedNonTwStocks", 3);
            }
        }
        return result;
    }

    public static ObjectNode evaluation(String market, String code) {
        ObjectNode result = envelope();
        result.set("market", market());
        result.set("summary", summary(market, code));
        ObjectNode stock = summary(market, code);
        stock.remove("dailyCandleAsOfDate");
        for (String field : new String[]{"reasons", "risks", "shortReasons", "shortRisks", "swingReasons", "swingRisks"}) {
            stock.putArray(field).add("合成說明");
        }
        stock.putObject("evidence");
        stock.putNull("dailyCandle");
        stock.putNull("extendedIndicators");
        result.set("stock", stock);
        return result;
    }

    public static ObjectNode job(String id, String status) {
        ObjectNode result = json("""
                {"jobId":"%s","status":"%s","createdAt":"2026-09-23T09:00:00+08:00",
                 "completedAt":null,"priceRefresh":null}
                """.formatted(id, status));
        if ("COMPLETED".equals(status)) {
            result.put("completedAt", "2026-09-23T09:00:05+08:00");
            result.set("priceRefresh", json("{\"outcome\":\"FETCHED\",\"twMarketOpen\":true,\"elapsedMs\":5000}"));
        }
        return result;
    }

    public static ObjectNode market() {
        return json("""
                {"regime":"NEUTRAL","regimeLabel":"中性","score":50,"dataComplete":true,"stale":false,
                 "asOfDate":"2026-09-22","price":22000.12,"changePercent":0.1,"quoteStatus":"VERIFIED_CLOSE",
                 "weeklyMa":21000.1,"monthlyMa":21000.1,"quarterlyMa":21000.1,"annualMa":21000.1,
                 "kValue":50.1,"dValue":48.2,"quarterlyConfirmation":"ABOVE","annualConfirmation":"ABOVE",
                 "reasons":["合成支持"],"risks":[],"intraday":false,"liveUpdatedAt":null,"extendedIndicators":null,
                 "marketVolumeRatio":1.1,"marketTurnoverRatio":null,"marketVolumeAsOfDate":"2026-09-22",
                 "nasdaqChangePercent":null,"soxChangePercent":null,"usTechCompositePercent":null,
                 "usTechAsOfDate":null,"usTechAvailable":false,"weeklyIndicators":null}
                """);
    }

    public static ObjectNode summary(String market, String code) {
        return json("""
                {"stockCode":"%s","stockName":"合成標的","market":"%s","assetClass":"EQUITY",
                 "distributionAdjusted":false,"held":true,"fxPercentile":null,"underlyingCurrency":"TWD",
                 "fundamental":{"applicable":true,"coverage":2,"industryName":null,"industryRevenueYoyPct":null},
                 "shortAction":"HOLD","shortActionLabel":"持有","shortScore":51,"swingAction":"WATCH",
                 "swingActionLabel":"觀察","swingScore":52,"action":"HOLD","actionLabel":"持有","score":53,
                 "horizonConflict":true,"timingState":"NEUTRAL","timingLabel":"中性","counterTrendState":"NONE",
                 "counterTrendLabel":"無","price":100.12,"changePercent":-0.1,"quoteStatus":"LIVE",
                 "priceUpdatedAt":"2026-09-23T09:00:00+08:00","etfPremiumLivePct":null,"etfPremiumLiveNavAsOf":null,
                 "weeklyMa":101.1,"monthlyMa":100.1,"quarterlyMa":98.1,"annualMa":91.1,"kValue":50.1,"dValue":49.2,
                 "kdHeat":"NORMAL","weeklyIndicators":{"k":50.1,"d":49.2,"changePercent":1.2},
                 "dailyCandleAsOfDate":"2026-09-22"}
                """.formatted(code, market));
    }

    private static ObjectNode envelope() {
        return json("""
                {"ruleVersion":"TW_RULES_V20","actionPolicyVersion":"EVIDENCE_GATE_V1",
                 "generatedAt":"2026-09-23T09:00:00+08:00"}
                """);
    }

    private static ObjectNode json(String raw) {
        try { return (ObjectNode) JSON.readTree(raw); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
