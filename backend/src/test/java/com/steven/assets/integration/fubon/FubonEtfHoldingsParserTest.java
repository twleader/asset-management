package com.steven.assets.integration.fubon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FubonEtfHoldingsParserTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 30);
    private static final String VALID = """
            {"schemaVersion":1,"stockCode":"0050","sourceDate":"2026-08-28","holdings":[
              {"stockCode":"2330","stockName":"台積電","weight":"58.8200","shares":"530358242"},
              {"stockCode":"AAPL UQ","stockName":"Apple","weight":"0","shares":null}]}
            """;

    @Test
    void readsNormalizedHoldingsWithProviderDateAndForeignSymbols() {
        var parsed = FubonEtfHoldingsParser.parse("0050", VALID, TODAY).orElseThrow();
        assertThat(parsed.sourceDate()).isEqualTo(TODAY.minusDays(2));
        assertThat(parsed.holdings()).hasSize(2);
        assertThat(parsed.holdings().getFirst().weight()).isEqualByComparingTo("58.82");
        assertThat(parsed.holdings().getFirst().shares()).isEqualByComparingTo("530358242");
        assertThat(parsed.holdings().getLast().stockCode()).isEqualTo("AAPL UQ");
        assertThat(parsed.holdings().getLast().shares()).isNull();
    }

    @Test
    void legitimateEmptyDataRetainsUnknownDateInsteadOfInventingToday() {
        var parsed = FubonEtfHoldingsParser.parse("0050", """
                {"schemaVersion":1,"stockCode":"0050","sourceDate":null,"holdings":[]}
                """, TODAY).orElseThrow();
        assertThat(parsed.sourceDate()).isNull();
        assertThat(parsed.holdings()).isEmpty();
        assertThat(FubonEtfHoldingsParser.parse("0050", """
                {"schemaVersion":1,"stockCode":"0050","sourceDate":"2026-08-28","holdings":[]}
                """, TODAY).orElseThrow().sourceDate()).isEqualTo(TODAY.minusDays(2));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "{", "[]", "null", "{}", "true"})
    void malformedOrMissingEnvelopeIsUnavailable(String payload) {
        assertThat(FubonEtfHoldingsParser.parse("0050", payload, TODAY)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "58.82", "\"-1\"", "\"100.01\"", "\"NaN\"", "\"Infinity\"",
            "\"1e1\"", "\"01\"", "\" 1\"", "\"0.00000000001\"", "\"123456789012345678901\""})
    void rejectsUnsafeWeightOrNonStringNumbers(String numeric) {
        assertThat(FubonEtfHoldingsParser.parse("0050", VALID.replace("\"58.8200\"", numeric), TODAY)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "530358242", "\"-1\"", "\"Infinity\"", "\"1e3\""})
    void rejectsUnsafeShares(String numeric) {
        assertThat(FubonEtfHoldingsParser.parse("0050", VALID.replace("\"530358242\"", numeric), TODAY)).isEmpty();
    }

    @Test
    void rejectsIdentityVersionDateAndPayloadLeakage() {
        assertThat(FubonEtfHoldingsParser.parse("0056", VALID, TODAY)).isEmpty();
        for (String changed : new String[]{
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
                VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                VALID.replace("2026-08-28", "2026-08-31"),
                VALID.replace("2026-08-28", "2026-02-30"),
                VALID.replace("\"2026-08-28\"", "null"),
                VALID.replace("\"2330\"", "\" \""),
                VALID.replace("\"台積電\"", "null"),
                VALID.replace("\"schemaVersion\":1", "\"secret\":\"must-not-store\",\"schemaVersion\":1"),
                VALID.replace("\"stockName\":\"台積電\"", "\"account\":\"must-not-store\",\"stockName\":\"台積電\""),
                VALID + "{}"}) {
            assertThat(FubonEtfHoldingsParser.parse("0050", changed, TODAY)).as(changed).isEmpty();
        }
    }
}
