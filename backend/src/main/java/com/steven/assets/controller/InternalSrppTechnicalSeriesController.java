package com.steven.assets.controller;

import com.steven.assets.service.SrppTechnicalSeriesService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;

/** Internal read for one completed symbol series; no vendor or owner access. */
@RestController
@RequestMapping("/api/market-data")
@RequiredArgsConstructor
public class InternalSrppTechnicalSeriesController {
    private final SrppTechnicalSeriesService service;

    @GetMapping("/srpp-technical-series")
    public SrppTechnicalSeriesService.Response read(
            @RequestParam String market,
            @RequestParam String stockCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String bars) {
        ZoneId zone = switch (market) {
            case "台股" -> ZoneId.of("Asia/Taipei");
            case "美股" -> ZoneId.of("America/New_York");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MARKET");
        };
        if (asOf == null || !asOf.isBefore(LocalDate.now(zone)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AS_OF_NOT_COMPLETED");
        if (stockCode == null || !stockCode.matches("[A-Za-z0-9.\\-]{1,12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STOCK_CODE");
        int count = parseBars(bars);
        return service.read(market, stockCode, asOf, count);
    }

    private static int parseBars(String raw) {
        if (raw == null) return 60;
        if (!raw.matches("[1-9][0-9]{1,2}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_BARS");
        int count = Integer.parseInt(raw);
        if (count < 21 || count > 250)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_BARS");
        return count;
    }
}
