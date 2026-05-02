package com.steven.assets.service;

import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.FundDividendHistory;
import com.steven.assets.model.FundMaster;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.FundDividendHistoryRepository;
import com.steven.assets.repository.FundMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 信託基金預估年配息計算 (Requirement 20)。
 *
 * 公式：年配息台幣 = 近 12 個月配息加總 (asiAmt) × FX
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundDividendService {

    private final FundMasterRepository fundMasterRepo;
    private final FundDividendHistoryRepository divHistRepo;
    private final ExchangeRateHistoryRepository rateHistRepo;

    /**
     * 取得每單位年配息台幣（近 12 個月加總 × FX）。無資料回 Optional.empty。
     */
    public Optional<AnnualDividendEstimate> getAnnualEstimateTwd(String fundCode) {
        return getAnnualEstimateOnDate(fundCode, null);
    }

    /**
     * 指定基準日的年配息估算 (Requirement 21)：
     * basedate=null → 現在往前 12 個月
     * basedate=X → [X-12 個月, X] 區間 amount 加總 × 該日 FX (closest-on-or-before)
     */
    public Optional<AnnualDividendEstimate> getAnnualEstimateOnDate(String fundCode, LocalDate basedate) {
        FundMaster master = fundMasterRepo.findById(fundCode).orElse(null);
        if (master == null) return Optional.empty();

        LocalDate to = basedate != null ? basedate : LocalDate.now();
        LocalDate from = to.minusMonths(12);
        List<FundDividendHistory> rows = divHistRepo
                .findByFundCodeAndBaseDateBetweenOrderByBaseDateDesc(fundCode, from, to);
        if (rows.isEmpty()) return Optional.empty();

        BigDecimal annualPerUnit = rows.stream()
                .map(FundDividendHistory::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal fxRate;
        if ("TWD".equalsIgnoreCase(master.getCurrency())) {
            fxRate = BigDecimal.ONE;
        } else {
            String cur = master.getCurrency().toUpperCase();
            ExchangeRateHistory fx = (basedate == null
                    ? rateHistRepo.findFirstByCurrencyOrderByRateDateDesc(cur)
                    : rateHistRepo.findClosestRate(cur, basedate)
            ).orElse(null);
            if (fx == null) {
                log.warn("找不到 {} 匯率（basedate={}），無法計算 {} 年配息估算", cur, basedate, fundCode);
                return Optional.empty();
            }
            fxRate = fx.getMidRate();
        }
        BigDecimal annualPerUnitTwd = annualPerUnit.multiply(fxRate);
        return Optional.of(new AnnualDividendEstimate(
                fundCode, master.getCurrency(), annualPerUnit, annualPerUnitTwd, fxRate, rows.size()));
    }

    /** 給定 units 算年配息台幣（最新）。 */
    public Optional<BigDecimal> computeAnnualDividendTwd(String fundCode, BigDecimal units) {
        return computeAnnualDividendTwdOnDate(fundCode, units, null);
    }

    /** 給定 units 與基準日算年配息台幣 (Requirement 21)。 */
    public Optional<BigDecimal> computeAnnualDividendTwdOnDate(String fundCode, BigDecimal units, LocalDate basedate) {
        if (units == null) return Optional.empty();
        return getAnnualEstimateOnDate(fundCode, basedate).map(e ->
                units.multiply(e.annualPerUnitTwd()).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * 從凍結 currentValue 反推年配息估算 (Requirement 21 重算用)：
     * estimatedDividend = currentValue × (annualPerUnitTwd / twdPerUnit) at basedate
     * 這樣不依賴 units 即可估算，便於對舊 snapshot 沒回填 units 的情境。
     */
    public Optional<BigDecimal> estimateFromCurrentValue(String fundCode, BigDecimal currentValue,
                                                         LocalDate basedate,
                                                         FundNavService.LatestNav navInfo) {
        if (currentValue == null || currentValue.compareTo(BigDecimal.ZERO) <= 0) return Optional.empty();
        var divEst = getAnnualEstimateOnDate(fundCode, basedate).orElse(null);
        if (divEst == null || navInfo == null) return Optional.empty();
        BigDecimal twdPerUnit = navInfo.twdPerUnit();
        if (twdPerUnit == null || twdPerUnit.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();
        BigDecimal yield = divEst.annualPerUnitTwd().divide(twdPerUnit, 6, RoundingMode.HALF_UP);
        return Optional.of(currentValue.multiply(yield).setScale(0, RoundingMode.HALF_UP));
    }

    public record AnnualDividendEstimate(
            String fundCode,
            String currency,
            BigDecimal annualPerUnit,        // 原幣
            BigDecimal annualPerUnitTwd,     // 台幣
            BigDecimal fxRate,
            int monthsCounted
    ) {}
}
