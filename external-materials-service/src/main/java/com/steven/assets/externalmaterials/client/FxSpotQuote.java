package com.steven.assets.externalmaterials.client;

import java.math.BigDecimal;

/** 銀行即期買入/賣出匯率報價（{@link BotFxFetchClient} / {@link MegaFxFetchClient} 共用）。 */
public record FxSpotQuote(BigDecimal spotBuy, BigDecimal spotSell) {}
