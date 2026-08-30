package com.steven.assets.controller;

import com.steven.assets.dto.InstitutionDto;
import com.steven.assets.service.InstitutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class InstitutionController {

    private final InstitutionService institutionService;

    // ===================== Banks =====================

    @GetMapping("/banks")
    public List<InstitutionDto.BankResponse> getAllBanks() {
        return institutionService.getAllBanks();
    }

    @PostMapping("/banks")
    public ResponseEntity<InstitutionDto.BankResponse> createBank(
            @Valid @RequestBody InstitutionDto.CreateBankRequest req) {
        return ResponseEntity.ok(institutionService.createBank(req));
    }

    @PutMapping("/banks/{id}")
    public ResponseEntity<InstitutionDto.BankResponse> updateBank(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionDto.UpdateBankRequest req) {
        return ResponseEntity.ok(institutionService.updateBank(id, req));
    }

    @PatchMapping("/banks/{id}/active")
    public ResponseEntity<InstitutionDto.BankResponse> setBankActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return ResponseEntity.ok(institutionService.setBankActive(id, active));
    }

    // ===================== Brokers =====================

    @GetMapping("/brokers")
    public List<InstitutionDto.BrokerResponse> getAllBrokers() {
        return institutionService.getAllBrokers();
    }

    @PostMapping("/brokers")
    public ResponseEntity<InstitutionDto.BrokerResponse> createBroker(
            @Valid @RequestBody InstitutionDto.CreateBrokerRequest req) {
        return ResponseEntity.ok(institutionService.createBroker(req));
    }

    @PutMapping("/brokers/{id}")
    public ResponseEntity<InstitutionDto.BrokerResponse> updateBroker(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionDto.UpdateBrokerRequest req) {
        return ResponseEntity.ok(institutionService.updateBroker(id, req));
    }

    @PatchMapping("/brokers/{id}/active")
    public ResponseEntity<InstitutionDto.BrokerResponse> setBrokerActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return ResponseEntity.ok(institutionService.setBrokerActive(id, active));
    }

    // ===================== DepositTypes =====================

    @GetMapping("/deposit-types")
    public List<InstitutionDto.DepositTypeResponse> getAllDepositTypes() {
        return institutionService.getAllDepositTypes();
    }

    @PostMapping("/deposit-types")
    public ResponseEntity<InstitutionDto.DepositTypeResponse> createDepositType(
            @Valid @RequestBody InstitutionDto.CreateDepositTypeRequest req) {
        return ResponseEntity.ok(institutionService.createDepositType(req));
    }

    @PutMapping("/deposit-types/{id}")
    public ResponseEntity<InstitutionDto.DepositTypeResponse> updateDepositType(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionDto.UpdateDepositTypeRequest req) {
        return ResponseEntity.ok(institutionService.updateDepositType(id, req));
    }

    @PatchMapping("/deposit-types/{id}/active")
    public ResponseEntity<InstitutionDto.DepositTypeResponse> setDepositTypeActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return ResponseEntity.ok(institutionService.setDepositTypeActive(id, active));
    }

    // ===================== MarketTypes =====================

    @GetMapping("/market-types")
    public List<InstitutionDto.MarketTypeResponse> getAllMarketTypes() {
        return institutionService.getAllMarketTypes();
    }

    @PostMapping("/market-types")
    public ResponseEntity<InstitutionDto.MarketTypeResponse> createMarketType(
            @Valid @RequestBody InstitutionDto.CreateMarketTypeRequest req) {
        return ResponseEntity.ok(institutionService.createMarketType(req));
    }

    @PutMapping("/market-types/{id}")
    public ResponseEntity<InstitutionDto.MarketTypeResponse> updateMarketType(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionDto.UpdateMarketTypeRequest req) {
        return ResponseEntity.ok(institutionService.updateMarketType(id, req));
    }

    @PatchMapping("/market-types/{id}/active")
    public ResponseEntity<InstitutionDto.MarketTypeResponse> setMarketTypeActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return ResponseEntity.ok(institutionService.setMarketTypeActive(id, active));
    }

    // ===================== AssetClasses（Requirement 25）=====================

    @GetMapping("/asset-classes")
    public List<InstitutionDto.AssetClassResponse> getAllAssetClasses() {
        return institutionService.getAllAssetClasses();
    }

    @PostMapping("/asset-classes")
    public ResponseEntity<InstitutionDto.AssetClassResponse> createAssetClass(
            @Valid @RequestBody InstitutionDto.CreateAssetClassRequest req) {
        return ResponseEntity.ok(institutionService.createAssetClass(req));
    }

    @PutMapping("/asset-classes/{id}")
    public ResponseEntity<InstitutionDto.AssetClassResponse> updateAssetClass(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionDto.UpdateAssetClassRequest req) {
        return ResponseEntity.ok(institutionService.updateAssetClass(id, req));
    }

    @PatchMapping("/asset-classes/{id}/active")
    public ResponseEntity<InstitutionDto.AssetClassResponse> setAssetClassActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return ResponseEntity.ok(institutionService.setAssetClassActive(id, active));
    }

    // ===================== Securities 資產類別歸類（Requirement 25）=====================

    @GetMapping("/securities")
    public List<InstitutionDto.SecurityResponse> getAllSecurities() {
        return institutionService.getAllSecurities();
    }

    @PutMapping("/securities/asset-class")
    public ResponseEntity<InstitutionDto.SecurityResponse> setSecurityAssetClass(
            @Valid @RequestBody InstitutionDto.SetSecurityAssetClassRequest req) {
        return ResponseEntity.ok(institutionService.setSecurityAssetClass(req));
    }

    @PutMapping("/securities/stock-style")
    public ResponseEntity<InstitutionDto.SecurityResponse> setSecurityStockStyle(
            @Valid @RequestBody InstitutionDto.SetSecurityStockStyleRequest req) {
        return ResponseEntity.ok(institutionService.setSecurityStockStyle(req));
    }

    @PutMapping("/securities/bond-term")
    public ResponseEntity<InstitutionDto.SecurityResponse> setSecurityBondTerm(
            @Valid @RequestBody InstitutionDto.SetSecurityBondTermRequest req) {
        return ResponseEntity.ok(institutionService.setSecurityBondTerm(req));
    }

    // ===================== BondTerms（Requirement 27）=====================

    @GetMapping("/bond-terms")
    public List<InstitutionDto.BondTermResponse> getAllBondTerms() {
        return institutionService.getAllBondTerms();
    }

    @PostMapping("/bond-terms")
    public ResponseEntity<InstitutionDto.BondTermResponse> createBondTerm(
            @Valid @RequestBody InstitutionDto.CreateBondTermRequest req) {
        return ResponseEntity.ok(institutionService.createBondTerm(req));
    }

    @PutMapping("/bond-terms/{id}")
    public ResponseEntity<InstitutionDto.BondTermResponse> updateBondTerm(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionDto.UpdateBondTermRequest req) {
        return ResponseEntity.ok(institutionService.updateBondTerm(id, req));
    }

    @PatchMapping("/bond-terms/{id}/active")
    public ResponseEntity<InstitutionDto.BondTermResponse> setBondTermActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return ResponseEntity.ok(institutionService.setBondTermActive(id, active));
    }

    // ===================== StockStyles（Requirement 26）=====================

    @GetMapping("/stock-styles")
    public List<InstitutionDto.StockStyleResponse> getAllStockStyles() {
        return institutionService.getAllStockStyles();
    }

    @PostMapping("/stock-styles")
    public ResponseEntity<InstitutionDto.StockStyleResponse> createStockStyle(
            @Valid @RequestBody InstitutionDto.CreateStockStyleRequest req) {
        return ResponseEntity.ok(institutionService.createStockStyle(req));
    }

    @PutMapping("/stock-styles/{id}")
    public ResponseEntity<InstitutionDto.StockStyleResponse> updateStockStyle(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionDto.UpdateStockStyleRequest req) {
        return ResponseEntity.ok(institutionService.updateStockStyle(id, req));
    }

    @PatchMapping("/stock-styles/{id}/active")
    public ResponseEntity<InstitutionDto.StockStyleResponse> setStockStyleActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return ResponseEntity.ok(institutionService.setStockStyleActive(id, active));
    }

    // ===================== TransitFundTypes =====================

    @GetMapping("/transit-fund-types")
    public List<InstitutionDto.TransitFundTypeResponse> getAllTransitFundTypes() {
        return institutionService.getAllTransitFundTypes();
    }

    @GetMapping("/transit-fund-types/active")
    public List<InstitutionDto.TransitFundTypeResponse> getActiveTransitFundTypes() {
        return institutionService.getActiveTransitFundTypes();
    }

    @PostMapping("/transit-fund-types")
    public ResponseEntity<InstitutionDto.TransitFundTypeResponse> createTransitFundType(
            @Valid @RequestBody InstitutionDto.CreateTransitFundTypeRequest req) {
        return ResponseEntity.ok(institutionService.createTransitFundType(req));
    }

    @PutMapping("/transit-fund-types/{id}")
    public ResponseEntity<InstitutionDto.TransitFundTypeResponse> updateTransitFundType(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionDto.UpdateTransitFundTypeRequest req) {
        return ResponseEntity.ok(institutionService.updateTransitFundType(id, req));
    }

    @PatchMapping("/transit-fund-types/{id}/active")
    public ResponseEntity<InstitutionDto.TransitFundTypeResponse> setTransitFundTypeActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return ResponseEntity.ok(institutionService.setTransitFundTypeActive(id, active));
    }

    // ===================== AppFeature（Requirement 134／Task 407：角色功能管理）=====================

    @GetMapping("/app-features")
    public List<InstitutionDto.AppFeatureResponse> getAllAppFeatures() {
        return institutionService.getAllAppFeatures();
    }

    @PatchMapping("/app-features/{id}/enabled-for-user")
    public ResponseEntity<InstitutionDto.AppFeatureResponse> setAppFeatureEnabledForUser(
            @PathVariable Long id,
            @Valid @RequestBody InstitutionDto.UpdateAppFeatureEnabledRequest req) {
        return ResponseEntity.ok(institutionService.setAppFeatureEnabledForUser(id, req.enabled()));
    }
}
