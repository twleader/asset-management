package com.steven.assets.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退休現金流試算結果（Requirement 32 / Task 165）。
 *
 * <p>由 {@code RetirementProjectionService} 以「使用者自訂的試算假設」對現有資產做**決定性逐年試算**
 * （非預測、非投資建議）：起始資產每年複利成長，累積期加「年薪 − 退休前年生活費」淨投入，退休後扣年生活費
 * （長照前／長照後兩階段，依通膨逐年膨脹）、加勞保年金／勞退一次領，並在對應年份扣特定大筆花費，逐年推到 100 歲或資金耗盡。
 *
 * <p>{@code available=false} 時（缺生日／無資產快照／退休後每月生活費未填）不試算，帶 {@code unavailableReason}。
 */
public record RetirementProjectionDto(
        boolean available,
        String unavailableReason,
        Integer currentAge,
        Integer retirementAge,
        int endAge,
        BigDecimal startAssets,
        Assumptions assumptions,
        List<Point> points,
        /** 退休當年年末預估結餘（退休提領起點的資產水位）。 */
        BigDecimal retirementStartBalance,
        /** 資金缺口年齡（資產在該歲年末 ≤ 0）；null 表示撐到 endAge 仍有餘。 */
        Integer depletionAge,
        Integer depletionYear,
        /** 是否可支應至 endAge（未出現缺口）。 */
        boolean lastsToEndAge,
        /** 撐到 endAge 的年末餘額（未缺口時）。 */
        BigDecimal endBalance
) {
    /** 本次試算採用的假設（供前端顯示「這是依你的假設算的」）。 */
    public record Assumptions(
            BigDecimal accumulationReturnPct,   // 累積期年報酬率 %
            BigDecimal retirementReturnPct,     // 退休後年報酬率 %
            BigDecimal inflationPct,            // 假設年通膨率 %
            BigDecimal retirementAnnualExpense, // 長照前年生活費（今日幣值）
            BigDecimal longTermCareAnnualExpense,// 長照後年生活費（今日幣值；null=無長照階段）
            Integer longTermCareStartAge,       // 長照起始年齡（實際採用值；null=無長照階段）
            boolean accumReturnFromBand,        // 累積期報酬率是否為系統依「獲利預期」帶入之預設（非使用者自填）
            boolean retireReturnFromBand        // 退休後報酬率是否為帶入之預設
    ) {}

    /** 逐年一點（年末結餘）。金額均為當年名目值、四捨五入到元。 */
    public record Point(
            int year,
            int age,
            String phase,          // ACCUM（累積期）/ RETIRE（退休後-長照前）/ CARE（退休後-長照後）
            BigDecimal balance,    // 年末資產餘額
            BigDecimal income,     // 當年流入（累積期＝年薪；退休後＝勞保年金＋當年勞退一次領）
            BigDecimal expense     // 當年流出（累積期＝退休前年生活費；退休後＝年生活費＋大筆花費）
    ) {}

    public static RetirementProjectionDto unavailable(String reason, Integer currentAge, BigDecimal startAssets) {
        return new RetirementProjectionDto(false, reason, currentAge, null, 100, startAssets,
                null, List.of(), null, null, null, false, null);
    }
}
