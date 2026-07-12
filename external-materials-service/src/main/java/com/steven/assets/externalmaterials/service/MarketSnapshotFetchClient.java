package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.NewsRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 公開資訊「量化快照」組裝（Task 180）：由共用 DB 既有資料組出兩則 {@link NewsRow} 併入每輪公開資訊——
 * <ul>
 *   <li><b>台幣兌美元匯率</b>（{@code exchange_rate_history} 最新 USD 即期買/賣/中間價，{@code ExchangeRatePoller} 已抓）；</li>
 *   <li><b>美股主要指數收盤</b>（{@code us_index_daily_history} 道瓊/標普500/那斯達克/費半最新收盤＋漲跌%，
 *       由 Yahoo Finance 抓、{@code IndexDailyRefreshScheduler} 寫入）。</li>
 * </ul>
 *
 * <p>兩者都是使用者明確要求「一定要有」的公開資訊，但原本只落在各自資料表、<b>不在 {@code news_headline}</b>，
 * 故不會進入 SRPP 公開資訊 JSON、也不在今日股市分析的本地新聞區塊。本 client 把它們組成 NewsRow，交由
 * {@code NewsPoller} 一併 upsert，讓兩個下游（SRPP JSON／今日股市分析）都吃得到（DB 為單一來源）。
 *
 * <p>皆讀「已抓好」的 DB 資料、不另打外部 API（來源網站為台灣銀行／Yahoo Finance 美國站，非中港澳）。
 * {@code publishedAt} 取抓取當下時間以保證恆落在「當日公開資訊」範圍（真實資料日另明列於標題）；逐項 graceful。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSnapshotFetchClient {

    private final StockSourceQuery source;

    /** 美股主要指數（與今日股市分析 US_INDEX_CODES／prompt 命名一致）。 */
    private static final List<String> US_CODES = List.of("DJI", "SPX", "IXIC", "SOX");

    public List<NewsRow> fetchAll() {
        List<NewsRow> out = new ArrayList<>(2);
        try {
            NewsRow fx = buildFxSnapshot();
            if (fx != null) out.add(fx);
        } catch (Exception e) {
            log.warn("公開資訊匯率快照組裝失敗：{}", e.getMessage());
        }
        try {
            NewsRow us = buildUsMarketSnapshot();
            if (us != null) out.add(us);
        } catch (Exception e) {
            log.warn("公開資訊美股指數快照組裝失敗：{}", e.getMessage());
        }
        return out;
    }

    // ===== 台幣兌美元匯率 =====

    private NewsRow buildFxSnapshot() {
        StockSourceQuery.UsdRate r = source.loadLatestUsdRate();
        if (r == null || r.buyRate() == null || r.sellRate() == null) {
            // 使用者要求「一定要有」匯率——缺料屬異常（僅冷啟前、匯率排程尚未寫入才會發生），提高為 warn 以利察覺。
            log.warn("公開資訊匯率快照：exchange_rate_history 無 USD 資料，本輪略過（待 ExchangeRatePoller 寫入）");
            return null;
        }
        BigDecimal mid = r.buyRate().add(r.sellRate())
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        String title = String.format(
                "台幣兌美元(USD/TWD)匯率（資料日 %s）：即期買入 %s、賣出 %s、中間價 %s",
                r.rateDate(), rate(r.buyRate()), rate(r.sellRate()), rate(mid));
        // summary 只放資料出處（會餵入 LLM prompt 與 SRPP JSON）；台銀 WAF / Yahoo 暫定 / FinMind 覆寫屬內部備援機制，留在此註解不外洩。
        String summary = "來源：台灣銀行牌告 USD 即期買入/賣出匯率。";
        return new NewsRow(title, "bot-fx", "https://rate.bot.com.tw/xrt?Lang=zh-TW",
                "fx", "TW", summary, Instant.now());
    }

    // ===== 美股主要指數收盤 =====

    private NewsRow buildUsMarketSnapshot() {
        List<StockSourceQuery.UsIndexClose> closes = new ArrayList<>();
        for (String code : US_CODES) {
            StockSourceQuery.UsIndexClose c = source.loadLatestUsIndexClose(code);
            if (c != null && c.close() != null) closes.add(c);
        }
        if (closes.isEmpty()) {
            // 使用者要求「一定要有」美股資訊——缺料屬異常（僅冷啟前、指數排程尚未寫入才會發生），提高為 warn 以利察覺。
            log.warn("公開資訊美股指數快照：us_index_daily_history 無資料，本輪略過（待 IndexDailyRefreshScheduler 寫入）");
            return null;
        }
        LocalDate session = closes.stream()
                .map(StockSourceQuery.UsIndexClose::tradingDate)
                .max(LocalDate::compareTo).orElse(null);

        StringBuilder title = new StringBuilder("美股主要指數（截至 ").append(session).append(" 收盤）：");
        for (int i = 0; i < closes.size(); i++) {
            StockSourceQuery.UsIndexClose c = closes.get(i);
            if (i > 0) title.append("、");
            title.append(usName(c.indexCode())).append(" ").append(comma(c.close()))
                 .append("（").append(changePct(c.close(), c.prevClose()));
            // 某指數若落後於 session（部分回補 / 單碼抓取失敗）→ 標其自身資料日，避免把舊 session 值掛在最新日期下（Task 180 review 修正）。
            if (c.tradingDate() != null && !c.tradingDate().equals(session)) {
                title.append("，資料日 ").append(c.tradingDate());
            }
            title.append("）");
        }
        String summary = "來源：Yahoo Finance 美股指數日線收盤（道瓊 DJI／標普500 SPX／那斯達克 IXIC／費城半導體 SOX；漲跌% 對前一交易日收盤）。";
        return new NewsRow(title.toString(), "us-index", "https://finance.yahoo.com/world-indices",
                "us-market", "US", summary, Instant.now());
    }

    // ===== helpers =====

    /** 指數代碼 → 中文顯示名（與今日股市分析 prompt 命名一致）。 */
    private static String usName(String code) {
        return switch (code) {
            case "DJI" -> "道瓊";
            case "SPX" -> "標普500";
            case "IXIC" -> "那斯達克";
            case "SOX" -> "費半";
            default -> code;
        };
    }

    /** 匯率顯示：3 位小數（USD/TWD 約 29~33，足夠）。 */
    private static String rate(BigDecimal v) {
        return v.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    /** 指數點數千分位、2 位小數（固定 US locale，避免容器 locale 影響分位/小數符號）。 */
    private static String comma(BigDecimal v) {
        return new DecimalFormat("#,##0.00",
                new java.text.DecimalFormatSymbols(java.util.Locale.US)).format(v);
    }

    /** 漲跌%（對前一交易日收盤）；缺前一日 / 前值為 0 回 "—"。 */
    private static String changePct(BigDecimal close, BigDecimal prev) {
        if (close == null || prev == null || prev.signum() == 0) return "—";
        double pct = close.subtract(prev)
                .divide(prev, 6, RoundingMode.HALF_UP)
                .doubleValue() * 100.0;
        return String.format(java.util.Locale.US, "%+.2f%%", pct);
    }
}
