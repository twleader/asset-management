package com.steven.assets.bff.publicsrpp;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
@RestController
@RequiredArgsConstructor
public class PublicSrppReportController {
 private final PublicSrppReportService service;
 @GetMapping(SrppReportQuery.PATH)
 public Mono<ResponseEntity<byte[]>> read(ServerHttpRequest request){
  return service.read(request).map(bytes->ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
   .header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(bytes));
 }
}
