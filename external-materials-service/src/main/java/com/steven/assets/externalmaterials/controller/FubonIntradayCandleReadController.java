package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.FubonIntradayCandleBatch;
import com.steven.assets.externalmaterials.service.FubonIntradayCandleReadService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class FubonIntradayCandleReadController {
    private final FubonIntradayCandleReadService service;
    public FubonIntradayCandleReadController(FubonIntradayCandleReadService service) { this.service=service; }
    @GetMapping("/internal/market-data/intraday-candles/batch-read")
    public FubonIntradayCandleBatch read(@RequestParam String stockCodes,@RequestParam String tradingDate,
                                        @RequestParam String asOf) {
        try { return service.read(stockCodes,tradingDate,asOf); }
        catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST");
        }
    }
}
