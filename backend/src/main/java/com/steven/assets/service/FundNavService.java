package com.steven.assets.service;

import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.FundMaster;
import com.steven.assets.model.FundNav;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.FundMasterRepository;
import com.steven.assets.repository.FundNavRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 信託基金最新淨值 + 台幣現值計算（Requirement 19）。
 *
 * 公式：台幣現值 = units × nav × fxRate（TWD 計價基金 fxRate=1）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundNavService {

    private final FundMasterRepository fundMasterRepo;
    private final FundNavRepository fundNavRepo;
    private final ExchangeRateHistoryRepository rateHistRepo;

    /**
     * 取最新淨值 + 匯率資訊（給 BFF 預載 / 計算用）。
     * 若 fund_master 找不到、或最新 NAV 無資料，回 Optional.empty。
     */
    public Optional<LatestNav> getLatestNavTwd(String fundCode) {
        return getNavTwdOnDate(fundCode, null);
    }

    /**
     * 取指定基準日的 NAV + FX (Requirement 21)。null → 取最新。NAV / FX 都採 closest-on-or-before。
     */
    public Optional<LatestNav> getNavTwdOnDate(String fundCode, LocalDate basedate) {
        FundMaster master = fundMasterRepo.findById(fundCode).orElse(null);
        if (master == null) return Optional.empty();

        FundNav nav = (basedate == null
                ? fundNavRepo.findTopByFundCodeOrderByNavDateDesc(fundCode)
                : fundNavRepo.findFirstByFundCodeAndNavDateLessThanEqualOrderByNavDateDesc(fundCode, basedate)
        ).orElse(null);
        if (nav == null) return Optional.empty();

        String currency = master.getCurrency();
        BigDecimal fxRate;
        LocalDate fxDate = null;
        if ("TWD".equalsIgnoreCase(currency)) {
            fxRate = BigDecimal.ONE;
        } else {
            String cur = currency.toUpperCase();
            ExchangeRateHistory fx = (basedate == null
                    ? rateHistRepo.findFirstByCurrencyOrderByRateDateDesc(cur)
                    : rateHistRepo.findClosestRate(cur, basedate)
            ).orElse(null);
            if (fx == null) {
                log.warn("找不到 {} 匯率（basedate={}），無法計算 {} 台幣現值", cur, basedate, fundCode);
                return Optional.empty();
            }
            fxRate = fx.getMidRate();
            fxDate = fx.getRateDate();
        }
        return Optional.of(new LatestNav(
                fundCode, currency,
                nav.getNav(), nav.getNavDate(),
                fxRate, fxDate
        ));
    }

    /**
     * 給定 units 算出台幣現值（最新）。
     */
    public Optional<BigDecimal> computeCurrentValueTwd(String fundCode, BigDecimal units) {
        return computeCurrentValueTwdOnDate(fundCode, units, null);
    }

    /**
     * 給定 units 與基準日算出台幣現值 (Requirement 21)。
     */
    public Optional<BigDecimal> computeCurrentValueTwdOnDate(String fundCode, BigDecimal units, LocalDate basedate) {
        if (units == null) return Optional.empty();
        return getNavTwdOnDate(fundCode, basedate).map(l ->
                units.multiply(l.nav()).multiply(l.fxRate())
                        .setScale(2, RoundingMode.HALF_UP));
    }

    public record LatestNav(
            String fundCode,
            String currency,
            BigDecimal nav,
            LocalDate navDate,
            BigDecimal fxRate,
            LocalDate fxDate
    ) {
        public BigDecimal twdPerUnit() {
            return nav.multiply(fxRate);
        }
    }
}
