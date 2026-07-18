package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.NewsRow;
import com.steven.assets.externalmaterials.service.StockSourceQuery.ClosePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MaCrossSnapshotClient} 穿越偵測、分割防呆與去重鍵（Task 207）。
 *
 * <p>唯一相依 {@link StockSourceQuery} 以 mock 注入；本 client 不呼叫 {@code Instant.now()}
 * （{@code publishedAt} 取事件資料日），故輸出對注入序列完全確定、無時間相依。
 *
 * <p>測試只讓「0050」有資料（大盤與 00881 回空序列），使斷言聚焦單一標的。
 */
class MaCrossSnapshotClientTest {

    private static final LocalDate D0 = LocalDate.of(2026, 1, 5);
    private StockSourceQuery source;
    private MaCrossSnapshotClient client;

    @BeforeEach
    void setUp() {
        source = mock(StockSourceQuery.class);
        client = new MaCrossSnapshotClient(source);
        when(source.loadRecentTaiexCloses(anyInt())).thenReturn(List.of());
        when(source.loadRecentStockCloses(anyString(), anyString(), anyInt())).thenReturn(List.of());
    }

    /** 讓 0050 回傳指定序列（由舊到新，日期以 D0 起逐日遞增；僅作識別用，不需為真實交易日）。 */
    private void given0050(List<BigDecimal> closes) {
        List<ClosePoint> series = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            series.add(new ClosePoint(D0.plusDays(i), closes.get(i)));
        }
        when(source.loadRecentStockCloses("0050", "台股", 250)).thenReturn(series);
    }

    /** n 筆定值序列。 */
    private static List<BigDecimal> flat(int n, String v) {
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new BigDecimal(v));
        return out;
    }

    @Test
    @DisplayName("由下往上穿越季線 → 產出一則「漲破季線」")
    void detectsCrossUpThroughMa60() {
        // 60 筆 100.00 → MA60 恆為 100.00。倒數第二筆 99（≤ MA）、最後一筆 101（> MA）＝漲破。
        List<BigDecimal> closes = flat(60, "100.00");
        closes.add(new BigDecimal("99.00"));
        closes.add(new BigDecimal("101.00"));
        given0050(closes);

        List<NewsRow> rows = client.fetchAll();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).title()).contains("0050").contains("漲破季線");
        assertThat(rows.get(0).category()).isEqualTo("ma-cross");
    }

    @Test
    @DisplayName("由上往下穿越季線 → 產出一則「跌破季線」")
    void detectsCrossDownThroughMa60() {
        List<BigDecimal> closes = flat(60, "100.00");
        closes.add(new BigDecimal("101.00"));
        closes.add(new BigDecimal("99.00"));
        given0050(closes);

        assertThat(client.fetchAll()).singleElement()
                .extracting(NewsRow::title).asString().contains("跌破季線");
    }

    @Test
    @DisplayName("連兩日同側（未穿越）→ 不產出")
    void noEventWhenStaysOnSameSide() {
        List<BigDecimal> closes = flat(60, "100.00");
        closes.add(new BigDecimal("101.00"));
        closes.add(new BigDecimal("102.00"));
        given0050(closes);

        assertThat(client.fetchAll()).isEmpty();
    }

    @Test
    @DisplayName("筆數恰為 period（算不出前二日均線）→ 該均線略過；period+1 才可能觸發")
    void skipsMaWhenSeriesShorterThanPeriodPlusOne() {
        given0050(flat(60, "100.00"));                       // n = 60 = period → 略過
        assertThat(client.fetchAll()).isEmpty();

        List<BigDecimal> closes = flat(60, "100.00");        // n = 61 = period + 1
        closes.set(59, new BigDecimal("99.00"));
        closes.add(new BigDecimal("101.00"));
        given0050(closes);
        assertThat(client.fetchAll()).hasSize(1);
    }

    @Test
    @DisplayName("單日變動 16% → 整檔跳過；14% 仍正常偵測（門檻 15% 的兩側）")
    void dailyMoveThresholdBoundary() {
        List<BigDecimal> over = flat(60, "100.00");
        over.add(new BigDecimal("99.00"));
        over.add(new BigDecimal("114.84"));                  // 對 99 為 +16%
        given0050(over);
        assertThat(client.fetchAll()).isEmpty();

        List<BigDecimal> under = flat(60, "100.00");
        under.add(new BigDecimal("99.00"));
        under.add(new BigDecimal("112.86"));                 // 對 99 為 +14%
        given0050(under);
        assertThat(client.fetchAll()).hasSize(1);
    }

    /**
     * M1 回歸測試：分割污染的 MA 自己穿過靜止股價所產生的假突破。
     *
     * <p>序列＝30 筆分割前 190.00（未還原權值）＋ 60 筆分割後 47.50 起、每日 +0.01 的微幅走勢。
     * 到最後一日，MA60 視窗內最後一筆 190 剛好滾出：{@code maT1}=50.16 → {@code maT}=47.795，
     * 而股價僅由 48.08 走到 48.09。單日變動僅 0.02%（遠低於 15% 門檻，第一層防呆過不了濾），
     * 但均線由上而下切過股價 → 舊實作噴出「0050 漲破季線」的假訊號。視窗層防呆必須擋下。
     *
     * <p>此組數字經實測：停用 {@code windowHasGap} 後本測試會失敗（產出 1 則假漲破），
     * 啟用後為空——確認測試對該缺陷有鑑別力，非空測。
     */
    @Test
    @DisplayName("分割污染序列：均線視窗跨越分割點 → 不得產出假突破（M1 回歸測試）")
    void suppressesFalseCrossFromSplitPollutedWindow() {
        List<BigDecimal> closes = new ArrayList<>(flat(30, "190.00"));
        for (int j = 0; j < 60; j++) {
            closes.add(new BigDecimal("47.50").add(new BigDecimal("0.01").multiply(BigDecimal.valueOf(j))));
        }
        given0050(closes);

        assertThat(client.fetchAll())
                .as("視窗內含 -75%% 跳空，季線本輪應整段略過")
                .isEmpty();
    }

    @Test
    @DisplayName("同時穿越季線與年線 → 兩則各自一列，url fragment 不同（M2 去重鍵回歸測試）")
    void emitsSeparateRowsWithDistinctDedupeUrls() {
        // 240 筆 100 → MA60 與 MA240 皆為 100.00；一次穿越同時觸發兩條均線。
        List<BigDecimal> closes = flat(240, "100.00");
        closes.add(new BigDecimal("99.00"));
        closes.add(new BigDecimal("101.00"));
        given0050(closes);

        List<NewsRow> rows = client.fetchAll();

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(NewsRow::url).doesNotHaveDuplicates();
        assertThat(rows).extracting(NewsRow::url)
                .allSatisfy(u -> assertThat(u).contains("#0050-ma"));
        assertThat(rows).extracting(NewsRow::title)
                .anySatisfy(t -> assertThat(t).contains("季線"))
                .anySatisfy(t -> assertThat(t).contains("年線"));
    }

    @Test
    @DisplayName("publishedAt 取事件資料日 13:30 台北，而非抓取當下")
    void publishedAtUsesEventDataDate() {
        List<BigDecimal> closes = flat(60, "100.00");
        closes.add(new BigDecimal("99.00"));
        closes.add(new BigDecimal("101.00"));
        given0050(closes);

        LocalDate expectedDataDate = D0.plusDays(61);        // 序列最後一筆
        assertThat(client.fetchAll().get(0).publishedAt())
                .isEqualTo(expectedDataDate.atTime(13, 30).atZone(java.time.ZoneId.of("Asia/Taipei")).toInstant());
    }

    @Test
    @DisplayName("收盤含 null 或前二日為 0 → 不產出、不拋例外")
    void handlesNullAndZeroClosesGracefully() {
        List<BigDecimal> withNull = flat(60, "100.00");
        withNull.add(new BigDecimal("99.00"));
        withNull.add(null);
        given0050(withNull);
        assertThat(client.fetchAll()).isEmpty();

        List<BigDecimal> withZero = flat(60, "100.00");
        withZero.add(BigDecimal.ZERO);
        withZero.add(new BigDecimal("101.00"));
        given0050(withZero);
        assertThat(client.fetchAll()).isEmpty();
    }

    @Test
    @DisplayName("單一標的查詢失敗 → 只 log warn，不影響其餘標的與整輪")
    void oneTargetFailureDoesNotBreakTheRound() {
        when(source.loadRecentTaiexCloses(anyInt())).thenThrow(new RuntimeException("DB down"));
        List<BigDecimal> closes = flat(60, "100.00");
        closes.add(new BigDecimal("99.00"));
        closes.add(new BigDecimal("101.00"));
        given0050(closes);

        assertThat(client.fetchAll()).hasSize(1);
    }
}
