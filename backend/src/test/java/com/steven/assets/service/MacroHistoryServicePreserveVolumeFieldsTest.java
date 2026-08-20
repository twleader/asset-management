package com.steven.assets.service;

import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.JapanGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.KoreaGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TaiwanGdpPerCapitaHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link MacroHistoryService#preserveExistingTwseDailyFields(List)} 保值邏輯（Task t288）。
 *
 * <p>tradeVolume／tradeValue 來自獨立的 FMTQIK 月報，可能與主要 TWSE 價格來源分別失敗；
 * refreshTwseDaily 對 saveAll 前的最後一步呼叫本方法，避免「這次抓到的列這兩欄剛好是 null」
 * 時經 JPA merge 整列覆寫，把先前已回補的成交量／成交金額洗掉。
 *
 * <p>closePointTr 為既有（pre-t288）行為，一律無條件回填既有 DB 值，非本測試重點，
 * 僅在其中一個案例附帶驗證未被本次變更波及。
 */
@ExtendWith(MockitoExtension.class)
class MacroHistoryServicePreserveVolumeFieldsTest {

    @Mock private TaiwanGdpPerCapitaHistoryRepository gdpRepo;
    @Mock private JapanGdpPerCapitaHistoryRepository japanGdpRepo;
    @Mock private KoreaGdpPerCapitaHistoryRepository koreaGdpRepo;
    @Mock private TwseIndexDailyHistoryRepository twseDailyRepo;
    @Mock private UsIndexDailyHistoryRepository usDailyRepo;

    private MacroHistoryService service;

    @BeforeEach
    void setUp() {
        service = new MacroHistoryService(gdpRepo, japanGdpRepo, koreaGdpRepo,
                twseDailyRepo, usDailyRepo, "http://localhost:0");
    }

    private TwseIndexDailyHistory freshRow(LocalDate date, Long tradeVolume, BigDecimal tradeValue) {
        TwseIndexDailyHistory row = new TwseIndexDailyHistory();
        row.setTradingDate(date);
        row.setOpenPoint(new BigDecimal("100.00"));
        row.setHighPoint(new BigDecimal("101.00"));
        row.setLowPoint(new BigDecimal("99.00"));
        row.setClosePoint(new BigDecimal("100.50"));
        row.setTradeVolume(tradeVolume);
        row.setTradeValue(tradeValue);
        return row;
    }

    private TwseIndexDailyHistory existingDbRow(LocalDate date, BigDecimal closePointTr,
                                                 Long tradeVolume, BigDecimal tradeValue) {
        TwseIndexDailyHistory ex = new TwseIndexDailyHistory();
        ex.setTradingDate(date);
        ex.setClosePoint(new BigDecimal("100.50"));
        ex.setClosePointTr(closePointTr);
        ex.setTradeVolume(tradeVolume);
        ex.setTradeValue(tradeValue);
        return ex;
    }

    /**
     * (c) 本次 FMTQIK 抓取失敗（新列 tradeVolume/tradeValue 為 null）：既有 DB 值須回填，不得被洗成 null。
     */
    @Test
    void FMTQIK本次失敗為null時回填既有DB的成交量與成交金額() {
        LocalDate date = LocalDate.of(2026, 7, 1);
        TwseIndexDailyHistory fresh = freshRow(date, null, null);
        TwseIndexDailyHistory existing = existingDbRow(date, new BigDecimal("120.34"),
                14683404939L, new BigDecimal("1367817795171"));
        when(twseDailyRepo.findById(date)).thenReturn(Optional.of(existing));

        service.preserveExistingTwseDailyFields(List.of(fresh));

        assertThat(fresh.getTradeVolume()).isEqualTo(14683404939L);
        assertThat(fresh.getTradeValue()).isEqualByComparingTo("1367817795171");
        // closePointTr 為既有行為，附帶驗證：一律無條件回填
        assertThat(fresh.getClosePointTr()).isEqualByComparingTo("120.34");
    }

    /**
     * (d) 互補探針：本次 FMTQIK 抓取成功（新列帶新的非 null 值）時，
     * 不得被既有 DB 的舊值蓋掉——否則保值邏輯若誤寫成「一律用既有 DB 值覆蓋」，
     * 這個案例會失敗（即使 (c) 仍會通過），才真正證明是「僅在 null 時才回填」。
     */
    @Test
    void FMTQIK本次成功抓到新值時不被既有DB的舊值覆蓋() {
        LocalDate date = LocalDate.of(2026, 7, 2);
        TwseIndexDailyHistory fresh = freshRow(date, 99999999999L, new BigDecimal("88888888888"));
        TwseIndexDailyHistory existing = existingDbRow(date, null,
                11740053658L, new BigDecimal("1083583417368"));
        when(twseDailyRepo.findById(date)).thenReturn(Optional.of(existing));

        service.preserveExistingTwseDailyFields(List.of(fresh));

        assertThat(fresh.getTradeVolume()).isEqualTo(99999999999L);
        assertThat(fresh.getTradeValue()).isEqualByComparingTo("88888888888");
    }

    /**
     * 全新交易日（DB 尚無此列）：findById 回 Optional.empty()，無可回填來源，
     * 新列欄位維持原樣，且不得拋例外。
     */
    @Test
    void DB查無既有列時維持新列原值且不拋例外() {
        LocalDate date = LocalDate.of(2026, 7, 3);
        TwseIndexDailyHistory fresh = freshRow(date, 5000000000L, new BigDecimal("500000000000"));
        when(twseDailyRepo.findById(date)).thenReturn(Optional.empty());

        service.preserveExistingTwseDailyFields(List.of(fresh));

        assertThat(fresh.getTradeVolume()).isEqualTo(5000000000L);
        assertThat(fresh.getTradeValue()).isEqualByComparingTo("500000000000");
        assertThat(fresh.getClosePointTr()).isNull();
    }

    @Test
    void TPEX本次量能為null時保留同日既有量能但OHLC仍由本次覆寫() {
        LocalDate date = LocalDate.of(2026, 8, 3);
        UsIndexDailyHistory incoming = usRow("TPEX", date, new BigDecimal("321.00"), null);
        UsIndexDailyHistory existing = usRow("TPEX", date, new BigDecimal("300.00"), 998_000L);
        when(usDailyRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc("TPEX", date, date))
                .thenReturn(List.of(existing));

        service.preserveExistingTpexVolumes("TPEX", List.of(incoming));

        assertThat(incoming.getVolume()).isEqualTo(998_000L);
        assertThat(incoming.getClosePoint()).isEqualByComparingTo("321.00");
    }

    @Test
    void TPEX本次抓到非null量能時不得被舊值覆蓋() {
        LocalDate date = LocalDate.of(2026, 8, 4);
        UsIndexDailyHistory incoming = usRow("TPEX", date, new BigDecimal("322.00"), 1_001_000L);
        UsIndexDailyHistory existing = usRow("TPEX", date, new BigDecimal("300.00"), 998_000L);
        when(usDailyRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc("TPEX", date, date))
                .thenReturn(List.of(existing));

        service.preserveExistingTpexVolumes("TPEX", List.of(incoming));

        assertThat(incoming.getVolume()).isEqualTo(1_001_000L);
    }

    @Test
    void 非TPEX不得套用保值合併或查既有rows() {
        UsIndexDailyHistory incoming = usRow("DJI", LocalDate.of(2026, 8, 5),
                new BigDecimal("41000.00"), null);

        service.preserveExistingTpexVolumes("DJI", List.of(incoming));

        assertThat(incoming.getVolume()).isNull();
        org.mockito.Mockito.verifyNoInteractions(usDailyRepo);
        assertThat(MacroHistoryService.isTpexCode(" tpex ")).isTrue();
        assertThat(MacroHistoryService.isTpexCode("DJI")).isFalse();
    }

    private UsIndexDailyHistory usRow(String code, LocalDate date, BigDecimal close, Long volume) {
        return new UsIndexDailyHistory(code, date, new BigDecimal("300.00"), new BigDecimal("330.00"),
                new BigDecimal("290.00"), close, volume);
    }
}
