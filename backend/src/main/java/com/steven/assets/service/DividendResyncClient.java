package com.steven.assets.service;

/**
 * 「請 external-materials-service 重抓某一檔的股利 evidence」的 outbound port
 * （Requirement 94 / Task 357.3a 第一段）。
 *
 * <p><b>存在的理由是依賴反轉，不是為了多一層。</b>{@link DividendBackfillService} 是純業務邏輯
 * （分批、續跑、逐檔失敗記錄），必須能在不啟動 Spring context、不連網路的情況下單元測試；
 * 具體的 WebClient 實作 {@code com.steven.assets.client.ExternalDividendResyncClient} 屬於最外層。
 * 這與既有的 {@link TreasuryYieldClient} ／ {@code ExternalTreasuryYieldClient} 同一形狀。</p>
 *
 * <p><b>business-services 不得因此直連任何外部 API（Task 357.8a）。</b>本 port 的唯一實作打的是
 * docker network 內部的 {@code POST /internal/dividend/sync}，真正對 FinMind 的 IO 仍然只發生在
 * external-materials-service；這是服務間呼叫，不是新增第二條抓取路徑。</p>
 */
public interface DividendResyncClient {

    /**
     * 重抓單檔股利 evidence 並 append 進 immutable snapshot。
     *
     * @return 本次 observation 的事件筆數（{@code /internal/dividend/sync} 回傳的 {@code written}）
     * @throws RuntimeException 抓取失敗；呼叫端負責逐檔記錄，<b>不得</b>在此靜默吞掉
     */
    int resyncOne(String code, String market);
}
