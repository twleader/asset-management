package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.CommodityFetchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 油價／金價每日回補排程（Requirement 40）。
 *
 * WTI／Brent／COMEX 黃金掛牌於紐約，收盤（NY 17:00 前後）對應台北隔日凌晨，
 * 故排在台北時間每日 06:30 增量補前一交易日收盤。週日不跑（紐約週六無交易日可補）。
 *
 * 十年歷史的首次補齊由 {@link HistoricalBackfillService#startupBackfill()} 負責，本排程只做日常增量。
 * 單一標的失敗只記 log，不影響其他標的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommodityPricePoller {

    private final HistoricalBackfillService backfill;

    @Value("${commodity.enabled:true}")
    private boolean enabled;

    /** 每日 06:30（台北）增量補油金價收盤；紐約收盤已落在前一夜。 */
    @Scheduled(cron = "0 30 6 * * MON-SAT", zone = "Asia/Taipei")
    public void dailyCommodityUpdate() {
        if (!enabled) return;
        log.info("排程：每日回補油價／金價收盤");
        // 增量起點：無資料時才用得到 since，正常情況由 max(price_date)+1 決定
        LocalDate since = LocalDate.now().minusYears(10);
        for (String code : CommodityFetchClient.SYMBOLS.keySet()) {
            try {
                int n = backfill.backfillCommodity(code, since);
                if (n > 0) log.info("油金價回補 {} 共 {} 筆", code, n);
            } catch (Exception e) {
                log.warn("油金價回補 {} 失敗: {}", code, e.getMessage());
            }
        }
    }
}
