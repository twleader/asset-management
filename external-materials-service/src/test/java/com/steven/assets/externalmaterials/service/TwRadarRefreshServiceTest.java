package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 249：交易雷達專用的台股行情回補。
 *
 * <p>釘住三件容易做錯的事：13:30–13:32 空窗不得用昨收覆寫 Redis、盤前不得誤觸該守門、
 * 大盤逾時上限不得被 {@code ExecutorService.close()} 抵銷。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwRadarRefreshServiceTest {

    private static final String TW = "台股";
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 29);   // 週三

    @Mock private PricePoller poller;
    @Mock private TaiexIndexPoller taiex;
    @Mock private StockSourceQuery source;
    @Mock private MarketClock clock;

    private TwRadarRefreshService service;

    @BeforeEach
    void setUp() {
        service = new TwRadarRefreshService(poller, taiex, source, clock);
        at(LocalTime.of(14, 0));
        stockCodes("2330", "0050");
    }

    /** 固定「現在」為台北時間 TODAY 的指定時刻。 */
    private void at(LocalTime time) {
        service.timeSource = Clock.fixed(
                LocalDateTime.of(TODAY, time).atZone(MarketClock.TW_ZONE).toInstant(),
                MarketClock.TW_ZONE);
    }

    private void stockCodes(String... codes) {
        doAnswer(inv -> {
            Set<String> tw = inv.getArgument(0);
            tw.addAll(java.util.Arrays.asList(codes));
            return null;
        }).when(source).collectTwRadarCodes(any());
    }

    // ── 開盤中 ────────────────────────────────────────────────

    @Test
    void 開盤中個股與大盤都抓() {
        when(clock.isTwMarketOpen()).thenReturn(true);

        TwRadarRefreshService.Summary s = service.refresh();

        verify(poller).updatePrices(any(), eq(TW), eq(false));
        verify(taiex).updateOnce();
        verify(poller, never()).syncClosedFromDb(any(), anyString());
        assertTrue(s.performed());
        assertEquals(1, s.indexUpdated());
    }

    /**
     * 收集器已排除 0000，這裡是服務層的防禦性剔除；漏掉就會拿大盤代號去打 mis.twse.com.tw。
     */
    @Test
    void 大盤代號不得混進個股抓取清單() {
        when(clock.isTwMarketOpen()).thenReturn(true);
        stockCodes("2330", "0000", "0050");

        service.refresh();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(poller).updatePrices(captor.capture(), eq(TW), eq(false));
        assertFalse(captor.getValue().contains("0000"));
        assertTrue(captor.getValue().contains("2330"));
    }

    @Test
    void 大盤失敗不連累個股() {
        when(clock.isTwMarketOpen()).thenReturn(true);
        doAnswer(inv -> { throw new RuntimeException("Yahoo 429"); }).when(taiex).updateOnce();

        TwRadarRefreshService.Summary s = service.refresh();

        verify(poller).updatePrices(any(), eq(TW), eq(false));
        assertTrue(s.performed());
        assertEquals(0, s.indexUpdated());
    }

    /**
     * 大盤逾時上限必須真的生效。
     *
     * <p>若實作把 executor 包進 try-with-resources，{@code close()} 會 awaitTermination
     * 直到大盤那條跑完（5 秒），這條就會失敗——這是該缺陷的唯一迴歸守門。</p>
     */
    @Test
    void 大盤逾時不得拖住整體() throws Exception {
        when(clock.isTwMarketOpen()).thenReturn(true);
        service.taiexWaitSeconds = 1;
        doAnswer(inv -> {
            Thread.sleep(5000);
            return null;
        }).when(taiex).updateOnce();

        long t0 = System.nanoTime();
        TwRadarRefreshService.Summary s = service.refresh();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 3000, "大盤逾時後不得繼續等待，實測 " + elapsedMs + " ms");
        verify(poller).updatePrices(any(), eq(TW), eq(false));
        assertTrue(s.performed());
        assertEquals(0, s.indexUpdated());
    }

    @Test
    void 併發時立即回busy不抓取() throws Exception {
        when(clock.isTwMarketOpen()).thenReturn(true);
        java.util.concurrent.CountDownLatch inFlight = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            inFlight.countDown();
            release.await();
            return null;
        }).when(poller).updatePrices(any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        Thread first = new Thread(service::refresh);
        first.start();
        assertTrue(inFlight.await(5, java.util.concurrent.TimeUnit.SECONDS));

        TwRadarRefreshService.Summary second = service.refresh();
        release.countDown();
        first.join(5000);

        assertTrue(second.busy());
        assertFalse(second.performed());
        verify(poller, org.mockito.Mockito.times(1)).updatePrices(any(), eq(TW), eq(false));
    }

    // ── 休市 ─────────────────────────────────────────────────

    @Test
    void 非交易日照常同步收盤價() {
        when(clock.isTwMarketOpen()).thenReturn(false);
        when(clock.isTradingDay(TW, TODAY)).thenReturn(false);

        TwRadarRefreshService.Summary s = service.refresh();

        verify(poller).syncClosedFromDb(any(), eq(TW));
        verify(taiex, never()).updateOnce();
        assertFalse(s.skippedPendingClose());
    }

    /** 13:30–13:32：DB 還沒有今日收盤，同步會把昨收寫回 Redis 並被 13:32 dump 當成今日收盤。 */
    @Test
    void 收盤未落檔的空窗不得同步() {
        when(clock.isTwMarketOpen()).thenReturn(false);
        when(clock.isTradingDay(TW, TODAY)).thenReturn(true);
        at(LocalTime.of(13, 31));
        when(source.findMaxTradingDate(anyString(), eq(TW))).thenReturn(Optional.of(TODAY.minusDays(1)));

        TwRadarRefreshService.Summary s = service.refresh();

        verify(poller, never()).syncClosedFromDb(any(), anyString());
        assertTrue(s.skippedPendingClose());
    }

    @Test
    void 收盤已落檔後恢復同步() {
        when(clock.isTwMarketOpen()).thenReturn(false);
        when(clock.isTradingDay(TW, TODAY)).thenReturn(true);
        at(LocalTime.of(13, 35));
        when(source.findMaxTradingDate(anyString(), eq(TW))).thenReturn(Optional.of(TODAY));

        TwRadarRefreshService.Summary s = service.refresh();

        verify(poller).syncClosedFromDb(any(), eq(TW));
        assertFalse(s.skippedPendingClose());
    }

    /**
     * 盤前不得誤觸守門：08:30 同樣是「休市 ＋ 交易日 ＋ 今日收盤未落 DB」，
     * 但此時 DB 有 16:00 FinMind 校正過的權威收盤，Redis 才是該被同步的一方。
     */
    @Test
    void 盤前照常同步不得誤判為空窗() {
        when(clock.isTwMarketOpen()).thenReturn(false);
        when(clock.isTradingDay(TW, TODAY)).thenReturn(true);
        at(LocalTime.of(8, 30));
        when(source.findMaxTradingDate(anyString(), eq(TW))).thenReturn(Optional.of(TODAY.minusDays(1)));

        TwRadarRefreshService.Summary s = service.refresh();

        verify(poller).syncClosedFromDb(any(), eq(TW));
        assertFalse(s.skippedPendingClose());
    }

    /** 無任何歷史列（新掛牌／剛加入觀察清單）不得被當成「已有今日收盤」而納入同步。 */
    @Test
    void 無歷史列的代號不納入同步() {
        when(clock.isTwMarketOpen()).thenReturn(false);
        when(clock.isTradingDay(TW, TODAY)).thenReturn(true);
        at(LocalTime.of(14, 0));
        when(source.findMaxTradingDate(eq("2330"), eq(TW))).thenReturn(Optional.of(TODAY));
        when(source.findMaxTradingDate(eq("0050"), eq(TW))).thenReturn(Optional.empty());

        service.refresh();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(poller).syncClosedFromDb(captor.capture(), eq(TW));
        assertTrue(captor.getValue().contains("2330"));
        assertFalse(captor.getValue().contains("0050"));
    }

    /** 全庫無台股標的時不得回出「今日收盤價尚未落檔」的假訊息。 */
    @Test
    void 無台股標的時不回報空窗() {
        when(clock.isTwMarketOpen()).thenReturn(false);
        when(clock.isTradingDay(TW, TODAY)).thenReturn(true);
        at(LocalTime.of(14, 0));
        doAnswer(inv -> null).when(source).collectTwRadarCodes(any());

        TwRadarRefreshService.Summary s = service.refresh();

        verify(poller, never()).syncClosedFromDb(any(), anyString());
        assertFalse(s.skippedPendingClose());
        assertEquals(0, s.twStocks());
    }
}
