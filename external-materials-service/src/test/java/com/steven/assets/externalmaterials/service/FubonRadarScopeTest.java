package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class FubonRadarScopeTest {

    @Test
    void broaderFubonSymbolRuleCannotExpandMasterAnchoredTaiwanRadarScope() {
        StockSourceQuery source = mock(StockSourceQuery.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Set<String> codes = invocation.getArgument(0);
            codes.add("AB"); // Fubon 的一般 symbol 規則曾接受，但不是台股雷達代號。
            return null;
        }).when(source).collectTwRadarCodes(any());

        assertThatThrownBy(() -> new FubonRadarScope(source).current(40))
                .isInstanceOf(FubonMarketData.Unavailable.class)
                .hasMessage("RADAR_INVALID");
    }
}
