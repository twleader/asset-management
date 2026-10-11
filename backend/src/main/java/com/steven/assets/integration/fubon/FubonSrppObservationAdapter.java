package com.steven.assets.integration.fubon;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.fubon.FubonSrppObservationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
/** Exact token-protected official read endpoint; no broker SDK/write API is reachable. */
@Component
public class FubonSrppObservationAdapter implements FubonSrppObservationPort {
    private final WebClient client;private final FubonConfigState config;private final Duration timeout;
    public FubonSrppObservationAdapter(@Value("${fubon.base-url:http://fubon-broker-service:8080}")String url,FubonConfigState config,@Value("${fubon.timeout:30s}")Duration timeout){
        this.client=WebClient.builder().baseUrl(url).codecs(c->c.defaultCodecs().maxInMemorySize(8*1024*1024)).build();this.config=config;this.timeout=timeout;
    }
    public ObjectNode observe(){
        var gate=config.snapshot();if(gate.state()!=FubonConfigState.State.READY)throw new IllegalStateException(gate.reason());
        try{ObjectNode result=client.post().uri("/internal/orders/read").header("X-Internal-Service-Token",gate.token()).retrieve().bodyToMono(ObjectNode.class).timeout(timeout).block();if(result==null)throw new IllegalStateException("EMPTY_RESPONSE");return result;}catch(RuntimeException failure){throw new IllegalStateException("FUBON_ORDER_QUERY_UNAVAILABLE");}
    }
}
