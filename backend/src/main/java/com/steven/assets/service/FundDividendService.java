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
        FundMaster master = fundMasterRepo.findById(fundCode).orElse(null);
        if (master == null) return Optional.empty();

        LocalDate since = LocalDate.now().minusMonths(12);
        List<FundDividendHistory> rows = divHistRepo
                .findByFundCodeAndBaseDateGreaterThanEqualOrderByBaseDateDesc(fundCode, since);
        if (rows.isEmpty()) return Optional.empty();

        BigDecimal annualPerUnit = rows.stream()
                .map(FundDividendHistory::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal fxRate;
        if ("TWD".equalsIgnoreCase(master.getCurrency())) {
            fxRate = BigDecimal.ONE;
        } else {
            ExchangeRateHistory fx = rateHistRepo
                    .findFirstByCurrencyOrderByRateDateDesc(master.getCurrency().toUpperCase())
                    .orElse(null);
            if (fx == null) {
                log.warn("找不到 {} 匯率，無法計算 {} 年配息估算", master.getCurrency(), fundCode);
                return Optional.empty();
            }
            fxRate = fx.getMidRate();
        }
        BigDecimal annualPerUnitTwd = annualPerUnit.multiply(fxRate);
        return Optional.of(new AnnualDividendEstimate(
                fundCode, master.getCurrency(), annualPerUnit, annualPerUnitTwd, fxRate, rows.size()));
    }

    /** 給定 units 算年配息台幣（給 AssetService.createSnapshot 用）。 */
    public Optional<BigDecimal> computeAnnualDividendTwd(String fundCode, BigDecimal units) {
        if (units == null) return Optional.empty();
        return getAnnualEstimateTwd(fundCode).map(e ->
                units.multiply(e.annualPerUnitTwd()).setScale(2, RoundingMode.HALF_UP));
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
