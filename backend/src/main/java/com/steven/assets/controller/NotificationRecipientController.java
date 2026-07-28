package com.steven.assets.controller;

import com.steven.assets.dto.NotificationRecipientDto;
import com.steven.assets.service.NotificationRecipientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notification-recipients")
@RequiredArgsConstructor
public class NotificationRecipientController {

    private final NotificationRecipientService service;

    @GetMapping
    public List<NotificationRecipientDto.Response> findAll() {
        return service.findAll();
    }

    @PostMapping
    public NotificationRecipientDto.Response create(@Valid @RequestBody NotificationRecipientDto.CreateRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public NotificationRecipientDto.Response update(@PathVariable Long id,
                                                    @Valid @RequestBody NotificationRecipientDto.UpdateRequest req) {
        return service.update(id, req);
    }

    @PatchMapping("/{id}/active")
    public NotificationRecipientDto.Response toggleActive(@PathVariable Long id) {
        return service.toggleActive(id);
    }

    /** 切換「是否接收今日股市分析每日 Email」訂閱（Requirement 31 / Task 151）。 */
    @PatchMapping("/{id}/market-analysis")
    public NotificationRecipientDto.Response toggleMarketAnalysis(@PathVariable Long id) {
        return service.toggleMarketAnalysis(id);
    }

    /** 切換「警示 digest 夾帶 Google 日曆邀請」（Requirement 23 / Task 248）；非 Gmail 開啟時回 400。 */
    @PatchMapping("/{id}/calendar")
    public NotificationRecipientDto.Response toggleCalendar(@PathVariable Long id) {
        return service.toggleCalendar(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
