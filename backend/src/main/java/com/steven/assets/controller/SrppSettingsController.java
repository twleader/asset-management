package com.steven.assets.controller;

import com.steven.assets.dto.SrppSettingsDto.*;
import com.steven.assets.service.srpp.settings.SrppSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping(value="/api/portfolio-advice/srpp",produces=org.springframework.http.MediaType.APPLICATION_JSON_VALUE) @RequiredArgsConstructor
public class SrppSettingsController {
    private final SrppSettingsService service;
    @GetMapping public CenterResponse center(){return service.center();}
    @PostMapping("/drafts") public DraftResponse create(@RequestBody String raw){return service.create(raw);}
    @PutMapping("/drafts/{id}") public DraftResponse update(@PathVariable UUID id,@RequestBody String raw){return service.update(id,raw);}
    @PostMapping("/drafts/{id}/simulate") public RunResponse simulate(@PathVariable UUID id,@RequestBody String raw){return service.simulate(id,raw);}
    @GetMapping("/simulations/{id}") public RunResponse simulation(@PathVariable UUID id){return service.simulation(id);}
    @PostMapping("/drafts/{id}/publish") public ReleaseResponse publish(@PathVariable UUID id,@RequestBody String raw){return service.publish(id,raw);}
    @GetMapping("/releases/{id}/payload") public PayloadResponse payload(@PathVariable UUID id){return service.payload(id);}
}
