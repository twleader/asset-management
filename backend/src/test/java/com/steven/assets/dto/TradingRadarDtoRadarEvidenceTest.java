package com.steven.assets.dto;

import com.steven.assets.service.DividendEventEvidenceResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 357／Requirement 94：{@code RadarEvidence.withConfidence(...)} 把
 * {@link DividendEventEvidenceResolver.Resolution} 攤平成 wire DTO 時，必須正確帶出
 * 「下一配息」的四個獨立日期，且既有 {@code nextDistributionDate} 重新定義為 anchorDate
 * 之後不得對純配股事件（{@code exDividendDate} 為 null）拋 NPE。
 */
class TradingRadarDtoRadarEvidenceTest {

    @Test
    void withConfidencePopulatesAllFourDividendDatesFromTheSameEvent() {
        LocalDate exDividend = LocalDate.of(2026, 9, 10);
        LocalDate exRights = LocalDate.of(2026, 9, 20);
        LocalDate cashPay = LocalDate.of(2026, 10, 15);
        LocalDate stockPay = LocalDate.of(2026, 10, 25);
        var event = new DividendEventEvidenceResolver.Event(
                exDividend, new BigDecimal("2.00"), new BigDecimal("1.00"), cashPay, stockPay,
                Instant.parse("2026-08-06T00:00:00Z"), "FINMIND", List.of(), exRights);
        var resolution = new DividendEventEvidenceResolver.Resolution(
                DividendEventEvidenceResolver.Status.AVAILABLE, event, 0, 1, "FINMIND",
                List.of(), Instant.parse("2026-08-06T00:00:00Z"),
                Instant.parse("2026-08-06T00:00:00Z"), null);

        TradingRadarDto.RadarEvidence evidence = TradingRadarDto.RadarEvidence.withConfidence(
                null, null, "MISSING", false, null, null, null, null, null, false, null,
                List.of(), null, null, null, resolution, null, null, null, null);

        // nextDistributionDate 重新定義為 anchorDate：兩個日期皆有值時等於 exDividendDate。
        assertThat(evidence.nextDistributionDate()).isEqualTo(exDividend.toString());
        assertThat(evidence.nextExDividendDate()).isEqualTo(exDividend.toString());
        assertThat(evidence.nextExRightsDate()).isEqualTo(exRights.toString());
        assertThat(evidence.nextCashPaymentDate()).isEqualTo(cashPay.toString());
        assertThat(evidence.nextStockPaymentDate()).isEqualTo(stockPay.toString());
    }

    /**
     * 純配股事件（exDividendDate 為 null）：nextDistributionDate 改用 anchorDate 後
     * 不得拋 NPE，且必須落在 exRightsDate 而非維持 null。
     */
    @Test
    void withConfidenceDoesNotThrowForPureStockEventAndAnchorsOnExRightsDate() {
        LocalDate exRights = LocalDate.of(2026, 9, 22);
        var pureStock = new DividendEventEvidenceResolver.Event(
                null, BigDecimal.ZERO, new BigDecimal("0.30"), null, null,
                Instant.parse("2026-08-06T00:00:00Z"), "FINMIND", List.of(), exRights);
        var resolution = new DividendEventEvidenceResolver.Resolution(
                DividendEventEvidenceResolver.Status.AVAILABLE, pureStock, 0, 1, "FINMIND",
                List.of(), Instant.parse("2026-08-06T00:00:00Z"),
                Instant.parse("2026-08-06T00:00:00Z"), null);

        TradingRadarDto.RadarEvidence evidence = TradingRadarDto.RadarEvidence.withConfidence(
                null, null, "MISSING", false, null, null, null, null, null, false, null,
                List.of(), null, null, null, resolution, null, null, null, null);

        assertThat(evidence.nextDistributionDate()).isEqualTo(exRights.toString());
        assertThat(evidence.nextExDividendDate()).isNull();
        assertThat(evidence.nextExRightsDate()).isEqualTo(exRights.toString());
        assertThat(evidence.nextCashPaymentDate()).isNull();
        assertThat(evidence.nextStockPaymentDate()).isNull();
    }

    @Test
    void withConfidenceLeavesAllFourDatesNullWhenNoDistributionResolved() {
        TradingRadarDto.RadarEvidence evidence = TradingRadarDto.RadarEvidence.withConfidence(
                null, null, "MISSING", false, null, null, null, null, null, false, null,
                List.of(), null, null, null, null, null, null, null, null);

        assertThat(evidence.nextDistributionDate()).isNull();
        assertThat(evidence.nextExDividendDate()).isNull();
        assertThat(evidence.nextExRightsDate()).isNull();
        assertThat(evidence.nextCashPaymentDate()).isNull();
        assertThat(evidence.nextStockPaymentDate()).isNull();
    }
}
