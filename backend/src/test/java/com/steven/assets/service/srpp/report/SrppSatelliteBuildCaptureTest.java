package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.AT;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.qualified;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Task 623 frozen satellite sources: explicit owner, exact selection window and fail-closed month mids. */
class SrppSatelliteBuildCaptureTest {
    static final String BUNDLE = "a".repeat(64);

    static AssetTransaction row(long id, String code, String currency, String date, String shares, String amount, String rate) {
        AssetTransaction t = new AssetTransaction();t.setId(id);t.setOwnerUserId(7L);t.setAssetCode(code);t.setMarket(code.equals("VT") ? "美股" : "台股");
        t.setCurrency(currency);t.setTransactionType("買");t.setTradeDate(LocalDate.parse(date));t.setShares(new BigDecimal(shares));t.setPrice(new BigDecimal("10.000000"));
        t.setAmount(new BigDecimal(amount));t.setExchangeRate(rate == null ? null : new BigDecimal(rate));return t;
    }

    static ExchangeRateHistory rate(String date, String buy, String sell) {
        ExchangeRateHistory r = new ExchangeRateHistory();r.setCurrency("USD");r.setRateDate(LocalDate.parse(date));r.setBuyRate(buy == null ? null : new BigDecimal(buy));r.setSellRate(sell == null ? null : new BigDecimal(sell));return r;
    }

    @Test void selectionCoversCalendarMonthAndMondayWeekWithExplicitOwner() {
        assertThat(SrppSatelliteBuildCapture.selectionStart(LocalDate.parse("2026-12-02"))).isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(SrppSatelliteBuildCapture.selectionStart(LocalDate.parse("2026-11-24"))).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(SrppSatelliteBuildCapture.selectionStart(LocalDate.parse("2026-11-02"))).isEqualTo(LocalDate.parse("2026-11-01"));
        var transactions = mock(AssetTransactionRepository.class);var rates = mock(ExchangeRateHistoryRepository.class);
        LocalDate date = LocalDate.parse("2026-12-02");
        when(transactions.findSatelliteBuildRows(7L, List.of("009826", "VT"), LocalDate.parse("2026-11-30"), date)).thenReturn(List.of(
                row(9, "VT", "USD", "2026-12-01", "2.22533", "350.00", "32.5000"), row(3, "009826", "TWD", "2026-11-30", "1000.00000", "10060.00", null)));
        when(rates.findByCurrencyAndRateDateBetweenOrderByRateDateAsc("USD", date.withDayOfMonth(1), date)).thenReturn(List.of(rate("2026-12-01", "32.3000", "32.4000")));
        var out = new SrppSatelliteBuildCapture(transactions, rates).capture(7L, date, BUNDLE);
        verify(transactions).findSatelliteBuildRows(7L, List.of("009826", "VT"), LocalDate.parse("2026-11-30"), date);
        verify(transactions, never()).findAllByOrderByTradeDateDesc();
        JsonNode fills = out.fills();
        assertThat(fills.path("ownerUserId").asLong()).isEqualTo(7);assertThat(fills.path("tradingDate").asText()).isEqualTo("2026-12-02");assertThat(fills.path("policyBundleSha256").asText()).isEqualTo(BUNDLE);
        assertThat(fills.path("status").asText()).isEqualTo("VERIFIED");assertThat(fills.path("verified").asBoolean()).isTrue();assertThat(fills.path("coverage").asText()).isEqualTo("COMPLETE");
        assertThat(fills.path("selection").toString()).isEqualTo("{\"mode\":\"DATE_RANGE\",\"year\":null,\"start\":\"2026-11-30\",\"end\":\"2026-12-02\"}");
        assertThat(fills.path("records").get(0).path("id").asLong()).as("trade_date then id order").isEqualTo(3);
        JsonNode vt = fills.path("records").get(1);
        assertThat(vt.path("shares").asText()).isEqualTo("2.22533");assertThat(vt.path("amount").asText()).isEqualTo("350");assertThat(vt.path("exchangeRate").asText()).isEqualTo("32.5");
        assertThat(vt.path("amountTwd").asText()).isEqualTo("11375");assertThat(fills.path("records").get(0).path("amountTwd").asText()).isEqualTo("10060");
        assertThat(fills.path("records").get(0).path("exchangeRate").isNull()).isTrue();
        assertThat(out.mids().path("status").asText()).isEqualTo("VERIFIED");assertThat(out.mids().path("month").asText()).isEqualTo("2026-12");
        assertThat(out.mids().path("rows").get(0).toString()).isEqualTo("{\"date\":\"2026-12-01\",\"buyRate\":\"32.3\",\"sellRate\":\"32.4\",\"midRate\":\"32.35\"}");
    }

    @Test void amountTwdMatchesPublicHistoryRuleIncludingUnratedUsdRowsKeptAsIs() {
        assertThat(SrppSatelliteBuildCapture.amountTwd(row(1, "VT", "USD", "2026-11-23", "5", "750.00", null))).isEqualByComparingTo("750");
        assertThat(SrppSatelliteBuildCapture.amountTwd(row(1, "VT", "USD", "2026-11-23", "5", "750.00", "0"))).isEqualByComparingTo("0");
        assertThat(SrppSatelliteBuildCapture.amountTwd(row(1, "VT", "USD", "2026-11-23", "5", "750.00", "32.1234"))).isEqualByComparingTo("24092.55");
        assertThat(SrppSatelliteBuildCapture.amountTwd(row(1, "VT", "TWD", "2026-11-23", "5", "24375.00", null))).isEqualByComparingTo("24375");
        var fills = SrppSatelliteBuildCapture.fillsSource(7, LocalDate.parse("2026-11-23"), BUNDLE, List.of(row(1, "VT", "USD", "2026-11-23", "5", "750.00", null)));
        assertThat(fills.path("records")).hasSize(1);assertThat(fills.path("records").get(0).path("amountTwd").asText()).isEqualTo("750");assertThat(fills.path("records").get(0).path("exchangeRate").isNull()).isTrue();
    }

    @Test void databaseFailuresAreUnavailableAndNeverEmptyVerified() {
        var transactions = mock(AssetTransactionRepository.class);var rates = mock(ExchangeRateHistoryRepository.class);
        when(transactions.findSatelliteBuildRows(anyLong(), any(), any(), any())).thenThrow(new IllegalStateException("db"));
        when(rates.findByCurrencyAndRateDateBetweenOrderByRateDateAsc(any(), any(), any())).thenThrow(new IllegalStateException("db"));
        var out = new SrppSatelliteBuildCapture(transactions, rates).capture(7L, SrppReportFixtures.DATE, BUNDLE);
        assertThat(out.fills().path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(out.fills().path("verified").asBoolean()).isFalse();
        assertThat(out.fills().path("coverage").asText()).isEqualTo("NONE");assertThat(out.fills().path("reason").asText()).isEqualTo("SATELLITE_BUILD_FILLS_UNAVAILABLE");
        assertThat(out.fills().path("ownerUserId").asLong()).isEqualTo(7);assertThat(out.fills().path("tradingDate").asText()).isEqualTo(SrppReportFixtures.DATE.toString());assertThat(out.fills().path("policyBundleSha256").asText()).isEqualTo(BUNDLE);
        assertThat(out.mids().path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(out.mids().path("rows")).isEmpty();
    }

    @Test void anyMalformedCurrentMonthRowMakesTheWholeMonthUnavailableButOutOfMonthRowsAreNotRead() {
        LocalDate date = LocalDate.parse("2026-11-24");
        for (List<ExchangeRateHistory> bad : List.of(List.of(rate("2026-11-02", null, "32.4")), List.of(rate("2026-11-02", "32.5", "32.4")),
                List.of(rate("2026-11-02", "0", "32.4")), List.of(rate("2026-11-02", "32.3", "32.4"), rate("2026-11-02", "32.3", "32.4")))) {
            var mids = SrppSatelliteBuildCapture.midsSource(date, bad);
            assertThat(mids.path("status").asText()).isEqualTo("UNAVAILABLE");assertThat(mids.path("rows")).isEmpty();
        }
        // KNOWN_DIFFERENCE_MIDS_WINDOW: the source only queries the current month, so a bad October row never reaches it.
        var rates = mock(ExchangeRateHistoryRepository.class);
        when(rates.findByCurrencyAndRateDateBetweenOrderByRateDateAsc("USD", LocalDate.parse("2026-11-01"), date)).thenReturn(List.of(rate("2026-11-02", "32.3", "32.4")));
        when(rates.findByCurrencyAndRateDateBetweenOrderByRateDateAsc("USD", LocalDate.parse("2026-10-30"), date)).thenReturn(List.of(rate("2026-10-30", "33.5", "33.4"), rate("2026-11-02", "32.3", "32.4")));
        var out = new SrppSatelliteBuildCapture(mock(AssetTransactionRepository.class), rates).capture(7L, date, BUNDLE);
        assertThat(out.mids().path("status").asText()).isEqualTo("VERIFIED");assertThat(out.mids().path("rows")).hasSize(1);
        assertThat(out.mids().path("rows").get(0).path("midRate").asText()).isEqualTo("32.35");
    }

    @Test void frozenSatelliteSourcesHashAndPassTheEnvelopeValidatorWithStringDecimals() throws Exception {
        var original = qualified("09:05", AT, true);var bundle = original.input().path("sources").path("policy").path("bundleHash").asText();
        var fills = SrppSatelliteBuildCapture.fillsSource(7, SrppReportFixtures.DATE, bundle, List.of(row(1, "009826", "TWD", SrppReportFixtures.DATE.toString(), "1000", "10060.00", null), row(2, "VT", "USD", SrppReportFixtures.DATE.toString(), "1", "150.00", "32.5")));
        var mids = SrppSatelliteBuildCapture.midsSource(SrppReportFixtures.DATE, List.of(rate(SrppReportFixtures.DATE.withDayOfMonth(1).toString(), "32.3", "32.4")));
        var frozen = SrppReportProducerScheduler.withSatelliteBuildSources(original, fills, mids);
        JsonNode sources = frozen.input().path("sources");
        assertThat(frozen.sourceVector().path(SrppSatelliteBuildCapture.FILLS).path("sha256").asText()).isEqualTo(SrppJcs.hash(sources.path(SrppSatelliteBuildCapture.FILLS)));
        for (String key : List.of("buyRate", "sellRate", "midRate")) assertThat(sources.path(SrppSatelliteBuildCapture.MIDS).path("rows").get(0).path(key).isTextual()).isTrue();
        JsonNode envelope = SrppSatelliteBuildFixtures.publish(frozen, "09:05", SrppSatelliteBuildFixtures.enabledModel());
        JsonNode input = SrppJcs.parseStrict(envelope.path("inputSnapshot").path("inputBundleJcs").asText());
        assertThat(SrppJcs.canonicalize(input.path("sources").path(SrppSatelliteBuildCapture.FILLS))).isEqualTo(SrppJcs.canonicalize(sources.path(SrppSatelliteBuildCapture.FILLS)));
        assertThat(envelope.path("inputSnapshot").path("sourceVector").path(SrppSatelliteBuildCapture.MIDS).path("validation").asText()).isEqualTo("VERIFIED");
    }
}
