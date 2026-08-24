package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.IntradayTickStore;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Requirement 108／Task 372 的精確、純讀分時 bridge。
 *
 * <p>此 controller 刻意不併入 {@link InternalPriceController}：既有 controller 的
 * {@code /internal/intraday-ticks} 具有 cold-start／refresh 語意，而本 path 只能讀指定
 * Redis bucket。它只在 asset-net 供 business-services 代理，沒有 host port 或 gateway route。</p>
 */
@RestController
@RequestMapping("/internal/intraday-ticks-readonly")
@RequiredArgsConstructor
public class ReadOnlyIntradayTickController {

    private final IntradayTickStore tickStore;

    @GetMapping
    public IntradayTickStore.TickReadOutcome readOnly(
            @RequestParam String code,
            @RequestParam String market,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return tickStore.readTicksOutcome(code, market, date);
    }
}
