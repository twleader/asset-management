package com.steven.assets.externalmaterials.client;

import java.math.BigDecimal;
import java.time.Instant;

/** 銀行即期買入/賣出匯率報價（{@link BotFxFetchClient} / {@link MegaFxFetchClient} 共用）。 */
public record FxSpotQuote(BigDecimal spotBuy, BigDecimal spotSell, Instant sourceUpdatedAt) {

    /** 台銀 CSV 沒有 provider timestamp；保留兩參數建構子供既有歷史流程使用。 */
    public FxSpotQuote(BigDecimal spotBuy, BigDecimal spotSell) {
        this(spotBuy, spotSell, null);
    }
}
