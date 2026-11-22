package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.ExchangeRateSourceCapture;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.service.RadarCompletedPriceDrawdown;
import com.steven.assets.service.UsdFxPurchaseContext;
import com.steven.assets.service.UsdTwdLiveRateService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

/** Task 625 test helper: rebuilds the Java inputs of one original-SRPP D-215 golden payload. */
final class D215Golden {
    static final String HASH = "a".repeat(64);
    private D215Golden() {}

    static SrppReportD215FxPreference.Result evaluate(JsonNode policy, JsonNode payload) {
        JsonNode asset = payload.path("asset"), q = payload.path("fx_quote"), history = payload.path("fx_history");
        Instant checked = OffsetDateTime.parse(payload.path("checked_at").asText()).toInstant();
        LocalDate completed = LocalDate.parse(payload.path("fx_completed_as_of").asText());
        BigDecimal rate = new BigDecimal(q.path("rate").asText());
        var live = new UsdTwdLiveRateService.LiveRate("USD/TWD", "USD", "TWD", LocalDate.parse(q.path("rate_date").asText()),
                rate.subtract(new BigDecimal("0.1")), rate, "BANK_OF_TAIWAN", OffsetDateTime.parse(q.path("as_of").asText()).toInstant(),
                null, "ACTIVE", q.path("quote_status").asText());
        List<ExchangeRateHistory> rows = new ArrayList<>();
        UsdFxPurchaseContext.HistoryProof proof = null;
        if (history.isObject()) {
            LocalDate start = LocalDate.parse(history.path("windowStart").asText()), end = LocalDate.parse(history.path("windowEnd").asText());
            Instant captured = checked.minusSeconds(3600);
            var capture = ExchangeRateSourceCapture.builder().id(625L).provider("FINMIND_TAIWAN_EXCHANGE_RATE").rateBasis("BANK_SPOT_SELLING")
                    .sourceUrl("https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate&data_id=USD&start_date="
                            + end.minusYears(2).minusDays(30) + "&end_date=" + completed)
                    .capturedAt(captured).sourceSha256(HASH).build();
            for (JsonNode r : history.path("dailyRates")) {
                var row = ExchangeRateHistory.builder().currency("USD").rateDate(LocalDate.parse(r.path("date").asText()))
                        .buyRate(new BigDecimal(r.path("buyRate").asText())).sellRate(new BigDecimal(r.path("rate").asText())).build();
                row.setSourceCapture(capture);
                row.setSourceMemberSha256(ExchangeRateSourceCapture.memberSha(HASH, "USD", row.getRateDate(), row.getBuyRate(), row.getSellRate()));
                assertThat(row.isSpotBasisVerified()).isTrue();
                rows.add(row);
            }
            proof = new UsdFxPurchaseContext.HistoryProof("VERIFIED", "BANK_SPOT_SELLING", "SPOT", "ORIGINAL_SRPP_GOLDEN", HASH,
                    UsdFxPurchaseContext.rowsSha256(selected(rows, end, LocalDate.parse(history.path("completedAsOfDate").asText()))),
                    start, end, LocalDate.parse(history.path("completedAsOfDate").asText()));
        }
        String code = asset.path("symbol").asText(), market = asset.path("market").asText(), currency = asset.path("currency").asText();
        var data = UsdFxPurchaseContext.live(live, checked, completed, rows, proof);
        var context = SrppReportD215FxPreference.orderChecked(UsdFxPurchaseContext.forAsset(code, market, currency, data), rows);
        RadarCompletedPriceDrawdown.Context fact = null;
        BigDecimal price = asset.hasNonNull("quote_last") ? new BigDecimal(asset.path("quote_last").asText()) : null;
        LocalDate marketCompleted = null, session = null;
        if (payload.path("asset_price_fact").isObject()) {
            JsonNode f = payload.path("asset_price_fact");
            List<StockPriceHistory> desc = new ArrayList<>();
            for (JsonNode r : f.path("completedPriceHistory")) {
                var row = new StockPriceHistory();
                row.setStockCode(code); row.setMarket(market); row.setTradingDate(LocalDate.parse(r.path("tradingDate").asText()));
                row.setHighPrice(new BigDecimal(r.path("high").asText())); row.setClosePrice(new BigDecimal(r.path("close").asText()));
                desc.add(row);
            }
            fact = RadarCompletedPriceDrawdown.evaluate(code, market, desc, 0, price,
                    "台股".equals(market) ? RadarCompletedPriceDrawdown.PriceBasis.TW_SPLIT_STOCK_PRICE : RadarCompletedPriceDrawdown.PriceBasis.STORED_PRICE_ONLY,
                    Set.of(), false);
            marketCompleted = LocalDate.parse(payload.path("asset_completed_as_of").asText());
            session = LocalDate.parse(asset.path("session_date").asText());
        }
        return SrppReportD215FxPreference.evaluate(policy, code, market, currency, context, fact, price, marketCompleted, session);
    }

    /** Same selection as UsdFxPurchaseContext.windowRows: last boundary row through the completed date. */
    static List<ExchangeRateHistory> selected(List<ExchangeRateHistory> rows, LocalDate end, LocalDate completed) {
        List<ExchangeRateHistory> ordered = rows.stream().filter(r -> !r.getRateDate().isAfter(completed))
                .sorted(Comparator.comparing(ExchangeRateHistory::getRateDate)).toList();
        int boundary = -1;
        for (int i = 0; i < ordered.size(); i++) if (!ordered.get(i).getRateDate().isAfter(end.minusYears(2))) boundary = i;
        return boundary < 0 ? List.of() : ordered.subList(boundary, ordered.size());
    }

    static void decimal(String label, JsonNode expected, BigDecimal actual) {
        if (expected == null || expected.isNull()) { assertThat(actual).as(label).isNull(); return; }
        assertThat(actual).as(label).isNotNull();
        BigDecimal want = new BigDecimal(expected.asText()).round(SrppReportD215FxPreference.DISPLAY);
        assertThat(actual.round(SrppReportD215FxPreference.DISPLAY).compareTo(want)).as(label + " " + actual + " vs " + want).isZero();
    }

    /** A LIVE NORMAL D-215 context (S=31 in a verified 30–33 range) for the three bond bodies and VT, as the capture adapter writes it. */
    static com.fasterxml.jackson.databind.node.ObjectNode normalContext(String code, String market, LocalDate date, Instant at) {
        return context(code, market, date, at, "31", "LIVE", true, true);
    }
    /** Arbitrary frozen context: rate S, quality, verified 30–33 range or none, and whether the policy type authority loaded. */
    static com.fasterxml.jackson.databind.node.ObjectNode context(String code, String market, LocalDate date, Instant at, String rate,
                                                                   String quality, boolean range, boolean typeVerified) {
        var history = range ? new UsdFxPurchaseContext.HistoryRange(true, date.minusYears(2), date, date.minusDays(1),
                new BigDecimal("30"), new BigDecimal("33"), "BANK_SPOT_SELLING", "test-verified-range", null)
                : UsdFxPurchaseContext.HistoryRange.unavailable(date, date.minusDays(1), "TEST_RANGE_MISSING");
        var data = new UsdFxPurchaseContext.MarketData(quality, new BigDecimal(rate), at, "BANK_OF_TAIWAN", history, null);
        String currency = "台股".equals(market) ? "TWD" : "USD";
        var context = typeVerified ? UsdFxPurchaseContext.forAsset(code, market, currency, data)
                : new UsdFxPurchaseContext(code, market, currency, com.steven.assets.service.TradingRadarAssetProfileResolver.InstrumentKind.UNKNOWN, false, data, true);
        return (com.fasterxml.jackson.databind.node.ObjectNode) SrppReportModuleCalculator.normalize(SrppReportFixtures.MAPPER.valueToTree(context));
    }
    static void addNormal(com.fasterxml.jackson.databind.node.ObjectNode sources, LocalDate date, Instant at) {
        for (String code : List.of("00865B", "00719B", "00697B", "VT")) {
            String market = code.equals("VT") ? "美股" : "台股";
            JsonNode existing = sources.path("market:" + market + ":" + code);
            var body = existing.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) existing
                    : sources.putObject("market:" + market + ":" + code).put("symbol", code).put("market", market);
            body.set("usdFxPurchase", normalContext(code, market, date, at));
        }
    }
}
