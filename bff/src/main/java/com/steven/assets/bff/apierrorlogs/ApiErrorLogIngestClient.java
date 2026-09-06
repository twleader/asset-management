package com.steven.assets.bff.apierrorlogs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.*;
import java.util.Map;

@Slf4j @Component
public class ApiErrorLogIngestClient {
    private final WebClient client; private final String token;
    public ApiErrorLogIngestClient(@Value("${business-services.url}") String baseUrl,@Value("${API_ERROR_LOG_INGEST_TOKEN:}") String token){this.client=WebClient.builder().baseUrl(baseUrl).build();this.token=token;}
    public void ingest(String operationKey,String apiName,String header,String trace,Instant occurredAt){
        if(token==null||token.isBlank()) return;
        client.post().uri("/internal/api-error-logs").contentType(MediaType.APPLICATION_JSON).header("X-Internal-Service-Token",token)
                .bodyValue(Map.of("source","OPEN_API","operationKey",operationKey,"apiName",apiName,"messageHeader",header,"stackTrace",trace,"occurredAt",occurredAt.toString()))
                .retrieve().toBodilessEntity().timeout(Duration.ofSeconds(2)).onErrorResume(error->{log.warn("API error log ingest failed operation={} error={}",operationKey,error.getClass().getSimpleName());return reactor.core.publisher.Mono.empty();}).subscribe();
    }
}
