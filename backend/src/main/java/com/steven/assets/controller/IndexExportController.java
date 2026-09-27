package com.steven.assets.controller;

import com.steven.assets.dto.IndexExportDto;
import com.steven.assets.service.IndexExportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped CRUD and run-now routes for independent index export schedules. */
@RestController
@RequestMapping("/api/index-export/schedules")
@RequiredArgsConstructor
public class IndexExportController {
    private final IndexExportScheduleService service;

    @GetMapping
    public IndexExportDto.SettingResponse list() {
        return service.getForCurrentUser();
    }

    @PostMapping
    public IndexExportDto.SettingResponse create(@RequestBody IndexExportDto.ScheduleRequest request) {
        return service.createForCurrentUser(request);
    }

    @PutMapping("/{id}")
    public IndexExportDto.SettingResponse update(@PathVariable Long id,
                                                @RequestBody IndexExportDto.ScheduleRequest request) {
        return service.updateForCurrentUser(id, request);
    }

    @DeleteMapping("/{id}")
    public IndexExportDto.SettingResponse delete(@PathVariable Long id) {
        return service.deleteForCurrentUser(id);
    }

    @PostMapping("/{id}/run-now")
    public IndexExportDto.RunNowResponse runNow(@PathVariable Long id) {
        return service.runNowForCurrentUser(id);
    }
}
