package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TwseInfoFetchClientInstitutionalTest {

    @Test
    void parserReturnsTypedOfficialNumbersAndDoesNotDoubleCountForeignDealer() throws Exception {
        String body = """
                {
                  "stat":"OK",
                  "date":"20260807",
                  "data":[
                    ["外資及陸資(不含外資自營商)","0","0","100"],
                    ["外資自營商","0","0","999"],
                    ["投信","0","0","-20"],
                    ["自營商(自行買賣)","0","0","10"],
                    ["自營商(避險)","0","0","5"],
                    ["合計","0","0","95"]
                  ]
                }
                """;
        Instant observedAt = Instant.parse("2026-08-07T10:10:00Z");
        String sourceUrl = "https://www.twse.com.tw/rwd/zh/fund/BFI82U?response=json&date=20260807";

        TwseInfoFetchClient.InstitutionalObservation result = new TwseInfoFetchClient()
                .parseInstitutionalResponse(body, observedAt, sourceUrl);

        assertThat(result.available()).isTrue();
        assertThat(result.foreignNet()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(result.trustNet()).isEqualByComparingTo(new BigDecimal("-20"));
        assertThat(result.dealerNet()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(result.totalNet()).isEqualByComparingTo(new BigDecimal("95"));
        assertThat(result.provider()).isEqualTo("TWSE_BFI82U");
        assertThat(result.sourceUrl()).isEqualTo(sourceUrl);
        assertThat(result.observedAt()).isEqualTo(observedAt);
        assertThat(result.sourceAvailableAt()).isNull();
        assertThat(result.availabilityBasis())
                .isEqualTo("OBSERVED_AT_NO_PUBLISHED_TIMESTAMP");
    }

    @Test
    void unavailableResponseIsExplicitAndNeverZeroFilled() throws Exception {
        Instant observedAt = Instant.parse("2026-08-07T10:10:00Z");

        TwseInfoFetchClient.InstitutionalObservation result = new TwseInfoFetchClient()
                .parseInstitutionalResponse(
                        "{\"stat\":\"很抱歉，沒有符合條件的資料!\",\"data\":[]}",
                        observedAt,
                        "https://example.invalid/BFI82U");

        assertThat(result.available()).isFalse();
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.totalNet()).isNull();
        assertThat(result.errorReason()).contains("stat=");
    }

    @Test
    void missingOfficialTotalRowDoesNotFallBackToDerivedOrZeroValue() throws Exception {
        String body = """
                {"stat":"OK","date":"20260807","data":[
                  ["外資及陸資(不含外資自營商)","0","0","100"],
                  ["投信","0","0","-20"],
                  ["自營商(自行買賣)","0","0","15"]
                ]}
                """;

        TwseInfoFetchClient.InstitutionalObservation result = new TwseInfoFetchClient()
                .parseInstitutionalResponse(body, Instant.parse("2026-08-07T10:10:00Z"),
                        "https://example.invalid/BFI82U");

        assertThat(result.available()).isFalse();
        assertThat(result.totalNet()).isNull();
        assertThat(result.errorReason()).contains("缺少");
    }
}
