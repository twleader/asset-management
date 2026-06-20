package com.steven.assets.config;

import com.steven.assets.model.AssetClass;
import com.steven.assets.model.StockStyle;
import com.steven.assets.model.BondTerm;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.DepositTypeEntity;
import com.steven.assets.model.FundMaster;
import com.steven.assets.model.MarketType;
import com.steven.assets.model.PaymentCategory;
import com.steven.assets.model.TransitFundType;
import com.steven.assets.repository.AssetClassRepository;
import com.steven.assets.repository.StockStyleRepository;
import com.steven.assets.repository.BondTermRepository;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.DepositTypeRepository;
import com.steven.assets.repository.FundMasterRepository;
import com.steven.assets.repository.MarketTypeRepository;
import com.steven.assets.repository.PaymentCategoryRepository;
import com.steven.assets.repository.TransitFundTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 應用程式啟動時自動寫入預設銀行與券商資料。
 * 使用 findByCode 檢查是否已存在，確保重啟不重複插入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final BankRepository bankRepo;
    private final BrokerRepository brokerRepo;
    private final DepositTypeRepository depositTypeRepo;
    private final MarketTypeRepository marketTypeRepo;
    private final TransitFundTypeRepository transitFundTypeRepo;
    private final FundMasterRepository fundMasterRepo;
    private final PaymentCategoryRepository paymentCategoryRepo;
    private final AssetClassRepository assetClassRepo;
    private final StockStyleRepository stockStyleRepo;
    private final BondTermRepository bondTermRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedBanks();
        seedBrokers();
        seedDepositTypes();
        seedMarketTypes();
        seedTransitFundTypes();
        seedFundMasters();
        seedPaymentCategories();
        seedAssetClasses();
        seedStockStyles();
        seedBondTerms();
    }

    private void seedBondTerms() {
        record BondTermSeed(String code, String displayName, int sortOrder) {}

        List<BondTermSeed> seeds = List.of(
            new BondTermSeed("SHORT", "短期", 1),
            new BondTermSeed("MID",   "中期", 2),
            new BondTermSeed("LONG",  "長期", 3)
        );

        for (BondTermSeed s : seeds) {
            if (bondTermRepo.findByCode(s.code()).isEmpty()) {
                bondTermRepo.save(BondTerm.builder()
                        .code(s.code())
                        .displayName(s.displayName())
                        .sortOrder(s.sortOrder())
                        .active(true)
                        .build());
                log.info("初始化債券期別: {}", s.displayName());
            }
        }
    }

    private void seedStockStyles() {
        record StockStyleSeed(String code, String displayName, int sortOrder, java.math.BigDecimal threshold) {}

        List<StockStyleSeed> seeds = List.of(
            new StockStyleSeed("GROWTH", "成長型", 1, null),
            new StockStyleSeed("INCOME", "收益型", 2, new java.math.BigDecimal("0.0400"))
        );

        for (StockStyleSeed s : seeds) {
            if (stockStyleRepo.findByCode(s.code()).isEmpty()) {
                stockStyleRepo.save(StockStyle.builder()
                        .code(s.code())
                        .displayName(s.displayName())
                        .sortOrder(s.sortOrder())
                        .active(true)
                        .dividendThreshold(s.threshold())
                        .build());
                log.info("初始化股票風格: {}", s.displayName());
            }
        }
    }

    private void seedAssetClasses() {
        record AssetClassSeed(String code, String displayName, int sortOrder) {}

        List<AssetClassSeed> seeds = List.of(
            new AssetClassSeed("CASH",  "現金", 1),
            new AssetClassSeed("BOND",  "債券", 2),
            new AssetClassSeed("STOCK", "股票", 3)
        );

        for (AssetClassSeed s : seeds) {
            if (assetClassRepo.findByCode(s.code()).isEmpty()) {
                assetClassRepo.save(AssetClass.builder()
                        .code(s.code())
                        .displayName(s.displayName())
                        .sortOrder(s.sortOrder())
                        .active(true)
                        .build());
                log.info("初始化資產類別: {}", s.displayName());
            }
        }
    }

    private void seedBanks() {
        record BankSeed(String code, String displayName, String keywords) {}

        List<BankSeed> seeds = List.of(
            new BankSeed("fubon",    "台北富邦銀行", "富邦,北富,Fubon"),
            new BankSeed("cathay",   "國泰世華銀行", "國泰,Cathay"),
            new BankSeed("taishin",  "台新銀行",     "台新,Richart,Taishin"),
            new BankSeed("huanan",   "華南銀行",     "華南,Hua Nan"),
            new BankSeed("line",     "Line Bank",    "Line,LINE"),
            new BankSeed("yuanta",   "元大銀行",     "元大,Yuanta"),
            new BankSeed("sinopac",  "永豐銀行",     "永豐,SinoPac"),
            new BankSeed("dbs",      "星展銀行",     "星展,DBS")
        );

        for (BankSeed s : seeds) {
            if (bankRepo.findByCode(s.code()).isEmpty()) {
                bankRepo.save(Bank.builder()
                        .code(s.code())
                        .displayName(s.displayName())
                        .keywords(s.keywords())
                        .active(true)
                        .build());
                log.info("初始化銀行資料: {}", s.displayName());
            }
        }
    }

    private void seedBrokers() {
        record BrokerSeed(String code, String displayName, String keywords) {}

        List<BrokerSeed> seeds = List.of(
            new BrokerSeed("fubon",  "富邦證券", "富邦"),
            new BrokerSeed("cathay", "國泰證券", "國泰"),
            new BrokerSeed("yuanta", "元大證券", "元大,Yuanta"),
            new BrokerSeed("huanan", "華南證券", "華南")
        );

        for (BrokerSeed s : seeds) {
            if (brokerRepo.findByCode(s.code()).isEmpty()) {
                brokerRepo.save(BrokerEntity.builder()
                        .code(s.code())
                        .displayName(s.displayName())
                        .keywords(s.keywords())
                        .active(true)
                        .build());
                log.info("初始化券商資料: {}", s.displayName());
            }
        }
    }

    private void seedDepositTypes() {
        record DepositTypeSeed(String code, String displayName, int sortOrder) {}

        List<DepositTypeSeed> seeds = List.of(
            new DepositTypeSeed("活存",         "台幣活存",       1),
            new DepositTypeSeed("定存",         "台幣定存",       2),
            new DepositTypeSeed("美元活存",     "美元活存",       3),
            new DepositTypeSeed("美元定存",     "美元定存",       4),
            new DepositTypeSeed("證券戶",       "證券戶",         5),
            new DepositTypeSeed("信用卡待付款", "信用卡待付款",   6)
        );

        for (DepositTypeSeed s : seeds) {
            if (depositTypeRepo.findByCode(s.code()).isEmpty()) {
                depositTypeRepo.save(DepositTypeEntity.builder()
                        .code(s.code())
                        .displayName(s.displayName())
                        .sortOrder(s.sortOrder())
                        .active(true)
                        .build());
                log.info("初始化存款類型: {}", s.displayName());
            }
        }
    }

    private void seedMarketTypes() {
        record MarketTypeSeed(String code, String displayName, int sortOrder) {}

        List<MarketTypeSeed> seeds = List.of(
            new MarketTypeSeed("台股", "台灣股市", 1),
            new MarketTypeSeed("美股", "美國股市", 2),
            new MarketTypeSeed("英股", "英國股市", 3)
        );

        for (MarketTypeSeed s : seeds) {
            if (marketTypeRepo.findByCode(s.code()).isEmpty()) {
                marketTypeRepo.save(MarketType.builder()
                        .code(s.code())
                        .displayName(s.displayName())
                        .sortOrder(s.sortOrder())
                        .active(true)
                        .build());
                log.info("初始化市場類型: {}", s.displayName());
            }
        }
    }

    private void seedTransitFundTypes() {
        record TransitFundTypeSeed(String code, String displayName, boolean payable, int sortOrder) {}

        List<TransitFundTypeSeed> seeds = List.of(
            new TransitFundTypeSeed("信用卡待付款", "信用卡待付款", true,  1),
            new TransitFundTypeSeed("買股待付款",   "買股待付款",   true,  2),
            new TransitFundTypeSeed("賣股待收款",   "賣股待收款",   false, 3)
        );

        for (TransitFundTypeSeed s : seeds) {
            if (transitFundTypeRepo.findByCode(s.code()).isEmpty()) {
                transitFundTypeRepo.save(TransitFundType.builder()
                        .code(s.code())
                        .displayName(s.displayName())
                        .payable(s.payable())
                        .sortOrder(s.sortOrder())
                        .active(true)
                        .build());
                log.info("初始化在途款項類型: {}", s.displayName());
            }
        }
    }

    private void seedFundMasters() {
        // (fundCode, fundName, bankCode, currency, site, fundclearOrgCode, fundclearFundCode, fundclearClassCode)
        record FundSeed(String fundCode, String fundName, String bankCode, String currency,
                        String site, String fcOrg, String fcFund, String fcClass) {}

        List<FundSeed> seeds = List.of(
            new FundSeed("02A8", "富達亞洲非投資等級債券基金 A股F1穩定月配息美元",
                "huanan", "USD", "offshore", "043", "A003800030", "LU0937949237"),
            new FundSeed("02B9", "富達歐洲入息基金 A股F1穩定月配息美元避險",
                "huanan", "USD", "offshore", "043", "A003800063", "LU0997587240"),
            new FundSeed("01C2", "摩根環球策略債券基金 JPM美元A股每月派息",
                "huanan", "USD", "offshore", "007", "A001100067", "GSBAMU"),
            new FundSeed("1680", "聯博全球非投資等級債券基金 AA穩定月配南非幣避險",
                "huanan", "ZAR", "offshore", "029", "A001800015", "ABGHYAAZARH"),
            new FundSeed("24B2", "施羅德環球收息債券 南非幣避險A月配固定C",
                "huanan", "ZAR", "offshore", "052", "A004200088", "LU1884787869"),
            new FundSeed("1616", "聯博全球非投資等級債券基金 AT股美元",
                "huanan", "USD", "offshore", "029", "A001800015", "ABGHYATUSD"),
            new FundSeed("93100953A", "元大日本龍頭企業基金 新台幣A類型",
                "yuanta", "TWD", "onshore", "A0005", "93100953", "93100953A")
        );

        for (FundSeed s : seeds) {
            if (fundMasterRepo.existsById(s.fundCode())) continue;
            Bank bank = bankRepo.findByCode(s.bankCode()).orElse(null);
            fundMasterRepo.save(FundMaster.builder()
                    .fundCode(s.fundCode())
                    .fundName(s.fundName())
                    .bank(bank)
                    .currency(s.currency())
                    .site(s.site())
                    .fundclearOrgCode(s.fcOrg())
                    .fundclearFundCode(s.fcFund())
                    .fundclearClassCode(s.fcClass())
                    .active(true)
                    .build());
            log.info("初始化基金主檔: {} {}", s.fundCode(), s.fundName());
        }
    }

    private void seedPaymentCategories() {
        record PaymentCategorySeed(String code, String displayName, int sortOrder) {}

        List<PaymentCategorySeed> seeds = List.of(
            new PaymentCategorySeed("bill",    "繳費", 1),
            new PaymentCategorySeed("tax",     "繳稅", 2),
            new PaymentCategorySeed("service", "服務", 3)
        );

        for (PaymentCategorySeed s : seeds) {
            if (paymentCategoryRepo.findByCode(s.code()).isEmpty()) {
                paymentCategoryRepo.save(PaymentCategory.builder()
                        .code(s.code())
                        .displayName(s.displayName())
                        .sortOrder(s.sortOrder())
                        .active(true)
                        .build());
                log.info("初始化代繳分類: {}", s.displayName());
            }
        }
    }
}
