package com.steven.assets.bff.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Boot 的 1MB codec customizer 不可把 BFF→business-services 的 16MB 讀取上限縮回去（Task489 回應約 8MB）。 */
class BusinessServicesClientCodecLimitTest {

    @Test
    @SuppressWarnings("unchecked")
    void bootCodecCustomizerCannotShrinkBusinessResponseLimit() {
        byte[] body = new byte[2 * 1024 * 1024];
        Arrays.fill(body, (byte) 'a');
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes.get("/big", (request, response) -> response.sendByteArray(Mono.just(body))))
                .bindNow();
        try {
            // 模擬 Boot WebClientCodecCustomizer：spring.http.codecs.max-in-memory-size=1MB
            WebClient.Builder builder = WebClient.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024));
            ObjectProvider<ReactorResourceFactory> resources = mock(ObjectProvider.class);
            WebClientConfig config = new WebClientConfig(new ObjectMapper().findAndRegisterModules());
            ReflectionTestUtils.setField(config, "businessServicesUrl", "http://127.0.0.1:" + server.port());
            ReflectionTestUtils.setField(config, "externalMaterialsUrl", "http://external.invalid");

            byte[] read = config.businessServicesClient(builder, resources).get().uri("/big")
                    .retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(10));
            assertThat(read).hasSize(body.length);
        } finally {
            server.disposeNow();
        }
    }
}
