package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TxNightSessionPollerTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 24);

    @Test
    void identifiesNearMonthFromOfficialReportShape() {
        var quote = TxNightSessionPoller.parseReport(report(
                row("202701", "39,962", "▲8.63%", "1,348")
                        + row("202612", "39,829", "▲8.65%", "92,939")), DATE).orElseThrow();
        assertEquals("202612", quote.contractMonth());
        assertEquals(DATE.minusDays(1), quote.nightStartDate());
        assertEquals(new BigDecimal("8.65"), quote.changePct());
    }

    @Test
    void cannotReplaceInvalidFrontMonthWithAValidLaterMonth() {
        assertTrue(TxNightSessionPoller.parseReport(report(
                row("202612", "-", "-", "0")
                        + row("202701", "39,962", "▲8.63%", "1,348")), DATE).isEmpty());
    }

    @Test
    void handlesNegativeMoveAndIgnoresNonMonthlyRows() {
        var quote = TxNightSessionPoller.parseReport(report(
                row("202611W4", "39,962", "▲8.63%", "1,348")
                        + row("202612", "34,829", "▼8.65%", "92,939")), DATE).orElseThrow();
        assertEquals(new BigDecimal("-8.65"), quote.changePct());
    }

    @Test
    void rejectsStaleDateAndRegularSession() {
        String night = report(row("202612", "39,829", "▲8.65%", "92,939"));
        assertTrue(TxNightSessionPoller.parseReport(night, DATE.plusDays(1)).isEmpty());
        assertTrue(TxNightSessionPoller.parseReport(
                night.replace("盤後交易時段行情表", "一般交易時段行情表"), DATE).isEmpty());
    }

    private static String report(String rows) {
        return "<p>日期： 2026/11/24</p><h3>2026/11/23&nbsp;&nbsp;15:00~次日05:00 盤後交易時段行情表</h3>"
                + "<table><tbody>" + rows + "</tbody></table>";
    }

    private static String row(String contract, String last, String change, String volume) {
        return "<tr><td>TX</td><td>" + contract + "</td><td>36,671</td><td>40,324</td><td>36,280</td><td>"
                + last + "</td><td>▲3170</td><td>" + change + "</td><td>" + volume + "</td></tr>";
    }
}
