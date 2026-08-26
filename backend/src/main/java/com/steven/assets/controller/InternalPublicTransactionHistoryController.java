package com.steven.assets.controller;

import com.steven.assets.dto.PublicTransactionHistoryDto;
import com.steven.assets.service.PublicTransactionHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Container-only configured-owner ledger read. No browser route, export, sync, or mutation is exposed here. */
@RestController
@RequestMapping("/internal/public-transaction-history")
@RequiredArgsConstructor
public class InternalPublicTransactionHistoryController {

    private final PublicTransactionHistoryService service;

    @GetMapping("/current")
    public ResponseEntity<PublicTransactionHistoryDto.PublicTransactionHistoryResponse> current(
            @RequestParam(required = false) List<String> year,
            @RequestParam(required = false) List<String> start,
            @RequestParam(required = false) List<String> end) {
        return ResponseEntity.ok(service.current(year, start, end));
    }
}
