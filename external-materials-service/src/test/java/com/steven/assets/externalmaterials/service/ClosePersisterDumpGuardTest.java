package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Task 258：{@link ClosePersister#shouldDumpPayload} —— 收盤 dump 的逐檔守門。
 *
 * <p>原本 {@code dumpRedisToDb} 只要 Redis payload 有 {@code price} 就寫進 {@code stock_price_history}
 * 當日列，既不檢查 payload 的 {@code tradingDate}、也不檢查該值有多舊。代號集合來自 Redis SET
 * {@code price:index:{market}}，其中含開機 {@code warmCacheOnStartup} 以 {@code collectAllStockCodes}
 * 寫入、之後再也不被盤中 cron 刷新的「曾持有／曾觀察」標的；加上 {@code PriceCacheWriter.syncClosedFromDb}
 * 會從 DB 最近收盤寫回 Redis，錯誤值遂透過 Redis 洗一圈回到 DB、每個交易日自我延續一列。
 * 實測 2026 年已累積台股 122 列、美股 3 列、英股 3 列假收盤（例 {@code 2002} 中鋼 2026-07-16～07-29
 * 共 8 個交易日收盤全記 19.1000、O/H/L 為 null、volume 0）。</p>
 *
 * <p>本檔以固定的 {@code targetDate}／{@code now} 驅動，不依賴真實時鐘。</p>
 */
class ClosePersisterDumpGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final LocalDate TARGET = LocalDate.of(2026, 7, 29);          // 週三，台股交易日
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 13, 32, 0);  // 13:32 dump 時點

    /** 組一個 Redis live-cache payload。傳 null 表示該欄缺漏。 */
    private static JsonNode payload(String tradingDate, String updatedAt, Boolean closed) {
        var n = MAPPER.createObjectNode();
        n.put("price", "63.5");
        n.put("stockCode", "2885");
        n.put("market", "台股");
        if (tradingDate != null) n.put("tradingDate", tradingDate);
        if (updatedAt != null) n.put("updatedAt", updatedAt);
        if (closed != null) n.put("closed", closed);
        return n;
    }

    // ── 258.5.1 陳舊值（tradingDate 早於目標日）──────────────────────

    /**
     * 122 列腐化的主判準。{@code syncClosedFromDb} 寫回 Redis 的 payload 帶的是 DB 的
     * {@code maxTradingDate}（即前一交易日），dump 卻無條件冠上今日日期寫進 DB。
     */
    @Test
    void 前一交易日的陳舊值不得被當成今日收盤() {
        JsonNode p = payload("2026-07-28", "2026-07-29T13:30:00", false);

        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isFalse();
    }

    // ── 258.5.2 同日但過舊（盤中 tick）─────────────────────────────

    /**
     * 實測 2026-07-29 的 {@code 1301}（{@code updatedAt} 10:40、{@code closed:false}、寫成 55.00）／
     * {@code 2409}（10:55）／{@code 2882}（11:30）就是被當成收盤寫入的盤中值。
     */
    @Test
    void 同日但早於收盤三小時的盤中tick不得被當成收盤() {
        JsonNode p = payload("2026-07-29", "2026-07-29T10:40:55", false);

        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isFalse();
    }

    // ── 258.5.3 回歸錨點：不得以 closed 守門 ───────────────────────

    /**
     * <b>這是本檔最重要的一條。</b>13:32 dump 要取的正是 13:28～13:30 那輪盤中 cron 寫入的值，
     * 而 {@code PricePoller.scheduledTwIntradayUpdate} 呼叫的是 {@code updatePrices(tw, "台股", false)}，
     * 故合法值的 {@code closed} 為 {@code false}。若有人把守門寫成 {@code closed == true}，
     * 正常路徑會被整個擋掉、當日一列都寫不進去——這條測試就是要在那時候變紅。
     */
    @Test
    void 收盤前最後一輪cron的值即使closed為false也必須寫入() {
        JsonNode p = payload("2026-07-29", "2026-07-29T13:31:00", false);

        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isTrue();
    }

    // ── 258.5.4 12 分鐘邊界 ────────────────────────────────────────

    @Test
    void 距今恰為十二分鐘視為通過() {
        JsonNode p = payload("2026-07-29", "2026-07-29T13:20:00", false);

        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isTrue();
    }

    @Test
    void 距今超過十二分鐘即擋下() {
        JsonNode p = payload("2026-07-29", "2026-07-29T13:19:59", false);

        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isFalse();
    }

    // ── 258.5.5 欄位缺漏／格式錯誤 ─────────────────────────────────

    @Test
    void 缺tradingDate時擋下且不擲例外() {
        JsonNode p = payload(null, "2026-07-29T13:31:00", false);

        assertThatCode(() -> ClosePersister.shouldDumpPayload(p, TARGET, NOW)).doesNotThrowAnyException();
        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isFalse();
    }

    @Test
    void 缺updatedAt時擋下且不擲例外() {
        JsonNode p = payload("2026-07-29", null, false);

        assertThatCode(() -> ClosePersister.shouldDumpPayload(p, TARGET, NOW)).doesNotThrowAnyException();
        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isFalse();
    }

    @Test
    void 兩欄格式皆錯時擋下且不擲例外() {
        JsonNode p = payload("not-a-date", "also-not-a-timestamp", false);

        assertThatCode(() -> ClosePersister.shouldDumpPayload(p, TARGET, NOW)).doesNotThrowAnyException();
        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isFalse();
    }

    /** payload 完全沒有 closed 欄位也不得影響判定（守門不看它）。 */
    @Test
    void 沒有closed欄位也照既有兩條規則判定() {
        JsonNode p = payload("2026-07-29", "2026-07-29T13:31:00", null);

        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isTrue();
    }

    // ── 258.5.6 時鐘倒退 ───────────────────────────────────────────

    /** {@code updatedAt} 晚於 {@code now}（容器時鐘微幅倒退）不得因負值間隔被誤擋。 */
    @Test
    void updatedAt晚於now時視為零分鐘並通過() {
        JsonNode p = payload("2026-07-29", "2026-07-29T13:33:30", false);

        assertThat(ClosePersister.shouldDumpPayload(p, TARGET, NOW)).isTrue();
    }

    // ── 常數本身 ───────────────────────────────────────────────────

    /**
     * 12 分鐘的下界理由：三個 dump 皆排在收盤後 2 分鐘（13:32 TW／16:02 ET／16:32 LON），
     * 而盤中 cron 每 2 分鐘一輪，故合法值的 updatedAt 必落在收盤前最後幾輪。
     * 把它改小會擋掉正常路徑，改大會放進盤中 tick。
     */
    @Test
    void 陳舊上限為十二分鐘() {
        assertThat(ClosePersister.MAX_PAYLOAD_STALENESS.toMinutes()).isEqualTo(12);
    }
}
