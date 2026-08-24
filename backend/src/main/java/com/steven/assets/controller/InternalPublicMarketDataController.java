package com.steven.assets.controller;

import com.steven.assets.service.DividendHistoryService;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.PublicMarketDataReadOnlyService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Requirement 108／Task 372：只供 asset-net 內 no-tenant BFF client 使用的兩條 exact 市場資料 bridge。
 *
 * <p>此類別刻意不在 {@code /api/market-data/**}，不註冊於 AdminGate，也沒有 BFF inbound
 * route；它只回公開市場資料，不能讀取 configured-admin 或任何個人資產資料。</p>
 */
@RestController
@Validated
@RequestMapping("/internal/public-market-data")
@RequiredArgsConstructor
public class InternalPublicMarketDataController {

    private static final String CODE_PATTERN = "^[A-Za-z0-9.\\-]{1,12}$";
    private static final String MARKET_PATTERN = "^[\\p{L}0-9]{1,10}$";

    private final DividendHistoryService dividendHistoryService;
    private final PublicMarketDataReadOnlyService readOnlyService;

    /**
     * 保留完整 readonly dividend envelope；既有 /api/market-data/dividends-readonly 的 bare array 不變。
     */
    @GetMapping("/dividends-readonly-result")
    public ResponseEntity<MarketDataService.DividendHistoryResult> dividendsReadOnlyResult(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
            @RequestParam(defaultValue = "10") @Min(1) @Max(10) int years) {
        return ResponseEntity.ok(dividendHistoryService.findFromDbReadOnly(code, market, years));
    }

    /** 指定日期的 read-only tick outcome；不能選日期、cold-start 或寫入。 */
    @GetMapping("/intraday-ticks-readonly")
    public ResponseEntity<PublicMarketDataReadOnlyService.ReadOnlyIntradayTicks> intradayTicksReadOnly(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(readOnlyService.readIntradayTicks(code, market, date));
    }
}
