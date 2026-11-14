package com.steven.assets.service;

import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.ExchangeRateSourceCapture;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsdFxPurchaseContextTest {
    private static final LocalDate END = LocalDate.of(2026, 11, 13);
    private static final Instant NOW = Instant.parse("2026-11-13T02:00:00Z");
    private static final String HASH = "a".repeat(64);
    private UsdFxPurchaseContext.VerifiedInstrument type(String code, String market, String currency,
            TradingRadarAssetProfileResolver.InstrumentKind kind) {
        return new UsdFxPurchaseContext.VerifiedInstrument(code, market, currency, kind, "accepted-policy", HASH);
    }
    private UsdFxPurchaseContext asset(String code, String market, String currency,
            TradingRadarAssetProfileResolver.InstrumentKind kind, UsdFxPurchaseContext.MarketData data) {
        return UsdFxPurchaseContext.forAsset(code, market, currency, data, type(code, market, currency, kind));
    }
    private RadarCompletedPriceDrawdown.Context price(String code, String market, String value) {
        return new RadarCompletedPriceDrawdown.Context(RadarCompletedPriceDrawdown.Status.AVAILABLE,
                code, market, END.minusWeeks(52), END, END.minusMonths(2), new BigDecimal("100"),
                new BigDecimal(value), "台股".equals(market) ? RadarCompletedPriceDrawdown.PriceBasis.TW_SPLIT_STOCK_PRICE
                        : RadarCompletedPriceDrawdown.PriceBasis.STORED_PRICE_ONLY, null);
    }
    private UsdFxPurchaseContext.MarketData rate(String value, UsdFxPurchaseContext.HistoryRange range) {
        return new UsdFxPurchaseContext.MarketData("LIVE", new BigDecimal(value), NOW, "BANK_OF_TAIWAN", range, null);
    }
    private UsdFxPurchaseContext.HistoryRange range() {
        return new UsdFxPurchaseContext.HistoryRange(true, END.minusYears(2), END, END.minusDays(1),
                new BigDecimal("30"), new BigDecimal("33"), "BANK_SPOT_SELLING", "verified-source", null);
    }
    @Test void exactRateAndTwoYearPositionBoundariesAndUnknownShortCircuit() {
        var kind = TradingRadarAssetProfileResolver.InstrumentKind.STOCK;
        assertEquals(UsdFxPurchaseContext.Preference.NORMAL, asset("NVDA", "美股", "USD", kind, rate("32.699999", range())).preference());
        assertEquals(UsdFxPurchaseContext.Preference.HIGH, asset("NVDA", "美股", "USD", kind, rate("32.7", range())).preference());
        var missing = UsdFxPurchaseContext.HistoryRange.unavailable(END, END.minusDays(1), "MISSING");
        assertEquals(UsdFxPurchaseContext.Preference.UNKNOWN, asset("NVDA", "美股", "USD", kind, rate("32.8", missing)).preference());
        assertEquals(UsdFxPurchaseContext.Preference.HIGH, asset("NVDA", "美股", "USD", kind, rate("32.800001", missing)).preference());
    }
    @Test void etfStrictTenStockInclusiveThirtyAndExactIdentity() {
        var high = rate("33", null);
        for (String market : List.of("台股", "美股")) {
            String code = "台股".equals(market) ? "00865B" : "VOO";
            var context = asset(code, market, "台股".equals(market) ? "TWD" : "USD",
                    TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF, high);
            assertTrue(context.applies());
            assertFalse(context.priceException(price(code, market, "90"), new BigDecimal("90")));
            assertTrue(context.priceException(price(code, market, "89.999999"), new BigDecimal("89.999999")));
            assertFalse(context.priceException(price("OTHER", market, "80"), new BigDecimal("80")));
        }
        var stock = asset("OTHER", "美股", "USD", TradingRadarAssetProfileResolver.InstrumentKind.STOCK, high);
        assertTrue(stock.priceException(price("OTHER", "美股", "70"), new BigDecimal("70")));
        assertFalse(stock.priceException(price("OTHER", "美股", "70.000001"), new BigDecimal("70.000001")));
        assertFalse(price("OTHER", "美股", "70").eligible(new BigDecimal("70")), "Generic FX proof never expands named no-stability scope");
        assertFalse(asset("0050", "台股", "TWD", TradingRadarAssetProfileResolver.InstrumentKind.EQUITY_ETF, high).applies());
        var unknown = UsdFxPurchaseContext.forAsset("VOO", "美股", "USD", high, null);
        assertFalse(unknown.priceException(price("VOO", "美股", "50"), new BigDecimal("50")));
    }
    @Test void verifiedCheapPriceWaivesOnlyMissingHistoryAndNeverMissingCurrent() {
        var kind = TradingRadarAssetProfileResolver.InstrumentKind.STOCK;
        var missing = UsdFxPurchaseContext.HistoryRange.unavailable(END, END.minusDays(1), "MISSING");
        var context = asset("NVDA", "美股", "USD", kind, rate("31", missing));
        assertEquals(UsdFxPurchaseContext.Preference.UNKNOWN, context.preference());
        assertTrue(context.priceException(price("NVDA", "美股", "70"), new BigDecimal("70")));
        assertFalse(context.priceException(price("NVDA", "美股", "70.000001"), new BigDecimal("70.000001")));
        var noCurrent = asset("NVDA", "美股", "USD", kind, UsdFxPurchaseContext.MarketData.unavailable("STALE"));
        assertFalse(noCurrent.priceException(price("NVDA", "美股", "70"), new BigDecimal("70")));
        var etf = asset("00865B", "台股", "TWD", TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF, rate("31", missing));
        assertTrue(etf.priceException(price("00865B", "台股", "89.99"), new BigDecimal("89.99")));
        assertFalse(etf.priceException(price("00865B", "台股", "90"), new BigDecimal("90")));
    }
    @Test void liveFactoryRejectsFallbackIndicativeFutureAndMissingBankSpot() {
        var live = new UsdTwdLiveRateService.LiveRate("USD/TWD", "USD", "TWD", END,
                new BigDecimal("32.7"), new BigDecimal("33"), "BANK_OF_TAIWAN", NOW, null, "ACTIVE", "LIVE");
        assertEquals("LIVE", UsdFxPurchaseContext.live(live, NOW, END.minusDays(1), List.of(), null).quality());
        assertEquals("UNAVAILABLE", UsdFxPurchaseContext.live(live, NOW.minusSeconds(1), END.minusDays(1), List.of(), null).quality());
        assertEquals("UNAVAILABLE", UsdFxPurchaseContext.live(live, NOW.plusSeconds(7), END.minusDays(1), List.of(), null).quality());
        for (String quality : List.of("INDICATIVE", "HISTORY", "STALE", "LAST_AVAILABLE")) {
            var invalid = new UsdTwdLiveRateService.LiveRate("USD/TWD", "USD", "TWD", END,
                    live.buyRate(), live.sellRate(), live.source(), NOW, null, "ACTIVE", quality);
            assertEquals("UNAVAILABLE", UsdFxPurchaseContext.live(invalid, NOW, END, List.of(), null).quality());
        }
    }
    private ExchangeRateHistory row(LocalDate date, String buy, String sell) {
        return ExchangeRateHistory.builder().currency("USD").rateDate(date)
                .buyRate(new BigDecimal(buy)).sellRate(new BigDecimal(sell)).build();
    }
    @Test void genuineSpotProofSupportsFullTwoYearsAndBindsRowsBasisWindowAndCompletedDate() {
        var completed = END.minusDays(1);
        var rows = List.of(row(END.minusYears(2).minusDays(1), "29", "30"), row(END.minusYears(2), "29", "30"),
                row(END.minusMonths(2), "32", "33"), row(completed, "31", "32"));
        var proof = new UsdFxPurchaseContext.HistoryProof("VERIFIED", "BANK_SPOT_SELLING", "SPOT", "official-raw", HASH,
                UsdFxPurchaseContext.rowsSha256(rows.subList(1, rows.size())), END.minusYears(2), END, completed);
        var range = UsdFxPurchaseContext.history(rows, END, completed, proof);
        assertTrue(range.available()); assertEquals(new BigDecimal("30"), range.low()); assertEquals(new BigDecimal("33"), range.high());
        assertFalse(UsdFxPurchaseContext.history(rows, END, completed, null).available(), "Legacy buy != sell is not spot basis proof");
        assertFalse(UsdFxPurchaseContext.history(rows.subList(1, rows.size() - 1), END, completed, proof).available());
        var cash = new UsdFxPurchaseContext.HistoryProof("VERIFIED", "BANK_CASH_SELLING", "CASH", "official-raw", HASH,
                proof.rowsSha256(), END.minusYears(2), END, completed);
        assertFalse(UsdFxPurchaseContext.history(rows, END, completed, cash).available());
        var dirty = List.of(rows.get(0), rows.get(1), row(completed, "32", "32"));
        var dirtyProof = new UsdFxPurchaseContext.HistoryProof("VERIFIED", "BANK_SPOT_SELLING", "SPOT", "official-raw", HASH,
                UsdFxPurchaseContext.rowsSha256(dirty), END.minusYears(2), END, completed);
        assertFalse(UsdFxPurchaseContext.history(dirty, END, completed, dirtyProof).available());
    }
    private ExchangeRateHistory verified(ExchangeRateHistory row, Instant capturedAt) {
        row.setSourceCapture(ExchangeRateSourceCapture.builder().id(1L).provider("FINMIND_TAIWAN_EXCHANGE_RATE")
                .rateBasis("BANK_SPOT_SELLING").sourceUrl("https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate&data_id=USD&start_date=" + END.minusYears(2).minusDays(3) + "&end_date=" + END.minusDays(1))
                .capturedAt(capturedAt).sourceSha256(HASH).build());
        row.setSourceMemberSha256(ExchangeRateSourceCapture.memberSha(HASH, row.getCurrency(), row.getRateDate(), row.getBuyRate(), row.getSellRate()));
        return row;
    }
    @Test void realMemberReceiptsEnableRangeWithoutFiveYearSamplesAndRejectTamperingOrFutureCapture() {
        var completed = END.minusDays(1);
        var oldestLegacy = row(END.minusYears(4), "27", "28");
        var boundary = verified(row(END.minusYears(2), "29", "30"), NOW.minusSeconds(1));
        var peak = verified(row(END.minusMonths(2), "32", "33"), NOW.minusSeconds(1));
        var last = verified(row(completed, "31", "32"), NOW.minusSeconds(1));
        var legacyHoliday = row(END.minusDays(2), "90", "99");
        var rows = List.of(oldestLegacy, boundary, peak, legacyHoliday, last);
        var proof = UsdFxPurchaseContext.aggregateHistoryProof(rows, END, completed, NOW);
        assertNotNull(proof, "Older legacy rows outside the two-year boundary cannot taint verified window");
        var nativeRange = UsdFxPurchaseContext.history(rows, END, completed, proof);
        assertTrue(nativeRange.available());
        assertEquals(new BigDecimal("33"), nativeRange.high(), "Legacy holiday price is excluded, never faked as native spot");
        assertEquals(1, nativeRange.excludedLegacyRows());
        assertEquals("HISTORICAL_PROXY_UNAVAILABLE", UsdFxPurchaseContext.historical(rows, completed).quality(),
                "A later backfill receipt cannot prove what was known at the historical signal");
        peak.setSellRate(new BigDecimal("33.01"));
        assertNull(UsdFxPurchaseContext.aggregateHistoryProof(rows, END, completed, NOW), "A changed actual rate invalidates its member receipt");
        peak.setSellRate(new BigDecimal("33"));
        verified(peak, NOW.plusSeconds(1));
        assertNull(UsdFxPurchaseContext.aggregateHistoryProof(rows, END, completed, NOW));
    }
    @Test void bankSpotReplacementUsesItsPriceButNeverBridgesNativeQueryCoverageGap() {
        var completed = END.minusDays(1);
        var boundary = verified(row(END.minusYears(2), "29", "30"), NOW.minusSeconds(1));
        var peak = verified(row(END.minusMonths(2), "32", "33"), NOW.minusSeconds(1));
        var last = row(completed, "33", "34");
        var bank = ExchangeRateSourceCapture.builder().id(2L).provider("BANK_OF_TAIWAN")
                .rateBasis("BANK_SPOT_SELLING").sourceUrl("https://rate.bot.com.tw/xrt/flcsv/0/day")
                .capturedAt(completed.atTime(16, 0).atZone(java.time.ZoneId.of("Asia/Taipei")).toInstant())
                .sourceSha256("b".repeat(64)).build();
        last.setSourceCapture(bank);
        last.setSourceMemberSha256(ExchangeRateSourceCapture.memberSha(bank.getSourceSha256(), "USD", completed, last.getBuyRate(), last.getSellRate()));
        var rows = List.of(boundary, peak, last);
        var proof = UsdFxPurchaseContext.aggregateHistoryProof(rows, END, completed, NOW);
        assertNotNull(proof);
        assertEquals(new BigDecimal("34"), UsdFxPurchaseContext.history(rows, END, completed, proof).high());
        var shortQuery = ExchangeRateSourceCapture.builder().id(3L).provider("FINMIND_TAIWAN_EXCHANGE_RATE")
                .rateBasis("BANK_SPOT_SELLING").sourceUrl("https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate&data_id=USD&start_date="
                    + END.minusYears(2).plusDays(1) + "&end_date=" + completed)
                .capturedAt(NOW.minusSeconds(1)).sourceSha256("c".repeat(64)).build();
        peak.setSourceCapture(shortQuery);
        peak.setSourceMemberSha256(ExchangeRateSourceCapture.memberSha(shortQuery.getSourceSha256(), "USD", peak.getRateDate(), peak.getBuyRate(), peak.getSellRate()));
        var oldBank = ExchangeRateSourceCapture.builder().id(4L).provider("BANK_OF_TAIWAN")
                .rateBasis("BANK_SPOT_SELLING").sourceUrl(bank.getSourceUrl())
                .capturedAt(boundary.getRateDate().atTime(16, 0).atZone(java.time.ZoneId.of("Asia/Taipei")).toInstant())
                .sourceSha256(bank.getSourceSha256()).build();
        boundary.setSourceCapture(oldBank);
        boundary.setSourceMemberSha256(ExchangeRateSourceCapture.memberSha(bank.getSourceSha256(), "USD", boundary.getRateDate(), boundary.getBuyRate(), boundary.getSellRate()));
        assertNull(UsdFxPurchaseContext.aggregateHistoryProof(rows, END, completed, NOW), "Bank point observations cannot prove a missing query interval");
    }
    @Test void heuristicCompleteProfileNeverVerifiesInstrumentType() {
        var profile = TradingRadarAssetProfileResolver.resolve(null, "UNLISTED", "美股", "Vanguard Unlisted ETF", null);
        assertTrue(profile.instrumentKindComplete());
        var context = UsdFxPurchaseContext.forAsset("UNLISTED", "美股", profile.quoteCurrency(), rate("33", null));
        assertFalse(context.typeVerified());
        assertFalse(context.priceException(price("UNLISTED", "美股", "50"), new BigDecimal("50")));
    }

    @Test void identicalRawBodyRetainsDistinctQueryAndCaptureAuthorityContexts() {
        var completed = END.minusDays(1);
        var boundary = verified(row(END.minusYears(2), "29", "30"), NOW.minusSeconds(2));
        var peak = verified(row(END.minusMonths(2), "32", "33"), NOW.minusSeconds(2));
        var last = verified(row(completed, "31", "32"), NOW.minusSeconds(1));
        var second = ExchangeRateSourceCapture.builder().id(8L).provider("FINMIND_TAIWAN_EXCHANGE_RATE")
                .rateBasis("BANK_SPOT_SELLING").sourceUrl("https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate&data_id=USD&start_date="
                    + END.minusYears(2).minusDays(4) + "&end_date=" + completed)
                .capturedAt(NOW.minusSeconds(1)).sourceSha256(HASH).build();
        last.setSourceCapture(second);
        var rows = List.of(boundary, peak, last);
        var proof = UsdFxPurchaseContext.aggregateHistoryProof(rows, END, completed, NOW);
        assertNotNull(proof, "Same raw hash is compatible with two genuinely captured query intervals");
        assertTrue(UsdFxPurchaseContext.history(rows, END, completed, proof).available());
        var third = ExchangeRateSourceCapture.builder().id(9L).provider(second.getProvider()).rateBasis(second.getRateBasis())
                .sourceUrl(second.getSourceUrl()).capturedAt(NOW.minusNanos(1)).sourceSha256(HASH).build();
        last.setSourceCapture(third);
        var changed = UsdFxPurchaseContext.aggregateHistoryProof(rows, END, completed, NOW);
        assertNotNull(changed);
        assertNotEquals(proof.sourceSha256(), changed.sourceSha256(), "Capture time is part of the aggregate authority identity");
    }

    private ExchangeRateHistory holidaySpot(LocalDate date, String sell, Instant capturedAt) {
        var item = row(date, new BigDecimal(sell).subtract(BigDecimal.ONE).toPlainString(), sell);
        var capture = ExchangeRateSourceCapture.builder().id(30L).provider("FINMIND_TAIWAN_EXCHANGE_RATE")
                .rateBasis("BANK_SPOT_SELLING").sourceUrl("https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate&data_id=USD&start_date=2024-02-10&end_date=2026-02-13")
                .capturedAt(capturedAt).sourceSha256(HASH).build();
        item.setSourceCapture(capture);
        item.setSourceMemberSha256(ExchangeRateSourceCapture.memberSha(HASH, "USD", date, item.getBuyRate(), item.getSellRate()));
        return item;
    }
    @Test void nativeBankDatesOnTwStockHolidaysRetainExtremaAndRespectExactSeventeenCutoff() {
        var end = LocalDate.of(2026, 2, 13);
        var minimum = end.minusDays(2); // TW stock calendar closed Feb12/13; bank-native series has both.
        var captured = Instant.parse("2026-02-13T08:00:00Z");
        var rows = List.of(holidaySpot(LocalDate.of(2024, 2, 10), "30", captured),
                holidaySpot(minimum, "32", captured), holidaySpot(end.minusDays(1), "40", captured), holidaySpot(end, "45", captured));
        for (var at : List.of(Instant.parse("2026-02-13T09:00:00Z"), Instant.parse("2026-02-13T14:35:00Z"))) {
            var history = UsdFxPurchaseContext.historical(rows, at, minimum);
            assertEquals("HISTORICAL_PROXY", history.quality());
            assertEquals(end, history.history().completedAsOfDate());
            assertTrue(history.history().available());
            assertEquals(new BigDecimal("45"), history.history().high());
            var live = new UsdTwdLiveRateService.LiveRate("USD/TWD", "USD", "TWD", end,
                    new BigDecimal("30"), new BigDecimal("31"), "BANK_OF_TAIWAN", at, null, "ACTIVE", "LIVE");
            var actual = UsdFxPurchaseContext.live(live, at, minimum, rows, null);
            assertEquals(end, actual.history().completedAsOfDate());
            assertEquals(new BigDecimal("45"), actual.history().high());
        }
        var before = UsdFxPurchaseContext.historical(rows, Instant.parse("2026-02-13T08:59:59Z"), minimum);
        assertEquals(end.minusDays(1), before.history().completedAsOfDate());
        assertEquals(end, before.history().windowEnd(), "Replay window uses actual decision day, separately from completed bank D");
        assertEquals(end.minusYears(2), before.history().windowStart());
        assertEquals(new BigDecimal("40"), before.history().high(), "Today's native extreme is not completed before 17:00");
        assertEquals(new BigDecimal("40"), before.currentRate());
        var lagging = List.of(rows.get(0), holidaySpot(end.minusDays(3), "32", captured));
        assertEquals("HISTORICAL_PROXY_UNAVAILABLE", UsdFxPurchaseContext.historical(lagging,
                Instant.parse("2026-02-13T14:35:00Z"), minimum).quality(), "Native latest below independent TW minimum fails closed");
        assertEquals("HISTORICAL_PROXY_UNAVAILABLE", UsdFxPurchaseContext.historical(rows,
                captured.minusSeconds(1), minimum).quality(), "Later captures cannot establish an earlier replay decision");
    }

}
