package com.steven.assets.controller;

import com.steven.assets.service.srpp.report.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/** Container-only relays for Task497 immutable projections. */
@RestController @RequiredArgsConstructor
public class InternalSrppProjectionController {
    private final SrppProjectionService service;
    @GetMapping(value="/internal/public-srpp/order-plan",produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<String> order(@RequestParam MultiValueMap<String,String> q){return ok(service.order(q));}
    @GetMapping(value="/internal/public-srpp/symbol-rule-facts",produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<String> facts(@RequestParam MultiValueMap<String,String> q){return ok(service.facts(q));}
    @ExceptionHandler(SrppReportProblem.class) public ResponseEntity<Map<String,Object>> problem(SrppReportProblem e, HttpServletRequest request){return failure(e.code(), request.getRequestURI());}
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> unexpected(Exception e, HttpServletRequest request){return failure("INTERNAL_ERROR", request.getRequestURI());}
    private static ResponseEntity<String> ok(String body){return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").contentType(MediaType.APPLICATION_JSON).body(body);}
    private static ResponseEntity<Map<String,Object>> failure(String code,String internalPath){SrppReportProblemCatalog.Problem p=SrppReportProblemCatalog.get(code);Map<String,Object>b=new LinkedHashMap<>();b.put("type","about:blank");b.put("title",p.title());b.put("status",p.status());b.put("detail",p.detail());b.put("instance",internalPath.endsWith("symbol-rule-facts")?"/api/public/srpp/symbol-rule-facts":"/api/public/srpp/order-plan");b.put("code",code);b.put("retryable",p.retryable());return ResponseEntity.status(p.status()).header(HttpHeaders.CACHE_CONTROL,"private, no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON).body(b);}
}
