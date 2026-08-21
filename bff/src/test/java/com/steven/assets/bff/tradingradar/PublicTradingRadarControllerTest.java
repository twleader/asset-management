package com.steven.assets.bff.tradingradar;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
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
        byte[] bytes = "{\"ruleVersion\":\"TW_RULES_V14\"}".getBytes(StandardCharsets.UTF_8);
        PublicTradingRadarRelay relay = new PublicTradingRadarRelay(
                HttpStatus.PARTIAL_CONTENT.value(), "application/json;charset=UTF-8", bytes);
        PublicTradingRadarService service = mock(PublicTradingRadarService.class);
        when(service.today()).thenReturn(Mono.just(relay));
        PublicTradingRadarController controller = new PublicTradingRadarController(service);

        var response = controller.today().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("application/json;charset=UTF-8"));
        assertThat(response.getHeaders()).doesNotContainKey(HttpHeaders.LOCATION);
        assertThat(response.getBody()).containsExactly(bytes);
        verify(service).today();
        verifyNoMoreInteractions(service);
        assertThat(Arrays.stream(PublicTradingRadarController.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType))
                .noneMatch(WebClient.class::isAssignableFrom);
    }

    @Test
    void serviceContractIsHttpNeutralAndRelayBodyIsImmutable() throws NoSuchMethodException {
        ParameterizedType returnType = (ParameterizedType) PublicTradingRadarService.class
                .getDeclaredMethod("today").getGenericReturnType();
        assertThat(returnType.getRawType()).isEqualTo(Mono.class);
        assertThat(returnType.getActualTypeArguments()).containsExactly(PublicTradingRadarRelay.class);

        byte[] source = {1, 2, 3};
        PublicTradingRadarRelay relay = new PublicTradingRadarRelay(206, null, source);
        source[0] = 9;
        byte[] firstRead = relay.body();
        firstRead[1] = 9;

        assertThat(relay.body()).containsExactly(1, 2, 3);
    }
}
