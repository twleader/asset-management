package com.steven.assets.integration.fubon;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FubonRealizedGainFingerprintTest {

    @Test
    void sourceTupleUsesTheSpecifiedUtf8LfV1GoldenVector() {
        FubonDtos.RealizedGainRow row = row("2026-09-06", "123.45", "1000", "0");

        assertThat(FubonRealizedGainFingerprint.of(row))
                .isEqualTo("c4cfb726c52f0a061986cf3fa0a02ce90885d0105298d10a4063674cb160f574");
    }

    @Test
    void mappingUsesReportedNetPnlAndCanonicalStoredScales() {
        FubonDtos.RealizedGainRow row = row("2026-09-06", "123.45", "1000", "0");

        FubonRealizedGainWriter.PreparedGain gain = FubonRealizedGainWriter.prepare(row, "台積電");

        assertThat(gain.assetName()).isEqualTo("台積電");
        assertThat(gain.salePrice()).isEqualByComparingTo("123.4500");
        assertThat(gain.proceeds()).isEqualByComparingTo("123450.00");
        assertThat(gain.investmentCost()).isEqualByComparingTo("122450.00");
    }

    @Test
    void sourcePriceThatWouldNeedRoundingForSalePriceIsRejected() {
        FubonDtos.RealizedGainRow row = row("2026-09-06", "123.45678", "1000", "0");

        assertThatThrownBy(() -> FubonAccountingContract.prepareRealized(row))
                .isInstanceOf(FubonAccountingContract.Rejected.class)
                .hasMessage("INVALID_REALIZED_PRICE");
    }

    private static FubonDtos.RealizedGainRow row(String date, String price, String profit, String loss) {
        return new FubonDtos.RealizedGainRow("2330", "Sell", "Stock", 1000,
                CanonicalFubonDecimal.parsePositive(price),
                CanonicalFubonDecimal.parseNonNegative(profit),
                CanonicalFubonDecimal.parseNonNegative(loss), LocalDate.parse(date));
    }
}
