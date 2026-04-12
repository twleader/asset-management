package com.steven.assets.service;

import com.steven.assets.dto.InstitutionDto;
import com.steven.assets.model.Bank;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.DepositTypeEntity;
import com.steven.assets.model.MarketType;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.DepositTypeRepository;
import com.steven.assets.repository.MarketTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InstitutionService {

    private final BankRepository bankRepo;
    private final BrokerRepository brokerRepo;
    private final DepositTypeRepository depositTypeRepo;
    private final MarketTypeRepository marketTypeRepo;

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
}
