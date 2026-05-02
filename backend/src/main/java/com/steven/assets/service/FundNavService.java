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
        FundMaster master = fundMasterRepo.findById(fundCode).orElse(null);
        if (master == null) return Optional.empty();

        FundNav nav = fundNavRepo.findTopByFundCodeOrderByNavDateDesc(fundCode).orElse(null);
        if (nav == null) return Optional.empty();

        String currency = master.getCurrency();
        BigDecimal fxRate;
        LocalDate fxDate = null;
        if ("TWD".equalsIgnoreCase(currency)) {
            fxRate = BigDecimal.ONE;
        } else {
            ExchangeRateHistory fx = rateHistRepo
                    .findFirstByCurrencyOrderByRateDateDesc(currency.toUpperCase())
                    .orElse(null);
            if (fx == null) {
                log.warn("找不到 {} 匯率，無法計算 {} 台幣現值", currency, fundCode);
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
     * 給定 units 算出台幣現值。NAV / FX 缺一不可，缺 → 回 empty。
     */
    public Optional<BigDecimal> computeCurrentValueTwd(String fundCode, BigDecimal units) {
        if (units == null) return Optional.empty();
        return getLatestNavTwd(fundCode).map(l ->
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
