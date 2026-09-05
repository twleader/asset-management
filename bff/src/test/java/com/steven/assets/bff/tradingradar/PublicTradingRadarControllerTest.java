package com.steven.assets.bff.tradingradar;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.lang.reflect.ParameterizedType;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Requirement 86：public controller 不持有 IO 依賴，只委派 service。 */
class PublicTradingRadarControllerTest {

    @Test
    void controllerOnlyDelegatesAndBuildsTheSuccessfulHttpResponse() {
        var payload = JsonNodeFactory.instance.objectNode().put("ruleVersion", "TW_RULES_V14");
        PublicTradingRadarRelay relay = new PublicTradingRadarRelay(payload);
        PublicTradingRadarService service = mock(PublicTradingRadarService.class);
        when(service.today(null)).thenReturn(Mono.just(relay));
        PublicTradingRadarController controller = new PublicTradingRadarController(service);

        var response = controller.today(null).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders()).doesNotContainKey(HttpHeaders.LOCATION);
        assertThat(response.getBody()).isEqualTo(payload);
        verify(service).today(null);
        verifyNoMoreInteractions(service);
        assertThat(Arrays.stream(PublicTradingRadarController.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType))
                .noneMatch(WebClient.class::isAssignableFrom);
    }

    @Test
    void serviceContractIsHttpNeutralAndRelayBodyIsImmutable() throws NoSuchMethodException {
        ParameterizedType returnType = (ParameterizedType) PublicTradingRadarService.class
                .getDeclaredMethod("today", String.class).getGenericReturnType();
        assertThat(returnType.getRawType()).isEqualTo(Mono.class);
        assertThat(returnType.getActualTypeArguments()).containsExactly(PublicTradingRadarRelay.class);

        var source = JsonNodeFactory.instance.objectNode().put("value", 1);
        PublicTradingRadarRelay relay = new PublicTradingRadarRelay(source);
        source.put("value", 9);
        var firstRead = relay.body();
        ((com.fasterxml.jackson.databind.node.ObjectNode) firstRead).put("value", 9);

        assertThat(relay.body().path("value").asInt()).isEqualTo(1);
    }
}
