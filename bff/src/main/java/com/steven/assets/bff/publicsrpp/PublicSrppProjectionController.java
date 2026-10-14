package com.steven.assets.bff.publicsrpp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

@RestController @RequiredArgsConstructor
public class PublicSrppProjectionController {
 private final PublicSrppProjectionService service;
 @GetMapping(value=SrppProjectionQuery.ORDER,produces=MediaType.APPLICATION_JSON_VALUE) public Mono<ResponseEntity<byte[]>> order(ServerHttpRequest request){return service.read(request,true).map(this::response);}
 @GetMapping(value=SrppProjectionQuery.FACTS,produces=MediaType.APPLICATION_JSON_VALUE) public Mono<ResponseEntity<byte[]>> facts(ServerHttpRequest request){return service.read(request,false).map(this::response);}
 private ResponseEntity<byte[]> response(byte[] body){return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").contentType(MediaType.APPLICATION_JSON).body(body);}
}
