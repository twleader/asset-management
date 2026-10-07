package com.steven.assets.controller;
import com.steven.assets.service.srpp.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/internal/srpp")
public class InternalSrppCaptureController {
 private final SrppCaptureService service;
 @PostMapping(value="/event-evidence/capture",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<String> event(@RequestBody String raw){return response(service.captureEvent(raw));}
 @PostMapping(value="/daily-decision/evaluate",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE) public ResponseEntity<String> decision(@RequestBody String raw){return response(service.evaluate(raw));}
 private static ResponseEntity<String> response(SrppCaptureService.Result r){return ResponseEntity.status(r.status()).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON).body(r.body());}
 @ExceptionHandler(SrppCaptureProblem.class) public ResponseEntity<Map<String,Object>> problem(SrppCaptureProblem p){return ResponseEntity.status(p.status).header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON).body(Map.of("type","about:blank","title",p.code,"status",p.status.value(),"code",p.code));}
}
