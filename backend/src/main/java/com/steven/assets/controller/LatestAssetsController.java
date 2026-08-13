package com.steven.assets.controller;

import com.steven.assets.dto.LatestAssetsDto;
import com.steven.assets.service.LatestAssetsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** business network-only latest assets endpoint；匿名 owner 不從 request 選取。 */
@RestController
@RequestMapping("/api/assets/latest")
@RequiredArgsConstructor
public class LatestAssetsController {
    private final LatestAssetsService service;

    @GetMapping
    public LatestAssetsDto.Response getLatest() {
        return service.getLatest();
    }
}
