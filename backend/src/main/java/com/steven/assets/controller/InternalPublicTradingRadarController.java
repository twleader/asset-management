package com.steven.assets.controller;

import com.steven.assets.dto.PublicTradingRadarDto;
import com.steven.assets.service.PublicTradingRadarProjectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Container-only exact bridges for public 9090 radar reads; no browser-route prefix is used. */
@RestController
@RequestMapping("/internal/public-trading-radar/current")
@RequiredArgsConstructor
public class InternalPublicTradingRadarController {

    private final PublicTradingRadarProjectionService projectionService;

    @GetMapping("/list")
    public ResponseEntity<PublicTradingRadarDto.TradingRadarListResponse> list() {
        return ResponseEntity.ok(projectionService.list());
    }

    @GetMapping("/stock")
    public ResponseEntity<PublicTradingRadarDto.TradingRadarStockDetailResponse> stock(
            @RequestParam(required = false) String stockCode,
            @RequestParam(required = false) String market) {
        return ResponseEntity.ok(projectionService.stock(stockCode, market));
    }
}
