package com.steven.assets.service;

import com.steven.assets.dto.StockAlertDto;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertGroup;
import com.steven.assets.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** User-entered Taiwan alert names are validation input only, never stock-master authority. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockAlertTaiwanMasterBoundaryTest {
    @Mock StockAlertRepository alertRepo;
    @Mock StockAlertTriggerRepository triggerRepo;
    @Mock PriceQueryService priceQuery;
    @Mock StockPriceHistoryRepository historyRepo;
    @Mock StockRepository stockMasterRepo;
    @Mock StockMasterService stockMasterService;
    @Mock HistoricalDataService historicalDataService;
    @Mock TechnicalIndicatorService indicatorService;
    @Mock AlertNotificationDispatcher notificationDispatcher;
    @Mock StockAlertTriggerExportService triggerExportService;
    @Mock MarketDataService marketDataService;
    @Mock StockAlertRecipientRepository recipientLinkRepo;
    @Mock NotificationRecipientService notificationRecipientService;
    @Mock NotificationRecipientRepository recipientRepo;
    @Mock com.steven.assets.security.TenantGuard tenantGuard;
    @Mock StockAlertGroupRepository groupRepo;
    @Mock StockAlertGroupRecipientRepository groupRecipientRepo;
    @InjectMocks StockAlertService service;

    @BeforeEach
    void defaults() {
        when(alertRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(groupRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(alertRepo.findByStockCodeAndMarket(any(), any())).thenReturn(List.of());
        when(groupRepo.findByStockCodeAndMarket(any(), any())).thenReturn(List.of());
        when(stockMasterRepo.findByCodeAndMarket(any(), any())).thenReturn(Optional.empty());
        when(historyRepo.findDistinctTradingDatesByMarket(any(), any())).thenReturn(List.of());
        when(recipientLinkRepo.findRecipientIdsByAlertId(any())).thenReturn(List.of());
        when(tenantGuard.requireCurrentUserId()).thenReturn(9L);
        when(alertRepo.save(any(StockAlert.class))).thenAnswer(invocation -> {
            StockAlert alert = invocation.getArgument(0);
            if (alert.getId() == null) alert.setId(17L);
            return alert;
        });
        when(groupRepo.save(any(StockAlertGroup.class))).thenAnswer(invocation -> {
            StockAlertGroup group = invocation.getArgument(0);
            if (group.getId() == null) group.setId(18L);
            return group;
        });
        when(groupRecipientRepo.findRecipientIdsByGroupId(any())).thenReturn(List.of());
    }

    @Test
    void createTaiwanPayloadNeverWritesTheStockMaster() {
        service.create(request());

        verify(stockMasterService, never()).upsert(anyString(), anyString(), anyString());
    }

    @Test
    void updateTaiwanPayloadNeverWritesTheStockMaster() {
        StockAlert existing = StockAlert.builder().id(17L).ownerUserId(9L).stockCode("00850").market("台股")
                .alertType("PRICE_ABOVE").threshold(BigDecimal.ONE).active(true).build();
        when(alertRepo.findById(17L)).thenReturn(Optional.of(existing));
        when(alertRepo.findByStockCodeAndMarket("00850", "台股")).thenReturn(List.of(existing));

        service.update(17L, request());

        verify(stockMasterService, never()).upsert(anyString(), anyString(), anyString());
    }

    @Test
    void createTaiwanGroupPayloadNeverWritesTheStockMaster() {
        service.createGroup(groupRequest());

        verify(stockMasterService, never()).upsert(anyString(), anyString(), anyString());
    }

    @Test
    void updateTaiwanGroupPayloadNeverWritesTheStockMaster() {
        StockAlertGroup existing = StockAlertGroup.builder().id(18L).ownerUserId(9L).stockCode("00850")
                .market("台股").active(true).build();
        when(groupRepo.findById(18L)).thenReturn(Optional.of(existing));
        when(groupRepo.findByStockCodeAndMarket("00850", "台股")).thenReturn(List.of(existing));

        service.updateGroup(18L, groupRequest());

        verify(stockMasterService, never()).upsert(anyString(), anyString(), anyString());
    }

    private static StockAlertDto.Request request() {
        return new StockAlertDto.Request("00850", "使用者自填名稱", "台股", "PRICE_ABOVE", null,
                BigDecimal.ONE, true, List.of());
    }

    private static StockAlertDto.GroupRequest groupRequest() {
        return new StockAlertDto.GroupRequest("00850", "使用者自填名稱", "台股", List.of(
                new StockAlertDto.ConditionItem("PRICE_ABOVE", null, BigDecimal.ONE, null),
                new StockAlertDto.ConditionItem("PRICE_BELOW", null, BigDecimal.TEN, null)), true, List.of());
    }
}
