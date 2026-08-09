package com.steven.assets.controller;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.service.TreasuryYieldService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** ADMIN-only internal Treasury query/refresh API（Requirement 58 / Task 275）。 */
@RestController
@RequestMapping("/internal/macro/treasury-yield")
@RequiredArgsConstructor
public class TreasuryYieldController {

    private final TreasuryYieldService service;

    /** 稽核查詢：列出指定年度所有 official/fallback revisions（含 incomplete）。 */
    @GetMapping
    public List<TreasuryYieldDto.StoredBatch> findByYear(@RequestParam int year) {
        return service.findByYear(year);
    }

    /** decision-time 查詢：只回一個完整 batch；同日 official 優先 fallback。 */
    @GetMapping("/selected")
    public ResponseEntity<TreasuryYieldDto.StoredBatch> selected(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant decisionInstant) {
        return service.resolveSelected(decisionInstant)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 單 tenor query 仍先選完整 batch，回應保留 batch/provider 與四 tenor source manifest。 */
    @GetMapping("/rate-context")
    public ResponseEntity<TreasuryYieldDto.RateContext> rateContext(
            @RequestParam String tenor,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant decisionInstant) {
        return service.resolveRateContext(decisionInstant, tenor)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 省略 year 時刷新 current year；external 只抓資料，落地交易由 business 持有。 */
    @PostMapping("/refresh")
    public TreasuryYieldDto.RefreshSummary refresh(@RequestParam(required = false) Integer year) {
        return service.refresh(year);
    }
}
