package com.steven.assets.integration.fubon;

import com.steven.assets.service.StockMasterService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FubonLocalNameAdapterTest {

    @Test
    void delegatesOnlyToTheLocalTaiwanStockMasterLookup() {
        StockMasterService stockMaster = mock(StockMasterService.class);
        when(stockMaster.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");

        String name = new FubonLocalNameAdapter(stockMaster).resolveTaiwanStockName("2330");

        assertThat(name).isEqualTo("台積電");
        verify(stockMaster).resolveNameLocalOnly("2330", "台股");
    }
}
