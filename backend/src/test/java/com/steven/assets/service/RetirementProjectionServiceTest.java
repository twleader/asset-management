package com.steven.assets.service;

import com.steven.assets.dto.RetirementProjectionDto;
import com.steven.assets.model.InvestmentProfile;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 退休現金流試算（Requirement 32 / Task 165、167）核心數學驗證——純單元、無 Spring／Mockito。
 * Task 167：退休後兩階段（長照前／長照後），生活費為年支出。
 */
class RetirementProjectionServiceTest {

    private final RetirementProjectionService svc = new RetirementProjectionService();

    private InvestmentProfile base() {
        InvestmentProfile p = new InvestmentProfile();
        p.setAssumedAnnualInflationRate(new BigDecimal("2"));
        return p;
    }

    @Test
    void unavailable_whenNoBirthDate() {
        InvestmentProfile p = base();
        p.setRetirementDate(LocalDate.now().plusYears(5));
        p.setRetirementAnnualExpense(new BigDecimal("360000"));
        RetirementProjectionDto r = svc.project(p, new BigDecimal("10000000"), List.of());
        assertFalse(r.available());
        assertNotNull(r.unavailableReason());
    }

    @Test
    void unavailable_whenRetirementButNoAnnualExpense() {
        InvestmentProfile p = base();
        p.setBirthDate(LocalDate.now().minusYears(50));
        p.setRetirementDate(LocalDate.now().plusYears(10));
        // 未填長照前年生活費
        RetirementProjectionDto r = svc.project(p, new BigDecimal("10000000"), List.of());
        assertFalse(r.available());
        assertTrue(r.unavailableReason().contains("生活費"));
    }

    @Test
    void unavailable_whenNoAssets() {
        InvestmentProfile p = base();
        p.setBirthDate(LocalDate.now().minusYears(50));
        RetirementProjectionDto r = svc.project(p, null, List.of());
        assertFalse(r.available());
    }

    @Test
    void lastsToEndAge_whenAssetsAmpleAndExpenseLow() {
        InvestmentProfile p = base();
        p.setBirthDate(LocalDate.now().minusYears(40));      // 現齡 40
        p.setRetirementDate(LocalDate.now().plusYears(25));  // 退休 65
        p.setMonthlyInvestment(new BigDecimal("50000"));
        p.setRetirementAnnualExpense(new BigDecimal("360000"));
        p.setAccumulationAnnualReturnRate(new BigDecimal("6"));
        p.setRetirementAnnualReturnRate(new BigDecimal("5"));

        RetirementProjectionDto r = svc.project(p, new BigDecimal("50000000"), List.of());
        assertTrue(r.available());
        assertEquals(40, r.currentAge());
        assertEquals(65, r.retirementAge());
        assertEquals(100, r.endAge());
        assertTrue(r.lastsToEndAge(), "資產充裕、支出低，應撐到 100 歲");
        assertNull(r.depletionAge());
        assertNotNull(r.endBalance());
        assertTrue(r.endBalance().signum() > 0);
        assertEquals(61, r.points().size());   // 基準點 + (100-40) 年
        assertEquals(40, r.points().get(0).age());
        assertEquals(100, r.points().get(r.points().size() - 1).age());
    }

    @Test
    void depletes_whenAssetsThinAndExpenseHigh() {
        InvestmentProfile p = base();
        p.setBirthDate(LocalDate.now().minusYears(60));      // 現齡 60
        p.setRetirementDate(LocalDate.now().plusYears(1));   // 退休 61
        p.setRetirementAnnualExpense(new BigDecimal("960000")); // 年提領 96 萬
        p.setAccumulationAnnualReturnRate(new BigDecimal("2"));
        p.setRetirementAnnualReturnRate(new BigDecimal("2"));

        RetirementProjectionDto r = svc.project(p, new BigDecimal("2000000"), List.of());
        assertTrue(r.available());
        assertFalse(r.lastsToEndAge(), "資產薄、支出高，應提前耗盡");
        assertNotNull(r.depletionAge());
        assertTrue(r.depletionAge() >= 61 && r.depletionAge() <= 75,
                "2百萬、年領約 96 萬，應在退休後數年內耗盡，實際=" + r.depletionAge());
    }

    @Test
    void returnRateFromBand_whenNotSpecified() {
        InvestmentProfile p = base();
        p.setBirthDate(LocalDate.now().minusYears(45));
        p.setRetirementDate(LocalDate.now().plusYears(20));
        p.setRetirementAnnualExpense(new BigDecimal("480000"));
        p.setExpectedAnnualReturn("R6_10"); // 帶入：累積 8% / 退休 4.5%

        RetirementProjectionDto r = svc.project(p, new BigDecimal("20000000"), List.of());
        assertTrue(r.available());
        assertTrue(r.assumptions().accumReturnFromBand());
        assertTrue(r.assumptions().retireReturnFromBand());
        assertEquals(0, new BigDecimal("8.0").compareTo(r.assumptions().accumulationReturnPct()));
        assertEquals(0, new BigDecimal("4.5").compareTo(r.assumptions().retirementReturnPct()));
    }

    @Test
    void twoStage_careExpenseAppliesFromCareStartAge() {
        InvestmentProfile p = base();
        p.setBirthDate(LocalDate.now().minusYears(60));      // 現齡 60
        p.setRetirementDate(LocalDate.now().plusYears(1));   // 退休 61
        p.setRetirementAnnualExpense(new BigDecimal("500000"));      // 長照前年 50 萬
        p.setLongTermCareAnnualExpense(new BigDecimal("1500000"));   // 長照後年 150 萬
        p.setLongTermCareStartAge(85);
        p.setRetirementAnnualReturnRate(new BigDecimal("2"));

        RetirementProjectionDto r = svc.project(p, new BigDecimal("30000000"), List.of());
        assertTrue(r.available());
        assertEquals(85, r.assumptions().longTermCareStartAge());
        assertEquals(0, new BigDecimal("1500000").compareTo(r.assumptions().longTermCareAnnualExpense()));

        // 84 歲仍為長照前（RETIRE），85 歲起為長照後（CARE）
        RetirementProjectionDto.Point pre = pointAtAge(r, 84);
        RetirementProjectionDto.Point care = pointAtAge(r, 85);
        assertNotNull(pre);
        assertNotNull(care);
        assertEquals("RETIRE", pre.phase());
        assertEquals("CARE", care.phase());
        // 長照後年支出（名目）應明顯高於長照前（150 萬 vs 50 萬，通膨相近年份）
        assertTrue(care.expense().compareTo(pre.expense()) > 0,
                "長照後年支出應高於長照前：care=" + care.expense() + " pre=" + pre.expense());
    }

    @Test
    void careStartAge_defaultsTo80_whenCareExpenseSetButAgeNull() {
        InvestmentProfile p = base();
        p.setBirthDate(LocalDate.now().minusYears(60));
        p.setRetirementDate(LocalDate.now().plusYears(1));
        p.setRetirementAnnualExpense(new BigDecimal("500000"));
        p.setLongTermCareAnnualExpense(new BigDecimal("1200000"));
        // 未指定長照起始年齡 → 預設 80
        RetirementProjectionDto r = svc.project(p, new BigDecimal("30000000"), List.of());
        assertTrue(r.available());
        assertEquals(80, r.assumptions().longTermCareStartAge());
        assertEquals("RETIRE", pointAtAge(r, 79).phase());
        assertEquals("CARE", pointAtAge(r, 80).phase());
    }

    private RetirementProjectionDto.Point pointAtAge(RetirementProjectionDto r, int age) {
        return r.points().stream().filter(pt -> pt.age() == age).findFirst().orElse(null);
    }
}
