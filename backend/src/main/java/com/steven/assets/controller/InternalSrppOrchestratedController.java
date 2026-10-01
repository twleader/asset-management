package com.steven.assets.controller;

import com.steven.assets.srpp.SrppOrchestratedReadService;
import com.steven.assets.srpp.SrppReadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Set;

/** Container-only endpoints for the BFF's three on-demand SRPP reads. */
@RestController
@RequiredArgsConstructor
public class InternalSrppOrchestratedController {
    private static final String CONTEXT = "/api/public/srpp/calculation-context";
    private static final String CALCULATIONS = "/api/public/srpp/calculations";
    private static final String MARKET_FACTS = "/api/public/srpp/market-facts";
    private final SrppOrchestratedReadService service;

    @GetMapping("/internal/public-srpp/calculation-context")
    public ResponseEntity<String> context(@RequestParam(required = false) String tradingDate,
                                          @RequestParam(required = false) String slot,
                                          @RequestParam(required = false) String policyBundleSha256) {
        return response(service.context(tradingDate, slot, policyBundleSha256), CONTEXT);
    }

    @GetMapping("/internal/public-srpp/calculations")
    public ResponseEntity<String> calculations(@RequestParam(required = false) String contextId,
                                               @RequestParam(required = false) String calculationIds,
                                               @RequestParam(required = false) String stockCodes) {
        return response(service.calculations(contextId, split(calculationIds), splitOptional(stockCodes)), CALCULATIONS);
    }

    @GetMapping("/internal/public-srpp/market-facts")
    public ResponseEntity<String> marketFacts(@RequestParam(required = false) String contextId,
                                              @RequestParam(required = false) String stockCodes,
                                              @RequestParam(required = false) String include) {
        Set<String> selected = Arrays.stream((include == null ? "quote,radar" : include).split(",", -1))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return response(service.marketFacts(contextId, splitOptional(stockCodes), selected), MARKET_FACTS);
    }

    private static ResponseEntity<String> response(SrppReadResult result, String path) {
        return switch (result) {
            case SrppReadResult.Ok ok -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .contentType(MediaType.APPLICATION_JSON).body(ok.body());
            case SrppReadResult.Problem problem -> ResponseEntity.status(
                            SrppOrchestratedProblemCatalog.status(problem.code()))
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(SrppOrchestratedProblemCatalog.body(problem.code(), path));
        };
    }

    private static java.util.List<String> split(String value) {
        return value == null ? null : Arrays.asList(value.split(",", -1));
    }
    private static java.util.List<String> splitOptional(String value) {
        return value == null ? null : Arrays.asList(value.split(",", -1));
    }
}
