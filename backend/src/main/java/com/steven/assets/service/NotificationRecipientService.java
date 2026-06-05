package com.steven.assets.service;

import com.steven.assets.dto.NotificationRecipientDto;
import com.steven.assets.model.NotificationRecipient;
import com.steven.assets.repository.NotificationRecipientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationRecipientService {

    private final NotificationRecipientRepository repo;

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
                .email(email)
                .active(req.active() == null ? Boolean.TRUE : req.active())
                .build());
        return toResponse(saved);
    }

    @Transactional
    public NotificationRecipientDto.Response update(Long id, NotificationRecipientDto.UpdateRequest req) {
        NotificationRecipient r = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + id));
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
        r.setActive(!r.getActive());
        return toResponse(repo.save(r));
    }

    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private NotificationRecipientDto.Response toResponse(NotificationRecipient r) {
        return new NotificationRecipientDto.Response(
                r.getId(), r.getEmail(), r.getActive(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
