package com.steven.assets.service;

import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TechnicalIndicatorService.computeAll("0000","台股") 對大盤即時點位的融合邏輯（Task 228，
 * Requirement 43 修訂 V6）：完成日序列未到今日時比照一般個股既有作法暫加一筆 Redis 即時價。
 */
@ExtendWith(MockitoExtension.class)
class TechnicalIndicatorTaiexLiveBlendTest {

    @Mock private StockPriceHistoryRepository historyRepo;
    @Mock private PriceQueryService priceQuery;
    @Mock private TwseIndexDailyHistoryRepository twseDailyRepo;

    private TechnicalIndicatorService service() {
        // 第 4 參數為 Task 294 新增的 usIndexDailyHistoryRepo；本測試只涉及 TAIEX 融合，傳 null 即可。
        return new TechnicalIndicatorService(historyRepo, priceQuery, twseDailyRepo, null);
    }

    /** 240 筆「由新到舊」完成日收盤：closePoint = base+i，i=0 為最新一筆。 */
    private List<TwseIndexDailyHistory> descRows(LocalDate latest, int base) {
        List<TwseIndexDailyHistory> rows = new ArrayList<>();
        for (int i = 0; i < 240; i++) {
            TwseIndexDailyHistory h = new TwseIndexDailyHistory();
            h.setTradingDate(latest.minusDays(i));
            h.setClosePoint(BigDecimal.valueOf(base + i));
            rows.add(h);
        }
        return rows;
    }

    private PriceQueryService.LivePrice liveOn(LocalDate tradingDate, BigDecimal price) {
        return liveOn(tradingDate, price, null, null);
    }

    /** Task 263：high / low 有值的 live（大盤自該任務起由 Yahoo 5 分 K 的 high/low 陣列提供當日真實區間）。 */
    private PriceQueryService.LivePrice liveOn(LocalDate tradingDate, BigDecimal price,
                                               BigDecimal high, BigDecimal low) {
        return new PriceQueryService.LivePrice(
                "0000", "台股大盤", "台股", price, null, null, null,
                null, null, null, high, low, null,
                tradingDate.toString(), "2026-07-20T10:30:00", false, "TWSE指數(5m)", "LIVE");
    }

    @Test
    void completedKNotToday_liveFreshToday_blendsIntoMa20() {
        LocalDate today = LocalDate.now();
        List<TwseIndexDailyHistory> rows = descRows(today.minusDays(1), 100);
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(240)).thenReturn(rows);
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(liveOn(today, BigDecimal.valueOf(500))));

        TechnicalIndicatorService.FullIndicators ind = service().computeAll("0000", "台股");

        // MA20 = (live 500 + 前 19 筆完成日收盤 100..118) 的平均，與 taiexSimpleMa 相同的 scale(2, HALF_UP)。
        double sum = 500.0;
        for (int i = 0; i < 19; i++) sum += 100 + i;
        BigDecimal expectedMa20 = BigDecimal.valueOf(sum / 20).setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedMa20, ind.monthlyMa());
    }

    @Test
    void completedKAlreadyToday_doesNotDoubleCountLive() {
        LocalDate today = LocalDate.now();
        List<TwseIndexDailyHistory> rows = descRows(today, 100);
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(240)).thenReturn(rows);

        TechnicalIndicatorService.FullIndicators ind = service().computeAll("0000", "台股");

        double sum = 0;
        for (int i = 0; i < 20; i++) sum += 100 + i;
        BigDecimal expectedMa20 = BigDecimal.valueOf(sum / 20).setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedMa20, ind.monthlyMa());
        verify(priceQuery, never()).getLive("0000", "台股");
    }

    /**
     * Task 263：live 的 highPrice / lowPrice 真的會進 KD 的 RSV，不是被 fallback 成 price。
     *
     * <p>大盤的 Redis high / low 在 Task 263 之前恆為 null（`TaiexIndexPoller` 傳 null、
     * `PriceCacheWriter` 回退成「5 分格<b>收盤價</b>」的本地聚合），故 `live.highPrice() != null`
     * 的 true 分支原本零覆蓋。Task 263 起改為 Yahoo 「5 分格 high / low <b>陣列</b>」的 max/min，
     * 區間變寬 → RSV 變 → 盤中 K / D 與修正前不同（這是修正，不是 regression）。
     *
     * <p><b>fixture 約束</b>：`descRows` 的 live close(500) 遠高於其餘各格(100..108)，故不給 high 時
     * `highest == close`、RSV 恆為 100；此時<b>只放寬 low 不會改變 K/D</b>。方向性一般式為
     * `RSV_new &lt; RSV_old ⟺ δ·(H−C) &lt; (C−L)·ε`（δ=低點下移、ε=高點上移），並非恆真，
     * 故本 case 固定用「只放寬 high」（δ=0、ε&gt;0），該條件必然成立、K 必然下降。
     */
    @Test
    void completedKNotToday_liveHighLowEntersRsv_notFallenBackToPrice() {
        LocalDate today = LocalDate.now();
        List<TwseIndexDailyHistory> rows = descRows(today.minusDays(1), 100);
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(240)).thenReturn(rows);

        when(priceQuery.getLive("0000", "台股"))
                .thenReturn(Optional.of(liveOn(today, BigDecimal.valueOf(500))));
        TechnicalIndicatorService.FullIndicators withoutHl = service().computeAll("0000", "台股");

        // 只放寬 high（600 > price 500），low 維持不給 → highest 由 500 抬到 600，RSV 必然下降
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(
                liveOn(today, BigDecimal.valueOf(500), BigDecimal.valueOf(600), null)));
        TechnicalIndicatorService.FullIndicators withHl = service().computeAll("0000", "台股");

        assertNotEquals(withoutHl.k(), withHl.k());
        assertTrue(withHl.k().compareTo(withoutHl.k()) < 0,
                "放寬 high 後收盤在區間內的相對位置下降，K 必須跟著下降：" + withoutHl.k() + " → " + withHl.k());
    }

    @Test
    void completedKNotToday_liveMissing_fallsBackToPureCompletedSeries() {
        LocalDate today = LocalDate.now();
        List<TwseIndexDailyHistory> rows = descRows(today.minusDays(1), 100);
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(240)).thenReturn(rows);
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.empty());

        TechnicalIndicatorService.FullIndicators ind = service().computeAll("0000", "台股");

        double sum = 0;
        for (int i = 0; i < 20; i++) sum += 100 + i;
        BigDecimal expectedMa20 = BigDecimal.valueOf(sum / 20).setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedMa20, ind.monthlyMa());
    }
}
