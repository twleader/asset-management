package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.repository.TradingRadarNotificationRecipientRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingRadarNotificationDispatcherTest {

    @Test
    void 摘要信每筆明示決策市場() {
        TradingRadarNotificationRecipientRepository recipients = mock(TradingRadarNotificationRecipientRepository.class);
        EmailService email = mock(EmailService.class);
        when(email.isEnabled()).thenReturn(true);
        when(recipients.findActiveEmails(7L)).thenReturn(List.of("user@example.com"));
        TradingRadarNotificationDispatcher dispatcher = new TradingRadarNotificationDispatcher(recipients, email);

        dispatcher.enqueue(7L, usDecision(), List.of("續抱"));
        dispatcher.flush();

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(org.mockito.ArgumentMatchers.eq(List.of("user@example.com")),
                org.mockito.ArgumentMatchers.anyString(), html.capture(), org.mockito.ArgumentMatchers.anyMap());
        assertTrue(html.getValue().contains("<strong>市場：</strong>美股"));
    }

    private static TradingRadarDto.StockDecision usDecision() {
        return new TradingRadarDto.StockDecision(
                "AMD", "AMD", "美股", "STOCK", false, false,
                "HOLD", "續抱", 70,
                "NONE", "無", List.of(), List.of(),
                true, BigDecimal.TEN, BigDecimal.ONE,
                "LIVE", "2026-08-09T10:00:00-04:00", "2026-08-09",
                null, null, null, null, null,
                null, null, null, null, null,
                List.of(), List.of(), null, null, null,
                null, null, null, null, null, null);
    }
}
