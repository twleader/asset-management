package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceStreamServiceTest {

    @Test
    void 美股只有有效盤中事件才進入雷達通知佇列且既有警示fanOut不受影響() {
        StockAlertService alert = mock(StockAlertService.class);
        TradingRadarNotificationService notification = mock(TradingRadarNotificationService.class);
        PriceStreamService service = new PriceStreamService();
        ReflectionTestUtils.setField(service, "stockAlertService", alert);
        ReflectionTestUtils.setField(service, "tradingRadarNotificationService", notification);

        service.onPriceUpdate("{\"stockCode\":\"AMD\",\"market\":\"美股\",\"quoteStatus\":\"LIVE\",\"closed\":false}");
        service.onPriceUpdate("{\"stockCode\":\"AMD\",\"market\":\"美股\",\"quoteStatus\":\"CLOSE\",\"closed\":false}");
        service.onPriceUpdate("{\"stockCode\":\"AMD\",\"market\":\"美股\",\"quoteStatus\":\"LIVE\",\"closed\":true}");

        verify(alert, org.mockito.Mockito.times(3)).checkAlertsFor("AMD", "美股");
        verify(notification).queueEvaluation("AMD", "美股");
        verify(notification, never()).queueEvaluation("AMD", "台股");
    }

    @Test
    void 台股事件保持既有通知入列語意() throws Exception {
        assertTrue(PriceStreamService.shouldQueueRadarNotification("台股",
                new ObjectMapper().readTree("{\"quoteStatus\":\"CLOSE\",\"closed\":true}")));
    }
}
