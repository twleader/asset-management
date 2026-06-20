package com.steven.assets.service;

import com.steven.assets.dto.InstitutionDto;
import com.steven.assets.model.AssetClass;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BondTerm;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.DepositTypeEntity;
import com.steven.assets.model.MarketType;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockStyle;
import com.steven.assets.model.TransitFundType;
import com.steven.assets.repository.AssetClassRepository;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BondTermRepository;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.DepositTypeRepository;
import com.steven.assets.repository.MarketTypeRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.StockStyleRepository;
import com.steven.assets.repository.TransitFundTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InstitutionService {

    private final BankRepository bankRepo;
    private final BrokerRepository brokerRepo;
    private final DepositTypeRepository depositTypeRepo;
    private final MarketTypeRepository marketTypeRepo;
    private final TransitFundTypeRepository transitFundTypeRepo;
    private final AssetClassRepository assetClassRepo;
    private final StockRepository stockRepo;
    private final AssetClassifier assetClassifier;
    private final StockStyleRepository stockStyleRepo;
    private final BondTermRepository bondTermRepo;
    private final AssetSnapshotRepository snapshotRepo;

    // ===================== Bank =====================

    @Transactional(readOnly = true)
    public List<InstitutionDto.BankResponse> getAllBanks() {
        return bankRepo.findAllByOrderByDisplayNameAsc().stream()
                .map(this::toBankResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstitutionDto.BankResponse> getActiveBanks() {
        return bankRepo.findByActiveTrueOrderByDisplayNameAsc().stream()
                .map(this::toBankResponse)
                .toList();
    }

    @Transactional
    public InstitutionDto.BankResponse createBank(InstitutionDto.CreateBankRequest req) {
        if (bankRepo.findByCode(req.code()).isPresent()) {
            throw new IllegalArgumentException("銀行代碼已存在: " + req.code());
        }
        Bank bank = Bank.builder()
                .code(req.code())
                .displayName(req.displayName())
                .keywords(req.keywords())
                .active(true)
                .build();
        return toBankResponse(bankRepo.save(bank));
    }

    @Transactional
    public InstitutionDto.BankResponse updateBank(Long id, InstitutionDto.UpdateBankRequest req) {
        Bank bank = bankRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到銀行 ID: " + id));
        bank.setDisplayName(req.displayName());
        bank.setKeywords(req.keywords());
        return toBankResponse(bankRepo.save(bank));
    }

    @Transactional
    public InstitutionDto.BankResponse setBankActive(Long id, boolean active) {
        Bank bank = bankRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到銀行 ID: " + id));
        bank.setActive(active);
        return toBankResponse(bankRepo.save(bank));
    }

    /**
     * 依關鍵字比對啟用中的銀行（供 Excel 匯入使用）。
     * 比對邏輯：raw 字串包含任一 keyword（不分大小寫）即視為符合。
     */
    public Optional<Bank> matchBankByKeyword(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String lowerRaw = raw.toLowerCase();
        return bankRepo.findByActiveTrueOrderByDisplayNameAsc().stream()
                .filter(bank -> {
                    String keywords = bank.getKeywords();
                    if (keywords == null || keywords.isBlank()) return false;
                    return Arrays.stream(keywords.split(","))
                            .map(String::trim)
                            .filter(kw -> !kw.isEmpty())
                            .anyMatch(kw -> lowerRaw.contains(kw.toLowerCase()));
                })
                .findFirst();
    }

    // ===================== Broker =====================

    @Transactional(readOnly = true)
    public List<InstitutionDto.BrokerResponse> getAllBrokers() {
        return brokerRepo.findAllByOrderByDisplayNameAsc().stream()
                .map(this::toBrokerResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstitutionDto.BrokerResponse> getActiveBrokers() {
        return brokerRepo.findByActiveTrueOrderByDisplayNameAsc().stream()
                .map(this::toBrokerResponse)
                .toList();
    }

    @Transactional
    public InstitutionDto.BrokerResponse createBroker(InstitutionDto.CreateBrokerRequest req) {
        if (brokerRepo.findByCode(req.code()).isPresent()) {
            throw new IllegalArgumentException("券商代碼已存在: " + req.code());
        }
        BrokerEntity broker = BrokerEntity.builder()
                .code(req.code())
                .displayName(req.displayName())
                .keywords(req.keywords())
                .active(true)
                .build();
        return toBrokerResponse(brokerRepo.save(broker));
    }

    @Transactional
    public InstitutionDto.BrokerResponse updateBroker(Long id, InstitutionDto.UpdateBrokerRequest req) {
        BrokerEntity broker = brokerRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到券商 ID: " + id));
        broker.setDisplayName(req.displayName());
        broker.setKeywords(req.keywords());
        return toBrokerResponse(brokerRepo.save(broker));
    }

    @Transactional
    public InstitutionDto.BrokerResponse setBrokerActive(Long id, boolean active) {
        BrokerEntity broker = brokerRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到券商 ID: " + id));
        broker.setActive(active);
        return toBrokerResponse(brokerRepo.save(broker));
    }

    /**
     * 依關鍵字比對啟用中的券商（供 Excel 匯入使用）。
     */
    public Optional<BrokerEntity> matchBrokerByKeyword(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String lowerRaw = raw.toLowerCase();
        return brokerRepo.findByActiveTrueOrderByDisplayNameAsc().stream()
                .filter(broker -> {
                    String keywords = broker.getKeywords();
                    if (keywords == null || keywords.isBlank()) return false;
                    return Arrays.stream(keywords.split(","))
                            .map(String::trim)
                            .filter(kw -> !kw.isEmpty())
                            .anyMatch(kw -> lowerRaw.contains(kw.toLowerCase()));
                })
                .findFirst();
    }

    // ===================== DepositType =====================

    @Transactional(readOnly = true)
    public List<InstitutionDto.DepositTypeResponse> getAllDepositTypes() {
        return depositTypeRepo.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toDepositTypeResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstitutionDto.DepositTypeResponse> getActiveDepositTypes() {
        return depositTypeRepo.findByActiveTrueOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toDepositTypeResponse)
                .toList();
    }

    @Transactional
    public InstitutionDto.DepositTypeResponse createDepositType(InstitutionDto.CreateDepositTypeRequest req) {
        if (depositTypeRepo.findByCode(req.code()).isPresent()) {
            throw new IllegalArgumentException("存款類型代碼已存在: " + req.code());
        }
        DepositTypeEntity entity = DepositTypeEntity.builder()
                .code(req.code())
                .displayName(req.displayName())
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                .active(true)
                .build();
        return toDepositTypeResponse(depositTypeRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.DepositTypeResponse updateDepositType(Long id, InstitutionDto.UpdateDepositTypeRequest req) {
        DepositTypeEntity entity = depositTypeRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到存款類型 ID: " + id));
        entity.setDisplayName(req.displayName());
        if (req.sortOrder() != null) entity.setSortOrder(req.sortOrder());
        return toDepositTypeResponse(depositTypeRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.DepositTypeResponse setDepositTypeActive(Long id, boolean active) {
        DepositTypeEntity entity = depositTypeRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到存款類型 ID: " + id));
        entity.setActive(active);
        return toDepositTypeResponse(depositTypeRepo.save(entity));
    }

    // ===================== MarketType =====================

    @Transactional(readOnly = true)
    public List<InstitutionDto.MarketTypeResponse> getAllMarketTypes() {
        return marketTypeRepo.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toMarketTypeResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstitutionDto.MarketTypeResponse> getActiveMarketTypes() {
        return marketTypeRepo.findByActiveTrueOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toMarketTypeResponse)
                .toList();
    }

    @Transactional
    public InstitutionDto.MarketTypeResponse createMarketType(InstitutionDto.CreateMarketTypeRequest req) {
        if (marketTypeRepo.findByCode(req.code()).isPresent()) {
            throw new IllegalArgumentException("市場類型代碼已存在: " + req.code());
        }
        MarketType entity = MarketType.builder()
                .code(req.code())
                .displayName(req.displayName())
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                .active(true)
                .build();
        return toMarketTypeResponse(marketTypeRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.MarketTypeResponse updateMarketType(Long id, InstitutionDto.UpdateMarketTypeRequest req) {
        MarketType entity = marketTypeRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到市場類型 ID: " + id));
        entity.setDisplayName(req.displayName());
        if (req.sortOrder() != null) entity.setSortOrder(req.sortOrder());
        return toMarketTypeResponse(marketTypeRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.MarketTypeResponse setMarketTypeActive(Long id, boolean active) {
        MarketType entity = marketTypeRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到市場類型 ID: " + id));
        entity.setActive(active);
        return toMarketTypeResponse(marketTypeRepo.save(entity));
    }

    // ===================== TransitFundType =====================

    @Transactional(readOnly = true)
    public List<InstitutionDto.TransitFundTypeResponse> getAllTransitFundTypes() {
        return transitFundTypeRepo.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toTransitFundTypeResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstitutionDto.TransitFundTypeResponse> getActiveTransitFundTypes() {
        return transitFundTypeRepo.findByActiveTrueOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toTransitFundTypeResponse)
                .toList();
    }

    @Transactional
    public InstitutionDto.TransitFundTypeResponse createTransitFundType(InstitutionDto.CreateTransitFundTypeRequest req) {
        if (transitFundTypeRepo.findByCode(req.code()).isPresent()) {
            throw new IllegalArgumentException("在途款項類型代碼已存在: " + req.code());
        }
        TransitFundType entity = TransitFundType.builder()
                .code(req.code())
                .displayName(req.displayName())
                .payable(req.payable() != null ? req.payable() : true)
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                .active(true)
                .build();
        return toTransitFundTypeResponse(transitFundTypeRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.TransitFundTypeResponse updateTransitFundType(Long id, InstitutionDto.UpdateTransitFundTypeRequest req) {
        TransitFundType entity = transitFundTypeRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到在途款項類型 ID: " + id));
        entity.setDisplayName(req.displayName());
        if (req.payable() != null) entity.setPayable(req.payable());
        if (req.sortOrder() != null) entity.setSortOrder(req.sortOrder());
        return toTransitFundTypeResponse(transitFundTypeRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.TransitFundTypeResponse setTransitFundTypeActive(Long id, boolean active) {
        TransitFundType entity = transitFundTypeRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到在途款項類型 ID: " + id));
        entity.setActive(active);
        return toTransitFundTypeResponse(transitFundTypeRepo.save(entity));
    }

    // ===================== AssetClass（Requirement 25）=====================

    @Transactional(readOnly = true)
    public List<InstitutionDto.AssetClassResponse> getAllAssetClasses() {
        return assetClassRepo.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toAssetClassResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstitutionDto.AssetClassResponse> getActiveAssetClasses() {
        return assetClassRepo.findByActiveTrueOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toAssetClassResponse)
                .toList();
    }

    @Transactional
    public InstitutionDto.AssetClassResponse createAssetClass(InstitutionDto.CreateAssetClassRequest req) {
        String code = req.code().trim().toUpperCase();
        if (assetClassRepo.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("資產類別代碼已存在: " + code);
        }
        AssetClass entity = AssetClass.builder()
                .code(code)
                .displayName(req.displayName())
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                .active(true)
                .build();
        return toAssetClassResponse(assetClassRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.AssetClassResponse updateAssetClass(Long id, InstitutionDto.UpdateAssetClassRequest req) {
        AssetClass entity = assetClassRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到資產類別 ID: " + id));
        entity.setDisplayName(req.displayName());
        if (req.sortOrder() != null) entity.setSortOrder(req.sortOrder());
        return toAssetClassResponse(assetClassRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.AssetClassResponse setAssetClassActive(Long id, boolean active) {
        AssetClass entity = assetClassRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到資產類別 ID: " + id));
        entity.setActive(active);
        return toAssetClassResponse(assetClassRepo.save(entity));
    }

    // ===================== Securities 資產類別 + 股票風格歸類（Requirement 25/26）=====================

    /** 列出 stock 主檔每檔的生效資產類別、股票風格與來源（規則／人工）。 */
    @Transactional(readOnly = true)
    public List<InstitutionDto.SecurityResponse> getAllSecurities() {
        Map<String, BigDecimal> yieldMap = buildLatestYieldMap();
        BigDecimal threshold = loadIncomeThreshold();
        return stockRepo.findAll().stream()
                .sorted(Comparator.comparing(Stock::getMarket).thenComparing(Stock::getCode))
                .map(s -> toSecurityResponse(s, yieldMap, threshold))
                .toList();
    }

    /** 設定／清除個股的資產類別 override（assetClass 為 null/空白 = 還原為規則）。 */
    @Transactional
    public InstitutionDto.SecurityResponse setSecurityAssetClass(InstitutionDto.SetSecurityAssetClassRequest req) {
        Stock stock = stockRepo.findByCodeAndMarket(req.code(), req.market())
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "找不到標的: " + req.code() + " / " + req.market()));
        String override = (req.assetClass() == null || req.assetClass().isBlank())
                ? null : req.assetClass().trim().toUpperCase();
        if (override != null && assetClassRepo.findByCode(override).isEmpty()) {
            throw new IllegalArgumentException("資產類別代碼不存在: " + override);
        }
        stock.setAssetClass(override);
        Stock saved = stockRepo.save(stock);
        return toSecurityResponse(saved, buildLatestYieldMap(), loadIncomeThreshold());
    }

    /** 設定／清除個股的股票風格 override（stockStyle 為 null/空白 = 還原為規則）。 */
    @Transactional
    public InstitutionDto.SecurityResponse setSecurityStockStyle(InstitutionDto.SetSecurityStockStyleRequest req) {
        Stock stock = stockRepo.findByCodeAndMarket(req.code(), req.market())
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "找不到標的: " + req.code() + " / " + req.market()));
        String override = (req.stockStyle() == null || req.stockStyle().isBlank())
                ? null : req.stockStyle().trim().toUpperCase();
        if (override != null && stockStyleRepo.findByCode(override).isEmpty()) {
            throw new IllegalArgumentException("股票風格代碼不存在: " + override);
        }
        stock.setStockStyle(override);
        Stock saved = stockRepo.save(stock);
        return toSecurityResponse(saved, buildLatestYieldMap(), loadIncomeThreshold());
    }

    /** 設定／清除個股（債券）的期別 override（bondTerm 為 null/空白 = 還原為規則）。 */
    @Transactional
    public InstitutionDto.SecurityResponse setSecurityBondTerm(InstitutionDto.SetSecurityBondTermRequest req) {
        Stock stock = stockRepo.findByCodeAndMarket(req.code(), req.market())
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "找不到標的: " + req.code() + " / " + req.market()));
        String override = (req.bondTerm() == null || req.bondTerm().isBlank())
                ? null : req.bondTerm().trim().toUpperCase();
        if (override != null && bondTermRepo.findByCode(override).isEmpty()) {
            throw new IllegalArgumentException("債券期別代碼不存在: " + override);
        }
        stock.setBondTerm(override);
        Stock saved = stockRepo.save(stock);
        return toSecurityResponse(saved, buildLatestYieldMap(), loadIncomeThreshold());
    }

    /** 最新快照逐持股殖利率 Map&lt;market|code, dividendRate&gt;（供設定頁算生效風格）。 */
    private Map<String, BigDecimal> buildLatestYieldMap() {
        Map<String, BigDecimal> map = new HashMap<>();
        List<AssetSnapshot> snaps = snapshotRepo.findAllOrderByDateDesc();
        if (snaps.isEmpty()) return map;
        for (StockHolding st : snaps.get(0).getStocks()) {
            if (st.getDividendRate() != null) {
                map.put(st.getMarket() + "|" + st.getStockCode(), st.getDividendRate());
            }
        }
        return map;
    }

    private BigDecimal loadIncomeThreshold() {
        return stockStyleRepo.findByCode(AssetClassifier.INCOME)
                .map(StockStyle::getDividendThreshold).orElse(null);
    }

    // ===================== StockStyle（Requirement 26）=====================

    @Transactional(readOnly = true)
    public List<InstitutionDto.StockStyleResponse> getAllStockStyles() {
        return stockStyleRepo.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toStockStyleResponse)
                .toList();
    }

    @Transactional
    public InstitutionDto.StockStyleResponse createStockStyle(InstitutionDto.CreateStockStyleRequest req) {
        String code = req.code().trim().toUpperCase();
        if (stockStyleRepo.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("股票風格代碼已存在: " + code);
        }
        StockStyle entity = StockStyle.builder()
                .code(code)
                .displayName(req.displayName())
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                .active(true)
                .dividendThreshold(req.dividendThreshold())
                .build();
        return toStockStyleResponse(stockStyleRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.StockStyleResponse updateStockStyle(Long id, InstitutionDto.UpdateStockStyleRequest req) {
        StockStyle entity = stockStyleRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到股票風格 ID: " + id));
        entity.setDisplayName(req.displayName());
        if (req.sortOrder() != null) entity.setSortOrder(req.sortOrder());
        entity.setDividendThreshold(req.dividendThreshold());  // 允許設為 null
        return toStockStyleResponse(stockStyleRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.StockStyleResponse setStockStyleActive(Long id, boolean active) {
        StockStyle entity = stockStyleRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到股票風格 ID: " + id));
        entity.setActive(active);
        return toStockStyleResponse(stockStyleRepo.save(entity));
    }

    // ===================== BondTerm（Requirement 27）=====================

    @Transactional(readOnly = true)
    public List<InstitutionDto.BondTermResponse> getAllBondTerms() {
        return bondTermRepo.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toBondTermResponse)
                .toList();
    }

    @Transactional
    public InstitutionDto.BondTermResponse createBondTerm(InstitutionDto.CreateBondTermRequest req) {
        String code = req.code().trim().toUpperCase();
        if (bondTermRepo.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("債券期別代碼已存在: " + code);
        }
        BondTerm entity = BondTerm.builder()
                .code(code)
                .displayName(req.displayName())
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                .active(true)
                .build();
        return toBondTermResponse(bondTermRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.BondTermResponse updateBondTerm(Long id, InstitutionDto.UpdateBondTermRequest req) {
        BondTerm entity = bondTermRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到債券期別 ID: " + id));
        entity.setDisplayName(req.displayName());
        if (req.sortOrder() != null) entity.setSortOrder(req.sortOrder());
        return toBondTermResponse(bondTermRepo.save(entity));
    }

    @Transactional
    public InstitutionDto.BondTermResponse setBondTermActive(Long id, boolean active) {
        BondTerm entity = bondTermRepo.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("找不到債券期別 ID: " + id));
        entity.setActive(active);
        return toBondTermResponse(bondTermRepo.save(entity));
    }

    // ===================== Helpers =====================

    private InstitutionDto.BankResponse toBankResponse(Bank b) {
        return new InstitutionDto.BankResponse(b.getId(), b.getCode(), b.getDisplayName(), b.getKeywords(), b.getActive());
    }

    private InstitutionDto.BrokerResponse toBrokerResponse(BrokerEntity b) {
        return new InstitutionDto.BrokerResponse(b.getId(), b.getCode(), b.getDisplayName(), b.getKeywords(), b.getActive());
    }

    private InstitutionDto.DepositTypeResponse toDepositTypeResponse(DepositTypeEntity d) {
        return new InstitutionDto.DepositTypeResponse(d.getId(), d.getCode(), d.getDisplayName(), d.getSortOrder(), d.getActive());
    }

    private InstitutionDto.MarketTypeResponse toMarketTypeResponse(MarketType m) {
        return new InstitutionDto.MarketTypeResponse(m.getId(), m.getCode(), m.getDisplayName(), m.getSortOrder(), m.getActive());
    }

    private InstitutionDto.TransitFundTypeResponse toTransitFundTypeResponse(TransitFundType t) {
        return new InstitutionDto.TransitFundTypeResponse(t.getId(), t.getCode(), t.getDisplayName(), t.getPayable(), t.getSortOrder(), t.getActive());
    }

    private InstitutionDto.AssetClassResponse toAssetClassResponse(AssetClass a) {
        return new InstitutionDto.AssetClassResponse(a.getId(), a.getCode(), a.getDisplayName(), a.getSortOrder(), a.getActive());
    }

    private InstitutionDto.StockStyleResponse toStockStyleResponse(StockStyle s) {
        return new InstitutionDto.StockStyleResponse(
                s.getId(), s.getCode(), s.getDisplayName(), s.getSortOrder(), s.getActive(), s.getDividendThreshold());
    }

    private InstitutionDto.BondTermResponse toBondTermResponse(BondTerm t) {
        return new InstitutionDto.BondTermResponse(
                t.getId(), t.getCode(), t.getDisplayName(), t.getSortOrder(), t.getActive());
    }

    private InstitutionDto.SecurityResponse toSecurityResponse(
            Stock s, Map<String, BigDecimal> yieldMap, BigDecimal threshold) {
        String classOverride = s.getAssetClass();
        boolean hasClassOverride = classOverride != null && !classOverride.isBlank();
        String effectiveClass = assetClassifier.classifyStock(s.getCode(), s.getMarket(), classOverride);

        // 股票風格僅對 effectiveAssetClass=STOCK 者有意義；債券/現金 style 一律 null
        String styleOverride = s.getStockStyle();
        boolean hasStyleOverride = styleOverride != null && !styleOverride.isBlank();
        String effectiveStyle = null;
        String styleSource = null;
        if (AssetClassifier.STOCK.equals(effectiveClass)) {
            BigDecimal yield = yieldMap.get(s.getMarket() + "|" + s.getCode());
            effectiveStyle = assetClassifier.classifyStockStyle(
                    s.getCode(), s.getMarket(), styleOverride, yield, threshold);
            styleSource = hasStyleOverride ? "OVERRIDE" : "RULE";
        }

        // 債券期別僅對 effectiveAssetClass=BOND 者有意義；股票/現金 term 一律 null
        String termOverride = s.getBondTerm();
        boolean hasTermOverride = termOverride != null && !termOverride.isBlank();
        String effectiveTerm = null;
        String termSource = null;
        if (AssetClassifier.BOND.equals(effectiveClass)) {
            effectiveTerm = assetClassifier.classifyBondTerm(
                    s.getCode(), s.getMarket(), s.getName(), termOverride);
            termSource = hasTermOverride ? "OVERRIDE" : "RULE";
        }

        return new InstitutionDto.SecurityResponse(
                s.getCode(), s.getMarket(), s.getName(),
                hasClassOverride ? classOverride : null,
                effectiveClass,
                hasClassOverride ? "OVERRIDE" : "RULE",
                hasStyleOverride ? styleOverride : null,
                effectiveStyle,
                styleSource,
                hasTermOverride ? termOverride : null,
                effectiveTerm,
                termSource);
    }
}
