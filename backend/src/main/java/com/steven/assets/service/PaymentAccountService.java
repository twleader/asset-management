package com.steven.assets.service;

import com.steven.assets.dto.PaymentDto;
import com.steven.assets.model.PaymentAccount;
import com.steven.assets.model.PaymentCategory;
import com.steven.assets.repository.PaymentAccountRepository;
import com.steven.assets.repository.PaymentCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 代繳設定 service（Requirement 22）。
 * 同時管理 PaymentCategory（分類）與 PaymentAccount（記錄）。
 */
@Service
@RequiredArgsConstructor
public class PaymentAccountService {

    private final PaymentCategoryRepository categoryRepo;
    private final PaymentAccountRepository accountRepo;

    // ===================== Category =====================

    @Transactional(readOnly = true)
    public List<PaymentDto.CategoryResponse> getAllCategories() {
        return categoryRepo.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toCategoryResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentDto.CategoryResponse> getActiveCategories() {
        return categoryRepo.findByActiveTrueOrderBySortOrderAscDisplayNameAsc().stream()
                .map(this::toCategoryResponse)
                .toList();
    }

    @Transactional
    public PaymentDto.CategoryResponse createCategory(PaymentDto.CreateCategoryRequest req) {
        if (categoryRepo.findByCode(req.code()).isPresent()) {
            throw new IllegalArgumentException("代繳分類代碼已存在: " + req.code());
        }
        PaymentCategory entity = PaymentCategory.builder()
                .code(req.code())
                .displayName(req.displayName())
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                .active(true)
                .build();
        return toCategoryResponse(categoryRepo.save(entity));
    }

    @Transactional
    public PaymentDto.CategoryResponse updateCategory(Long id, PaymentDto.UpdateCategoryRequest req) {
        PaymentCategory entity = categoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("找不到代繳分類 ID: " + id));
        entity.setDisplayName(req.displayName());
        if (req.sortOrder() != null) entity.setSortOrder(req.sortOrder());
        return toCategoryResponse(categoryRepo.save(entity));
    }

    @Transactional
    public PaymentDto.CategoryResponse setCategoryActive(Long id, boolean active) {
        PaymentCategory entity = categoryRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("找不到代繳分類 ID: " + id));
        entity.setActive(active);
        return toCategoryResponse(categoryRepo.save(entity));
    }

    // ===================== Account =====================

    @Transactional(readOnly = true)
    public List<PaymentDto.AccountResponse> getAllAccounts() {
        return accountRepo.findAllByOrderByCategorySortOrderAscCategoryDisplayNameAscSortOrderAscIdAsc().stream()
                .map(this::toAccountResponse)
                .toList();
    }

    @Transactional
    public PaymentDto.AccountResponse createAccount(PaymentDto.CreateAccountRequest req) {
        PaymentCategory category = categoryRepo.findById(req.categoryId())
                .orElseThrow(() -> new NoSuchElementException("找不到代繳分類 ID: " + req.categoryId()));
        PaymentAccount entity = PaymentAccount.builder()
                .category(category)
                .itemName(req.itemName())
                .paymentAccount(req.paymentAccount())
                .note(req.note())
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                .build();
        return toAccountResponse(accountRepo.save(entity));
    }

    @Transactional
    public PaymentDto.AccountResponse updateAccount(Long id, PaymentDto.UpdateAccountRequest req) {
        PaymentAccount entity = accountRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("找不到代繳記錄 ID: " + id));
        if (!entity.getCategory().getId().equals(req.categoryId())) {
            PaymentCategory category = categoryRepo.findById(req.categoryId())
                    .orElseThrow(() -> new NoSuchElementException("找不到代繳分類 ID: " + req.categoryId()));
            entity.setCategory(category);
        }
        entity.setItemName(req.itemName());
        entity.setPaymentAccount(req.paymentAccount());
        entity.setNote(req.note());
        if (req.sortOrder() != null) entity.setSortOrder(req.sortOrder());
        return toAccountResponse(accountRepo.save(entity));
    }

    @Transactional
    public void deleteAccount(Long id) {
        if (!accountRepo.existsById(id)) {
            throw new NoSuchElementException("找不到代繳記錄 ID: " + id);
        }
        accountRepo.deleteById(id);
    }

    // ===================== Helpers =====================

    private PaymentDto.CategoryResponse toCategoryResponse(PaymentCategory c) {
        return new PaymentDto.CategoryResponse(
                c.getId(), c.getCode(), c.getDisplayName(), c.getSortOrder(), c.getActive());
    }

    private PaymentDto.AccountResponse toAccountResponse(PaymentAccount a) {
        PaymentCategory c = a.getCategory();
        return new PaymentDto.AccountResponse(
                a.getId(),
                c.getId(),
                c.getCode(),
                c.getDisplayName(),
                a.getItemName(),
                a.getPaymentAccount(),
                a.getNote(),
                a.getSortOrder());
    }
}
