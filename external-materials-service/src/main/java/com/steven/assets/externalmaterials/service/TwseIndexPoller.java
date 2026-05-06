package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.MacroDataFetchClient;
import com.steven.assets.externalmaterials.client.MacroDataFetchClient.DailyClose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * 台股大盤每日收盤排程：盤後抓 TWSE FMTQIK 當月月報，upsert 每日 TAIEX。
 *
 * TWSE openapi 更新時點不固定（觀察通常隔日才上）；故每日多次嘗試補齊：
 *   14:00 / 17:00 / 隔日 08:30 — 抓「當月 + 上月（跨月時銜接）」並 upsert。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwseIndexPoller {

    private final MacroDataFetchClient macroFetch;
    private final StockSourceQuery store;

    /** 收盤後（13:30 + 30 分鐘） */
    @Scheduled(cron = "0 0 14 * * MON-FRI", zone = "Asia/Taipei")
    public void afternoonRefresh() { refresh("14:00"); }

    /** 收盤後 3.5 小時，給 openapi 多點時間發佈 */
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Taipei")
    public void eveningRefresh() { refresh("17:00"); }

    /** 隔日早盤前最後一次 catch-up（若上面兩次 openapi 還沒更新） */
    @Scheduled(cron = "0 30 8 * * TUE-SAT", zone = "Asia/Taipei")
    public void morningCatchup() { refresh("08:30"); }

    private void refresh(String tag) {
        YearMonth thisMonth = YearMonth.now(ZoneId.of("Asia/Taipei"));
        int total = upsertMonth(thisMonth);
        // 跨月 catch-up：每月 1~5 日也順便補上月（避免 cut-off 漏掉月底交易日）
        if (LocalDate.now(ZoneId.of("Asia/Taipei")).getDayOfMonth() <= 5) {
            total += upsertMonth(thisMonth.minusMonths(1));
        }
        log.info("TWSE index daily 排程 [{}]：upsert {} 筆", tag, total);
    }

    private int upsertMonth(YearMonth ym) {
        List<DailyClose> rows = macroFetch.fetchTwseMonthlyDaily(ym.getYear(), ym.getMonthValue());
        for (DailyClose r : rows) {
            store.upsertTwseIndexDaily(r.tradingDate(), r.close());
        }
        return rows.size();
    }
}
