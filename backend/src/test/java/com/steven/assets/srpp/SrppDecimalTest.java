package com.steven.assets.srpp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 163／Task 452.3：canonical Decimal 正反例。 */
class SrppDecimalTest {

    @Test
    void formatsCanonicalStrings() {
        assertThat(SrppDecimal.format(new BigDecimal("1600000"))).isEqualTo("1600000");
        assertThat(SrppDecimal.format(new BigDecimal("1600000.00"))).isEqualTo("1600000");
        assertThat(SrppDecimal.format(new BigDecimal("0.05"))).isEqualTo("0.05");
        assertThat(SrppDecimal.format(new BigDecimal("0.0500"))).isEqualTo("0.05");
        assertThat(SrppDecimal.format(new BigDecimal("-31004"))).isEqualTo("-31004");
        assertThat(SrppDecimal.format(BigDecimal.ZERO)).isEqualTo("0");
        assertThat(SrppDecimal.format(new BigDecimal("0.00"))).isEqualTo("0");
        assertThat(SrppDecimal.format(new BigDecimal("-0.0"))).isEqualTo("0");
        assertThat(SrppDecimal.format(new BigDecimal("1E+3"))).isEqualTo("1000");
        for (String s : new String[]{"1600000", "0.05", "-31004", "0", "1000", "0.2", "-0.2"}) {
            assertThat(SrppDecimal.isCanonical(s)).as(s).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "1.0", "-0", "1e3", "1E3", "1,000", "NaN", "", "+1", ".5", "1.", "Infinity", "-0.0"})
    void rejectsNonCanonical(String text) {
        assertThat(SrppDecimal.isCanonical(text)).isFalse();
    }

    @Test
    void rejectsNullAndOverlong() {
        assertThat(SrppDecimal.isCanonical(null)).isFalse();
        assertThat(SrppDecimal.isCanonical("1".repeat(81))).isFalse();
        assertThat(SrppDecimal.isCanonical("1".repeat(80))).isTrue();
    }
}
