package com.steven.assets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 日曆事件 DESCRIPTION 的單檔摘要行（Requirement 23 / Task 248）。
 * 這一行與信件正文取用同一組 latest / labels，格式錯了會直接反映在使用者的日曆上。
 */
class AlertCalendarLineTest {

    @Test
    @DisplayName("單一條件")
    void singleLabel() {
        assertEquals("台積電 (2330 台股) — 跌破季線 觸發價 1085",
                AlertNotificationDispatcher.calendarLine(
                        "台積電", "2330", "台股", List.of("跌破季線"), new BigDecimal("1085")));
    }

    @Test
    @DisplayName("同股多條件以「、」串接，順序即傳入順序")
    void multipleLabelsJoined() {
        LinkedHashSet<String> labels = new LinkedHashSet<>(List.of("跌破季線", "K 值低於 20"));
        assertEquals("台積電 (2330 台股) — 跌破季線、K 值低於 20 觸發價 1085",
                AlertNotificationDispatcher.calendarLine(
                        "台積電", "2330", "台股", labels, new BigDecimal("1085")));
    }

    @Test
    @DisplayName("價格為 null 時顯示 -（沿用信件正文的 formatNumber 行為）")
    void nullPriceRendersDash() {
        assertEquals("台積電 (2330 台股) — 跌破季線 觸發價 -",
                AlertNotificationDispatcher.calendarLine(
                        "台積電", "2330", "台股", List.of("跌破季線"), null));
    }

    @Test
    @DisplayName("價格去掉尾隨零，不用科學記號")
    void stripsTrailingZeros() {
        assertEquals("Apple (AAPL 美股) — 突破月線 觸發價 54.35",
                AlertNotificationDispatcher.calendarLine(
                        "Apple", "AAPL", "美股", List.of("突破月線"), new BigDecimal("54.350")));
    }
}
