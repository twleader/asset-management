package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FubonNumericContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void canonicalDecimalPreservesPointOneAndAcceptsPrecisionTwentyScaleTen() {
        assertThat(CanonicalFubonDecimal.parsePositive("0.1").value())
                .isEqualTo(new BigDecimal("0.1"));
        assertThat(CanonicalFubonDecimal.parsePositive("9999999999.9999999999").value())
                .isEqualTo(new BigDecimal("9999999999.9999999999"));
    }

    @Test
    void decimalRejectsPrecisionTwentyOneScaleElevenAndNonCanonicalForms() {
        assertThatThrownBy(() -> CanonicalFubonDecimal.parsePositive("10000000000.0000000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DECIMAL_PRECISION_EXCEEDED");
        assertThatThrownBy(() -> CanonicalFubonDecimal.parsePositive("0.00000000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DECIMAL_SCALE_EXCEEDED");
        for (String invalid : new String[]{"1e2", "1E2", "+1", "01", "-1", "0", "NaN", "Infinity"}) {
            assertThatThrownBy(() -> CanonicalFubonDecimal.parsePositive(invalid))
                    .as(invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void jacksonRequiresDecimalStringAndExactShareBounds() throws Exception {
        FubonDtos.Position minimum = mapper.readValue(
                "{\"stockCode\":\"2330\",\"shares\":1,\"costPrice\":\"0.1\"}",
                FubonDtos.Position.class);
        FubonDtos.Position maximum = mapper.readValue(
                "{\"stockCode\":\"2330\",\"shares\":9999999999,\"costPrice\":\"1\"}",
                FubonDtos.Position.class);
        assertThat(minimum.shares()).isEqualTo(1);
        assertThat(maximum.shares()).isEqualTo(9_999_999_999L);

        assertThatThrownBy(() -> mapper.readValue(
                "{\"stockCode\":\"2330\",\"shares\":10000000000,\"costPrice\":\"1\"}",
                FubonDtos.Position.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue(
                "{\"stockCode\":\"2330\",\"shares\":1.0,\"costPrice\":\"1\"}",
                FubonDtos.Position.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue(
                "{\"stockCode\":\"2330\",\"shares\":1,\"costPrice\":0.1}",
                FubonDtos.Position.class)).isInstanceOf(Exception.class);
    }

    @Test
    void exactVolumeAcceptsZeroAndLongMaxButRejectsOverflowAndNegative() throws Exception {
        assertThat(readVolume("0")).isZero();
        assertThat(readVolume("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> readVolume("9223372036854775808")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> readVolume("-1")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> readVolume("1.0")).isInstanceOf(Exception.class);
    }

    @Test
    void moneyMultiplicationRoundsOnlyAtTheEndAndChecksStoredPrecision() {
        assertThat(FubonInventoryWriter.checkedMoney(new BigDecimal("12.345").multiply(BigDecimal.valueOf(3))))
                .isEqualByComparingTo("37.04");
        assertThat(FubonInventoryWriter.checkedMoney(new BigDecimal("999999999999999999.99")))
                .isEqualByComparingTo("999999999999999999.99");
        assertThatThrownBy(() -> FubonInventoryWriter.checkedMoney(
                new BigDecimal("1000000000").multiply(new BigDecimal("9999999999"))))
                .isInstanceOf(FubonInventoryWriter.CommitRejected.class)
                .hasMessage("MONEY_PRECISION_EXCEEDED");
    }

    @Test
    void nonNegativeDecimalAllowsZeroButRejectsNegativeAndSharesTheSamePrecisionScaleBounds() {
        assertThat(CanonicalFubonDecimal.parseNonNegative("0").value()).isEqualByComparingTo("0");
        assertThat(CanonicalFubonDecimal.parseNonNegative("0.1").value())
                .isEqualTo(new BigDecimal("0.1"));
        assertThat(CanonicalFubonDecimal.parseNonNegative("9999999999.9999999999").value())
                .isEqualTo(new BigDecimal("9999999999.9999999999"));
        // Note: a leading "-" already fails the canonical-string regex before the signum check
        // ever runs (same pre-existing property as parsePositive's negative-input handling), so
        // this is NON_CANONICAL_DECIMAL, not NEGATIVE_DECIMAL -- that message is unreachable via
        // the public String-parsing entry point, exactly like parsePositive's analogous quirk.
        assertThatThrownBy(() -> CanonicalFubonDecimal.parseNonNegative("-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NON_CANONICAL_DECIMAL");
        assertThatThrownBy(() -> CanonicalFubonDecimal.parseNonNegative("10000000000.0000000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DECIMAL_PRECISION_EXCEEDED");
        assertThatThrownBy(() -> CanonicalFubonDecimal.parseNonNegative("0.00000000001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DECIMAL_SCALE_EXCEEDED");
        for (String invalid : new String[]{"1e2", "1E2", "+1", "01", "NaN", "Infinity"}) {
            assertThatThrownBy(() -> CanonicalFubonDecimal.parseNonNegative(invalid))
                    .as(invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void realizedGainRowAllowsZeroProfitOrLossButFilledPriceStaysStrictlyPositive() throws Exception {
        FubonDtos.RealizedGainRow zeroLoss = mapper.readValue("""
                {"stockNo":"2330","buySell":"Sell","filledQty":1000,"filledPrice":"105.50",
                 "realizedProfit":"550.00","realizedLoss":"0","sourceDate":"2026-08-10","orderType":"Stock"}
                """, FubonDtos.RealizedGainRow.class);
        assertThat(zeroLoss.realizedLoss().value()).isEqualByComparingTo("0");
        assertThat(zeroLoss.realizedProfit().value()).isEqualByComparingTo("550.00");

        // filledPrice keeps the class-default strictly-positive deserializer -- "0" must still fail.
        assertThatThrownBy(() -> mapper.readValue("""
                {"stockNo":"2330","buySell":"Sell","filledQty":1000,"filledPrice":"0",
                 "realizedProfit":"550.00","realizedLoss":"0","sourceDate":"2026-08-10","orderType":"Stock"}
                """, FubonDtos.RealizedGainRow.class)).isInstanceOf(Exception.class);

        // realizedProfit/realizedLoss still reject a genuinely negative value.
        assertThatThrownBy(() -> mapper.readValue("""
                {"stockNo":"2330","buySell":"Sell","filledQty":1000,"filledPrice":"105.50",
                 "realizedProfit":"-1","realizedLoss":"0","sourceDate":"2026-08-10","orderType":"Stock"}
                """, FubonDtos.RealizedGainRow.class)).isInstanceOf(Exception.class);
    }

    private long readVolume(String raw) throws Exception {
        String quote = """
                {"stockCode":"2330","stockName":"台積電","market":"台股",
                 "actualPrice":"1","previousClose":"1","openPrice":"1","highPrice":"1","lowPrice":"1",
                 "buyPrice":null,"sellPrice":null,"volume":%s,
                 "updatedAt":"2026-08-21T01:00:00Z","tradingDate":"2026-08-21",
                 "source":"FUBON_INTRADAY","closed":false,"quoteStatus":"LIVE"}
                """.formatted(raw);
        return mapper.readValue(quote, FubonDtos.Quote.class).volume();
    }
}
