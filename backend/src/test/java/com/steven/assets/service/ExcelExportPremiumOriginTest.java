package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 259：{@link ExcelExportService#premiumDiscountPct} 的台股分流。
 *
 * <p>既有實作對台股與美股一視同仁地以「淨值有值、折溢價欄留白」為條件反推，違反 Requirement 34
 * 「折溢價原樣保存、不由淨值反推」。本檔釘住台股缺漏一律回 null，並確認美股既有行為（反推、
 * 來源已有值）未被本次改動波及。</p>
 */
class ExcelExportPremiumOriginTest {

    // ── 259.5.8 台股分支 ──────────────────────────────────────────

    @Test
    void 台股折溢價欄留白時回null不使用即時價() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "00713", "台股", new BigDecimal("55.20"), null, "20260730 133000", "TWSE");

        BigDecimal result = ExcelExportService.premiumDiscountPct(nav, new BigDecimal("56.00"));

        assertThat(result).isNull();
    }

    // ── 259.5.9 既有行為不回歸 ────────────────────────────────────

    @Test
    void 來源已有折溢價時直接回傳該值() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "0050", "台股", new BigDecimal("93.50"), new BigDecimal("0.12"),
                "20260730 133000", "TWSE");

        BigDecimal result = ExcelExportService.premiumDiscountPct(nav, new BigDecimal("94.00"));

        assertThat(result).isEqualByComparingTo("0.12");
    }

    @Test
    void 美股仍以即時價反推折溢價() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "VOO", "美股", new BigDecimal("500.00"), null, "2026-07-29", "Yahoo Finance");

        // (501.00 - 500.00) / 500.00 * 100 = 0.20
        BigDecimal result = ExcelExportService.premiumDiscountPct(nav, new BigDecimal("501.00"));

        assertThat(result).isEqualByComparingTo("0.20");
    }

    @Test
    void 淨值為零時回null不擲除以零例外() {
        PriceQueryService.EtfNav nav = new PriceQueryService.EtfNav(
                "SGOV", "美股", BigDecimal.ZERO, null, "2026-07-29", "Yahoo Finance");

        BigDecimal result = ExcelExportService.premiumDiscountPct(nav, new BigDecimal("100.00"));

        assertThat(result).isNull();
    }

    // ── Task 320 驗證 (g)：抽出共用實作後既有輸出零回歸 ──────────────

    /**
     * {@code ExcelExportService.premiumDiscountPct} 自 Task 320 起純委派
     * {@link EtfLivePremiumCalculator}。上面四條既有斷言已覆蓋四個分支，本條再直接釘住
     * 「兩支方法對同一組輸入回傳同一個值」——委派若哪天被改回自己算一份（或改成
     * 「市場二分」那種偏離現行語意的寫法），資產總覽與交易雷達就會靜默分岔。
     */
    @Test
    void 委派共用實作後與交易雷達同一份計算逐格相同() {
        List<PriceQueryService.EtfNav> navs = Arrays.asList(
                null,
                new PriceQueryService.EtfNav("0050", "台股", new BigDecimal("93.50"),
                        new BigDecimal("0.12"), "20260730 133000", "TWSE"),
                new PriceQueryService.EtfNav("00713", "台股", new BigDecimal("55.20"),
                        null, "20260730 133000", "TWSE"),
                new PriceQueryService.EtfNav("VOO", "美股", new BigDecimal("500.00"),
                        null, "2026-07-29", "Yahoo Finance"),
                new PriceQueryService.EtfNav("VOO", "美股", new BigDecimal("500.00"),
                        new BigDecimal("0.33"), "2026-07-29", "Yahoo Finance"),
                new PriceQueryService.EtfNav("SGOV", "美股", BigDecimal.ZERO,
                        null, "2026-07-29", "Yahoo Finance"),
                new PriceQueryService.EtfNav("VT", "美股", null,
                        null, "2026-07-29", "Yahoo Finance"));
        List<BigDecimal> prices = Arrays.asList(null, new BigDecimal("501.00"), new BigDecimal("55.00"));

        for (PriceQueryService.EtfNav nav : navs) {
            for (BigDecimal price : prices) {
                assertThat(ExcelExportService.premiumDiscountPct(nav, price))
                        .as("nav=%s price=%s", nav, price)
                        .isEqualTo(EtfLivePremiumCalculator.premiumDiscountPct(nav, price));
            }
        }
    }
}
