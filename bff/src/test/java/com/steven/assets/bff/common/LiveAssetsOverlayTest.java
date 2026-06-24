package com.steven.assets.bff.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LiveAssetsOverlay#applyToLatest} per-market 基準日閘門（Task 121）。
 * 重點：最新一筆為「過去日期」時，live-assets（讀 Redis = 最新收盤）不得覆蓋最新列，
 * 否則會把較新交易日收盤洩漏到過去基準日（原 bug：刪掉今日快照後昨日列被今日收盤蓋掉）。
 */
class LiveAssetsOverlayTest {

    private Map<String, Object> row(String date, double tw, double us,
                                    double deposit, double fund, double total) {
        Map<String, Object> m = new HashMap<>();
        m.put("snapshotDate", date);
        m.put("totalTwStockValue", new BigDecimal(String.valueOf(tw)));
        m.put("totalUsStockValue", new BigDecimal(String.valueOf(us)));
        m.put("totalUkStockValue", BigDecimal.ZERO);
        m.put("totalStockValue", new BigDecimal(String.valueOf(tw + us)));
        m.put("totalDeposit", new BigDecimal(String.valueOf(deposit)));
        m.put("totalFundValue", new BigDecimal(String.valueOf(fund)));
        m.put("totalAssets", new BigDecimal(String.valueOf(total)));
        return m;
    }

    private Map<String, Object> live(String date, double twLive, double usLive) {
        Map<String, Object> m = new HashMap<>();
        m.put("snapshotDate", date);
        List<Map<String, Object>> stocks = new ArrayList<>();
        Map<String, Object> a = new HashMap<>();
        a.put("market", "台股");
        a.put("liveValue", new BigDecimal(String.valueOf(twLive)));
        stocks.add(a);
        Map<String, Object> b = new HashMap<>();
        b.put("market", "美股");
        b.put("liveValue", new BigDecimal(String.valueOf(usLive)));
        stocks.add(b);
        m.put("stocks", stocks);
        m.put("liveStockValue", new BigDecimal(String.valueOf(twLive + usLive)));
        m.put("liveTotalAssets", BigDecimal.ZERO);
        return m;
    }

    @Test
    void pastLatest_keepsFrozenCloseValues() {
        // 最新一筆 = 過去日期（2020-06-23）→ 三市場皆非今日 → 完全不覆蓋。
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(row("2020-06-22", 10650741, 1358189, 8000000, 60000, 20405933));
        history.add(row("2020-06-23", 10567772, 1360337, 8000000, 60000, 20325409));
        // live 指向同一筆最新快照，但帶較新收盤值（會洩漏的值）
        Map<String, Object> live = live("2020-06-23", 10320009, 1352282);

        LiveAssetsOverlay.applyToLatest(history, live);

        Map<String, Object> latest = history.get(1);
        assertThat(LiveAssetsOverlay.toBd(latest.get("totalTwStockValue")))
                .isEqualByComparingTo("10567772");   // 維持 6/23 凍結收盤，非 10320009
        assertThat(LiveAssetsOverlay.toBd(latest.get("totalUsStockValue")))
                .isEqualByComparingTo("1360337");
        assertThat(LiveAssetsOverlay.toBd(latest.get("totalAssets")))
                .isEqualByComparingTo("20325409");
    }

    @Test
    void todayLatest_overlaysTwWithLive() {
        // 最新一筆 = 台北今日 → 台股基準日==今日 → 用 live 覆蓋。
        String today = LocalDate.now(ZoneId.of("Asia/Taipei")).toString();
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(row("2020-06-22", 10650741, 1358189, 8000000, 60000, 20405933));
        history.add(row(today, 10567772, 1360337, 8000000, 60000, 20325409));
        Map<String, Object> live = live(today, 10320009, 1352282);

        LiveAssetsOverlay.applyToLatest(history, live);

        assertThat(LiveAssetsOverlay.toBd(history.get(1).get("totalTwStockValue")))
                .isEqualByComparingTo("10320009");    // 台股被 live 覆蓋
    }

    @Test
    void emptyLive_isNoop() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(row("2020-06-23", 100, 0, 0, 0, 100));
        LiveAssetsOverlay.applyToLatest(history, new HashMap<>());  // 空 live → 不動
        assertThat(LiveAssetsOverlay.toBd(history.get(0).get("totalTwStockValue")))
                .isEqualByComparingTo("100");
    }

    @Test
    void nullArgs_doNotThrow() {
        LiveAssetsOverlay.applyToLatest(null, null);
        LiveAssetsOverlay.applyToLatest(new ArrayList<>(), null);
    }
}
