package com.steven.assets.controller;

import com.steven.assets.dto.RealizedGainExportDto;
import com.steven.assets.service.RealizedGainExportScheduleService;
import com.steven.assets.service.RcloneClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/realized-gains/export")
@RequiredArgsConstructor
public class RealizedGainExportController {
    private final RealizedGainExportScheduleService service;
    @GetMapping("/schedules")
    public RealizedGainExportDto.SettingResponse getSchedules() { return service.getForCurrentUser(); }
    @PostMapping("/schedules")
    public RealizedGainExportDto.SettingResponse createSchedule(@RequestBody RealizedGainExportDto.ScheduleRequest request) {
        return service.createForCurrentUser(request);
    }
    @PutMapping("/schedules/{id}")
    public RealizedGainExportDto.SettingResponse updateSchedule(@PathVariable Long id, @RequestBody RealizedGainExportDto.ScheduleRequest request) {
        return service.updateForCurrentUser(id, request);
    }
    @DeleteMapping("/schedules/{id}")
    public RealizedGainExportDto.SettingResponse deleteSchedule(@PathVariable Long id) { return service.deleteForCurrentUser(id); }
    @PostMapping("/schedules/{id}/run-now")
    public RealizedGainExportDto.RunNowResponse runNow(@PathVariable Long id) { return service.runNowForCurrentUser(id); }
    @RequestMapping(value = "/schedule", method = {RequestMethod.GET, RequestMethod.PUT})
    public void retiredSettings() { throw retired(); }
    @PostMapping("/run-now")
    public void retiredRunNow() { throw retired(); }
    private ResponseStatusException retired() {
        return new ResponseStatusException(HttpStatus.GONE, "匯出設定已改為多筆獨立排程，請重新整理頁面");
    }

}
