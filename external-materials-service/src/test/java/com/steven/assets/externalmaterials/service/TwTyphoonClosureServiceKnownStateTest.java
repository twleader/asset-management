package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwTyphoonClosureServiceKnownStateTest {

    @Test
    void successfulEmptyLoadIsKnownButFailedReloadFailsClosed() {
        TwMarketClosureQuery store = mock(TwMarketClosureQuery.class);
        TwTyphoonClosureService service = new TwTyphoonClosureService(
                store, mock(StockSourceQuery.class), mock(IntradayTickStore.class));
        when(store.findAll()).thenReturn(Map.of());

        service.loadFromDb();
        assertThat(service.isClosureCalendarKnown()).isTrue();

        when(store.findAll()).thenThrow(new IllegalStateException("db unavailable"));
        service.loadFromDb();
        assertThat(service.isClosureCalendarKnown()).isFalse();
    }
}
