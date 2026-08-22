package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadarObservationResolverTest {

    private static final Instant TW_DECISION = Instant.parse("2026-08-07T04:00:00Z");

    @Test
    void preCloseDecisionFallsBackToLatestCompletedSessionNotFutureUntrustedRow() {
        List<StockPriceHistory> rows = List.of(
                row("2026-08-07", "999", null),
                row("2026-08-06", "100", "TWSE_MI_INDEX"));

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, live("2026-08-06", "1"), "台股", TW_DECISION);

        assertEquals(new BigDecimal("100"), accepted.value());
        assertEquals(LocalDate.of(2026, 8, 6), accepted.tradingDate());
        assertEquals(RadarObservationResolver.Quality.COMPLETED_CLOSE, accepted.quality());
        assertFalse(accepted.liveAccepted());
        assertEquals(1, accepted.verifiedCompletedRows().size());
    }

    @Test
    void futureLiveCannotBecomeAcceptedPrice() {
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        List.of(), live("2026-08-09", "200"), "台股", TW_DECISION);

        assertNull(accepted.value());
        assertEquals(RadarObservationResolver.Quality.INVALID, accepted.quality());
        assertEquals("CLOSE_PENDING", accepted.quoteStatus());
    }

    @Test
    void sameMarketDateLiveIsAcceptedAndSharedWithTechnicalSequence() {
        List<StockPriceHistory> rows = List.of(row("2026-08-06", "100", "TWSE_MI_INDEX"));

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, live("2026-08-07", "101"), "台股", TW_DECISION);

        assertTrue(accepted.liveAccepted());
        assertEquals(new BigDecimal("101"), accepted.value());
        assertEquals(LocalDate.of(2026, 8, 7), accepted.tradingDate());
        assertEquals(1, accepted.verifiedCompletedRows().size());
        assertEquals(LocalDate.of(2026, 8, 6), accepted.verifiedCompletedRows().get(0).getTradingDate());
    }

    @Test
    void twCloseSourceMissingIsNotTrustedFallback() {
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        List.of(row("2026-08-06", "100", null)),
                        null, "台股", TW_DECISION);

        assertNull(accepted.value());
        assertEquals(RadarObservationResolver.Quality.MISSING, accepted.quality());
        assertEquals("CLOSE_PENDING", accepted.quoteStatus());
    }

    @Test
    void monthOldCompletedCloseIsMissingAndCannotDriveRules() {
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        List.of(row("2026-07-01", "100", "TWSE_MI_INDEX")),
                        null, "台股", TW_DECISION);

        assertNull(accepted.value());
        assertEquals(RadarObservationResolver.Quality.MISSING, accepted.quality());
        assertEquals("CLOSE_PENDING", accepted.quoteStatus());
    }

    @Test
    void weekendDecisionFallsBackToLatestCompletedSessionAndRejectsWeekendLive() {
        Instant saturday = Instant.parse("2026-08-08T04:00:00Z");
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        List.of(row("2026-08-07", "100", "TWSE_MI_INDEX")),
                        live("2026-08-08", "101"), "台股", saturday);

        assertEquals(new BigDecimal("100"), accepted.value());
        assertEquals(LocalDate.of(2026, 8, 7), accepted.tradingDate());
        assertEquals(RadarObservationResolver.Quality.COMPLETED_CLOSE, accepted.quality());
        assertEquals("VERIFIED_CLOSE", accepted.quoteStatus());
    }

    @Test
    void mondayPreOpenUsesFridayCompletedSessionButAcceptsCurrentLive() {
        Instant mondayPreOpen = Instant.parse("2026-08-10T00:30:00Z"); // 08:30 Taipei
        List<StockPriceHistory> rows = List.of(row("2026-08-07", "100", "TWSE_MI_INDEX"));
        RadarObservationResolver.AcceptedPrice fallback =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, null, "台股", mondayPreOpen,
                        date -> date.getDayOfWeek().getValue() <= 5);
        assertEquals(LocalDate.of(2026, 8, 7), fallback.tradingDate());
        assertEquals(new BigDecimal("100"), fallback.value());

        RadarObservationResolver.AcceptedPrice live =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, live("2026-08-10", "101"), "台股", mondayPreOpen,
                        date -> date.getDayOfWeek().getValue() <= 5);
        assertTrue(live.liveAccepted());
        assertEquals(LocalDate.of(2026, 8, 10), live.tradingDate());
    }

    @Test
    void marketCalendarHolidayFallsBackToPriorSessionAndRejectsHolidayLive() {
        Instant holiday = Instant.parse("2026-08-10T04:00:00Z"); // Monday, custom holiday
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 8, 10));
        List<StockPriceHistory> rows = List.of(row("2026-08-07", "100", "TWSE_MI_INDEX"));
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, live("2026-08-10", "101"), "台股", holiday,
                        date -> date.getDayOfWeek().getValue() <= 5 && !holidays.contains(date));
        assertFalse(accepted.liveAccepted());
        assertEquals(LocalDate.of(2026, 8, 7), accepted.tradingDate());
        assertEquals(new BigDecimal("100"), accepted.value());
    }

    // ───────── Task 319：provenance 白名單只界定 verified，不得當技術序列的納入判準 ─────────

    /** 台股盤後 14:00（收盤 13:30 之後）：completedSession 即當日 2026-08-07。 */
    private static final Instant TW_AFTER_CLOSE = Instant.parse("2026-08-07T06:00:00Z");

    @Test
    void indicatorSeriesKeepsNullCloseSourceHistoryWhileVerifiedKeepsOnlyWhitelistedRow() {
        // (i) 這正是線上 DB 的形狀：Task 290 之前的台股歷史列 close_source 一律為 null
        //     且明令不得回填，只有最近幾個對帳過的交易日有來源。
        List<StockPriceHistory> rows = List.of(
                row("2026-08-07", "104", "TWSE_MI_INDEX"),
                row("2026-08-06", "103", null),
                row("2026-08-05", "102", null),
                row("2026-08-04", "101", null));

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, null, "台股", TW_AFTER_CLOSE);

        assertEquals(
                List.of(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 6),
                        LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 4)),
                dates(accepted.indicatorSeriesRows()),
                "來源為 null 的歷史列必須全部留在技術序列裡，否則 MA20／60／240 全算不出來");
        assertEquals(List.of(LocalDate.of(2026, 8, 7)), dates(accepted.verifiedCompletedRows()),
                "verified 仍只認命中白名單的那一列（Task 290 的可見價語意不變）");
        assertEquals(new BigDecimal("104"), accepted.value());
        assertEquals("VERIFIED_CLOSE", accepted.quoteStatus());
    }

    @Test
    void completedSessionRowWithoutTrustedSourceIsExcludedFromIndicatorSeriesButPriorRowsStay() {
        // (ii) 當日尚未官方對帳完成時，那一根可能是盤中誤寫值，不得當成完成收盤 K；
        //      但更早的 null 來源歷史列與它無關，必須留下。
        //      live 是必要的：verified 那份沒有當日完成列時 resolveAcceptedPrice 會回 missing()，
        //      而 missing() 的兩個欄位都是空的，就驗不到分列結果。
        List<StockPriceHistory> rows = List.of(
                row("2026-08-07", "104", null),
                row("2026-08-06", "103", null),
                row("2026-08-05", "102", null));

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, live("2026-08-07", "105"), "台股", TW_AFTER_CLOSE);

        assertEquals(
                List.of(LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 5)),
                dates(accepted.indicatorSeriesRows()),
                "completedSession 當日的未驗證列須剔除，更早的 null 來源列仍納入");
        assertTrue(accepted.verifiedCompletedRows().isEmpty());
    }

    @Test
    void verifiedCompletedRowsStillMatchesLegacyTrustedWhitelist() {
        // (iii) 回歸保護：拆欄位不得順手放寬 verified 判準。
        //       白名單三個來源全收、其餘來源與 null 全擋、未來列與非正價一律排除。
        List<StockPriceHistory> rows = List.of(
                row("2026-08-10", "110", "TWSE_MI_INDEX"),   // 未來列（> completedSession）
                row("2026-08-07", "104", "TPEX_DAILY_CLOSE"),
                row("2026-08-06", "103", "FINMIND_TW_CLOSE"),
                row("2026-08-05", "102", "YAHOO_TW_INTRADAY"),
                row("2026-08-04", "101", null),
                row("2026-08-03", "0", "TWSE_MI_INDEX"));    // 非正價

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, null, "台股", TW_AFTER_CLOSE);

        assertEquals(
                List.of(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 6)),
                dates(accepted.verifiedCompletedRows()),
                "verified 仍是「日期 ≤ session ＋ 正價 ＋ 命中台股白名單」，逐列與拆分前相同");
        assertEquals(
                List.of(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 6),
                        LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 4)),
                dates(accepted.indicatorSeriesRows()),
                "技術序列同樣排除未來列與非正價，但不看 close_source");
    }

    @Test
    void nonTwMarketHasIdenticalVerifiedAndIndicatorRows() {
        // (iv) 白名單只對台股生效，美股兩份必須一致——修法不得意外改動美股既有行為。
        Instant usAfterClose = Instant.parse("2026-08-07T21:00:00Z"); // 17:00 ET
        List<StockPriceHistory> rows = List.of(
                usRow("2026-08-07", "104", "NASDAQ_REDIS_CLOSE"),
                usRow("2026-08-06", "103", null),
                usRow("2026-08-05", "102", null));

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, null, "美股", usAfterClose);

        assertEquals(
                List.of(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 5)),
                dates(accepted.verifiedCompletedRows()));
        assertEquals(dates(accepted.verifiedCompletedRows()), dates(accepted.indicatorSeriesRows()));
    }

    @Test
    void indicatorSeriesIsCappedAt241RowsSoPercentileDenominatorNeverDriftsWithFetchSize() {
        // 319.4 的契約：取數多抓是剔除緩衝，序列上限固定 241
        //（confirm(closes, 240) 需要 241 根；放大取數卻不截斷會靜默改掉 ma60BiasPercentile 的分母）。
        List<StockPriceHistory> rows = new java.util.ArrayList<>();
        LocalDate session = LocalDate.of(2026, 8, 7);
        for (int i = 0; i < 250; i++) {
            rows.add(row(session.minusDays(i).toString(), "100", i == 0 ? "TWSE_MI_INDEX" : null));
        }

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, null, "台股", TW_AFTER_CLOSE);

        assertEquals(241, accepted.indicatorSeriesRows().size());
        assertEquals(session, accepted.indicatorSeriesRows().get(0).getTradingDate(),
                "截斷須從最舊的一端砍，最新一根必須保留");
    }

    @Test
    void weeklySeriesKeepsEveryEligibleRowAndIndicatorSeriesIsItsPrefix() {
        // Task 356.4b：兩份序列套用<b>完全相同</b>的過濾，唯一差別是週K 那份不做 241 截斷。
        List<StockPriceHistory> rows = new java.util.ArrayList<>();
        LocalDate session = LocalDate.of(2026, 8, 7);
        for (int i = 0; i < 500; i++) {
            rows.add(row(session.minusDays(i).toString(), "100", i == 0 ? "TWSE_MI_INDEX" : null));
        }

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, null, "台股", TW_AFTER_CLOSE);

        assertEquals(500, accepted.weeklySeriesRows().size());
        assertEquals(RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS,
                accepted.indicatorSeriesRows().size());
        assertEquals(dates(accepted.indicatorSeriesRows()),
                dates(accepted.weeklySeriesRows().subList(
                        0, RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS)),
                "日K 契約序列必須恆為週K 長序列的前綴，兩者不可能分歧");
    }

    @Test
    void weeklySeriesAppliesTheSameProvenanceAndFutureRowFiltersAsTheIndicatorSeries() {
        // 盤中決策：completedSession 為前一交易日，當日那一根盤中誤寫值兩份序列都必須剔除；
        // 更早的歷史列則一律不看 close_source（Task 290 明定台股歷史列留 null）。
        List<StockPriceHistory> rows = List.of(
                row("2026-08-07", "999", null),
                row("2026-08-06", "100", "TWSE_MI_INDEX"),
                row("2026-08-05", "101", null));

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, null, "台股", TW_DECISION);

        assertEquals(dates(accepted.indicatorSeriesRows()), dates(accepted.weeklySeriesRows()));
        assertEquals(List.of(LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 5)),
                dates(accepted.weeklySeriesRows()));
    }

    @Test
    void compatibilityConstructorMirrorsIndicatorRowsIntoTheWeeklySeries() {
        // 自組 snapshot 的呼叫端（回測）只備妥一份序列，週K 序列即該序列，行為與擴窗前相同。
        List<StockPriceHistory> series = List.of(row("2026-08-06", "100", "TWSE_MI_INDEX"));

        RadarObservationResolver.AcceptedPrice accepted = new RadarObservationResolver.AcceptedPrice(
                new BigDecimal("100"), LocalDate.of(2026, 8, 6), null, "TEST",
                RadarObservationResolver.Quality.COMPLETED_CLOSE, false, null,
                series, series, null);

        assertEquals(series, accepted.weeklySeriesRows());
    }

    private static List<LocalDate> dates(List<StockPriceHistory> rows) {
        return rows.stream().map(StockPriceHistory::getTradingDate).toList();
    }

    private static StockPriceHistory row(String date, String close, String source) {
        return StockPriceHistory.builder()
                .stockCode("2330")
                .market("台股")
                .tradingDate(LocalDate.parse(date))
                .closePrice(new BigDecimal(close))
                .closeSource(source)
                .build();
    }

    private static StockPriceHistory usRow(String date, String close, String source) {
        return StockPriceHistory.builder()
                .stockCode("AAPL")
                .market("美股")
                .tradingDate(LocalDate.parse(date))
                .closePrice(new BigDecimal(close))
                .closeSource(source)
                .build();
    }

    private static PriceQueryService.LivePrice live(String date, String price) {
        return new PriceQueryService.LivePrice(
                "2330", null, "台股", new BigDecimal(price), null, null, null,
                null, null, null, null, null, null, date, "2026-08-08T04:00:00Z",
                false, "REDIS", "LIVE");
    }
}
