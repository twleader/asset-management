package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.FubonLiveResponseReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Docker-network-only pure-read bridge.  It is intentionally not a public/gateway route. */
@RestController
@RequestMapping("/internal/fubon-live-response")
@RequiredArgsConstructor
public class FubonLiveResponseController {

    private final FubonLiveResponseReadService reader;

    @GetMapping
    public FubonLiveResponseReadService.BridgeResponse one(
            @RequestParam String code, @RequestParam String market) {
        return reader.read(code, market);
    }
}
