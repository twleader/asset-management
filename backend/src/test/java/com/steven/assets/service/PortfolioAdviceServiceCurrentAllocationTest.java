package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.dto.CurrentAllocationDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.FundClassOverride;
import com.steven.assets.model.FundHolding;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BankDepositRepository;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.FundClassOverrideRepository;
import com.steven.assets.repository.FundHoldingRepository;
import com.steven.assets.repository.InvestmentPlannedExpenseRepository;
import com.steven.assets.repository.InvestmentProfileRepository;
import com.steven.assets.repository.PortfolioAdviceRepository;
import com.steven.assets.repository.PortfolioAdviceSettingRepository;
import com.steven.assets.repository.RealizedGainRepository;
import com.steven.assets.repository.StockHoldingRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.StockStyleRepository;
import com.steven.assets.repository.TransitFundTypeRepository;
import com.steven.assets.security.TenantGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Requirement 82 / Task 341：{@code PortfolioAdviceService.getCurrentAllocation()} 的子分類邏輯測試。
 *
 * <p>驗證重點：子分類邏輯完全比照既有 {@code AssetService.getHoldingsClassified()}（同一組
 * {@link AssetClassifier} 呼叫、同一套 override 查表模式），兩者對同一份測試快照與同一組 override
 * 資料的分類結果須一致；子類別金額加總須等於頂層桶金額；「存款（現金）」的 {@code subItems} 恆為空。
 *
 * <p>刻意使用<b>真實</b> {@link AssetClassifier}（非 mock）——本測試的意義正是驗證真實分類規則，
 * mock 掉它會讓測試失去意義。
 */
@ExtendWith(MockitoExtension.class)
class PortfolioAdviceServiceCurrentAllocationTest {

    // ===== PortfolioAdviceService 的依賴 =====
    @Mock private InvestmentProfileRepository profileRepo;
    @Mock private InvestmentPlannedExpenseRepository expenseRepo;
    @Mock private PortfolioAdviceRepository adviceRepo;
    @Mock private PortfolioAdviceSettingRepository settingRepo;
    @Mock private AssetSnapshotRepository snapshotRepo;
    @Mock private BankDepositRepository depositRepo;
    @Mock private com.steven.assets.repository.DepositTypeRepository depositTypeRepo;
    @Mock private FundHoldingRepository fundRepo;
    @Mock private StockHoldingRepository stockRepo;
    @Mock private RetirementProjectionService projectionService;
    @Mock private TenantGuard tenantGuard;
    @Mock private StockRepository stockMasterRepo;
    @Mock private FundClassOverrideRepository fundClassOverrideRepo;
    @Mock private StockStyleRepository stockStyleRepo;

    // ===== AssetService 額外的依賴（getHoldingsClassified 用不到，但建構子需要）=====
    @Mock private RealizedGainRepository gainRepo;
    @Mock private ExchangeRateHistoryRepository rateHistRepo;
    @Mock private MarketDataService marketDataService;
    @Mock private BankRepository bankRepo;
    @Mock private BrokerRepository brokerRepo;
    @Mock private StockMasterService stockMasterService;
    @Mock private TransitFundTypeRepository transitFundTypeRepo;
    @Mock private FundNavService fundNavService;
    @Mock private FundDividendService fundDividendService;
    @Mock private AssetSnapshotMutationLock snapshotMutationLock;
    @Mock private SnapshotAggregateCalculator snapshotAggregateCalculator;

    private final AssetClassifier assetClassifier = new AssetClassifier();
    private final LocalPortfolioAllocationEngine localEngine = new LocalPortfolioAllocationEngine();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private PortfolioAdviceService portfolioAdviceService;
    private AssetService assetService;
    private AssetSnapshot snapshot;

    @BeforeEach
    void setUp() {
        portfolioAdviceService = new PortfolioAdviceService(
                profileRepo, expenseRepo, adviceRepo, settingRepo, snapshotRepo,
                depositRepo, depositTypeRepo, fundRepo, stockRepo, projectionService, objectMapper, tenantGuard,
                assetClassifier, stockMasterRepo, fundClassOverrideRepo, stockStyleRepo, localEngine);

        assetService = new AssetService(
                snapshotRepo, depositRepo, fundRepo, stockRepo, gainRepo, rateHistRepo, marketDataService,
                bankRepo, brokerRepo, stockMasterRepo, stockMasterService, transitFundTypeRepo, fundNavService,
                fundDividendService, assetClassifier, stockStyleRepo, fundClassOverrideRepo, tenantGuard,
                snapshotMutationLock, snapshotAggregateCalculator);

        // 股票主檔 override：0056（高股息 ETF，命中規則清單 → 收益型）、00679B（債券 ETF，名稱含「20」→ 長期債）、2330（無 override，走規則）
        when(stockMasterRepo.findAll()).thenReturn(List.of(
                Stock.builder().code("2330").market("台股").name("台積電").build(),
                Stock.builder().code("00679B").market("台股").name("元大美債20年").build(),
                Stock.builder().code("0056").market("台股").name("元大高股息").build()));
        // 基金分類：無 override，走名稱規則
        when(fundClassOverrideRepo.findAll()).thenReturn(List.of());
        when(stockStyleRepo.findByCode(AssetClassifier.INCOME)).thenReturn(Optional.empty());

        StockHolding growthStock = StockHolding.builder()
                .stockCode("2330").market("台股")
                .currentValue(new BigDecimal("1000000")).dividendRate(new BigDecimal("0.01")).build();
        StockHolding bondEtf = StockHolding.builder()
                .stockCode("00679B").market("台股")
                .currentValue(new BigDecimal("200000")).build();
        StockHolding incomeStock = StockHolding.builder()
                .stockCode("0056").market("台股")
                .currentValue(new BigDecimal("500000")).dividendRate(new BigDecimal("0.03")).build();

        FundHolding bondFund = FundHolding.builder()
                .fundName("安聯收益基金").currentValue(new BigDecimal("300000")).build();
        FundHolding growthFund = FundHolding.builder()
                .fundName("富達全球成長基金").currentValue(new BigDecimal("200000")).build();

        snapshot = AssetSnapshot.builder()
                .id(1L)
                .ownerUserId(1L)
                .snapshotDate(LocalDate.of(2026, 8, 15))
                .totalDeposit(new BigDecimal("800000"))
                .totalFundValue(new BigDecimal("500000"))
                .totalStockValue(new BigDecimal("1700000"))
                .totalAssets(new BigDecimal("3000000"))
                .stocks(List.of(growthStock, bondEtf, incomeStock))
                .funds(List.of(bondFund, growthFund))
                .build();

        when(snapshotRepo.findLatest()).thenReturn(Optional.of(snapshot));
    }

    @Test
    void getCurrentAllocation_subclassificationMatchesGetHoldingsClassified() {
        when(snapshotRepo.findById(1L)).thenReturn(Optional.of(snapshot));

        CurrentAllocationDto current = portfolioAdviceService.getCurrentAllocation();
        List<AssetSnapshotDto.HoldingClassifiedResponse> flat = assetService.getHoldingsClassified(1L);

        // 依 AssetClassifier 分類代碼 → 中文子類別名稱，與 PortfolioAdviceService 私有 addToSub 同一套映射
        Map<String, String> labelOf = Map.of(
                AssetClassifier.GROWTH, LocalPortfolioAllocationEngine.SUBCLASS_GROWTH,
                AssetClassifier.INCOME, LocalPortfolioAllocationEngine.SUBCLASS_INCOME,
                AssetClassifier.SHORT, LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT,
                AssetClassifier.MID, LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID,
                AssetClassifier.LONG, LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG);

        // 逐筆比對：flat 清單中屬「股票」的（market != "基金"）與屬「信託基金」的（market == "基金"）分別彙總後
        // 應與 getCurrentAllocation() 的 subItems 完全一致
        Map<String, BigDecimal> stockExpected = new java.util.LinkedHashMap<>();
        Map<String, BigDecimal> fundExpected = new java.util.LinkedHashMap<>();
        for (AssetSnapshotDto.HoldingClassifiedResponse hr : flat) {
            String code = AssetClassifier.BOND.equals(hr.assetClass()) ? hr.bondTerm() : hr.stockStyle();
            String label = labelOf.get(code);
            assertThat(label).as("未知的分類代碼：" + code).isNotNull();
            Map<String, BigDecimal> target = "基金".equals(hr.market()) ? fundExpected : stockExpected;
            target.merge(label, hr.currentValue(), BigDecimal::add);
        }

        CurrentAllocationDto.Item stockItem = itemOf(current, "股票");
        CurrentAllocationDto.Item fundItem = itemOf(current, "信託基金");

        assertThat(subItemsAsMap(stockItem)).as("股票桶子分類須與 getHoldingsClassified 一致")
                .containsExactlyInAnyOrderEntriesOf(stockExpected);
        assertThat(subItemsAsMap(fundItem)).as("信託基金桶子分類須與 getHoldingsClassified 一致")
                .containsExactlyInAnyOrderEntriesOf(fundExpected);
    }

    @Test
    void getCurrentAllocation_subclassAmountsSumToBucketTotal() {
        CurrentAllocationDto current = portfolioAdviceService.getCurrentAllocation();

        CurrentAllocationDto.Item stockItem = itemOf(current, "股票");
        BigDecimal stockSubSum = stockItem.subItems().stream()
                .map(CurrentAllocationDto.SubItem::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(stockSubSum).isEqualByComparingTo(stockItem.value());

        CurrentAllocationDto.Item fundItem = itemOf(current, "信託基金");
        BigDecimal fundSubSum = fundItem.subItems().stream()
                .map(CurrentAllocationDto.SubItem::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(fundSubSum).isEqualByComparingTo(fundItem.value());
    }

    @Test
    void getCurrentAllocation_depositSubItemsAlwaysEmpty() {
        CurrentAllocationDto current = portfolioAdviceService.getCurrentAllocation();

        CurrentAllocationDto.Item depositItem = itemOf(current, "存款（現金）");
        assertThat(depositItem.subItems()).isEmpty();
    }

    @Test
    void getCurrentAllocation_onlyIncludesSubclassesWithPositiveAmount() {
        CurrentAllocationDto current = portfolioAdviceService.getCurrentAllocation();

        // 測試快照的股票桶只有 成長型/收益型/長期債 三類非 0（無短期債／中期債個股）
        CurrentAllocationDto.Item stockItem = itemOf(current, "股票");
        assertThat(stockItem.subItems()).extracting(CurrentAllocationDto.SubItem::subClass)
                .containsExactlyInAnyOrder(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH,
                        LocalPortfolioAllocationEngine.SUBCLASS_INCOME,
                        LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG);
        assertThat(stockItem.subItems()).allSatisfy(si -> assertThat(si.value()).isGreaterThan(BigDecimal.ZERO));
    }

    private static CurrentAllocationDto.Item itemOf(CurrentAllocationDto dto, String assetClass) {
        return dto.items().stream().filter(i -> assetClass.equals(i.assetClass())).findFirst()
                .orElseThrow(() -> new AssertionError("找不到類別：" + assetClass));
    }

    private static Map<String, BigDecimal> subItemsAsMap(CurrentAllocationDto.Item item) {
        Map<String, BigDecimal> m = new java.util.LinkedHashMap<>();
        for (CurrentAllocationDto.SubItem si : item.subItems()) {
            m.put(si.subClass(), si.value());
        }
        return m;
    }
}
