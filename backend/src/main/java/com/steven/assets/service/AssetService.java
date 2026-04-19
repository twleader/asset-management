package com.steven.assets.service;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.dto.RealizedGainDto;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BrokerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetSnapshotRepository snapshotRepo;
    private final BankDepositRepository depositRepo;
    private final FundHoldingRepository fundRepo;
    private final StockHoldingRepository stockRepo;
    private final RealizedGainRepository gainRepo;
    private final ExchangeRateHistoryRepository rateHistRepo;
    private final MarketDataService marketDataService;
    private final BankRepository bankRepo;
    private final BrokerRepository brokerRepo;

    // ===================== Snapshot =====================

    @Transactional(readOnly = true)
    public List<AssetSnapshotDto.SnapshotSummaryResponse> getAllSnapshots() {
        return snapshotRepo.findAllOrderByDateDesc().stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AssetSnapshotDto.SnapshotDetailResponse getSnapshotDetail(Long id) {
        AssetSnapshot s = findSnapshot(id);
        return toDetailResponse(s);
    }

    /**
     * 對快照中所有缺少 dividendRate 的持股，呼叫 MarketDataService 補齊，
     * 並更新 estimatedDividend 及快照層級 estimatedAnnualDividend，最後存回 DB。
     */
    private void autoEnrichDividendRates(AssetSnapshot snapshot) {
        boolean updated = false;
        for (StockHolding st : snapshot.getStocks()) {
            if (st.getDividendRate() != null && st.getDividendRate().compareTo(BigDecimal.ZERO) > 0) continue;
            try {
                MarketDataService.DividendRateResult r =
                        marketDataService.getDividendRate(st.getStockCode(), st.getMarket());
                if (r != null && r.dividendRate() != null && r.dividendRate().compareTo(BigDecimal.ZERO) > 0) {
                    st.setDividendRate(r.dividendRate());
                    if (st.getCurrentValue() != null) {
                        st.setEstimatedDividend(
                            st.getCurrentValue().multiply(r.dividendRate()).setScale(0, RoundingMode.HALF_UP));
                    }
                    updated = true;
                    log.info("自動補齊配息率 {} {}: {}%", st.getStockCode(), st.getStockName(),
                            r.dividendRate().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
                }
            } catch (Exception e) {
                log.warn("查詢配息率失敗 {} {}: {}", st.getStockCode(), st.getStockName(), e.getMessage());
            }
        }
        if (updated) {
            // 重算快照合計
            BigDecimal totalDividend = snapshot.getStocks().stream()
                    .filter(st -> st.getEstimatedDividend() != null)
                    .map(StockHolding::getEstimatedDividend)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            snapshot.setEstimatedAnnualDividend(totalDividend);
            snapshotRepo.save(snapshot);
        }
    }

    @Transactional
    public AssetSnapshotDto.SnapshotSummaryResponse createSnapshot(AssetSnapshotDto.CreateSnapshotRequest req) {
        if (snapshotRepo.existsBySnapshotDate(req.snapshotDate())) {
            throw new IllegalArgumentException("該日期的快照已存在: " + req.snapshotDate());
        }

        AssetSnapshot snapshot = AssetSnapshot.builder()
                .snapshotDate(req.snapshotDate())
                .usdExchangeRate(req.usdExchangeRate())
                .notes(req.notes())
                .build();

        // 存款
        if (req.deposits() != null) {
            req.deposits().forEach(d -> {
                Bank bank = d.bankId() != null ? bankRepo.findById(d.bankId()).orElse(null) : null;
                BankDeposit deposit = BankDeposit.builder()
                        .snapshot(snapshot)
                        .bank(bank)
                        .depositType(d.depositType())
                        .amount(d.amount())
                        .originalAmount(d.originalAmount())
                        .currency(d.currency() != null ? d.currency() : "TWD")
                        .notes(d.notes())
                        .build();
                snapshot.getDeposits().add(deposit);
            });
        }

        // 基金
        if (req.funds() != null) {
            req.funds().forEach(f -> {
                Bank bank = f.bankId() != null ? bankRepo.findById(f.bankId()).orElse(null) : null;
                FundHolding fund = FundHolding.builder()
                        .snapshot(snapshot)
                        .fundName(f.fundName())
                        .fundCode(f.fundCode())
                        .bank(bank)
                        .investmentAmount(f.investmentAmount())
                        .currentValue(f.currentValue())
                        .build();
                snapshot.getFunds().add(fund);
            });
        }

        // 股票
        if (req.stocks() != null) {
            req.stocks().forEach(st -> {
                BrokerEntity broker = st.brokerId() != null ? brokerRepo.findById(st.brokerId()).orElse(null) : null;
                StockHolding stock = StockHolding.builder()
                        .snapshot(snapshot)
                        .stockCode(st.stockCode())
                        .stockName(st.stockName())
                        .market(st.market())
                        .broker(broker)
                        .shares(st.shares())
                        .investmentCost(st.investmentCost())
                        .currentValue(st.currentValue())
                        .estimatedDividend(st.estimatedDividend())
                        .dividendRate(st.dividendRate())
                        .currency(st.currency() != null ? st.currency() : "TWD")
                        .originalCurrencyValue(st.originalCurrencyValue())
                        .transactionType(st.transactionType())
                        .transactionDate(st.transactionDate())
                        .transactionExchangeRate(st.transactionExchangeRate())
                        .build();
                snapshot.getStocks().add(stock);
            });
        }

        recalcTotals(snapshot);
        AssetSnapshot saved = snapshotRepo.save(snapshot);
        return toSummaryResponse(saved);
    }

    @Transactional
    public AssetSnapshotDto.SnapshotSummaryResponse updateSnapshot(Long id, AssetSnapshotDto.CreateSnapshotRequest req) {
        AssetSnapshot snapshot = findSnapshot(id);
        if (req.snapshotDate() != null && !req.snapshotDate().equals(snapshot.getSnapshotDate())) {
            if (snapshotRepo.existsBySnapshotDate(req.snapshotDate())) {
                throw new IllegalArgumentException("該日期的快照已存在: " + req.snapshotDate());
            }
            snapshot.setSnapshotDate(req.snapshotDate());
        }
        snapshot.setUsdExchangeRate(req.usdExchangeRate());
        snapshot.setNotes(req.notes());

        snapshot.getDeposits().clear();
        snapshot.getFunds().clear();
        snapshot.getStocks().clear();

        if (req.deposits() != null) {
            req.deposits().forEach(d -> {
                Bank bank = d.bankId() != null ? bankRepo.findById(d.bankId()).orElse(null) : null;
                snapshot.getDeposits().add(BankDeposit.builder()
                    .snapshot(snapshot).bank(bank).depositType(d.depositType())
                    .amount(d.amount()).originalAmount(d.originalAmount())
                    .currency(d.currency() != null ? d.currency() : "TWD").notes(d.notes()).build());
            });
        }
        if (req.funds() != null) {
            req.funds().forEach(f -> {
                Bank bank = f.bankId() != null ? bankRepo.findById(f.bankId()).orElse(null) : null;
                snapshot.getFunds().add(FundHolding.builder()
                    .snapshot(snapshot).fundName(f.fundName()).fundCode(f.fundCode())
                    .bank(bank).investmentAmount(f.investmentAmount()).currentValue(f.currentValue()).build());
            });
        }
        if (req.stocks() != null) {
            req.stocks().forEach(st -> {
                BrokerEntity broker = st.brokerId() != null ? brokerRepo.findById(st.brokerId()).orElse(null) : null;
                snapshot.getStocks().add(StockHolding.builder()
                    .snapshot(snapshot).stockCode(st.stockCode()).stockName(st.stockName())
                    .market(st.market()).broker(broker).shares(st.shares())
                    .investmentCost(st.investmentCost()).currentValue(st.currentValue())
                    .estimatedDividend(st.estimatedDividend()).dividendRate(st.dividendRate())
                    .currency(st.currency() != null ? st.currency() : "TWD")
                    .originalCurrencyValue(st.originalCurrencyValue())
                    .transactionType(st.transactionType())
                    .transactionDate(st.transactionDate())
                    .transactionExchangeRate(st.transactionExchangeRate())
                    .build());
            });
        }

        recalcTotals(snapshot);
        return toSummaryResponse(snapshotRepo.save(snapshot));
    }

    @Transactional
    public void deleteSnapshot(Long id) {
        snapshotRepo.deleteById(id);
    }

    // ===================== Asset History =====================

    @Transactional(readOnly = true)
    public List<AssetSnapshotDto.AssetHistoryResponse> getAssetHistory() {
        List<AssetSnapshot> snapshots = snapshotRepo.findAllByOrderBySnapshotDateAsc();
        BigDecimal prevTotal = null;
        var result = new java.util.ArrayList<AssetSnapshotDto.AssetHistoryResponse>();

        // 預先加總每個年度的已實現損益（台幣等值）
        java.util.Map<Integer, BigDecimal> realizedByYear = new java.util.HashMap<>();
        for (Integer year : gainRepo.findDistinctYears()) {
            BigDecimal total = gainRepo.findByYearOrderByTradeDateAsc(year).stream()
                .map(g -> {
                    if (g.getProfit() == null) return BigDecimal.ZERO;
                    // USD 計價：profit × exchangeRate 換算台幣
                    if ("USD".equals(g.getCurrency())) {
                        BigDecimal rate = g.getExchangeRate();
                        if (rate == null) rate = lookupExchangeRate(g.getTradeDate());
                        if (rate != null) return g.getProfit().multiply(rate).setScale(0, RoundingMode.HALF_UP);
                    }
                    return g.getProfit();
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            realizedByYear.put(year, total);
        }

        for (AssetSnapshot s : snapshots) {
            BigDecimal total = s.getTotalAssets() != null ? s.getTotalAssets() : BigDecimal.ZERO;
            BigDecimal increase = prevTotal != null ? total.subtract(prevTotal) : null;
            BigDecimal increaseRate = (prevTotal != null && prevTotal.compareTo(BigDecimal.ZERO) != 0)
                    ? increase.divide(prevTotal, 6, RoundingMode.HALF_UP) : null;

            // 投資比例 = (信託基金現值 + 股票現值) / 資產總計
            BigDecimal investAmount = BigDecimal.ZERO;
            if (s.getTotalFundValue() != null) investAmount = investAmount.add(s.getTotalFundValue());
            if (s.getTotalStockValue() != null) investAmount = investAmount.add(s.getTotalStockValue());
            BigDecimal investRate = total.compareTo(BigDecimal.ZERO) != 0
                    ? investAmount.divide(total, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;

            // 計算台股/美股分項現值
            BigDecimal twStockValue = BigDecimal.ZERO;
            BigDecimal usStockValue = BigDecimal.ZERO;
            for (StockHolding st : s.getStocks()) {
                BigDecimal val = st.getCurrentValue() != null ? st.getCurrentValue() : BigDecimal.ZERO;
                if ("美股".equals(st.getMarket())) {
                    usStockValue = usStockValue.add(val);
                } else {
                    twStockValue = twStockValue.add(val);
                }
            }

            // 直接使用 DB 已存的 estimatedAnnualDividend（由 autoEnrichDividendRates 維護）
            BigDecimal estimatedDividend = s.getEstimatedAnnualDividend() != null
                    ? s.getEstimatedAnnualDividend() : BigDecimal.ZERO;

            BigDecimal realizedGain = realizedByYear.get(s.getSnapshotDate().getYear());

            result.add(new AssetSnapshotDto.AssetHistoryResponse(
                s.getId(), s.getSnapshotDate(), s.getTotalDeposit(), s.getTotalFundValue(),
                twStockValue, usStockValue,
                s.getTotalStockValue(), total, increase, increaseRate, investRate,
                estimatedDividend, realizedGain
            ));
            prevTotal = total;
        }
        return result;
    }

    // ===================== Realized Gains =====================

    @Transactional(readOnly = true)
    public List<RealizedGainDto.YearSummaryResponse> getRealizedGainsByYear() {
        List<Integer> years = gainRepo.findDistinctYears();
        return years.stream().map(year -> {
            List<RealizedGain> records = gainRepo.findByYearOrderByTradeDateAsc(year);

            List<RealizedGainDto.RealizedGainResponse> responses = records.stream()
                    .map(this::toGainResponse).toList();

            // 使用台幣金額加總
            BigDecimal totalProceedsTwd = responses.stream()
                    .map(RealizedGainDto.RealizedGainResponse::proceedsTwd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalProfitTwd = responses.stream()
                    .map(RealizedGainDto.RealizedGainResponse::profitTwd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCostTwd = totalProceedsTwd.subtract(totalProfitTwd);
            BigDecimal avgRate = totalCostTwd.compareTo(BigDecimal.ZERO) != 0
                    ? totalProfitTwd.divide(totalCostTwd, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;

            return new RealizedGainDto.YearSummaryResponse(year, totalProceedsTwd, totalCostTwd,
                    totalProfitTwd, avgRate, responses);
        }).toList();
    }

    @Transactional
    public RealizedGainDto.RealizedGainResponse createRealizedGain(RealizedGainDto.CreateRealizedGainRequest req) {
        BigDecimal profitRate = req.profitRate();
        if (profitRate == null && req.investmentCost().compareTo(BigDecimal.ZERO) != 0) {
            profitRate = req.profit().divide(req.investmentCost(), 6, RoundingMode.HALF_UP);
        }

        String currency = req.currency();
        if (currency == null || currency.isBlank()) {
            currency = "美股".equals(req.market()) ? "USD" : "TWD";
        }

        // USD 計價時自動查交易日匯率
        BigDecimal exchangeRate = null;
        if ("USD".equals(currency)) {
            exchangeRate = lookupExchangeRate(req.tradeDate());
        }

        RealizedGain gain = RealizedGain.builder()
                .assetName(req.assetName())
                .assetCode(req.assetCode())
                .market(req.market())
                .currency(currency)
                .broker(req.broker())
                .tradeDate(req.tradeDate())
                .shares(req.shares())
                .salePrice(req.salePrice())
                .proceeds(req.proceeds())
                .investmentCost(req.investmentCost())
                .profit(req.profit())
                .exchangeRate(exchangeRate)
                .profitRate(profitRate)
                .year(req.tradeDate().getYear())
                .build();

        return toGainResponse(gainRepo.save(gain));
    }

    @Transactional
    public RealizedGainDto.RealizedGainResponse updateRealizedGain(Long id, RealizedGainDto.CreateRealizedGainRequest req) {
        RealizedGain gain = gainRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("RealizedGain not found: " + id));

        BigDecimal profitRate = req.profitRate();
        if (profitRate == null && req.investmentCost().compareTo(BigDecimal.ZERO) != 0) {
            profitRate = req.profit().divide(req.investmentCost(), 6, RoundingMode.HALF_UP);
        }

        String currency = req.currency();
        if (currency == null || currency.isBlank()) {
            currency = "美股".equals(req.market()) ? "USD" : "TWD";
        }

        // USD 計價時自動查交易日匯率
        BigDecimal exchangeRate = null;
        if ("USD".equals(currency)) {
            exchangeRate = lookupExchangeRate(req.tradeDate());
        }

        gain.setAssetName(req.assetName());
        gain.setAssetCode(req.assetCode());
        gain.setMarket(req.market());
        gain.setCurrency(currency);
        gain.setBroker(req.broker());
        gain.setTradeDate(req.tradeDate());
        gain.setShares(req.shares());
        gain.setSalePrice(req.salePrice());
        gain.setProceeds(req.proceeds());
        gain.setInvestmentCost(req.investmentCost());
        gain.setProfit(req.profit());
        gain.setExchangeRate(exchangeRate);
        gain.setProfitRate(profitRate);
        gain.setYear(req.tradeDate().getYear());

        return toGainResponse(gainRepo.save(gain));
    }

    @Transactional
    public void deleteRealizedGain(Long id) {
        gainRepo.deleteById(id);
    }

    // ===================== Dividend Rate Enrich =====================

    /**
     * 對所有快照中缺少 dividendRate 的持股自動補齊，存回 DB。
     * 每個股票代碼只查一次 API，共享結果給所有快照。
     */
    @Transactional
    public void enrichAllSnapshotDividendRates() {
        List<AssetSnapshot> all = snapshotRepo.findAllByOrderBySnapshotDateAsc();
        // 先蒐集所有缺少配息率的 (code, market) 組合，避免重複呼叫 API
        java.util.Map<String, BigDecimal> rateCache = new java.util.HashMap<>();

        for (AssetSnapshot snapshot : all) {
            boolean updated = false;
            for (StockHolding st : snapshot.getStocks()) {
                if (st.getDividendRate() != null && st.getDividendRate().compareTo(BigDecimal.ZERO) > 0) continue;
                String key = st.getMarket() + "_" + st.getStockCode();
                BigDecimal rate = rateCache.computeIfAbsent(key, k -> {
                    try {
                        MarketDataService.DividendRateResult r =
                                marketDataService.getDividendRate(st.getStockCode(), st.getMarket());
                        return (r != null) ? r.dividendRate() : null;
                    } catch (Exception e) {
                        return null;
                    }
                });
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                    st.setDividendRate(rate);
                    if (st.getCurrentValue() != null)
                        st.setEstimatedDividend(st.getCurrentValue().multiply(rate).setScale(0, RoundingMode.HALF_UP));
                    updated = true;
                }
            }
            if (updated) {
                BigDecimal totalDiv = snapshot.getStocks().stream()
                        .filter(st -> st.getEstimatedDividend() != null)
                        .map(StockHolding::getEstimatedDividend)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                snapshot.setEstimatedAnnualDividend(totalDiv);
                snapshotRepo.save(snapshot);
                log.info("快照 {} {} 配息率補齊完成", snapshot.getId(), snapshot.getSnapshotDate());
            }
        }
    }

    // ===================== Recalc All Dividends =====================

    /**
     * 重新計算所有快照的預估配息：
     * 對每個持股，若 dividendRate > 0 且 currentValue > 0，重算 estimatedDividend，
     * 再加總至快照的 estimatedAnnualDividend。
     */
    @Transactional
    public int recalcAllDividends() {
        List<AssetSnapshot> all = snapshotRepo.findAllByOrderBySnapshotDateAsc();
        int updatedCount = 0;
        for (AssetSnapshot snapshot : all) {
            boolean changed = false;
            for (StockHolding st : snapshot.getStocks()) {
                if (st.getDividendRate() == null || st.getDividendRate().compareTo(BigDecimal.ZERO) <= 0) continue;
                if (st.getCurrentValue() == null || st.getCurrentValue().compareTo(BigDecimal.ZERO) <= 0) continue;
                BigDecimal newDiv = st.getCurrentValue().multiply(st.getDividendRate()).setScale(0, RoundingMode.HALF_UP);
                if (!newDiv.equals(st.getEstimatedDividend())) {
                    st.setEstimatedDividend(newDiv);
                    changed = true;
                }
            }
            // Always resum in case individual dividends were updated externally
            BigDecimal totalDiv = snapshot.getStocks().stream()
                    .filter(st -> st.getEstimatedDividend() != null)
                    .map(StockHolding::getEstimatedDividend)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (changed || !totalDiv.equals(snapshot.getEstimatedAnnualDividend())) {
                snapshot.setEstimatedAnnualDividend(totalDiv);
                snapshotRepo.save(snapshot);
                updatedCount++;
                log.info("重算配息完成: 快照 {} {} estimatedAnnualDividend={}", snapshot.getId(), snapshot.getSnapshotDate(), totalDiv);
            }
        }
        return updatedCount;
    }

    // ===================== Dividend Rate Patch =====================

    /**
     * 更新指定快照中各股票的自訂顯示順序。
     * 同一支股票可能有多筆 StockHolding（不同券商），統一套用相同 displayOrder。
     */
    @Transactional
    public void updateStockDisplayOrder(Long snapshotId,
                                        java.util.List<AssetSnapshotDto.StockOrderRequest> orders) {
        findSnapshot(snapshotId); // 確認快照存在
        for (AssetSnapshotDto.StockOrderRequest order : orders) {
            stockRepo.updateDisplayOrder(
                snapshotId, order.stockCode(), order.market(), order.displayOrder());
        }
    }

    /**
     * 將前端即時查詢到的配息率回寫到 DB，同時更新各持股 estimatedDividend 及快照合計。
     * rates: stockCode → dividendRate (e.g. "0050" → 0.047492)
     */
    @Transactional
    public void updateSnapshotDividendRates(Long snapshotId, java.util.Map<String, BigDecimal> rates) {
        if (rates == null || rates.isEmpty()) return;
        AssetSnapshot snapshot = findSnapshot(snapshotId);

        for (StockHolding st : snapshot.getStocks()) {
            BigDecimal rate = rates.get(st.getStockCode());
            if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) continue;
            st.setDividendRate(rate.setScale(6, RoundingMode.HALF_UP));
            if (st.getCurrentValue() != null && st.getCurrentValue().compareTo(BigDecimal.ZERO) > 0) {
                st.setEstimatedDividend(
                    st.getCurrentValue().multiply(rate).setScale(0, RoundingMode.HALF_UP));
            }
        }

        // 重算快照層級的 estimatedAnnualDividend
        BigDecimal totalDividend = snapshot.getStocks().stream()
                .filter(st -> st.getEstimatedDividend() != null)
                .map(StockHolding::getEstimatedDividend)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        snapshot.setEstimatedAnnualDividend(totalDividend);

        snapshotRepo.save(snapshot);
    }

    // ===================== Private Helpers =====================

    private AssetSnapshot findSnapshot(Long id) {
        return snapshotRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("找不到快照 ID: " + id));
    }

    private void recalcTotals(AssetSnapshot s) {
        BigDecimal totalDeposit = s.getDeposits().stream()
                .map(BankDeposit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFundValue = s.getFunds().stream()
                .map(FundHolding::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFundCost = s.getFunds().stream()
                .map(FundHolding::getInvestmentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalStockValue = s.getStocks().stream()
                .map(StockHolding::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalStockCost = s.getStocks().stream()
                .map(StockHolding::getInvestmentCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDividend = s.getStocks().stream()
                .filter(st -> st.getEstimatedDividend() != null)
                .map(StockHolding::getEstimatedDividend)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        s.setTotalDeposit(totalDeposit);
        s.setTotalFundValue(totalFundValue);
        s.setTotalFundCost(totalFundCost);
        s.setTotalStockValue(totalStockValue);
        s.setTotalStockCost(totalStockCost);
        s.setTotalAssets(totalDeposit.add(totalFundValue).add(totalStockValue));
        s.setEstimatedAnnualDividend(totalDividend);
    }

    private AssetSnapshotDto.SnapshotSummaryResponse toSummaryResponse(AssetSnapshot s) {
        BigDecimal fundProfit = s.getTotalFundValue() != null && s.getTotalFundCost() != null
                ? s.getTotalFundValue().subtract(s.getTotalFundCost()) : BigDecimal.ZERO;
        BigDecimal stockProfit = s.getTotalStockValue() != null && s.getTotalStockCost() != null
                ? s.getTotalStockValue().subtract(s.getTotalStockCost()) : BigDecimal.ZERO;

        return new AssetSnapshotDto.SnapshotSummaryResponse(
            s.getId(), s.getSnapshotDate(), s.getUsdExchangeRate(),
            s.getTotalDeposit(), s.getTotalFundValue(), s.getTotalFundCost(),
            s.getTotalStockValue(), s.getTotalStockCost(), s.getTotalAssets(),
            s.getEstimatedAnnualDividend(), s.getRealizedGain(),
            fundProfit, stockProfit, s.getNotes()
        );
    }

    private AssetSnapshotDto.SnapshotDetailResponse toDetailResponse(AssetSnapshot s) {
        List<AssetSnapshotDto.DepositResponse> deposits = s.getDeposits().stream()
                .map(d -> new AssetSnapshotDto.DepositResponse(
                    d.getId(),
                    d.getBank() != null ? d.getBank().getId() : null,
                    d.getBank() != null ? d.getBank().getDisplayName() : null,
                    d.getDepositType(), d.getDepositType(),
                    d.getAmount(), d.getOriginalAmount(), d.getCurrency(), d.getNotes()
                )).toList();

        List<AssetSnapshotDto.FundResponse> funds = s.getFunds().stream()
                .map(f -> new AssetSnapshotDto.FundResponse(
                    f.getId(), f.getFundName(), f.getFundCode(),
                    f.getBank() != null ? f.getBank().getId() : null,
                    f.getBank() != null ? f.getBank().getDisplayName() : null,
                    f.getInvestmentAmount(), f.getCurrentValue(), f.getProfit(), f.getProfitRate()
                )).toList();

        // 快照匯率作為 fallback
        BigDecimal snapshotUsdRate = s.getUsdExchangeRate() != null
                ? s.getUsdExchangeRate() : BigDecimal.ONE;

        List<AssetSnapshotDto.StockResponse> stocks = s.getStocks().stream()
                .sorted(java.util.Comparator.comparing(
                    st -> st.getDisplayOrder() != null ? st.getDisplayOrder() : Integer.MAX_VALUE))
                .map(st -> {
                    // investmentCostTwd：USD 幣別須乘有效匯率換算台幣，供匯總顯示用
                    BigDecimal rawCost = st.getInvestmentCost() != null ? st.getInvestmentCost() : BigDecimal.ZERO;
                    BigDecimal investmentCostTwd;
                    if ("USD".equals(st.getCurrency())) {
                        BigDecimal rate = st.getTransactionExchangeRate() != null
                                ? st.getTransactionExchangeRate() : snapshotUsdRate;
                        investmentCostTwd = rawCost.multiply(rate).setScale(0, RoundingMode.HALF_UP);
                    } else {
                        investmentCostTwd = rawCost.setScale(0, RoundingMode.HALF_UP);
                    }
                    return new AssetSnapshotDto.StockResponse(
                        st.getId(), st.getStockCode(), st.getStockName(), st.getMarket(),
                        st.getBroker() != null ? st.getBroker().getId() : null,
                        st.getBroker() != null ? st.getBroker().getDisplayName() : null,
                        st.getShares(), rawCost, investmentCostTwd, st.getCurrentValue(),
                        st.getProfit(), st.getProfitRate(),
                        st.getEstimatedDividend(), st.getDividendRate(),
                        st.getCurrency(), st.getOriginalCurrencyValue(),
                        st.getTransactionType(), st.getTransactionDate(), st.getTransactionExchangeRate(),
                        st.getDisplayOrder()
                    );
                }).toList();

        return new AssetSnapshotDto.SnapshotDetailResponse(
            s.getId(), s.getSnapshotDate(), s.getUsdExchangeRate(),
            s.getTotalDeposit(), s.getTotalFundValue(), s.getTotalFundCost(),
            s.getTotalStockValue(), s.getTotalStockCost(), s.getTotalAssets(),
            s.getEstimatedAnnualDividend(), s.getRealizedGain(), s.getNotes(),
            deposits, funds, stocks
        );
    }

    /**
     * 查詢交易日的 USD 匯率 (midRate)
     * 找交易日或之前最近的匯率紀錄
     */
    private BigDecimal lookupExchangeRate(java.time.LocalDate tradeDate) {
        return rateHistRepo.findClosestRate("USD", tradeDate)
                .map(ExchangeRateHistory::getMidRate)
                .orElse(null);
    }

    /** 將所有 currency=USD 的已實現損益改為 TWD（Excel 匯入欄位均為台幣）*/
    @Transactional
    public int fixRealizedGainCurrencyToTwd() {
        List<RealizedGain> usdRecords = gainRepo.findAll().stream()
                .filter(g -> "USD".equals(g.getCurrency()))
                .toList();
        usdRecords.forEach(g -> {
            g.setCurrency("TWD");
            g.setExchangeRate(null);
        });
        gainRepo.saveAll(usdRecords);
        return usdRecords.size();
    }

    private RealizedGainDto.RealizedGainResponse toGainResponse(RealizedGain g) {
        String currency = g.getCurrency() != null ? g.getCurrency() : ("美股".equals(g.getMarket()) ? "USD" : "TWD");
        BigDecimal rate = g.getExchangeRate();

        // 若 USD 計價但沒有匯率，嘗試查詢
        if ("USD".equals(currency) && rate == null) {
            rate = lookupExchangeRate(g.getTradeDate());
        }

        BigDecimal proceedsTwd, investmentCostTwd, profitTwd;
        if ("USD".equals(currency) && rate != null) {
            proceedsTwd = g.getProceeds().multiply(rate).setScale(0, RoundingMode.HALF_UP);
            investmentCostTwd = g.getInvestmentCost().multiply(rate).setScale(0, RoundingMode.HALF_UP);
            profitTwd = g.getProfit().multiply(rate).setScale(0, RoundingMode.HALF_UP);
        } else {
            // TWD 計價或無匯率：原值即台幣
            proceedsTwd = g.getProceeds().setScale(0, RoundingMode.HALF_UP);
            investmentCostTwd = g.getInvestmentCost().setScale(0, RoundingMode.HALF_UP);
            profitTwd = g.getProfit().setScale(0, RoundingMode.HALF_UP);
        }

        return new RealizedGainDto.RealizedGainResponse(
            g.getId(), g.getAssetName(), g.getAssetCode(), g.getMarket(),
            currency, g.getBroker(), rate,
            g.getTradeDate(), g.getShares(), g.getSalePrice(),
            g.getProceeds(), g.getInvestmentCost(), g.getProfit(), g.getProfitRate(),
            proceedsTwd, investmentCostTwd, profitTwd,
            g.getYear()
        );
    }
}
