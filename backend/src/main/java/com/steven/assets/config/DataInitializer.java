package com.steven.assets.config;

import com.steven.assets.model.Bank;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.DepositTypeEntity;
import com.steven.assets.model.MarketType;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.DepositTypeRepository;
import com.steven.assets.repository.MarketTypeRepository;
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

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedBanks();
        seedBrokers();
        seedDepositTypes();
        seedMarketTypes();
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
            new BankSeed("sinopac",  "永豐銀行",     "永豐,SinoPac")
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
            new MarketTypeSeed("美股", "美國股市", 2)
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
}
