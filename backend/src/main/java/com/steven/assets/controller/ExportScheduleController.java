package com.steven.assets.controller;

import com.steven.assets.dto.ExportScheduleDto;
import com.steven.assets.service.ExportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 歷年資產每日排程自動匯出設定端點（Requirement 34 / Task 171）。
 *
 * <p>per-user（owner-scoped）：由 BFF 帶 {@code X-User-*} → {@code CurrentUserContext} → {@code ownerFilter}，
 * 每個使用者只存取自己的排程設定。非 admin-only。
 */
@RestController
@RequestMapping("/api/export-schedule")
@RequiredArgsConstructor
public class ExportScheduleController {

    private final ExportScheduleService service;

    @GetMapping("/settings")
    public ExportScheduleDto.SettingResponse getSettings() {
        return service.getForCurrentUser();
    }

    @PutMapping("/settings")
    public ExportScheduleDto.SettingResponse updateSettings(@RequestBody ExportScheduleDto.SettingRequest req) {
        return service.updateForCurrentUser(req);
    }

    @PostMapping("/run-now")
    public ExportScheduleDto.RunNowResponse runNow() {
        return service.runNowForCurrentUser();
    }

    /** 唯讀列出基底（家目錄）下 {@code subpath} 的子目錄，供前端檔案總管式選擇器逐層懶載入。 */
    @GetMapping("/browse")
    public ExportScheduleDto.BrowseResponse browse(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return service.browse(subpath);
    }
}
