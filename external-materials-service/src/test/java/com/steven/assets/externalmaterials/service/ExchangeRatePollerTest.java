package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.BotFxFetchClient;
import com.steven.assets.externalmaterials.client.FxSpotQuote;
import com.steven.assets.externalmaterials.client.MegaFxFetchClient;
import com.steven.assets.externalmaterials.client.YahooFxFetchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExchangeRatePoller} 台銀 → 兆豐銀行 → Yahoo 備援鏈順序（Task 306）。
 */
class ExchangeRatePollerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);

    private BotFxFetchClient botFx;
    private MegaFxFetchClient megaFx;
    private YahooFxFetchClient yahooFx;
    private HistoricalBackfillService backfill;
    private StockSourceQuery store;
    private ExchangeRatePoller poller;

    @BeforeEach
    void setUp() {
        botFx = mock(BotFxFetchClient.class);
        megaFx = mock(MegaFxFetchClient.class);
        yahooFx = mock(YahooFxFetchClient.class);
        backfill = mock(HistoricalBackfillService.class);
        store = mock(StockSourceQuery.class);
        poller = new ExchangeRatePoller(botFx, megaFx, yahooFx, backfill, store);
    }

    @Test
    @DisplayName("台銀成功 → 直接採用，不打兆豐與 Yahoo")
    void usesBotWhenAvailable() {
        when(botFx.fetchSpot("USD")).thenReturn(
                Optional.of(new FxSpotQuote(new BigDecimal("32.25"), new BigDecimal("32.35"))));

        boolean result = poller.updateOne("USD", TODAY);

        assertThat(result).isTrue();
        verify(store).upsertExchangeRate("USD", TODAY, new BigDecimal("32.25"), new BigDecimal("32.35"));
        verify(megaFx, never()).fetchSpot(anyString());
        verify(yahooFx, never()).fetchUsdTwdMid();
    }

    @Test
    @DisplayName("台銀失敗、兆豐成功 → 採用兆豐真實買賣價，不打 Yahoo")
    void fallsBackToMegaWhenBotEmpty() {
        when(botFx.fetchSpot("USD")).thenReturn(Optional.empty());
        when(megaFx.fetchSpot("USD")).thenReturn(
                Optional.of(new FxSpotQuote(new BigDecimal("32.20"), new BigDecimal("32.40"))));

        boolean result = poller.updateOne("USD", TODAY);

        assertThat(result).isTrue();
        verify(store).upsertExchangeRate("USD", TODAY, new BigDecimal("32.20"), new BigDecimal("32.40"));
        verify(yahooFx, never()).fetchUsdTwdMid();
    }

    @Test
    @DisplayName("台銀、兆豐皆失敗，USD → 退到 Yahoo 中間價（買=賣=中間價）")
    void fallsBackToYahooMidForUsdWhenBotAndMegaEmpty() {
        when(botFx.fetchSpot("USD")).thenReturn(Optional.empty());
        when(megaFx.fetchSpot("USD")).thenReturn(Optional.empty());
        when(yahooFx.fetchUsdTwdMid()).thenReturn(Optional.of(new BigDecimal("32.30")));

        boolean result = poller.updateOne("USD", TODAY);

        assertThat(result).isTrue();
        verify(store).upsertExchangeRate("USD", TODAY, new BigDecimal("32.30"), new BigDecimal("32.30"));
    }

    @Test
    @DisplayName("兆豐涵蓋非 USD 幣別（如 ZAR）：台銀失敗時一樣 fallback 到兆豐")
    void megaFallbackAppliesToNonUsdCurrency() {
        when(botFx.fetchSpot("ZAR")).thenReturn(Optional.empty());
        when(megaFx.fetchSpot("ZAR")).thenReturn(
                Optional.of(new FxSpotQuote(new BigDecimal("1.96"), new BigDecimal("2.06"))));

        boolean result = poller.updateOne("ZAR", TODAY);

        assertThat(result).isTrue();
        verify(store).upsertExchangeRate("ZAR", TODAY, new BigDecimal("1.96"), new BigDecimal("2.06"));
    }

    @Test
    @DisplayName("非 USD 幣別、台銀與兆豐皆失敗 → 不打 Yahoo（僅 USD 有中間價備援），回傳 false")
    void nonUsdCurrencyDoesNotFallBackToYahoo() {
        when(botFx.fetchSpot("ZAR")).thenReturn(Optional.empty());
        when(megaFx.fetchSpot("ZAR")).thenReturn(Optional.empty());

        boolean result = poller.updateOne("ZAR", TODAY);

        assertThat(result).isFalse();
        verify(yahooFx, never()).fetchUsdTwdMid();
        verify(store, never()).upsertExchangeRate(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("三層皆失敗（USD）→ 回傳 false，不寫入")
    void allSourcesFailReturnsFalse() {
        when(botFx.fetchSpot("USD")).thenReturn(Optional.empty());
        when(megaFx.fetchSpot("USD")).thenReturn(Optional.empty());
        when(yahooFx.fetchUsdTwdMid()).thenReturn(Optional.empty());

        boolean result = poller.updateOne("USD", TODAY);

        assertThat(result).isFalse();
    }
}
