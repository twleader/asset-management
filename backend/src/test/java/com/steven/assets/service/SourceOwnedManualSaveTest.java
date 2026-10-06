package com.steven.assets.service;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.model.StockHolding;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Requirement 179／Task 479：富邦 SOURCE_OWNED 列手動存檔吸收股數、成本、交易日期。 */
class SourceOwnedManualSaveTest {
    private static StockHolding existing(BrokerEntity broker) {
        return StockHolding.builder().stockCode("00679B").market("台股").broker(broker)
                .shares(new BigDecimal("3859")).investmentCost(new BigDecimal("148685.40"))
                .currentValue(new BigDecimal("93889.00")).currency("TWD").build();
    }

    private static StockHolding apply(StockHolding holding, BrokerEntity broker, String shares, String cost,
                                      LocalDate date) throws Exception {
        AssetSnapshot snapshot = new AssetSnapshot();
        snapshot.getStocks().add(holding);
        AssetSnapshotDto.StockRequest req = new AssetSnapshotDto.StockRequest("00679B", "n", "台股", broker.getId(),
                new BigDecimal(shares), new BigDecimal(cost), new BigDecimal("1"), null, null, "TWD", null, "買", date, null);
        Class<?> idClass = Class.forName("com.steven.assets.service.AssetService$SourceOwnedStockIdentity");
        Constructor<?> ctor = idClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object id = ctor.newInstance(broker.getId(), "00679B", "台股");
        Method m = AssetService.class.getDeclaredMethod("applySourceOwnedManualCosts", AssetSnapshot.class,
                SnapshotStockScopeOwnership.class, Map.class);
        m.setAccessible(true);
        m.invoke(null, snapshot, SnapshotStockScopeOwnership.sourceOwnsFubonTw(), Map.of(id, List.of(req)));
        return holding;
    }

    private static BrokerEntity fubon() {
        return BrokerEntity.builder().id(1L).code("fubon").build();
    }

    @Test
    void absorbsSharesCostAndDate() throws Exception {
        BrokerEntity b = fubon();
        StockHolding h = apply(existing(b), b, "4000", "150000.00", LocalDate.of(2024, 3, 15));
        assertEquals(0, new BigDecimal("4000").compareTo(h.getShares()));
        assertEquals(0, new BigDecimal("150000.00").compareTo(h.getInvestmentCost()));
        assertEquals(LocalDate.of(2024, 3, 15), h.getTransactionDate());
        assertEquals(0, new BigDecimal("97319.51").compareTo(h.getCurrentValue()));
    }

    @Test
    void invalidCostLeavesRowUntouched() throws Exception {
        BrokerEntity b = fubon();
        StockHolding h = apply(existing(b), b, "4000", "150000.123", LocalDate.of(2024, 3, 15));
        assertEquals(0, new BigDecimal("3859").compareTo(h.getShares()));
        assertNull(h.getTransactionDate());
    }
}
