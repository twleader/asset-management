package com.steven.assets.controller;

import com.steven.assets.service.srpp.DailyReportMailProblem;
import com.steven.assets.service.srpp.DailyReportMailService;
import java.util.LinkedHashMap; import java.util.Map; import lombok.RequiredArgsConstructor; import org.springframework.http.*; import org.springframework.web.bind.annotation.*;

/** HTTP adapter only. Validation, hashing, SMTP and persistence remain in DailyReportMailService. */
@RestController @RequiredArgsConstructor @RequestMapping("/internal/srpp/daily-report-mail")
public class InternalSrppDailyReportMailController {
    private final DailyReportMailService service;
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> post(@RequestBody String body){return ok(service.submit(body));}
    @GetMapping(value="/{idempotencyKey}",produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<?> get(@PathVariable String idempotencyKey){return ok(service.get(idempotencyKey));}
    private static ResponseEntity<?> ok(DailyReportMailService.Response body){return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(body);}
    @ExceptionHandler(DailyReportMailProblem.class) public ResponseEntity<Map<String,Object>> problem(DailyReportMailProblem e){Map<String,Object> p=new LinkedHashMap<>();p.put("type","about:blank");p.put("title",e.code);p.put("status",e.status.value());p.put("code",e.code);if(e.details!=null)p.put("details",e.details);return ResponseEntity.status(e.status).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
}
