package com.steven.assets.bff.aiflow;

import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public final class AiFlowProducerController {
    private final AiFlowBffService service;
    AiFlowProducerController(AiFlowBffService service){this.service=service;}
    @PostMapping(AiFlowProducerTokenWebFilter.PATH)
    Mono<ResponseEntity<AiFlowDtos.Ack>> record(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(),AiFlowProtocol.MAX_BODY)
            .map(buffer->{try{byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);return bytes;}finally{DataBufferUtils.release(buffer);}})
            .defaultIfEmpty(new byte[0]).onErrorMap(DataBufferLimitException.class,e->new AiFlowProblem("PAYLOAD_TOO_LARGE"))
            .flatMap(bytes->service.record(exchange.getRequest(),bytes))
            .map(result->ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(result.ack()));
    }
}
