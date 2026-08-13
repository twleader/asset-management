package com.steven.assets.controller;

import com.steven.assets.dto.UsdTwdLiveRateDto;
import com.steven.assets.service.UsdTwdLiveRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BFF 專用的固定 USD/TWD live 唯讀 endpoint。 */
@RestController
@RequestMapping("/api/market-data/exchange-rate/usd-twd/live")
@RequiredArgsConstructor
public class UsdTwdLiveRateController {

    private final UsdTwdLiveRateService service;

    @GetMapping
    public UsdTwdLiveRateDto.Response getLiveRate() {
        UsdTwdLiveRateService.LiveRate result = service.getLiveRate();
        return new UsdTwdLiveRateDto.Response(
                result.pair(), result.baseCurrency(), result.quoteCurrency(), result.date(),
                result.buyRate(), result.sellRate(), result.source(), result.polledAt(),
                result.sourceUpdatedAt(), result.liveUpdateStatus(), result.quoteStatus());
    }
}
