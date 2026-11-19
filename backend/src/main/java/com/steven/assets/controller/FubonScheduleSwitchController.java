package com.steven.assets.controller;

import com.steven.assets.dto.FubonScheduleSwitchDto;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.FubonScheduleSwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** 富邦唯讀排程執行開關（Requirement 218／Task 612）：GET 已登入者；PUT 限管理者，未知 key 404。 */
@RestController
@RequestMapping("/api/settings/fubon-schedule-switches")
@RequiredArgsConstructor
public class FubonScheduleSwitchController {

    private final FubonScheduleSwitchService service;
    private final CurrentUserContext currentUser;

    @GetMapping
    public List<FubonScheduleSwitchDto> list() {
        return service.list();
    }

    @PutMapping("/{scheduleKey}")
    public FubonScheduleSwitchDto update(@PathVariable String scheduleKey, @RequestBody Map<String, Object> body) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        // 只接受 JSON boolean：缺值、null、字串、數字、陣列、物件一律 400，不得被 Jackson 轉型成 false
        if (body == null || !(body.get("enabled") instanceof Boolean enabled)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled 必須是 true 或 false");
        }
        return service.setEnabled(scheduleKey, enabled);
    }

    /** 非 JSON 物件或無法解析的 body 也是 400（全域兜底會把它變 500）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void unreadableBody() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請求內容不是合法的 JSON 物件");
    }
}
