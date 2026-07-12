package com.steven.assets.service;

import com.steven.assets.dto.RetirementProjectionDto;
import com.steven.assets.model.InvestmentPlannedExpense;
import com.steven.assets.model.InvestmentProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 退休現金流試算（Requirement 32 / Task 165）。
 *
 * <p>對使用者現有資產做**決定性逐年試算**（非預測、非投資建議）：以使用者自訂（或依「獲利預期」帶入）的試算報酬率、
 * 通膨率與退休後每月生活費，從今天逐年推到 {@link #END_AGE} 歲或資金耗盡，回答「退休後資產能撐到幾歲／哪一年出現缺口」。
 *
 * <p>每一年的順序：期初餘額先以當期報酬率複利成長 → 累積期加「年薪 − 退休前年生活費」淨投入（皆依通膨逐年膨脹）、
 * 退休後扣年生活費（長照前／長照後兩階段，×(1+通膨)^距今年數）→
 * 加勞保年金（自起領年起，每年，依勞保條例 §65-4 CPI 累計±5% 階梯調整、見 {@link #laborAnnuityMultiplier}）與勞退一次領（領取當年）→ 扣當年到期的特定大筆花費（依通膨換算為名目值）→ 記錄年末餘額。
 * 皆為年granularity 近似，供規劃用折線圖與白話結論，不追求精算精度。
 *
 * <p>金額試算內部以 {@code double} 運算（資產量級遠小於 2^53，精度足夠），輸出前四捨五入為 {@link BigDecimal}（元）。
 */
@Slf4j
@Service
public class RetirementProjectionService {

    /** 試算終點年齡（撐到幾歲的上限）。 */
    static final int END_AGE = 100;

    /** 假設年通膨率預設值（%）：與 {@code PortfolioAdviceService} 一致（台灣長期 CPI 目標約 2%）。 */
    private static final BigDecimal DEFAULT_INFLATION_RATE = new BigDecimal("2");

    /** 長照起始年齡預設值（歲）：使用者有填長照後年生活費但未指定起始年齡時採用。 */
    private static final int DEFAULT_LTC_START_AGE = 80;

    /**
     * 勞保年金物價調整門檻（勞工保險條例第65條之4）：自起領年（或上次調整年）起，
     * CPI 累計成長率達此值（±5%）之年，年金給付即依**實際累計漲幅**調整、隨後重設基準；
     * 非逐年隨通膨、非固定 5%。定值通膨 2% 下約每 3 年跳一階（1.02^3−1≈6.12%）。
     */
    private static final double LABOR_ANNUITY_CPI_STEP = 0.05;

    /** 依「獲利預期」區間帶入之預設試算報酬率（累積期 / 退休後）；退休後預設較保守。使用者可於表單覆寫。 */
    private record ReturnDefault(BigDecimal accum, BigDecimal retire) {}

    private ReturnDefault returnDefault(String band) {
        return switch (band == null ? "" : band) {
            case "LT3"   -> new ReturnDefault(new BigDecimal("2.5"), new BigDecimal("2.0"));
            case "R3_6"  -> new ReturnDefault(new BigDecimal("4.5"), new BigDecimal("3.0"));
            case "R6_10" -> new ReturnDefault(new BigDecimal("8.0"), new BigDecimal("4.5"));
            case "GT10"  -> new ReturnDefault(new BigDecimal("12.0"), new BigDecimal("6.0"));
            default      -> new ReturnDefault(new BigDecimal("5.0"), new BigDecimal("3.0"));
        };
    }

    /**
     * 逐年試算。{@code startAssets} 為最新快照的資產總額（試算起點）。
     * 缺生日／無資產／（有退休日但）未填退休後每月生活費 → 回 unavailable（帶原因），不試算。
     */
    public RetirementProjectionDto project(InvestmentProfile p, BigDecimal startAssets,
                                           List<InvestmentPlannedExpense> expenses) {
        Integer currentAge = deriveAge(p == null ? null : p.getBirthDate());
        if (p == null || p.getBirthDate() == null || currentAge == null) {
            return RetirementProjectionDto.unavailable(
                    "請先填「生日」，才能依年齡逐年試算退休現金流。", currentAge, startAssets);
        }
        if (startAssets == null || startAssets.signum() <= 0) {
            return RetirementProjectionDto.unavailable(
                    "尚無資產快照或資產為 0，無法試算；請先於「管理資產」建立快照。", currentAge, startAssets);
        }
        LocalDate today = LocalDate.now();
        LocalDate retirementDate = p.getRetirementDate();
        boolean hasRetirement = retirementDate != null && retirementDate.getYear() <= today.getYear() + (END_AGE - currentAge);
        Integer retirementAge = hasRetirement
                ? Math.max(0, Period.between(p.getBirthDate(), retirementDate).getYears())
                : null;
        if (hasRetirement && (p.getRetirementAnnualExpense() == null || p.getRetirementAnnualExpense().signum() <= 0)) {
            return RetirementProjectionDto.unavailable(
                    "請填「長照前年生活費」，才能試算退休後的提領與資金能否支應。", currentAge, startAssets);
        }

        ReturnDefault def = returnDefault(p.getExpectedAnnualReturn());
        boolean accumFromBand = p.getAccumulationAnnualReturnRate() == null;
        boolean retireFromBand = p.getRetirementAnnualReturnRate() == null;
        BigDecimal accumPct = accumFromBand ? def.accum() : p.getAccumulationAnnualReturnRate();
        BigDecimal retirePct = retireFromBand ? def.retire() : p.getRetirementAnnualReturnRate();
        BigDecimal inflationPct = resolveInflationRate(p.getAssumedAnnualInflationRate());

        double ra = accumPct.doubleValue() / 100.0;
        double rr = retirePct.doubleValue() / 100.0;
        double infl = inflationPct.doubleValue() / 100.0;
        double annualSalary = dbl(p.getPreRetirementAnnualSalary());
        double preRetireExpense = dbl(p.getPreRetirementAnnualExpense());
        double annualExpensePreCare = dbl(p.getRetirementAnnualExpense());
        // 長照後階段：僅在使用者有填長照後年生活費（>0）時啟用；起始年齡預設 80，夾在 [退休年齡, 100]
        boolean careActive = p.getLongTermCareAnnualExpense() != null && p.getLongTermCareAnnualExpense().signum() > 0;
        double annualExpenseCare = dbl(p.getLongTermCareAnnualExpense());
        Integer ltcResolved = null;
        if (careActive && retirementAge != null) {
            int raw = p.getLongTermCareStartAge() != null ? p.getLongTermCareStartAge() : DEFAULT_LTC_START_AGE;
            ltcResolved = Math.min(END_AGE, Math.max(retirementAge, raw));
        }
        double laborMonthly = dbl(p.getLaborInsuranceMonthly());
        Integer laborStartYear = p.getLaborInsuranceStartDate() == null ? null : p.getLaborInsuranceStartDate().getYear();
        double pensionLump = dbl(p.getLaborPensionLumpSum());
        Integer pensionYear = p.getLaborPensionClaimDate() == null ? null : p.getLaborPensionClaimDate().getYear();
        int startYear = today.getYear();

        List<RetirementProjectionDto.Point> points = new ArrayList<>();
        double balance = startAssets.doubleValue();
        // 基準點（age = currentAge，今年，尚未套用當年流量）
        points.add(new RetirementProjectionDto.Point(startYear, currentAge,
                phaseOf(hasRetirement, currentAge, retirementAge, ltcResolved),
                round(balance), BigDecimal.ZERO, BigDecimal.ZERO));

        Integer depletionAge = null, depletionYear = null;
        BigDecimal retirementStartBalance = null;

        for (int age = currentAge + 1; age <= END_AGE; age++) {
            int year = startYear + (age - currentAge);
            int yearsFromNow = age - currentAge;
            boolean retire = hasRetirement && age >= retirementAge;
            boolean care = retire && ltcResolved != null && age >= ltcResolved;
            double rate = retire ? rr : ra;

            balance *= (1.0 + rate);
            double expense = 0, income = 0;
            double inflFactor = Math.pow(1.0 + infl, yearsFromNow);
            if (!retire) {
                // 累積期：年薪流入、退休前年生活費流出（皆依通膨逐年膨脹）；淨投入可為負
                if (annualSalary > 0) income += annualSalary * inflFactor;
                if (preRetireExpense > 0) expense += preRetireExpense * inflFactor;
            } else {
                double baseAnnual = care ? annualExpenseCare : annualExpensePreCare;
                if (baseAnnual > 0) expense += baseAnnual * inflFactor;
            }
            if (laborMonthly > 0 && laborStartYear != null && year >= laborStartYear) {
                // 勞保年金依 CPI 累計±5% 階梯調整（§65-4），非逐年隨通膨；勞退一次領不適用（見下）
                income += laborMonthly * 12.0 * laborAnnuityMultiplier(year, laborStartYear, infl);
            }
            if (pensionLump > 0 && pensionYear != null && year == pensionYear) {
                income += pensionLump;
            }
            if (expenses != null) {
                for (InvestmentPlannedExpense e : expenses) {
                    if (e == null || e.getExpenseDate() == null || e.getAmount() == null) continue;
                    if (e.getExpenseDate().getYear() == year) {
                        expense += inflate(e.getAmount(), inflationPct, e.getExpenseDate());
                    }
                }
            }
            balance += income - expense;

            points.add(new RetirementProjectionDto.Point(year, age,
                    care ? "CARE" : (retire ? "RETIRE" : "ACCUM"),
                    round(balance), round(income), round(expense)));

            if (retire && retirementStartBalance == null) {
                retirementStartBalance = round(balance);
            }
            if (balance <= 0) {
                depletionAge = age;
                depletionYear = year;
                break;
            }
        }

        boolean lasts = depletionAge == null;
        BigDecimal endBalance = lasts && !points.isEmpty() ? points.get(points.size() - 1).balance() : null;

        RetirementProjectionDto.Assumptions assumptions = new RetirementProjectionDto.Assumptions(
                accumPct, retirePct, inflationPct,
                p.getRetirementAnnualExpense(),
                careActive ? p.getLongTermCareAnnualExpense() : null,
                ltcResolved,
                accumFromBand, retireFromBand);

        return new RetirementProjectionDto(true, null, currentAge, retirementAge, END_AGE, startAssets,
                assumptions, points, retirementStartBalance, depletionAge, depletionYear, lasts, endBalance);
    }

    /** 某年齡的階段：累積期 / 退休後-長照前 / 退休後-長照後。 */
    private static String phaseOf(boolean hasRetirement, int age, Integer retirementAge, Integer ltcResolved) {
        if (!hasRetirement || retirementAge == null || age < retirementAge) return "ACCUM";
        if (ltcResolved != null && age >= ltcResolved) return "CARE";
        return "RETIRE";
    }

    // ===== helpers（與 PortfolioAdviceService 同語意；本服務自足以維持內聚）=====

    private Integer deriveAge(LocalDate birthDate) {
        if (birthDate == null) return null;
        return Math.max(0, Period.between(birthDate, LocalDate.now()).getYears());
    }

    private BigDecimal resolveInflationRate(BigDecimal rate) {
        return (rate == null || rate.signum() < 0) ? DEFAULT_INFLATION_RATE : rate;
    }

    /** 今日幣值 → 指定日期未來名目值：amount × (1+r%)^(距今年數)，年數＝天數 ÷ 365.25。 */
    private double inflate(BigDecimal amount, BigDecimal ratePct, LocalDate date) {
        if (amount == null || date == null) return dbl(amount);
        double r = ratePct.doubleValue() / 100.0;
        double years = ChronoUnit.DAYS.between(LocalDate.now(), date) / 365.25;
        return amount.doubleValue() * Math.pow(1.0 + r, years);
    }

    /**
     * 勞保年金物價調整倍數（勞工保險條例第65條之4）。
     *
     * <p>年金給付非逐年隨通膨：自起領年（{@code startYear}）起累計 CPI，於**累計成長率達 ±5%**
     * （{@link #LABOR_ANNUITY_CPI_STEP}）之年，才依**實際累計漲幅**調升並重設基準，隨後重新累計。
     * 定值通膨下每 {@code k} 年跨一次門檻（{@code k = ⌈ln(1.05)/ln(1+infl)⌉}，如 2% → 3 年），
     * 每階乘 {@code (1+infl)^k}（即實際累計漲幅、非固定 5%），故第 {@code n=year−startYear} 年倍數 = {@code (1+infl)^(k·⌊n/k⌋)}。
     *
     * <p>本專案 {@code infl} 已由 {@link #resolveInflationRate} 夾為 ≥0（負通膨夾為預設正值），
     * 故僅需處理上調；{@code infl≈0} 永不觸發、倍數恆為 1。倍數僅依 {@code (year−startYear)}，
     * 自動涵蓋起領年早於試算首年（已在請領）之歷史調整。勞退（一次領）不適用，不呼叫本方法。
     */
    private double laborAnnuityMultiplier(int year, int startYear, double infl) {
        if (year <= startYear || infl <= 1e-9) return 1.0;
        // 每 stepYears 年 CPI 累計首次達門檻。−1e-9 消除 ln(1.05)/ln(1+infl) 恰為整數時
        // ceil 的浮點刀鋒（否則首階延後一年）；對非整數比值無影響（FP 誤差 ≪ 1e-9）。
        int stepYears = (int) Math.ceil(Math.log(1.0 + LABOR_ANNUITY_CPI_STEP) / Math.log(1.0 + infl) - 1e-9);
        int steps = (year - startYear) / stepYears;   // 已跨門檻次數（整數除法＝⌊⌋）
        return Math.pow(1.0 + infl, (double) stepYears * steps);
    }

    private static double dbl(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static BigDecimal round(double v) {
        return new BigDecimal(v, MathContext.DECIMAL64).setScale(0, RoundingMode.HALF_UP);
    }
}
