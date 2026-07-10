package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 台股颱風假 / 臨時休市偵測（Requirement 7 / Task 160）。
 *
 * <p>證交所颱風天是否休市，法規上取決於「臺北市政府是否宣布停止上班」；此類臨時休市**不在** TWSE 年度
 * holidaySchedule（年初即公告的固定假期）中，故由本服務每早爬行政院人事行政總處（DGPA）「天然災害停止
 * 上班及上課情形」公告，判臺北市當日是否停止上班（且涵蓋 09:00–13:30 交易時段），命中即 upsert 至
 * {@code tw_market_closure}，並由 {@link MarketDataFetchService#getTwHolidays} read-time union 進台股假日
 * 唯一入口，令 market-status / 抓價 / 收盤 / 警示 / 備份 / 分析 / 交易日曆與國定假日同一 cascade 一體休市。
 *
 * <p>退化：DGPA 抓取失敗 / 查無臺北市狀態 → 保守維持交易日（與 TWSE 假日抓取失敗一致）；只新增臨時休市、
 * 不覆寫既有 TWSE 固定假日。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwTyphoonClosureService {

    static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final String DGPA_URL = "https://www.dgpa.gov.tw/typh/daily/nds.html";
    // DGPA 為 .gov.tw 站，短 UA 即可（比照全站對外抓取慣例，避免長 Chrome UA 被部分 WAF 阻擋）。
    private static final String UA = "Mozilla/5.0";
    // 台股收盤 13:30；僅在此之後才起的停班不影響交易時段。
    private static final int TW_CLOSE_MINUTES = 13 * 60 + 30;

    private final TwMarketClosureQuery store;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** DB 台股臨時休市日快取（{@code yyyy-MM-dd → reason}）；啟動與每次 upsert 後 reload。 */
    private volatile Map<String, String> closures = Map.of();

    @EventListener(ApplicationReadyEvent.class)
    void loadFromDb() {
        try {
            closures = store.findAll();
            if (!closures.isEmpty()) log.info("載入台股臨時休市日 {} 筆：{}", closures.size(), closures.keySet());
        } catch (Exception e) {
            // 冷啟時 backend Liquibase 可能尚未建表 → 保持空集合，poller / self-heal 之後會 reload
            log.warn("載入 tw_market_closure 失敗（保持空集合）：{}", e.getMessage());
        }
    }

    /** 供 {@link MarketDataFetchService#getTwHolidays} union：指定年份的台股臨時休市日（{@code {yyyy-MM-dd: reason}}）。 */
    public Map<String, String> closuresForYear(int year) {
        String prefix = year + "-";
        Map<String, String> out = new LinkedHashMap<>();
        closures.forEach((d, r) -> { if (d.startsWith(prefix)) out.put(d, r); });
        return out;
    }

    /**
     * 偵測「今天」臺北市是否停止上班；停班即 upsert 並 reload 快取。回傳今日是否休市。
     * 供排程 / 開機 self-heal / 內部觸發端點呼叫。
     */
    public boolean detectAndPersistToday() {
        LocalDate today = LocalDate.now(TW_ZONE);
        try {
            Optional<String> clause = fetchTaipeiTodayClause();
            if (clause.isEmpty()) {
                log.info("DGPA 停班公告查無臺北市今日狀態（{}）→ 維持交易日", today);
                return false;
            }
            String raw = clause.get();
            boolean closed = closedForTrading(raw);
            if (closed) {
                store.upsert(today, "颱風假（臺北市停止上班）", "DGPA", trunc(raw, 500));
                loadFromDb();
                log.warn("偵測到台股颱風假休市 {}：臺北市「{}」→ 已寫入 tw_market_closure", today, raw);
            } else {
                log.info("DGPA：臺北市今日「{}」→ 台股照常交易（{}）", raw, today);
            }
            return closed;
        } catch (Exception e) {
            log.warn("偵測台股颱風假失敗（{}）：{}（保守維持交易日）", today, e.getMessage());
            return false;
        }
    }

    private Optional<String> fetchTaipeiTodayClause() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DGPA_URL)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA).header("Accept", "text/html").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new IllegalStateException("DGPA HTTP " + resp.statusCode());
        return parseTaipeiTodayClause(resp.body());
    }

    /**
     * 從 DGPA HTML 取臺北市列的「今天」狀態文字。臺北市列格式：
     * {@code <FONT>臺北市</FONT>} 後接狀態 {@code <FONT color=#XXXXXX>今天停止上班、停止上課。</FONT>}
     * （部分列會另有 {@code <br>今天/明天…} 段）。取含「今天」的段；無則取第一段狀態。
     */
    static Optional<String> parseTaipeiTodayClause(String html) {
        if (html == null) return Optional.empty();
        int idx = html.indexOf("臺北市");
        if (idx < 0) idx = html.indexOf("台北市");
        if (idx < 0) return Optional.empty();
        // 自「臺北市」起、至該 <TR> 結束（避免吃到下一列新北市）
        String after = html.substring(idx);
        int end = indexOfIgnoreCase(after, "</TR>");
        String row = end > 0 ? after.substring(0, end) : after.substring(0, Math.min(after.length(), 1000));
        Matcher m = Pattern.compile("<FONT[^>]*>([^<]*)</FONT>", Pattern.CASE_INSENSITIVE).matcher(row);
        String firstStatus = null;
        while (m.find()) {
            String seg = m.group(1).trim();
            if (seg.isEmpty()) continue;
            if (firstStatus == null) firstStatus = seg;
            if (seg.contains("今天")) return Optional.of(seg);
        }
        return Optional.ofNullable(firstStatus);
    }

    /**
     * 臺北市「今天」狀態是否構成台股休市：停止上班且涵蓋 09:00–13:30 交易時段。
     * <ul>
     *   <li>含「照常上班」/ 無「停止上班」→ false（交易日）</li>
     *   <li>僅「晚上 / 傍晚 / 夜間」限定停班 → false（不影響交易時段）</li>
     *   <li>明確「HH:MM 起」停班且 HH:MM ≥ 13:30（收盤後）→ false</li>
     *   <li>其餘（全日 / 上午 / 下午覆蓋交易時段停班）→ true（休市）</li>
     * </ul>
     */
    static boolean closedForTrading(String clause) {
        if (clause == null) return false;
        String s = clause.replace(" ", "").replace("　", "");
        if (!s.contains("停止上班")) return false;
        // 僅傍晚 / 晚間起停班（未涵蓋 09:00–13:30）→ 交易日。DGPA 作業辦法正式用詞為「晚間」，
        // 縣市狀態列亦見「晚上」；兩者並列（另含傍晚 / 夜間）以免純晚間停班被誤判為全日休市。
        if (s.contains("晚上") || s.contains("晚間") || s.contains("傍晚") || s.contains("夜間")) return false;
        // 明確「（上午/下午）HH:MM 起」停班：DGPA 採 12 小時制附上午/下午，換算 24 小時後若 ≥ 13:30（收盤）→ 交易日
        Matcher tm = Pattern.compile("(上午|下午|中午)?(\\d{1,2}):(\\d{2})起").matcher(s);
        if (tm.find()) {
            String period = tm.group(1);
            int hh = Integer.parseInt(tm.group(2));
            int mm = Integer.parseInt(tm.group(3));
            if ("下午".equals(period) && hh < 12) hh += 12;
            if (hh * 60 + mm >= TW_CLOSE_MINUTES) return false;
        }
        return true;
    }

    private static int indexOfIgnoreCase(String s, String sub) {
        return s.toUpperCase().indexOf(sub.toUpperCase());
    }

    private static String trunc(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
