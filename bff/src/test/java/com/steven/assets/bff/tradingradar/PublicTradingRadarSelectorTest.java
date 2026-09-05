package com.steven.assets.bff.tradingradar;

import com.steven.assets.bff.security.BusinessUserClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Requirement 111: malformed public detail selector cannot trigger configured-admin bootstrap or business I/O. */
class PublicTradingRadarSelectorTest {

    @Test
    void invalidOrRepeatedStockSelectorFailsLocallyBeforeBootstrap() {
        BusinessUserClient users = mock(BusinessUserClient.class);
        PublicTradingRadarService service = new PublicTradingRadarService(users,
                WebClient.builder().baseUrl("http://business").build());

        assertThatThrownBy(() -> service.stock(List.of("2330", "2317"), List.of("台股"), null))
                .isInstanceOf(PublicTradingRadarRequestException.class);
        assertThatThrownBy(() -> service.stock(List.of("bad!"), List.of("台股"), null))
                .isInstanceOf(PublicTradingRadarRequestException.class);
        assertThatThrownBy(() -> service.stock(List.of("2330"), List.of("台 股"), null))
                .isInstanceOf(PublicTradingRadarRequestException.class);
        verifyNoInteractions(users);
    }
}
