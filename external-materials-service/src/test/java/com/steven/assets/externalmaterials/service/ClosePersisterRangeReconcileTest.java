package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.TwOfficialCloseClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Task 572：台股官方收盤歷史區間對帳。 */
class ClosePersisterRangeReconcileTest {

    private final PriceFetchClient priceClient = mock(PriceFetchClient.class);
    private final StockSourceQuery source = mock(StockSourceQuery.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PriceCacheWriter cache = mock(PriceCacheWriter.class);
    private final MarketCalendar calendar = mock(MarketCalendar.class);
    private final TwOfficialCloseClient official = mock(TwOfficialCloseClient.class);
    private final List<Long> sleeps = new ArrayList<>();
    private ClosePersister persister;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        persister = new ClosePersister(priceClient, source, redis, cache, calendar, official);
        persister.timeSource = Clock.fixed(Instant.parse("2026-11-12T02:00:00Z"), ZoneId.of("UTC"));
        persister.sleeper = sleeps::add;
        when(calendar.isTwTradingDay(any())).thenAnswer(inv -> ((LocalDate) inv.getArgument(0)).getDayOfWeek().getValue() <= 5);
        doAnswer(inv -> {
            ((Set<String>) inv.getArgument(0)).addAll(List.of("0056", "00878", "0000"));
            return null;
        }).when(source).collectHeldStockCodes(anySet(), anySet(), anySet());
        when(source.upsertVerifiedHistory(anyString(), eq("台股"), any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(true);
        when(source.hasTrustedTwClose(anyString(), any())).thenReturn(true);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private static TwOfficialCloseClient.OfficialCloseBatch batch(LocalDate d, String... codes) {
        Map<String, TwOfficialCloseClient.OfficialClose> rows = new LinkedHashMap<>();
        for (String c : codes) {
            rows.put(c, new TwOfficialCloseClient.OfficialClose(c, c, d, new BigDecimal("40"), new BigDecimal("41"),
                    new BigDecimal("39"), new BigDecimal("40.5"), 1000L, StockSourceQuery.TWSE_MI_INDEX));
        }
        return new TwOfficialCloseClient.OfficialCloseBatch(rows, List.of());
    }

    @Test
    void 只處理交易日_間隔三秒_寫入官方來源且不寫Redis() {
        LocalDate fri = LocalDate.of(2026, 11, 6), mon = LocalDate.of(2026, 11, 9), tue = LocalDate.of(2026, 11, 10);
        for (LocalDate d : List.of(fri, mon, tue)) when(official.fetch(d)).thenReturn(batch(d, "0056", "00878"));

        ClosePersister.TwCloseRangeReconciliation r = persister.reconcileTwOfficialCloseRange(fri, tue);

        assertThat(r.tradingDays()).isEqualTo(3);
        assertThat(r.daysWithTrusted()).isEqualTo(3);
        assertThat(r.rowsTrusted()).isEqualTo(6);
        assertThat(r.missingByDate()).isEmpty();
        assertThat(r.interrupted()).isFalse();
        assertThat(sleeps).containsExactly(3000L, 3000L);
        verify(official, never()).fetch(LocalDate.of(2026, 11, 7));
        verify(official, never()).fetch(LocalDate.of(2026, 11, 8));
        verify(source, times(6)).upsertVerifiedHistory(anyString(), eq("台股"), any(), any(), any(), any(), any(), any(),
                eq(StockSourceQuery.TWSE_MI_INDEX));
        verify(source, never()).upsertVerifiedHistory(eq("0000"), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(cache);
    }

    @Test
    void 單日例外不中斷整批_缺檔代號列入該日() {
        LocalDate fri = LocalDate.of(2026, 11, 6), mon = LocalDate.of(2026, 11, 9), tue = LocalDate.of(2026, 11, 10);
        when(official.fetch(fri)).thenThrow(new IllegalStateException("db down"));
        when(official.fetch(mon)).thenReturn(batch(mon, "0056"));
        when(official.fetch(tue)).thenReturn(batch(tue, "0056", "00878"));
        when(source.hasTrustedTwClose("00878", mon)).thenReturn(false);

        ClosePersister.TwCloseRangeReconciliation r = persister.reconcileTwOfficialCloseRange(fri, tue);

        assertThat(r.tradingDays()).isEqualTo(3);
        assertThat(r.missingByDate().get(fri)).singleElement().asString().startsWith("ERROR: ");
        assertThat(r.missingByDate().get(mon)).containsExactly("00878");
        assertThat(r.missingByDate()).doesNotContainKey(tue);
        assertThat(r.daysWithTrusted()).isEqualTo(2);
        assertThat(r.rowsTrusted()).isEqualTo(3);
        assertThat(r.missingByDate().values()).noneMatch(list -> list.contains("0000"));
        verify(official).fetch(tue);
    }

    @Test
    void 參數驗證失敗不呼叫官方來源() {
        LocalDate today = LocalDate.of(2026, 11, 12);
        assertThatThrownBy(() -> persister.reconcileTwOfficialCloseRange(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 9)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> persister.reconcileTwOfficialCloseRange(LocalDate.of(2026, 11, 10), today))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> persister.reconcileTwOfficialCloseRange(LocalDate.of(2026, 11, 10), today.plusDays(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> persister.reconcileTwOfficialCloseRange(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 11, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> persister.reconcileTwOfficialCloseRange(null, LocalDate.of(2026, 11, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(official);

        when(official.fetch(any())).thenAnswer(inv -> batch(inv.getArgument(0), "0056"));
        ClosePersister.TwCloseRangeReconciliation ok =
                persister.reconcileTwOfficialCloseRange(LocalDate.of(2025, 10, 2), LocalDate.of(2026, 11, 5));
        assertThat(ok.tradingDays()).isPositive();
    }

    @Test
    void 被中斷時還原旗標並提前回傳() {
        LocalDate fri = LocalDate.of(2026, 11, 6), mon = LocalDate.of(2026, 11, 9);
        when(official.fetch(fri)).thenReturn(batch(fri, "0056", "00878"));
        persister.sleeper = millis -> { throw new InterruptedException("stop"); };

        ClosePersister.TwCloseRangeReconciliation r =
                persister.reconcileTwOfficialCloseRange(fri, LocalDate.of(2026, 11, 10));

        assertThat(r.interrupted()).isTrue();
        assertThat(r.tradingDays()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(official, never()).fetch(mon);
    }
}
