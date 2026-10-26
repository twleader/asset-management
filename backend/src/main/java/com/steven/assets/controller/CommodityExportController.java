package com.steven.assets.controller;

import com.steven.assets.dto.CommodityExportDto;
import com.steven.assets.service.CommodityExportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/commodity-export")
@RequiredArgsConstructor
public class CommodityExportController {
    private final CommodityExportScheduleService service;
    @GetMapping("/schedules")
    public CommodityExportDto.SettingResponse getSchedules() { return service.getForCurrentUser(); }
    @PostMapping("/schedules")
    public CommodityExportDto.SettingResponse createSchedule(@RequestBody CommodityExportDto.ScheduleRequest request) {
        return service.createForCurrentUser(request);
    }
    @PutMapping("/schedules/{id}")
    public CommodityExportDto.SettingResponse updateSchedule(@PathVariable Long id, @RequestBody CommodityExportDto.ScheduleRequest request) {
        return service.updateForCurrentUser(id, request);
    }
    @DeleteMapping("/schedules/{id}")
    public CommodityExportDto.SettingResponse deleteSchedule(@PathVariable Long id) { return service.deleteForCurrentUser(id); }
    @PostMapping("/schedules/{id}/run-now")
    public CommodityExportDto.RunNowResponse runNow(@PathVariable Long id) { return service.runNowForCurrentUser(id); }
    @RequestMapping(value = "/schedule", method = {RequestMethod.GET, RequestMethod.PUT})
    public void retiredSettings() { throw retired(); }
    @PostMapping("/run-now")
    public void retiredRunNow() { throw retired(); }
    private ResponseStatusException retired() {
        return new ResponseStatusException(HttpStatus.GONE, "匯出設定已改為多筆獨立排程，請重新整理頁面");
    }
}
