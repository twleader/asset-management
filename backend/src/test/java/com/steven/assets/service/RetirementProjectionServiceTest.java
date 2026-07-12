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
        p.setPreRetirementAnnualSalary(new BigDecimal("1200000"));  // 退休前年薪 120 萬
        p.setPreRetirementAnnualExpense(new BigDecimal("600000"));  // 退休前年支出 60 萬 → 淨投入 60 萬/年
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

    @Test
    void accumulation_usesSalaryAndPreRetirementExpense() {
        InvestmentProfile p = base();
        p.setBirthDate(LocalDate.now().minusYears(50));      // 現齡 50
        p.setRetirementDate(LocalDate.now().plusYears(10));  // 退休 60（累積期 10 年）
        p.setPreRetirementAnnualSalary(new BigDecimal("1000000"));   // 年薪 100 萬
        p.setPreRetirementAnnualExpense(new BigDecimal("400000"));   // 退休前年支出 40 萬 → 淨投入 60 萬/年
        p.setRetirementAnnualExpense(new BigDecimal("300000"));
        p.setAccumulationAnnualReturnRate(new BigDecimal("0"));      // 無報酬，隔離淨投入效果
        p.setRetirementAnnualReturnRate(new BigDecimal("0"));

        RetirementProjectionDto r = svc.project(p, new BigDecimal("10000000"), List.of());
        assertTrue(r.available());
        // 第 1 年（age 51）累積期：income≈年薪×通膨、expense≈退休前年支出×通膨（通膨 2%、yearsFromNow=1）
        RetirementProjectionDto.Point y1 = pointAtAge(r, 51);
        assertEquals("ACCUM", y1.phase());
        assertTrue(y1.income().doubleValue() > 1_000_000 && y1.income().doubleValue() < 1_050_000,
                "累積期流入應約等於年薪（依通膨微升），實際=" + y1.income());
        assertTrue(y1.expense().doubleValue() > 400_000 && y1.expense().doubleValue() < 430_000,
                "累積期流出應約等於退休前年支出，實際=" + y1.expense());
    }

    @Test
    void laborAnnuity_stepsUpByCumulativeCpiEvery5pct() {
        // 勞保條例 §65-4：年金非逐年隨通膨，累計 CPI 達 ±5% 之年才依實際累計漲幅調整並重設基準。
        // 通膨 2% → 每 3 年累計達 6.12% 跳一階（×1.02^3）；階內維持不變。
        InvestmentProfile p = base();                        // 通膨 2%
        p.setBirthDate(LocalDate.now().minusYears(60));      // 現齡 60
        p.setRetirementDate(LocalDate.now().plusYears(1));   // 退休 61
        p.setRetirementAnnualExpense(new BigDecimal("500000"));
        p.setRetirementAnnualReturnRate(new BigDecimal("3"));
        p.setLaborInsuranceMonthly(new BigDecimal("20000")); // 月領 2 萬 → 年 24 萬（起領年基準）
        p.setLaborInsuranceStartDate(LocalDate.now().plusYears(2)); // 起領＝62 歲

        RetirementProjectionDto r = svc.project(p, new BigDecimal("30000000"), List.of());
        assertTrue(r.available());

        // 起領年與其後 2 年（累計 2%、4.04% < 5%）維持基準年金 24 萬
        assertEquals(0, new BigDecimal("240000").compareTo(pointAtAge(r, 62).income()), "起領年基準");
        assertEquals(0, new BigDecimal("240000").compareTo(pointAtAge(r, 63).income()), "累計 2% 未調");
        assertEquals(0, new BigDecimal("240000").compareTo(pointAtAge(r, 64).income()), "累計 4.04% 未調");

        // 第 3 年（累計 6.12% ≥ 5%）依實際累計漲幅調升：240000×1.02^3 ≈ 254690（非固定 5% 的 252000）
        BigDecimal step1 = pointAtAge(r, 65).income();
        assertEquals(0, new BigDecimal("254690").compareTo(step1), "第3年依實際累計漲幅跳升，實際=" + step1);
        // 階內（65~67 歲）維持不變，直到下一次累計再達 5%
        assertEquals(0, step1.compareTo(pointAtAge(r, 66).income()), "階內不變");
        assertEquals(0, step1.compareTo(pointAtAge(r, 67).income()), "階內不變");
        // 第 6 年再跳一階：240000×1.02^6 ≈ 270279
        assertEquals(0, new BigDecimal("270279").compareTo(pointAtAge(r, 68).income()),
                "第6年再跳一階，實際=" + pointAtAge(r, 68).income());
    }

    @Test
    void laborAnnuity_noAdjustmentWhenZeroInflation() {
        // 通膨 0 → 累計永不達 5%，年金恆為基準（倍數恆為 1），驗證 laborAnnuityMultiplier 的 infl≈0 分支
        InvestmentProfile p = base();
        p.setAssumedAnnualInflationRate(BigDecimal.ZERO);
        p.setBirthDate(LocalDate.now().minusYears(60));
        p.setRetirementDate(LocalDate.now().plusYears(1));
        p.setRetirementAnnualExpense(new BigDecimal("500000"));
        p.setRetirementAnnualReturnRate(new BigDecimal("3"));
        p.setLaborInsuranceMonthly(new BigDecimal("20000"));
        p.setLaborInsuranceStartDate(LocalDate.now().plusYears(2));

        RetirementProjectionDto r = svc.project(p, new BigDecimal("30000000"), List.of());
        assertTrue(r.available());
        assertEquals(0, new BigDecimal("240000").compareTo(pointAtAge(r, 62).income()));
        assertEquals(0, new BigDecimal("240000").compareTo(pointAtAge(r, 75).income()), "通膨 0 永不調整");
        assertEquals(0, new BigDecimal("240000").compareTo(pointAtAge(r, 90).income()), "通膨 0 永不調整");
    }

    private RetirementProjectionDto.Point pointAtAge(RetirementProjectionDto r, int age) {
        return r.points().stream().filter(pt -> pt.age() == age).findFirst().orElse(null);
    }
}
