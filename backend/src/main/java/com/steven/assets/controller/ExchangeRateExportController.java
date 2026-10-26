package com.steven.assets.controller;

import com.steven.assets.dto.ExchangeRateExportDto;
import com.steven.assets.service.ExchangeRateExportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/exchange-rate-export")
@RequiredArgsConstructor
public class ExchangeRateExportController {
    private final ExchangeRateExportScheduleService service;
    @GetMapping("/schedules")
    public ExchangeRateExportDto.SettingResponse getSchedules() { return service.getForCurrentUser(); }
    @PostMapping("/schedules")
    public ExchangeRateExportDto.SettingResponse createSchedule(@RequestBody ExchangeRateExportDto.ScheduleRequest request) {
        return service.createForCurrentUser(request);
    }
    @PutMapping("/schedules/{id}")
    public ExchangeRateExportDto.SettingResponse updateSchedule(@PathVariable Long id, @RequestBody ExchangeRateExportDto.ScheduleRequest request) {
        return service.updateForCurrentUser(id, request);
    }
    @DeleteMapping("/schedules/{id}")
    public ExchangeRateExportDto.SettingResponse deleteSchedule(@PathVariable Long id) { return service.deleteForCurrentUser(id); }
    @PostMapping("/schedules/{id}/run-now")
    public ExchangeRateExportDto.RunNowResponse runNow(@PathVariable Long id) { return service.runNowForCurrentUser(id); }
    @RequestMapping(value = "/schedule", method = {RequestMethod.GET, RequestMethod.PUT})
    public void retiredSettings() { throw retired(); }
    @PostMapping("/run-now")
    public void retiredRunNow() { throw retired(); }
    private ResponseStatusException retired() {
        return new ResponseStatusException(HttpStatus.GONE, "匯出設定已改為多筆獨立排程，請重新整理頁面");
    }
}
