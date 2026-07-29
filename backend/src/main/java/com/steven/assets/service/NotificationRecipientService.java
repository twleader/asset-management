package com.steven.assets.service;

import com.steven.assets.dto.NotificationRecipientDto;
import com.steven.assets.model.NotificationRecipient;
import com.steven.assets.repository.NotificationRecipientRepository;
import com.steven.assets.repository.StockAlertGroupRecipientRepository;
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
    private final StockAlertGroupRecipientRepository groupRecipientRepo;
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
            // Task 248：改成非 Gmail 就把日曆邀請關掉，不留「非 Gmail 卻已開啟」的殘留狀態
            if (!isGmail(email)) {
                r.setAddToCalendar(false);
            }
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

    /**
     * 切換「警示 digest 夾帶 Google 日曆邀請」（Requirement 23 / Task 248），owner-scoped 縱深保護。
     *
     * <p>網域檢查**只擋開啟方向**（false → true）：關閉一律放行，否則資料被改壞後
     * （如殘留「非 Gmail 卻已開啟」的列）將永遠無法從畫面關掉。
     */
    @Transactional
    public NotificationRecipientDto.Response toggleCalendar(Long id) {
        NotificationRecipient r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + id));
        tenantGuard.assertOwned(r.getOwnerUserId());
        boolean turningOn = !Boolean.TRUE.equals(r.getAddToCalendar());
        if (turningOn && !isGmail(r.getEmail())) {
            throw new IllegalArgumentException("僅 Gmail 收件人可加入 Google 日曆");
        }
        r.setAddToCalendar(turningOn);
        return toResponse(repo.save(r));
    }

    /**
     * 是否為 Google 信箱（Task 248）：只有 gmail.com / googlemail.com 的收件人能開啟日曆邀請，
     * 因為「ics 邀請自動落進日曆」是 Google 端的行為。email 寫入前已 trim + 轉小寫。
     */
    public static boolean isGmail(String email) {
        if (email == null || email.isBlank()) return false;
        int at = email.lastIndexOf('@');
        if (at < 0) return false;
        String domain = email.substring(at + 1).trim().toLowerCase();
        return "gmail.com".equals(domain) || "googlemail.com".equals(domain);
    }

    @Transactional
    public void delete(Long id) {
        NotificationRecipient r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + id));
        tenantGuard.assertOwned(r.getOwnerUserId());
        alertRecipientRepo.deleteByRecipientId(id);   // Task 125：連帶刪除其在警示 join 表的列（DB 亦有 ON DELETE CASCADE 雙保險）
        groupRecipientRepo.deleteByRecipientId(id);   // Task 253：複合條件群組的 join 表同步清（維持「service 顯式刪 ＋ DB CASCADE」雙保險，只做一半日後 CASCADE 一調整就留孤兒列）
        repo.delete(r);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private NotificationRecipientDto.Response toResponse(NotificationRecipient r) {
        return new NotificationRecipientDto.Response(
                r.getId(), r.getEmail(), r.getActive(), r.getReceiveMarketAnalysis(),
                r.getAddToCalendar(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
