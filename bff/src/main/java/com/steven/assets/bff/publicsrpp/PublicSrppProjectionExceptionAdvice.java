package com.steven.assets.bff.publicsrpp;
import org.springframework.core.*;import org.springframework.core.annotation.Order;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;import org.springframework.web.server.ServerWebExchange;
import java.util.*;
@RestControllerAdvice(assignableTypes=PublicSrppProjectionController.class) @Order(Ordered.HIGHEST_PRECEDENCE)
class PublicSrppProjectionExceptionAdvice {
 @ExceptionHandler(SrppReportProblemException.class) ResponseEntity<Map<String,Object>> problem(SrppReportProblemException e,ServerWebExchange x){return body(e.problem(),x.getRequest().getPath().value());}
 @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unexpected(Exception e,ServerWebExchange x){return body(SrppReportProblemCatalog.UPSTREAM_INVALID,x.getRequest().getPath().value());}
 private static ResponseEntity<Map<String,Object>> body(SrppReportProblemCatalog p,String path){Map<String,Object>b=new LinkedHashMap<>();b.put("type","about:blank");b.put("title","SRPP projection "+p.name());b.put("status",p.status().value());b.put("detail",p.detail());b.put("instance",path);b.put("code",p.name());b.put("retryable",p.retryable());return ResponseEntity.status(p.status()).header(HttpHeaders.CACHE_CONTROL,"private, no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON).body(b);}
}
