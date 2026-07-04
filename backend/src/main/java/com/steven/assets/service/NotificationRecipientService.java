package com.steven.assets.service;

import com.steven.assets.dto.NotificationRecipientDto;
import com.steven.assets.model.NotificationRecipient;
import com.steven.assets.repository.NotificationRecipientRepository;
import com.steven.assets.repository.StockAlertRecipientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationRecipientService {

    private final NotificationRecipientRepository repo;
    private final StockAlertRecipientRepository alertRecipientRepo;
    private final com.steven.assets.security.TenantGuard tenantGuard;

    public List<NotificationRecipientDto.Response> findAll() {
        return repo.findAllByOrderByCreatedAtAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public NotificationRecipientDto.Response create(NotificationRecipientDto.CreateRequest req) {
        String email = normalize(req.email());
        repo.findByEmail(email).ifPresent(r -> {
            throw new IllegalArgumentException("收件人 " + email + " 已存在");
        });
        NotificationRecipient saved = repo.save(NotificationRecipient.builder()
                .ownerUserId(tenantGuard.requireCurrentUserId())
                .email(email)
                .active(req.active() == null ? Boolean.TRUE : req.active())
                .build());
        return toResponse(saved);
    }

    @Transactional
    public NotificationRecipientDto.Response update(Long id, NotificationRecipientDto.UpdateRequest req) {
        NotificationRecipient r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + id));
        tenantGuard.assertOwned(r.getOwnerUserId());
        String email = normalize(req.email());
        if (!email.equals(r.getEmail())) {
            repo.findByEmail(email).ifPresent(other -> {
                throw new IllegalArgumentException("收件人 " + email + " 已存在");
            });
            r.setEmail(email);
        }
        return toResponse(repo.save(r));
    }

    @Transactional
    public NotificationRecipientDto.Response toggleActive(Long id) {
        NotificationRecipient r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + id));
        tenantGuard.assertOwned(r.getOwnerUserId());
        r.setActive(!r.getActive());
        return toResponse(repo.save(r));
    }

    /** 切換「是否接收今日股市分析每日 Email」訂閱（Requirement 31 / Task 151），owner-scoped 縱深保護。 */
    @Transactional
    public NotificationRecipientDto.Response toggleMarketAnalysis(Long id) {
        NotificationRecipient r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + id));
        tenantGuard.assertOwned(r.getOwnerUserId());
        r.setReceiveMarketAnalysis(!Boolean.TRUE.equals(r.getReceiveMarketAnalysis()));
        return toResponse(repo.save(r));
    }

    @Transactional
    public void delete(Long id) {
        NotificationRecipient r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + id));
        tenantGuard.assertOwned(r.getOwnerUserId());
        alertRecipientRepo.deleteByRecipientId(id);   // Task 125：連帶刪除其在警示 join 表的列（DB 亦有 ON DELETE CASCADE 雙保險）
        repo.delete(r);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private NotificationRecipientDto.Response toResponse(NotificationRecipient r) {
        return new NotificationRecipientDto.Response(
                r.getId(), r.getEmail(), r.getActive(), r.getReceiveMarketAnalysis(),
                r.getCreatedAt(), r.getUpdatedAt());
    }
}
