package com.steven.assets.controller;

import com.steven.assets.dto.ExportScheduleDto;
import com.steven.assets.service.ExportScheduleService;
import com.steven.assets.service.RcloneClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/export-schedule")
@RequiredArgsConstructor
public class ExportScheduleController {
    private final ExportScheduleService service;
    @GetMapping("/schedules")
    public ExportScheduleDto.SettingResponse getSchedules() { return service.getForCurrentUser(); }
    @PostMapping("/schedules")
    public ExportScheduleDto.SettingResponse createSchedule(@RequestBody ExportScheduleDto.ScheduleRequest request) {
        return service.createForCurrentUser(request);
    }
    @PutMapping("/schedules/{id}")
    public ExportScheduleDto.SettingResponse updateSchedule(@PathVariable Long id, @RequestBody ExportScheduleDto.ScheduleRequest request) {
        return service.updateForCurrentUser(id, request);
    }
    @DeleteMapping("/schedules/{id}")
    public ExportScheduleDto.SettingResponse deleteSchedule(@PathVariable Long id) { return service.deleteForCurrentUser(id); }
    @PostMapping("/schedules/{id}/run-now")
    public ExportScheduleDto.RunNowResponse runNow(@PathVariable Long id) { return service.runNowForCurrentUser(id); }
    @RequestMapping(value = "/settings", method = {RequestMethod.GET, RequestMethod.PUT})
    public void retiredSettings() { throw retired(); }
    @PostMapping("/run-now")
    public void retiredRunNow() { throw retired(); }
    private ResponseStatusException retired() {
        return new ResponseStatusException(HttpStatus.GONE, "匯出設定已改為多筆獨立排程，請重新整理頁面");
    }
    /** 唯讀列出基底（家目錄）下 {@code subpath} 的子目錄，供前端檔案總管式選擇器逐層懶載入。 */
    @GetMapping("/browse")
    public ExportScheduleDto.BrowseResponse browse(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        return service.browse(subpath);
    }

    /**
     * 唯讀列出 Google Drive remote 下 {@code subpath} 的子目錄（Requirement 50 / Task 241）。
     *
     * <p>與 {@link #browse} 並列為兩支端點而非加參數（語意不同：本機基底 vs. Drive remote）；
     * 但「Drive 目錄列舉」全庫只准這一支，其餘匯出頁日後接 Drive 一律沿用它、不得各自新造。
     */
    @GetMapping("/browse-gdrive")
    public ExportScheduleDto.BrowseResponse browseGdrive(
            @RequestParam(value = "subpath", required = false, defaultValue = "") String subpath) {
        try {
            return service.browseGdrive(subpath);
        } catch (RcloneClient.RcloneUnavailableException e) {
            // remote 未設定／授權失效：必須讓使用者看到可讀原因。
            // 刻意不回 500（前端只會顯示「伺服器錯誤」），也刻意不吞成空清單——
            // 空樹會被誤讀為「Drive 裡沒有資料夾」而讓使用者以為是自己選錯位置。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }

}
