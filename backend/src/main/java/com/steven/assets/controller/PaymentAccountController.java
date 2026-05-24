package com.steven.assets.controller;

import com.steven.assets.dto.PaymentDto;
import com.steven.assets.service.PaymentAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 代繳設定 controller（Requirement 22）。
 * 分類管理：/api/settings/payment-categories
 * 代繳記錄：/api/payment-accounts
 */
@RestController
@RequiredArgsConstructor
public class PaymentAccountController {

    private final PaymentAccountService service;

    // ===================== Payment Categories =====================

    @GetMapping("/api/settings/payment-categories")
    public List<PaymentDto.CategoryResponse> getAllCategories() {
        return service.getAllCategories();
    }

    @GetMapping("/api/settings/payment-categories/active")
    public List<PaymentDto.CategoryResponse> getActiveCategories() {
        return service.getActiveCategories();
    }

    @PostMapping("/api/settings/payment-categories")
    public ResponseEntity<PaymentDto.CategoryResponse> createCategory(
            @Valid @RequestBody PaymentDto.CreateCategoryRequest req) {
        return ResponseEntity.ok(service.createCategory(req));
    }

    @PutMapping("/api/settings/payment-categories/{id}")
    public ResponseEntity<PaymentDto.CategoryResponse> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody PaymentDto.UpdateCategoryRequest req) {
        return ResponseEntity.ok(service.updateCategory(id, req));
    }

    @PatchMapping("/api/settings/payment-categories/{id}/active")
    public ResponseEntity<PaymentDto.CategoryResponse> setCategoryActive(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return ResponseEntity.ok(service.setCategoryActive(id, active));
    }

    // ===================== Payment Accounts =====================

    @GetMapping("/api/payment-accounts")
    public List<PaymentDto.AccountResponse> getAllAccounts() {
        return service.getAllAccounts();
    }

    @PostMapping("/api/payment-accounts")
    public ResponseEntity<PaymentDto.AccountResponse> createAccount(
            @Valid @RequestBody PaymentDto.CreateAccountRequest req) {
        return ResponseEntity.ok(service.createAccount(req));
    }

    @PutMapping("/api/payment-accounts/{id}")
    public ResponseEntity<PaymentDto.AccountResponse> updateAccount(
            @PathVariable Long id,
            @Valid @RequestBody PaymentDto.UpdateAccountRequest req) {
        return ResponseEntity.ok(service.updateAccount(id, req));
    }

    @DeleteMapping("/api/payment-accounts/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable Long id) {
        service.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }
}
