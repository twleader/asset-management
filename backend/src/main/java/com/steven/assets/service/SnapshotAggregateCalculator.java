package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BankDeposit;
import com.steven.assets.model.FundHolding;
import com.steven.assets.model.StockHolding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Asset CRUD 與 broker 局部同步共用的唯一 snapshot aggregate 公式。 */
@Component
public class SnapshotAggregateCalculator {

    public void recalculate(AssetSnapshot snapshot) {
        BigDecimal totalDeposit = snapshot.getDeposits().stream()
                .map(BankDeposit::getAmount)
                .map(this::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFundValue = snapshot.getFunds().stream()
                .map(FundHolding::getCurrentValue)
                .map(this::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFundCost = snapshot.getFunds().stream()
                .map(FundHolding::getInvestmentAmount)
                .map(this::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalStockValue = snapshot.getStocks().stream()
                .map(StockHolding::getCurrentValue)
                .map(this::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal snapshotRate = snapshot.getUsdExchangeRate() != null
                ? snapshot.getUsdExchangeRate() : BigDecimal.ONE;
        BigDecimal totalStockCost = snapshot.getStocks().stream()
                .map(stock -> stockInvestmentCostTwd(stock, snapshotRate))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalStockDividend = snapshot.getStocks().stream()
                .map(StockHolding::getEstimatedDividend)
                .map(this::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFundDividend = snapshot.getFunds().stream()
                .map(FundHolding::getEstimatedDividend)
                .map(this::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDepositInterest = snapshot.getDeposits().stream()
                .map(this::depositEstimatedInterest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        snapshot.setTotalDeposit(totalDeposit);
        snapshot.setTotalFundValue(totalFundValue);
        snapshot.setTotalFundCost(totalFundCost);
        snapshot.setTotalStockValue(totalStockValue);
        snapshot.setTotalStockCost(totalStockCost);
        snapshot.setTotalAssets(totalDeposit.add(totalFundValue).add(totalStockValue));
        snapshot.setEstimatedAnnualDividend(
                totalStockDividend.add(totalFundDividend).add(totalDepositInterest));
    }

    public BigDecimal stockInvestmentCostTwd(StockHolding stock, BigDecimal snapshotRate) {
        BigDecimal cost = zeroIfNull(stock.getInvestmentCost());
        if ("USD".equals(stock.getCurrency())) {
            BigDecimal rate = stock.getTransactionExchangeRate() != null
                    ? stock.getTransactionExchangeRate()
                    : (snapshotRate != null ? snapshotRate : BigDecimal.ONE);
            return cost.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        }
        return cost.setScale(0, RoundingMode.HALF_UP);
    }

    public BigDecimal depositEstimatedInterest(BankDeposit deposit) {
        return depositEstimatedInterest(deposit.getAmount(), deposit.getAnnualInterestRate(), deposit.getCurrency());
    }

    /**
     * 存款預估年利息的唯一公式（entity 版與 SRPP 等以 DTO 為輸入者共用）：
     * {@code amount × rate / 100}，scale 0、HALF_UP；amount null 視為 0；
     * rate null 或 ≤0、或 currency 為 {@code TRANSIT_TWD}／{@code TRANSIT_USD} 時為 0。
     */
    public static BigDecimal depositEstimatedInterest(BigDecimal amount, BigDecimal rate, String currency) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        if ("TRANSIT_TWD".equals(currency) || "TRANSIT_USD".equals(currency)) return BigDecimal.ZERO;
        BigDecimal safeAmount = amount != null ? amount : BigDecimal.ZERO;
        return safeAmount
                .multiply(rate)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
