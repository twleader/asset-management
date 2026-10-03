package com.steven.assets.controller;

import com.steven.assets.service.SrppCompletedTechnicalService;
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
import java.util.Arrays;
import java.util.List;

/** Internal market-only read called by the exact 9090 BFF route. */
@RestController
@RequestMapping("/api/market-data")
@RequiredArgsConstructor
public class InternalSrppCompletedTechnicalController {
    private final SrppCompletedTechnicalService service;

    @GetMapping("/srpp-completed-technicals")
    public SrppCompletedTechnicalService.Response read(
            @RequestParam String market,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam String stockCodes) {
        ZoneId zone = switch (market) {
            case "台股" -> ZoneId.of("Asia/Taipei");
            case "美股" -> ZoneId.of("America/New_York");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MARKET");
        };
        if (asOf == null || !asOf.isBefore(LocalDate.now(zone)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AS_OF_NOT_COMPLETED");
        List<String> codes = Arrays.asList(stockCodes.split(",", -1));
        if (codes.isEmpty() || codes.size() > 40 || codes.stream().anyMatch(s -> !s.matches("[A-Za-z0-9.\\-]{1,12}"))
                || codes.stream().distinct().count() != codes.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STOCK_CODES");
        return service.read(market, asOf, codes);
    }
}
