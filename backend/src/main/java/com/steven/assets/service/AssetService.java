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
    private final StockRepository stockMasterRepo;
    private final StockMasterService stockMasterService;
    private final TransitFundTypeRepository transitFundTypeRepo;
    private final FundNavService fundNavService;
    private final FundDividendService fundDividendService;
    private final AssetClassifier assetClassifier;
    private final StockStyleRepository stockStyleRepo;
    private final FundClassOverrideRepository fundClassOverrideRepo;
    private final com.steven.assets.security.TenantGuard tenantGuard;

    /**
     * 算 FundHolding currentValue：若 units 非空 → 嘗試 NAV(basedate) × FX(basedate) 自動算
     * (Requirement 19, 21)，失敗（NAV / FX 缺）就 fallback 用使用者手填值。
     */
    private BigDecimal resolveFundCurrentValue(String fundCode, BigDecimal units,
                                               BigDecimal manualCurrentValue, java.time.LocalDate basedate) {
        if (units == null || fundCode == null || fundCode.isBlank()) return manualCurrentValue;
        return fundNavService.computeCurrentValueTwdOnDate(fundCode, units, basedate).orElse(manualCurrentValue);
    }

    /**
     * 算 FundHolding estimatedDividend (Requirement 20, 21)：units 非空 + 配息歷史齊全才覆寫，
     * 否則保留前端送進來的值。basedate 用於決定「12 個月區間」與「FX 取值日」。
     */
    private BigDecimal resolveFundEstimatedDividend(String fundCode, BigDecimal units,
                                                    BigDecimal manualValue, java.time.LocalDate basedate) {
        if (units == null || fundCode == null || fundCode.isBlank()) return manualValue;
        return fundDividendService.computeAnnualDividendTwdOnDate(fundCode, units, basedate).orElse(manualValue);
    }

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
                    log.info("自動補齊配息率 {} {}: {}%", st.getStockCode(), st.getMarket(),
                            r.dividendRate().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
                }
            } catch (Exception e) {
                log.warn("查詢配息率失敗 {} {}: {}", st.getStockCode(), st.getMarket(), e.getMessage());
            }
        }
        if (updated) {
            // 重算快照合計（stocks + funds + deposit interest）
            BigDecimal totalStockDiv = snapshot.getStocks().stream()
                    .filter(st -> st.getEstimatedDividend() != null)
                    .map(StockHolding::getEstimatedDividend)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalFundDiv = snapshot.getFunds().stream()
                    .filter(fh -> fh.getEstimatedDividend() != null)
                    .map(FundHolding::getEstimatedDividend)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            snapshot.setEstimatedAnnualDividend(
                    totalStockDiv.add(totalFundDiv).add(sumDepositInterest(snapshot)));
            snapshotRepo.save(snapshot);
        }
    }

    @Transactional
    public AssetSnapshotDto.SnapshotSummaryResponse createSnapshot(AssetSnapshotDto.CreateSnapshotRequest req) {
        if (snapshotRepo.existsBySnapshotDate(req.snapshotDate())) {
            throw new IllegalArgumentException("該日期的快照已存在: " + req.snapshotDate());
        }

        AssetSnapshot snapshot = AssetSnapshot.builder()
                .ownerUserId(tenantGuard.requireCurrentUserId())
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
                        .amount(normalizeDepositAmount(d, req.usdExchangeRate()))
                        .originalAmount(d.originalAmount())
                        .currency(d.currency() != null ? d.currency() : "TWD")
                        .annualInterestRate(sanitizeInterestRate(d))
                        .notes(d.notes())
                        .build();
                snapshot.getDeposits().add(deposit);
            });
        }

        // 基金
        if (req.funds() != null) {
            req.funds().forEach(f -> {
                Bank bank = f.bankId() != null ? bankRepo.findById(f.bankId()).orElse(null) : null;
                BigDecimal cv = resolveFundCurrentValue(f.fundCode(), f.units(), f.currentValue(), req.snapshotDate());
                BigDecimal divEst = resolveFundEstimatedDividend(f.fundCode(), f.units(), f.estimatedDividend(), req.snapshotDate());
                FundHolding fund = FundHolding.builder()
                        .snapshot(snapshot)
                        .fundName(f.fundName())
                        .fundCode(f.fundCode())
                        .bank(bank)
                        .investmentAmount(f.investmentAmount())
                        .currentValue(cv)
                        .units(f.units())
                        .estimatedDividend(divEst)
                        .build();
                snapshot.getFunds().add(fund);
            });
        }

        // 股票
        if (req.stocks() != null) {
            java.util.Map<String, Integer> displayOrderMap = assignDisplayOrder(req.stocks());
            req.stocks().forEach(st -> {
                BrokerEntity broker = st.brokerId() != null ? brokerRepo.findById(st.brokerId()).orElse(null) : null;
                StockHolding stock = StockHolding.builder()
                        .snapshot(snapshot)
                        .stockCode(st.stockCode())
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
                        .displayOrder(displayOrderMap.get(st.market() + "_" + st.stockCode()))
                        .build();
                snapshot.getStocks().add(stock);
                // 同步到 stock 主檔（名稱不得與代號相同，否則視為無效資料）
                if (st.stockCode() != null && st.stockName() != null
                        && !st.stockName().isBlank()
                        && !st.stockName().equalsIgnoreCase(st.stockCode())) {
                    stockMasterService.upsert(st.stockCode(), st.market(), st.stockName());
                }
            });
        }

        recalcTotals(snapshot);
        AssetSnapshot saved = snapshotRepo.save(snapshot);
        return toSummaryResponse(saved);
    }

    /**
     * 把存款的台幣值（amount）由後端統一決定，不信任前端的計算結果。
     *
     * 規則：
     *  - TWD：amount = abs(前端送的值)
     *  - TRANSIT_TWD：amount = abs；若 deposit_type 為 payable 則 negate
     *  - USD：amount = originalAmount × snapshotRate（無匯率時 fallback 前端 amount）
     *  - TRANSIT_USD：amount = originalAmount × snapshotRate；若 payable 則 negate
     */
    private BigDecimal normalizeDepositAmount(AssetSnapshotDto.DepositRequest d, BigDecimal snapshotRate) {
        String currency = d.currency() != null ? d.currency() : "TWD";
        boolean isUsd = "USD".equals(currency) || "TRANSIT_USD".equals(currency);
        boolean isTransit = "TRANSIT_TWD".equals(currency) || "TRANSIT_USD".equals(currency);

        BigDecimal twd;
        if (isUsd && d.originalAmount() != null
                && snapshotRate != null && snapshotRate.compareTo(BigDecimal.ZERO) > 0) {
            twd = d.originalAmount().abs().multiply(snapshotRate)
                    .setScale(0, RoundingMode.HALF_UP);
        } else {
            twd = d.amount() != null ? d.amount().abs() : BigDecimal.ZERO;
        }
        if (isTransit && isTransitPayable(d.depositType())) {
            twd = twd.negate();
        }
        return twd;
    }

    /** TRANSIT_* 不適用年利率欄位；其餘原樣回傳 */
    private BigDecimal sanitizeInterestRate(AssetSnapshotDto.DepositRequest d) {
        String currency = d.currency() != null ? d.currency() : "TWD";
        if ("TRANSIT_TWD".equals(currency) || "TRANSIT_USD".equals(currency)) return null;
        return d.annualInterestRate();
    }

    /**
     * 該筆存款的預估年利息（TWD）。`amount` 已是台幣等值，無論幣別都得到 TWD。
     * rate null / 非正 → 0；TRANSIT_* 即使誤帶 rate 也不計入。
     */
    private BigDecimal depositEstimatedInterest(BankDeposit d) {
        BigDecimal rate = d.getAnnualInterestRate();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        String cur = d.getCurrency();
        if ("TRANSIT_TWD".equals(cur) || "TRANSIT_USD".equals(cur)) return BigDecimal.ZERO;
        BigDecimal amt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
        return amt.multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    /** 快照存款預估年利息合計 */
    private BigDecimal sumDepositInterest(AssetSnapshot snapshot) {
        return snapshot.getDeposits().stream()
                .map(this::depositEstimatedInterest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isTransitPayable(String depositType) {
        if (depositType == null) return false;
        return transitFundTypeRepo.findByCode(depositType)
                .map(TransitFundType::getPayable)
                .orElse(false);
    }

    /**
     * 依 req.stocks() 的提交順序，為每個 (market, stockCode) 分配 displayOrder（每個市場獨立編號）。
     * 多筆 StockHolding 屬於同一支股票（不同券商）時套用相同 displayOrder。
     */
    private java.util.Map<String, Integer> assignDisplayOrder(List<AssetSnapshotDto.StockRequest> stocks) {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> perMarketNextIdx = new java.util.HashMap<>();
        for (AssetSnapshotDto.StockRequest st : stocks) {
            String key = st.market() + "_" + st.stockCode();
            if (result.containsKey(key)) continue;
            int idx = perMarketNextIdx.getOrDefault(st.market(), 0);
            result.put(key, idx);
            perMarketNextIdx.put(st.market(), idx + 1);
        }
        return result;
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
                    .amount(normalizeDepositAmount(d, req.usdExchangeRate()))
                    .originalAmount(d.originalAmount())
                    .currency(d.currency() != null ? d.currency() : "TWD")
                    .annualInterestRate(sanitizeInterestRate(d))
                    .notes(d.notes()).build());
            });
        }
        if (req.funds() != null) {
            req.funds().forEach(f -> {
                Bank bank = f.bankId() != null ? bankRepo.findById(f.bankId()).orElse(null) : null;
                BigDecimal cv = resolveFundCurrentValue(f.fundCode(), f.units(), f.currentValue(), req.snapshotDate());
                BigDecimal divEst = resolveFundEstimatedDividend(f.fundCode(), f.units(), f.estimatedDividend(), req.snapshotDate());
                snapshot.getFunds().add(FundHolding.builder()
                    .snapshot(snapshot).fundName(f.fundName()).fundCode(f.fundCode())
                    .bank(bank).investmentAmount(f.investmentAmount()).currentValue(cv)
                    .units(f.units()).estimatedDividend(divEst).build());
            });
        }
        if (req.stocks() != null) {
            java.util.Map<String, Integer> displayOrderMap = assignDisplayOrder(req.stocks());
            req.stocks().forEach(st -> {
                BrokerEntity broker = st.brokerId() != null ? brokerRepo.findById(st.brokerId()).orElse(null) : null;
                snapshot.getStocks().add(StockHolding.builder()
                    .snapshot(snapshot).stockCode(st.stockCode())
                    .market(st.market()).broker(broker).shares(st.shares())
                    .investmentCost(st.investmentCost()).currentValue(st.currentValue())
                    .estimatedDividend(st.estimatedDividend()).dividendRate(st.dividendRate())
                    .currency(st.currency() != null ? st.currency() : "TWD")
                    .originalCurrencyValue(st.originalCurrencyValue())
                    .transactionType(st.transactionType())
                    .transactionDate(st.transactionDate())
                    .transactionExchangeRate(st.transactionExchangeRate())
                    .displayOrder(displayOrderMap.get(st.market() + "_" + st.stockCode()))
                    .build());
                if (st.stockCode() != null && st.stockName() != null
                        && !st.stockName().isBlank()
                        && !st.stockName().equalsIgnoreCase(st.stockCode())) {
                    stockMasterService.upsert(st.stockCode(), st.market(), st.stockName());
                }
            });
        }

        recalcTotals(snapshot);
        return toSummaryResponse(snapshotRepo.save(snapshot));
    }

    @Transactional
    public void deleteSnapshot(Long id) {
        snapshotRepo.delete(findSnapshot(id)); // findSnapshot 已驗證歸屬
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
                    BigDecimal profit = g.getProceeds().subtract(g.getInvestmentCost());
                    // USD 計價：profit × exchangeRate 換算台幣
                    if ("USD".equals(g.getCurrency())) {
                        BigDecimal rate = g.getExchangeRate();
                        if (rate == null) rate = lookupExchangeRate(g.getTradeDate());
                        if (rate != null) return profit.multiply(rate).setScale(0, RoundingMode.HALF_UP);
                    }
                    return profit;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            realizedByYear.put(year, total);
        }

        // Requirement 25/26/27：一次載入 stock 主檔的 override 與名稱，建 Map<market|code, ...>
        java.util.Map<String, String> stockClassOverride = new java.util.HashMap<>();
        java.util.Map<String, String> stockStyleOverride = new java.util.HashMap<>();
        java.util.Map<String, String> bondTermOverride = new java.util.HashMap<>();
        java.util.Map<String, String> stockNameMap = new java.util.HashMap<>();
        for (Stock sm : stockMasterRepo.findAll()) {
            String key = sm.getMarket() + "|" + sm.getCode();
            stockNameMap.put(key, sm.getName());
            if (sm.getAssetClass() != null && !sm.getAssetClass().isBlank()) {
                stockClassOverride.put(key, sm.getAssetClass());
            }
            if (sm.getStockStyle() != null && !sm.getStockStyle().isBlank()) {
                stockStyleOverride.put(key, sm.getStockStyle());
            }
            if (sm.getBondTerm() != null && !sm.getBondTerm().isBlank()) {
                bondTermOverride.put(key, sm.getBondTerm());
            }
        }
        // Requirement 25/26/27：一次載入基金分類 override（以名稱為 key），建 Map<fundName, FundClassOverride>
        java.util.Map<String, FundClassOverride> fundOverride = new java.util.HashMap<>();
        for (FundClassOverride fo : fundClassOverrideRepo.findAll()) {
            fundOverride.put(fo.getFundName(), fo);
        }
        // Requirement 26：收益型殖利率門檻（stock_style INCOME 列，可設定；缺則 classifier 內 fallback 4%）
        BigDecimal incomeThreshold = stockStyleRepo.findByCode(AssetClassifier.INCOME)
                .map(StockStyle::getDividendThreshold).orElse(null);

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

            // 計算台股/美股/英股分項現值
            BigDecimal twStockValue = BigDecimal.ZERO;
            BigDecimal usStockValue = BigDecimal.ZERO;
            BigDecimal ukStockValue = BigDecimal.ZERO;
            // Requirement 25：同一迴圈順便按「現金/債券/股票」分類加總
            BigDecimal bondValue = BigDecimal.ZERO;
            BigDecimal stockEquityValue = BigDecimal.ZERO;
            // Requirement 26：股票再依「成長/收益」風格細分（growthValue + incomeValue == stockEquityValue）
            BigDecimal growthValue = BigDecimal.ZERO;
            BigDecimal incomeValue = BigDecimal.ZERO;
            // Requirement 27：債券再依「短/中/長期」細分（bondShort + bondMid + bondLong == bondValue）
            BigDecimal bondShortValue = BigDecimal.ZERO;
            BigDecimal bondMidValue = BigDecimal.ZERO;
            BigDecimal bondLongValue = BigDecimal.ZERO;
            for (StockHolding st : s.getStocks()) {
                BigDecimal val = st.getCurrentValue() != null ? st.getCurrentValue() : BigDecimal.ZERO;
                if ("美股".equals(st.getMarket())) usStockValue = usStockValue.add(val);
                else if ("英股".equals(st.getMarket())) ukStockValue = ukStockValue.add(val);
                else twStockValue = twStockValue.add(val);

                String key = st.getMarket() + "|" + st.getStockCode();
                if (AssetClassifier.BOND.equals(
                        assetClassifier.classifyStock(st.getStockCode(), st.getMarket(), stockClassOverride.get(key)))) {
                    bondValue = bondValue.add(val);
                    // 僅債券類再分期別：依名稱年期 + override
                    String term = assetClassifier.classifyBondTerm(
                            st.getStockCode(), st.getMarket(), stockNameMap.get(key), bondTermOverride.get(key));
                    if (AssetClassifier.SHORT.equals(term)) bondShortValue = bondShortValue.add(val);
                    else if (AssetClassifier.LONG.equals(term)) bondLongValue = bondLongValue.add(val);
                    else bondMidValue = bondMidValue.add(val);
                } else {
                    stockEquityValue = stockEquityValue.add(val);
                    // 僅股票類再分風格：用該快照該持股殖利率 + override + 門檻
                    String style = assetClassifier.classifyStockStyle(
                            st.getStockCode(), st.getMarket(), stockStyleOverride.get(key),
                            st.getDividendRate(), incomeThreshold);
                    if (AssetClassifier.INCOME.equals(style)) incomeValue = incomeValue.add(val);
                    else growthValue = growthValue.add(val);
                }
            }
            // 基金：override 優先（asset_class/stock_style/bond_term 皆可逐檔指定，key=名稱），否則依規則。
            // 基金無 dividendRate，故 STOCK 風格自動成長型、override 可改收益型；BOND 期別自動依名稱、override 可改。
            for (FundHolding fh : s.getFunds()) {
                BigDecimal val = fh.getCurrentValue() != null ? fh.getCurrentValue() : BigDecimal.ZERO;
                String fname = fh.getFundName();
                FundClassOverride ov = fundOverride.get(fname);
                if (AssetClassifier.BOND.equals(
                        assetClassifier.classifyFund(fname, ov != null ? ov.getAssetClass() : null))) {
                    bondValue = bondValue.add(val);
                    String term = assetClassifier.classifyBondTerm(
                            null, null, fname, ov != null ? ov.getBondTerm() : null);
                    if (AssetClassifier.SHORT.equals(term)) bondShortValue = bondShortValue.add(val);
                    else if (AssetClassifier.LONG.equals(term)) bondLongValue = bondLongValue.add(val);
                    else bondMidValue = bondMidValue.add(val);
                } else {
                    stockEquityValue = stockEquityValue.add(val);
                    String style = assetClassifier.classifyStockStyle(
                            null, null, ov != null ? ov.getStockStyle() : null, null, incomeThreshold);
                    if (AssetClassifier.INCOME.equals(style)) incomeValue = incomeValue.add(val);
                    else growthValue = growthValue.add(val);
                }
            }

            // 計算台幣 / 美元存款分項（與前端 bankSummary 同邏輯：TRANSIT_TWD/TRANSIT_USD 各歸對應幣別，
            // amount 已是台幣等值，可直接相加）
            BigDecimal twdDeposit = BigDecimal.ZERO;
            BigDecimal usdDeposit = BigDecimal.ZERO;
            for (var d : s.getDeposits()) {
                BigDecimal amt = d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO;
                String cur = d.getCurrency();
                if ("USD".equals(cur) || "TRANSIT_USD".equals(cur)) {
                    usdDeposit = usdDeposit.add(amt);
                } else {
                    twdDeposit = twdDeposit.add(amt);
                }
            }

            // Requirement 25：現金 = 台幣存款 + 美元存款（口徑同上方分項，含 TRANSIT_*）
            BigDecimal cashValue = twdDeposit.add(usdDeposit);

            // 直接使用 DB 已存的 estimatedAnnualDividend（由 autoEnrichDividendRates 維護）
            BigDecimal estimatedDividend = s.getEstimatedAnnualDividend() != null
                    ? s.getEstimatedAnnualDividend() : BigDecimal.ZERO;

            BigDecimal realizedGain = realizedByYear.get(s.getSnapshotDate().getYear());

            result.add(new AssetSnapshotDto.AssetHistoryResponse(
                s.getId(), s.getSnapshotDate(), s.getTotalDeposit(),
                twdDeposit, usdDeposit,
                s.getTotalFundValue(),
                twStockValue, usStockValue, ukStockValue,
                s.getTotalStockValue(), total, increase, increaseRate, investRate,
                estimatedDividend, realizedGain,
                cashValue, bondValue, stockEquityValue,
                growthValue, incomeValue,
                bondShortValue, bondMidValue, bondLongValue
            ));
            prevTotal = total;
        }
        return result;
    }

    /**
     * 單一快照逐持股分類（Requirement 25/26/27）：供圓餅圖外圈 hover 列出該分類底下的個別持股與金額。
     * 與 getAssetHistory 同一套 classifier / override / 門檻，確保桶的歸屬與圓餅圖加總一致。
     */
    @Transactional(readOnly = true)
    public List<AssetSnapshotDto.HoldingClassifiedResponse> getHoldingsClassified(Long snapshotId) {
        AssetSnapshot s = snapshotRepo.findById(snapshotId)
                .orElseThrow(() -> new NoSuchElementException("找不到快照 ID: " + snapshotId));
        tenantGuard.assertOwned(s.getOwnerUserId());

        java.util.Map<String, String> classOv = new java.util.HashMap<>();
        java.util.Map<String, String> styleOv = new java.util.HashMap<>();
        java.util.Map<String, String> termOv = new java.util.HashMap<>();
        java.util.Map<String, String> nameMap = new java.util.HashMap<>();
        for (Stock sm : stockMasterRepo.findAll()) {
            String key = sm.getMarket() + "|" + sm.getCode();
            nameMap.put(key, sm.getName());
            if (sm.getAssetClass() != null && !sm.getAssetClass().isBlank()) classOv.put(key, sm.getAssetClass());
            if (sm.getStockStyle() != null && !sm.getStockStyle().isBlank()) styleOv.put(key, sm.getStockStyle());
            if (sm.getBondTerm() != null && !sm.getBondTerm().isBlank()) termOv.put(key, sm.getBondTerm());
        }
        // Requirement 25/26/27：基金分類 override（以名稱為 key，Map<fundName, FundClassOverride>）
        java.util.Map<String, FundClassOverride> fundOv = new java.util.HashMap<>();
        for (FundClassOverride fo : fundClassOverrideRepo.findAll()) {
            fundOv.put(fo.getFundName(), fo);
        }
        BigDecimal incomeThreshold = stockStyleRepo.findByCode(AssetClassifier.INCOME)
                .map(StockStyle::getDividendThreshold).orElse(null);

        var result = new java.util.ArrayList<AssetSnapshotDto.HoldingClassifiedResponse>();
        for (StockHolding st : s.getStocks()) {
            String key = st.getMarket() + "|" + st.getStockCode();
            String cls = assetClassifier.classifyStock(st.getStockCode(), st.getMarket(), classOv.get(key));
            String style = null, term = null;
            if (AssetClassifier.BOND.equals(cls)) {
                term = assetClassifier.classifyBondTerm(st.getStockCode(), st.getMarket(), nameMap.get(key), termOv.get(key));
            } else {
                style = assetClassifier.classifyStockStyle(st.getStockCode(), st.getMarket(),
                        styleOv.get(key), st.getDividendRate(), incomeThreshold);
            }
            result.add(new AssetSnapshotDto.HoldingClassifiedResponse(
                    st.getStockCode(), nameMap.getOrDefault(key, st.getStockCode()), st.getMarket(),
                    st.getCurrentValue(), cls, style, term));
        }
        for (FundHolding fh : s.getFunds()) {
            String fname = fh.getFundName();
            FundClassOverride ov = fundOv.get(fname);
            String cls = assetClassifier.classifyFund(fname, ov != null ? ov.getAssetClass() : null);
            String style = null, term = null;
            if (AssetClassifier.BOND.equals(cls)) {
                term = assetClassifier.classifyBondTerm(null, null, fname, ov != null ? ov.getBondTerm() : null);
            } else {
                // 基金無 dividendRate：override 或成長型（與 getAssetHistory 一致）
                style = assetClassifier.classifyStockStyle(
                        null, null, ov != null ? ov.getStockStyle() : null, null, incomeThreshold);
            }
            result.add(new AssetSnapshotDto.HoldingClassifiedResponse(
                    fh.getFundCode(), fname, "基金", fh.getCurrentValue(), cls, style, term));
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
        String currency = req.currency();
        if (currency == null || currency.isBlank()) {
            // 英股 UCITS ETF（CSPX.L 等）同樣 USD 計價，與美股共用 USD 匯率
            currency = ("美股".equals(req.market()) || "英股".equals(req.market())) ? "USD" : "TWD";
        }

        // USD 計價時自動查交易日匯率
        BigDecimal exchangeRate = null;
        if ("USD".equals(currency)) {
            exchangeRate = lookupExchangeRate(req.tradeDate());
        }

        RealizedGain gain = RealizedGain.builder()
                .ownerUserId(tenantGuard.requireCurrentUserId())
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
                .exchangeRate(exchangeRate)
                .build();

        return toGainResponse(gainRepo.save(gain));
    }

    @Transactional
    public RealizedGainDto.RealizedGainResponse updateRealizedGain(Long id, RealizedGainDto.CreateRealizedGainRequest req) {
        RealizedGain gain = gainRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("RealizedGain not found: " + id));
        tenantGuard.assertOwned(gain.getOwnerUserId());

        String currency = req.currency();
        if (currency == null || currency.isBlank()) {
            // 英股 UCITS ETF（CSPX.L 等）同樣 USD 計價，與美股共用 USD 匯率
            currency = ("美股".equals(req.market()) || "英股".equals(req.market())) ? "USD" : "TWD";
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
        gain.setExchangeRate(exchangeRate);

        return toGainResponse(gainRepo.save(gain));
    }

    @Transactional
    public void deleteRealizedGain(Long id) {
        RealizedGain gain = gainRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("RealizedGain not found: " + id));
        tenantGuard.assertOwned(gain.getOwnerUserId());
        gainRepo.delete(gain);
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
                BigDecimal totalStockDiv = snapshot.getStocks().stream()
                        .filter(st -> st.getEstimatedDividend() != null)
                        .map(StockHolding::getEstimatedDividend)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal totalFundDiv = snapshot.getFunds().stream()
                        .filter(fh -> fh.getEstimatedDividend() != null)
                        .map(FundHolding::getEstimatedDividend)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                snapshot.setEstimatedAnnualDividend(
                        totalStockDiv.add(totalFundDiv).add(sumDepositInterest(snapshot)));
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
            // 信託基金 (Requirement 20, 21)：對每筆 fund_holding 用基準日 NAV / 配息歷史重算 estimatedDividend
            // - 若有 units 直接用 units × annualPerUnitTwd
            // - 沒 units 但 currentValue > 0 → 用 dividend yield × currentValue 反推
            for (FundHolding fh : snapshot.getFunds()) {
                if (fh.getFundCode() == null || fh.getFundCode().isBlank()) continue;
                java.time.LocalDate basedate = snapshot.getSnapshotDate();
                BigDecimal newDiv = null;
                if (fh.getUnits() != null && fh.getUnits().compareTo(BigDecimal.ZERO) > 0) {
                    newDiv = fundDividendService
                            .computeAnnualDividendTwdOnDate(fh.getFundCode(), fh.getUnits(), basedate)
                            .orElse(null);
                } else if (fh.getCurrentValue() != null && fh.getCurrentValue().compareTo(BigDecimal.ZERO) > 0) {
                    var navInfo = fundNavService.getNavTwdOnDate(fh.getFundCode(), basedate).orElse(null);
                    if (navInfo != null) {
                        newDiv = fundDividendService
                                .estimateFromCurrentValue(fh.getFundCode(), fh.getCurrentValue(), basedate, navInfo)
                                .orElse(null);
                    }
                }
                if (newDiv != null && !newDiv.equals(fh.getEstimatedDividend())) {
                    fh.setEstimatedDividend(newDiv);
                    changed = true;
                }
            }
            // Always resum (stocks + funds) in case individual dividends were updated externally
            BigDecimal totalStockDiv = snapshot.getStocks().stream()
                    .filter(st -> st.getEstimatedDividend() != null)
                    .map(StockHolding::getEstimatedDividend)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalFundDiv = snapshot.getFunds().stream()
                    .filter(fh -> fh.getEstimatedDividend() != null)
                    .map(FundHolding::getEstimatedDividend)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalDepositInterest = sumDepositInterest(snapshot);
            BigDecimal totalDiv = totalStockDiv.add(totalFundDiv).add(totalDepositInterest);
            if (changed || !totalDiv.equals(snapshot.getEstimatedAnnualDividend())) {
                snapshot.setEstimatedAnnualDividend(totalDiv);
                snapshotRepo.save(snapshot);
                updatedCount++;
                log.info("重算配息完成: 快照 {} {} estimatedAnnualDividend={} (stock={}, fund={}, depositInterest={})",
                        snapshot.getId(), snapshot.getSnapshotDate(), totalDiv, totalStockDiv, totalFundDiv, totalDepositInterest);
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

        // 重算快照層級的 estimatedAnnualDividend（stocks + funds + deposit interest，與 recalcAllDividends 行為一致）
        BigDecimal totalStockDiv = snapshot.getStocks().stream()
                .filter(st -> st.getEstimatedDividend() != null)
                .map(StockHolding::getEstimatedDividend)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFundDiv = snapshot.getFunds().stream()
                .filter(fh -> fh.getEstimatedDividend() != null)
                .map(FundHolding::getEstimatedDividend)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        snapshot.setEstimatedAnnualDividend(
                totalStockDiv.add(totalFundDiv).add(sumDepositInterest(snapshot)));

        snapshotRepo.save(snapshot);
    }

    // ===================== Private Helpers =====================

    private AssetSnapshot findSnapshot(Long id) {
        AssetSnapshot s = snapshotRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("找不到快照 ID: " + id));
        tenantGuard.assertOwned(s.getOwnerUserId()); // by-id 載入不受 @Filter 約束，需手動驗證歸屬
        return s;
    }

    /**
     * 單筆股票投資成本換算為台幣。
     * - 台股 / 美股 TWD 計價：investmentCost 本身即台幣，直接回傳（四捨五入至整數）。
     * - 美股 USD 計價：investmentCost 為美元，乘有效匯率（交易日匯率優先，否則快照匯率）換算台幣。
     * recalcTotals（彙總）與 toDetailResponse（逐筆 investmentCostTwd）共用，
     * 確保「total_stock_cost == Σ 各列 investmentCostTwd」（同義欄位同一邏輯）。
     */
    private BigDecimal stockInvestmentCostTwd(StockHolding st, BigDecimal snapshotRate) {
        BigDecimal cost = st.getInvestmentCost() != null ? st.getInvestmentCost() : BigDecimal.ZERO;
        if ("USD".equals(st.getCurrency())) {
            BigDecimal rate = st.getTransactionExchangeRate() != null
                    ? st.getTransactionExchangeRate()
                    : (snapshotRate != null ? snapshotRate : BigDecimal.ONE);
            return cost.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        }
        return cost.setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 重算所有快照的彙總欄位（total_*），修正歷史快照因舊邏輯造成的偏差
     * （例：美股 USD 計價成本未換匯，導致 total_stock_cost 低估、股票損益虛高）。
     * 由 POST /api/snapshots/recalc-totals 觸發。
     */
    @Transactional
    public int recalcAllTotals() {
        List<AssetSnapshot> all = snapshotRepo.findAll();
        for (AssetSnapshot s : all) {
            recalcTotals(s);
            snapshotRepo.save(s);
        }
        return all.size();
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
        BigDecimal snapshotRate = s.getUsdExchangeRate() != null ? s.getUsdExchangeRate() : BigDecimal.ONE;
        BigDecimal totalStockCost = s.getStocks().stream()
                .map(st -> stockInvestmentCostTwd(st, snapshotRate))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalStockDividend = s.getStocks().stream()
                .filter(st -> st.getEstimatedDividend() != null)
                .map(StockHolding::getEstimatedDividend)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFundDividend = s.getFunds().stream()
                .filter(fh -> fh.getEstimatedDividend() != null)
                .map(FundHolding::getEstimatedDividend)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDepositInterest = sumDepositInterest(s);
        BigDecimal totalDividend = totalStockDividend.add(totalFundDividend).add(totalDepositInterest);

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
                .map(d -> {
                    BigDecimal interest = depositEstimatedInterest(d);
                    BigDecimal interestOut = interest.compareTo(BigDecimal.ZERO) > 0 ? interest : null;
                    return new AssetSnapshotDto.DepositResponse(
                        d.getId(),
                        d.getBank() != null ? d.getBank().getId() : null,
                        d.getBank() != null ? d.getBank().getDisplayName() : null,
                        d.getDepositType(), d.getDepositType(),
                        d.getAmount(), d.getOriginalAmount(), d.getCurrency(),
                        d.getAnnualInterestRate(), interestOut,
                        d.getNotes()
                    );
                }).toList();

        List<AssetSnapshotDto.FundResponse> funds = s.getFunds().stream()
                .map(f -> {
                    BigDecimal cv = f.getCurrentValue();
                    BigDecimal divEst = f.getEstimatedDividend();
                    BigDecimal divRate = (divEst != null && cv != null && cv.compareTo(BigDecimal.ZERO) > 0)
                            ? divEst.divide(cv, 6, RoundingMode.HALF_UP) : null;
                    return new AssetSnapshotDto.FundResponse(
                        f.getId(), f.getFundName(), f.getFundCode(),
                        f.getBank() != null ? f.getBank().getId() : null,
                        f.getBank() != null ? f.getBank().getDisplayName() : null,
                        f.getInvestmentAmount(), cv, f.getUnits(),
                        divEst, divRate,
                        f.getProfit(), f.getProfitRate()
                    );
                }).toList();

        // 快照匯率作為 fallback
        BigDecimal snapshotUsdRate = s.getUsdExchangeRate() != null
                ? s.getUsdExchangeRate() : BigDecimal.ONE;

        List<AssetSnapshotDto.StockResponse> stocks = s.getStocks().stream()
                .sorted(java.util.Comparator.comparing(
                    st -> st.getDisplayOrder() != null ? st.getDisplayOrder() : Integer.MAX_VALUE))
                .map(st -> {
                    // investmentCostTwd：USD 幣別須乘有效匯率換算台幣，供匯總顯示用（與 recalcTotals 同一 helper）
                    BigDecimal rawCost = st.getInvestmentCost() != null ? st.getInvestmentCost() : BigDecimal.ZERO;
                    BigDecimal investmentCostTwd = stockInvestmentCostTwd(st, snapshotUsdRate);
                    // 損益 / 損益率一律以「台幣現值 − 台幣成本」計算；美股 rawCost 為美元，不可直接相減
                    BigDecimal cvTwd = st.getCurrentValue() != null ? st.getCurrentValue() : BigDecimal.ZERO;
                    BigDecimal profitTwd = cvTwd.subtract(investmentCostTwd);
                    BigDecimal profitRateTwd = investmentCostTwd.compareTo(BigDecimal.ZERO) != 0
                            ? profitTwd.divide(investmentCostTwd, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
                    String stName = stockMasterRepo.findByCodeAndMarket(st.getStockCode(), st.getMarket())
                            .map(Stock::getName).orElse(st.getStockCode());
                    return new AssetSnapshotDto.StockResponse(
                        st.getId(), st.getStockCode(), stName, st.getMarket(),
                        st.getBroker() != null ? st.getBroker().getId() : null,
                        st.getBroker() != null ? st.getBroker().getDisplayName() : null,
                        st.getShares(), rawCost, investmentCostTwd, st.getCurrentValue(),
                        profitTwd, profitRateTwd,
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
        String currency = g.getCurrency() != null ? g.getCurrency()
                : (("美股".equals(g.getMarket()) || "英股".equals(g.getMarket())) ? "USD" : "TWD");
        BigDecimal rate = g.getExchangeRate();

        // 若 USD 計價但沒有匯率，嘗試查詢
        if ("USD".equals(currency) && rate == null) {
            rate = lookupExchangeRate(g.getTradeDate());
        }

        BigDecimal profit = g.getProceeds().subtract(g.getInvestmentCost());
        BigDecimal profitRate = g.getInvestmentCost().compareTo(BigDecimal.ZERO) != 0
                ? profit.divide(g.getInvestmentCost(), 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal proceedsTwd, investmentCostTwd, profitTwd;
        if ("USD".equals(currency) && rate != null) {
            proceedsTwd = g.getProceeds().multiply(rate).setScale(0, RoundingMode.HALF_UP);
            investmentCostTwd = g.getInvestmentCost().multiply(rate).setScale(0, RoundingMode.HALF_UP);
            profitTwd = profit.multiply(rate).setScale(0, RoundingMode.HALF_UP);
        } else {
            // TWD 計價或無匯率：原值即台幣
            proceedsTwd = g.getProceeds().setScale(0, RoundingMode.HALF_UP);
            investmentCostTwd = g.getInvestmentCost().setScale(0, RoundingMode.HALF_UP);
            profitTwd = profit.setScale(0, RoundingMode.HALF_UP);
        }

        return new RealizedGainDto.RealizedGainResponse(
            g.getId(), g.getAssetName(), g.getAssetCode(), g.getMarket(),
            currency, g.getBroker(), rate,
            g.getTradeDate(), g.getShares(), g.getSalePrice(),
            g.getProceeds(), g.getInvestmentCost(), profit, profitRate,
            proceedsTwd, investmentCostTwd, profitTwd,
            g.getYear()
        );
    }
}
