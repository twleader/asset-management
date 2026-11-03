package com.steven.assets.controller;

import com.steven.assets.service.srpp.us.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Container-only relay. Owner is supplied by the existing trusted BFF tenant boundary. */
@RestController
@RequiredArgsConstructor
public class InternalUsSrppReportController {
    private final UsReportReadService service;
    @GetMapping(value="/internal/public-srpp/us-report-calculations",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> read(@RequestParam MultiValueMap<String,String> values,HttpServletRequest request){if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw new UsReportProblem("INVALID_REQUEST");return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").contentType(MediaType.APPLICATION_JSON).body(service.read(values));}
    @ExceptionHandler(UsReportProblem.class) public ResponseEntity<Map<String,Object>> problem(UsReportProblem error){return ResponseEntity.status(error.status()).header(HttpHeaders.CACHE_CONTROL,"private, no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON).body(error.body());}
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> unexpected(Exception error){return problem(new UsReportProblem("INTERNAL_ERROR"));}
}
