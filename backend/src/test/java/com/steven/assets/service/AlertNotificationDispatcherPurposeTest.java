package com.steven.assets.service;

import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertTrigger;
import com.steven.assets.repository.StockAlertGroupRecipientRepository;
import com.steven.assets.repository.StockAlertRecipientRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockAlertTriggerRepository;
import com.steven.assets.repository.projection.AlertRecipientTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Requirement 194：Email、日曆與補發都必須使用同一份用途→條件 mapping。 */
@ExtendWith(MockitoExtension.class)
class AlertNotificationDispatcherPurposeTest {

    @Mock StockAlertRecipientRepository recipientLinkRepo;
    @Mock StockAlertGroupRecipientRepository groupRecipientRepo;
    @Mock EmailService emailService;
    @Mock StockMasterService stockMasterService;
    @Mock StockAlertTriggerRepository triggerRepo;
    @Mock StockAlertRepository alertRepo;
    @Mock AlertChartRenderer chartRenderer;
    @Mock MarketDataService marketDataService;
    @Mock AlertCalendarInviteBuilder calendarInviteBuilder;
    @InjectMocks AlertNotificationDispatcher dispatcher;

    @Test
    void mixedPurposesKeepConditionOwnershipAndDeduplicateWithinEachPurpose() {
        var mapping = AlertNotificationDispatcher.purposeConditionMap(List.of(
                new AlertNotificationDispatcher.PurposeCondition("BUY", "低於月線"),
                new AlertNotificationDispatcher.PurposeCondition("SELL", "K 值高於 80"),
                new AlertNotificationDispatcher.PurposeCondition("BUY", "低於月線"),
                new AlertNotificationDispatcher.PurposeCondition("BUY", "RSI5 低於 20")));

        assertEquals(List.of("BUY", "SELL"), new ArrayList<>(mapping.keySet()));
        assertEquals(new LinkedHashSet<>(List.of("低於月線", "RSI5 低於 20")), mapping.get("BUY"));
        assertEquals(new LinkedHashSet<>(List.of("K 值高於 80")), mapping.get("SELL"));
        assertEquals("買進：低於月線、RSI5 低於 20；賣出：K 值高於 80",
                AlertNotificationDispatcher.formatPurposeConditions(mapping));
        assertEquals("台積電 (2330 台股) — 買進：低於月線、RSI5 低於 20；賣出：K 值高於 80 觸發價 1085",
                AlertNotificationDispatcher.calendarLine("台積電", "2330", "台股", mapping,
                        new BigDecimal("1085")));
    }

    @Test
    void unspecifiedAndUnknownSnapshotsRemainUnspecifiedInsteadOfBeingInferred() {
        var mapping = AlertNotificationDispatcher.purposeConditionMap(List.of(
                new AlertNotificationDispatcher.PurposeCondition("UNSPECIFIED", "低於季線"),
                new AlertNotificationDispatcher.PurposeCondition(null, "K 值低於 20"),
                new AlertNotificationDispatcher.PurposeCondition("UNKNOWN", "RSI5 低於 30")));

        assertEquals(List.of("UNSPECIFIED"), new ArrayList<>(mapping.keySet()));
        assertEquals("未設定：低於季線、K 值低於 20、RSI5 低於 30",
                AlertNotificationDispatcher.formatPurposeConditions(mapping));
    }

    @Test
    void activeLegacyAlertCarriesUnspecifiedPurposeIntoLiveNotification() throws Exception {
        // 未跑 migration 的 fixture 同時驗證 @Builder.Default；live enqueue 不得從條件猜測用途。
        StockAlert legacy = StockAlert.builder()
                .id(8L).ownerUserId(1L).stockCode("2330").market("台股")
                .alertType("PRICE_BELOW").threshold(new BigDecimal("1000")).active(true).build();
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");

        dispatcher.enqueue(legacy, LocalDateTime.of(2026, 10, 18, 10, 0),
                new BigDecimal("999"), null, null, null, null, null);

        Field queueField = AlertNotificationDispatcher.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Queue<Object> queue = (Queue<Object>) queueField.get(dispatcher);
        Object pending = queue.remove();
        Method purpose = pending.getClass().getDeclaredMethod("purpose");
        purpose.setAccessible(true);

        assertEquals("UNSPECIFIED", purpose.invoke(pending));
    }

    @Test
    void resendUsesPurposeStoredOnTriggerRatherThanCurrentAlertPurpose() {
        StockAlert currentAlert = StockAlert.builder()
                .id(7L).ownerUserId(1L).stockCode("2330").market("台股")
                .alertType("PRICE_BELOW").threshold(new BigDecimal("1000"))
                .purpose("SELL").build();
        StockAlertTrigger historicalTrigger = StockAlertTrigger.builder()
                .id(9L).alertId(7L).stockCode("2330").market("台股")
                .purpose("BUY").triggeredAt(LocalDateTime.of(2026, 10, 16, 10, 0))
                .price(new BigDecimal("999")).build();

        when(emailService.isEnabled()).thenReturn(true);
        when(marketDataService.isTradingDay(eq("台股"), any(LocalDate.class))).thenReturn(true);
        when(triggerRepo.findByMarketAndTriggeredAtInDay(eq("台股"), any(LocalDateTime.class),
                any(LocalDateTime.class))).thenReturn(List.of(historicalTrigger));
        when(alertRepo.findById(7L)).thenReturn(Optional.of(currentAlert));
        when(recipientLinkRepo.findActiveTargetsByAlertId(7L))
                .thenReturn(List.of(new AlertRecipientTarget(4L, "reader@example.com", false)));
        when(stockMasterService.resolveNameLocalOnly("2330", "台股")).thenReturn("台積電");
        when(chartRenderer.renderPriceMaPng("2330", "台股")).thenReturn(Optional.empty());
        when(chartRenderer.renderIntradayPng("2330", "台股")).thenReturn(Optional.empty());

        AlertNotificationDispatcher.ResendResult result = dispatcher.resendLastTradingDay("台股");

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(eq(List.of("reader@example.com")),
                eq("[資產管理] 股票警示補發 1 筆"), html.capture(), anyMap());
        assertEquals(AlertNotificationDispatcher.ResendStatus.SENT, result.status());
        assertTrue(html.getValue().contains("買進：股價低於 1000"));
        assertTrue(!html.getValue().contains("賣出：股價低於 1000"));
    }
}
