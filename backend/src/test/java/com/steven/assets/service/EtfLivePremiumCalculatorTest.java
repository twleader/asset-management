package com.steven.assets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 320 驗證 (a)–(d)：{@link EtfLivePremiumCalculator} 的分流。
 *
 * <p>純靜態、不需 Spring context；台股／美股 fixture 直接構造 {@link PriceQueryService.EtfNav}。
 */
class EtfLivePremiumCalculatorTest {

    // ── (a) 台股原樣取用權威值，不重算 ──────────────────────────────

    /**
     * fixture 刻意讓「權威值」與「以本列現價重算的值」相差極大，否則這條沒有鑑別力：
     * 例如拿 livePrice=105.20 時重算為 −0.350478…，四捨五入後恰好也是 −0.35，兩者相等、斷言恆紅。
     */
    @Test
    @DisplayName("(a) 台股直取證交所權威值，且明顯不等於以本列現價重算的值")
    void 台股原樣取用權威值不重算() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "0050", "台股", new BigDecimal("105.57"), new BigDecimal("-0.35"),
                "20260812 133000", "TWSE");
        BigDecimal livePrice = new BigDecimal("110.00");

        BigDecimal result = EtfLivePremiumCalculator.premiumDiscountPct(nav, livePrice);

        assertThat(result).isEqualByComparingTo("-0.35");
        // (110.00 − 105.57) / 105.57 × 100 = 4.19627… → HALF_UP 2 位 = 4.20
        BigDecimal recomputed = livePrice.subtract(nav.nav())
                .multiply(BigDecimal.valueOf(100))
                .divide(nav.nav(), 2, RoundingMode.HALF_UP);
        assertThat(recomputed).isEqualByComparingTo("4.20");
        assertThat(result)
                .as("台股取的必須是權威值，不是重算值——淨值欄四捨五入至 2 位，重算誤差達 0.07 個百分點")
                .isNotEqualByComparingTo(recomputed);
    }

    // ── (b) 台股折溢價欄留白時回 null，不反推 ────────────────────────

    @Test
    @DisplayName("(b) 台股折溢價欄留白時回 null，不以現價反推（Task 259 鐵則）")
    void 台股折溢價留白時回null不反推() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "00713", "台股", new BigDecimal("105.57"), null, "20260812 133000", "TWSE");

        assertThat(EtfLivePremiumCalculator.premiumDiscountPct(nav, new BigDecimal("110.00"))).isNull();
    }

    // ── (c) 美股以該列現價反推 ──────────────────────────────────────

    @Test
    @DisplayName("(c) 美股以該列現價反推，且不得改用 T-1 收盤價")
    void 美股以該列現價反推() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "VOO", "美股", new BigDecimal("710.53"), null, "2026-08-11", "Yahoo Finance");

        // (712.00 − 710.53) / 710.53 × 100 = 0.20688… → 0.21
        BigDecimal result = EtfLivePremiumCalculator.premiumDiscountPct(nav, new BigDecimal("712.00"));
        assertThat(result).isEqualByComparingTo("0.21");

        // 同一檔改用 T-1 收盤價（previousClose）反推會得到完全不同的量級——實測會把真實的
        // 微幅溢價放大成 1% 級的折價，故一律以「該列畫面上顯示的現價」為準。
        BigDecimal fromPreviousClose =
                EtfLivePremiumCalculator.premiumDiscountPct(nav, new BigDecimal("703.30"));
        assertThat(fromPreviousClose).isEqualByComparingTo("-1.02");
        assertThat(result).isNotEqualByComparingTo(fromPreviousClose);
    }

    // ── (d) 個股與缺值一律 null ─────────────────────────────────────

    @Test
    @DisplayName("(d) Redis 查無 etfnav（個股）回 null，不補 0、不補 N/A")
    void 個股查無淨值回null() {
        assertThat(EtfLivePremiumCalculator.premiumDiscountPct(null, new BigDecimal("1105.00"))).isNull();
    }

    @Test
    @DisplayName("(d) 淨值為 null 回 null")
    void 淨值為null回null() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "SGOV", "美股", null, null, "2026-08-11", "Yahoo Finance");

        assertThat(EtfLivePremiumCalculator.premiumDiscountPct(nav, new BigDecimal("100.00"))).isNull();
    }

    @Test
    @DisplayName("(d) 現價為 null 回 null")
    void 現價為null回null() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "VT", "美股", new BigDecimal("130.00"), null, "2026-08-11", "Yahoo Finance");

        assertThat(EtfLivePremiumCalculator.premiumDiscountPct(nav, null)).isNull();
    }

    @Test
    @DisplayName("(d) 淨值為 0 回 null，不擲除以零例外")
    void 淨值為零回null() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "SGOV", "美股", BigDecimal.ZERO, null, "2026-08-11", "Yahoo Finance");

        assertThat(EtfLivePremiumCalculator.premiumDiscountPct(nav, new BigDecimal("100.00"))).isNull();
    }

    // ── 判斷順序（320.2 的第 0 條）：先看權威值、不看 market ────────────

    @Test
    @DisplayName("來源已提供權威值時原樣回傳，不論市場——順序是「先看權威值」而非「先做市場二分」")
    void 權威值優先於市場二分() {
        PriceQueryService.EtfNav us = new PriceQueryService.EtfNav(
                "VOO", "美股", new BigDecimal("500.00"), new BigDecimal("0.12"),
                "2026-08-11", "Yahoo Finance");

        // 若寫成 if (市場 == 台股) 直取 else 反推，這裡會回 0.20（反推值）而非 0.12
        assertThat(EtfLivePremiumCalculator.premiumDiscountPct(us, new BigDecimal("501.00")))
                .isEqualByComparingTo("0.12");
    }
}
