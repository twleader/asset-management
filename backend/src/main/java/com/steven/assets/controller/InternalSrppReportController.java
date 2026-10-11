package com.steven.assets.controller;

import com.steven.assets.service.srpp.report.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Exact container-only BFF relay. HTTP concerns only; all policy/parsing/read rules live in service. */
@RestController
@RequiredArgsConstructor
public class InternalSrppReportController {
    private final SrppReportReadService service;
    @GetMapping(value="/internal/public-srpp/report-calculations",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> read(@RequestParam MultiValueMap<String,String> values) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").contentType(MediaType.APPLICATION_JSON).body(service.read(values));
    }
    @ExceptionHandler(SrppReportProblem.class)
    public ResponseEntity<Map<String,Object>> problem(SrppReportProblem error) { return failure(error.code()); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> unexpected(Exception error) { return failure("INTERNAL_ERROR"); }
    private static ResponseEntity<Map<String,Object>> failure(String code) {
        return ResponseEntity.status(SrppReportProblemCatalog.get(code).status()).header(HttpHeaders.CACHE_CONTROL,"private, no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON).body(SrppReportProblemCatalog.body(code));
    }
}
