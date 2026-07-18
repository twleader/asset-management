package com.steven.assets.controller;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.dto.TradingRadarNotificationDto;
import com.steven.assets.service.TradingRadarNotificationSettingService;
import com.steven.assets.service.TradingRadarService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/** 今日交易雷達與逐檔 Email 通知設定（Requirement 43／44）business API。 */
@RestController
@RequestMapping("/api/trading-radar")
@RequiredArgsConstructor
@Validated
public class TradingRadarController {

    private static final String CODE_PATTERN = "^[A-Za-z0-9.\\-]{1,12}$";
    private static final String MARKET_PATTERN = "^[\\p{L}0-9]{1,10}$";

    private final TradingRadarService service;
    private final TradingRadarNotificationSettingService notificationSettingService;

    @GetMapping
    public TradingRadarDto.Response get() {
        return service.get();
    }

    @GetMapping("/notifications/{stockCode}")
    public TradingRadarNotificationDto.Response getNotification(
            @PathVariable @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String stockCode,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market) {
        return notificationSettingService.get(stockCode, market);
    }

    @PutMapping("/notifications/{stockCode}")
    public TradingRadarNotificationDto.Response saveNotification(
            @PathVariable @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String stockCode,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
            @RequestBody TradingRadarNotificationDto.Request request) {
        return notificationSettingService.save(stockCode, market, request);
    }
}
