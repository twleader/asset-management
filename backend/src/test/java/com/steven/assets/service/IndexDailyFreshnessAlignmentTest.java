package com.steven.assets.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 海外指數日線「新鮮度」判準與交易雷達同源（Requirement 75 / Task 332）。
 *
 * <p>不啟 Spring context、不連 DB：{@link MarketDataService} 直接 new（其交易日曆為純計算，
 * 建構子只需一個未被呼叫的 base-url 與 null repo，比照 {@code MarketDataServiceUsCalendarTest}），
 * {@link UsIndexDailyHistoryRepository} 與 {@link MacroHistoryService} 以替身注入。
 */
@ExtendWith(MockitoExtension.class)
class IndexDailyFreshnessAlignmentTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    /**
     * 事故當下的時刻：2026-08-13 21:58 Asia/Taipei ＝ ET 09:58（8/12 那盤早已收盤、8/13 尚未收）。
     * 故「最近一個已完成美股交易日」＝ 2026-08-12（週三）。
     */
    private static final Instant INCIDENT_INSTANT =
            ZonedDateTime.of(2026, 8, 13, 21, 58, 0, 0, TAIPEI).toInstant();
    private static final LocalDate INCIDENT_TODAY_TPE = LocalDate.of(2026, 8, 13);
    private static final LocalDate EXPECTED_US_DAY = LocalDate.of(2026, 8, 12);

    @Mock MacroHistoryService macroHistoryService;
    @Mock UsIndexDailyHistoryRepository usDailyRepo;

    private final MarketDataService marketDataService = new MarketDataService("http://unused", null);
    private final Map<String, LocalDate> latestByCode = new HashMap<>();
    private IndexDailyRefreshScheduler scheduler;

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger schedulerLogger;

    @BeforeEach
    void setUp() {
        scheduler = new IndexDailyRefreshScheduler(macroHistoryService, usDailyRepo, marketDataService);

        // 全部指數預設為「已追上」：美股取最近一個已完成美股交易日、非美股取台北當日。
        IndexDailyRefreshScheduler.US_INDEX_CODES.forEach(c -> latestByCode.put(c, EXPECTED_US_DAY));
        IndexDailyRefreshScheduler.NON_US_INDEX_CODES.forEach(c -> latestByCode.put(c, INCIDENT_TODAY_TPE));

        schedulerLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(IndexDailyRefreshScheduler.class);
        logs = new ListAppender<>();
        logs.start();
        schedulerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        schedulerLogger.detachAppender(logs);
    }

    /** 讓 repo 依 {@link #latestByCode} 回覆；未登錄的 code 視為查無資料。 */
    private void stubRepo() {
        when(usDailyRepo.findTopByIndexCodeOrderByTradingDateDesc(anyString()))
                .thenAnswer(inv -> {
                    String code = inv.getArgument(0);
                    LocalDate latest = latestByCode.get(code);
                    if (latest == null) return Optional.empty();
                    UsIndexDailyHistory row = new UsIndexDailyHistory();
                    row.setIndexCode(code);
                    row.setTradingDate(latest);
                    return Optional.of(row);
                });
    }

    private List<String> messages(Level level) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ---------- (a) 迴歸錨點：美股指數落後「一盤」即判過時 ----------

    @Test
    @DisplayName("(a) IXIC 落後一盤即判過時——正是舊 STALE_DAYS=4 會判為正常的區間")
    void 美股指數落後一盤即判過時() {
        latestByCode.put("IXIC", LocalDate.of(2026, 8, 11));   // 事故實測值：缺 8/12
        stubRepo();

        assertThat(scheduler.staleUsIndexes(INCIDENT_INSTANT))
                .extracting(IndexDailyRefreshScheduler.StaleIndex::code)
                .containsExactly("IXIC");

        // 舊判準（落後超過 4 個日曆天才算過時）在同一組輸入下會判為「正常」——本案即為迴歸錨點。
        assertThat(LocalDate.of(2026, 8, 11).isBefore(INCIDENT_TODAY_TPE.minusDays(4))).isFalse();
    }

    @Test
    @DisplayName("(a) 查無任何列的美股指數一律視為落後")
    void 美股指數查無資料視為落後() {
        latestByCode.remove("SP500TR");
        stubRepo();

        assertThat(scheduler.staleUsIndexes(INCIDENT_INSTANT))
                .extracting(IndexDailyRefreshScheduler.StaleIndex::code)
                .containsExactly("SP500TR");
        assertThat(scheduler.staleUsIndexes(INCIDENT_INSTANT).get(0).latest()).isNull();
    }

    @Test
    @DisplayName("(a) 美股指數皆已追上最近一個已完成美股交易日時，判定為空")
    void 美股指數皆已追上時不判過時() {
        stubRepo();

        assertThat(scheduler.staleUsIndexes(INCIDENT_INSTANT)).isEmpty();
    }

    // ---------- (b) 非美股指數維持 4 日曆天容忍 ----------

    @Test
    @DisplayName("(b) 非美股指數落後一盤不判過時，落後超過 4 個日曆天才判過時")
    void 非美股指數維持日曆天容忍() {
        latestByCode.put("N225", INCIDENT_TODAY_TPE.minusDays(1));   // 落後一盤
        latestByCode.put("KOSPI", INCIDENT_TODAY_TPE.minusDays(4));  // 恰好 4 日：仍在容忍內
        latestByCode.put("DAX", INCIDENT_TODAY_TPE.minusDays(5));    // 超過 4 日：過時
        stubRepo();

        assertThat(scheduler.staleNonUsIndexes(INCIDENT_TODAY_TPE))
                .extracting(IndexDailyRefreshScheduler.StaleIndex::code)
                .containsExactly("DAX");
    }

    @Test
    @DisplayName("(b) FTSE 有英股日曆可用，但本任務一併沿用 4 日容忍、不套逐盤判準")
    void FTSE沿用日曆天容忍() {
        latestByCode.put("FTSE", INCIDENT_TODAY_TPE.minusDays(2));
        stubRepo();

        assertThat(scheduler.staleNonUsIndexes(INCIDENT_TODAY_TPE)).isEmpty();
        // 兩份清單為正面列舉且互斥，新增指數時不會被反向判斷默默歸類
        assertThat(IndexDailyRefreshScheduler.US_INDEX_CODES)
                .doesNotContainAnyElementsOf(IndexDailyRefreshScheduler.NON_US_INDEX_CODES);
        assertThat(IndexDailyRefreshScheduler.US_INDEX_CODES)
                .containsExactlyInAnyOrder("DJI", "SPX", "IXIC", "SOX", "SP500TR");
    }

    // ---------- (c) 補救檢查：追上就不打外部來源 ----------

    @Test
    @DisplayName("(c) 美股指數已追上時，補救檢查零回補呼叫")
    void 補救檢查已追上時不觸發回補() {
        stubRepo();

        scheduler.runUsIndexGapCheck(INCIDENT_INSTANT);

        verify(macroHistoryService, never()).refreshUsIndexDaily(anyString());
        assertThat(messages(Level.INFO)).isEmpty();   // 追上時不得印 INFO 洗版
    }

    @Test
    @DisplayName("(c) 補救檢查只回補判定為落後的那幾檔，未落後者不發外部請求")
    void 補救檢查只回補落後的指數() {
        latestByCode.put("IXIC", LocalDate.of(2026, 8, 11));
        latestByCode.put("SOX", LocalDate.of(2026, 8, 10));
        stubRepo();

        scheduler.runUsIndexGapCheck(INCIDENT_INSTANT);

        verify(macroHistoryService).refreshUsIndexDaily("IXIC");
        verify(macroHistoryService).refreshUsIndexDaily("SOX");
        verify(macroHistoryService, never()).refreshUsIndexDaily("DJI");
        verify(macroHistoryService, never()).refreshUsIndexDaily("SPX");
        verify(macroHistoryService, never()).refreshUsIndexDaily("SP500TR");
        // 落後補救不重複跑 TWSE 報酬指數增量（那是每日全量回補的收尾步驟）
        verify(macroHistoryService, never()).fillRecentTwseReturnIndexGaps(org.mockito.ArgumentMatchers.anyInt());

        assertThat(messages(Level.INFO))
                .anySatisfy(m -> assertThat(m)
                        .contains("gap-check", "IXIC", "2026-08-11", "2026-08-12"));
    }

    @Test
    @DisplayName("(c) 自癒同樣只補落後的那幾檔，且美股用逐盤、非美股用日曆天各自判斷")
    void 自癒只補落後的指數() {
        latestByCode.put("IXIC", LocalDate.of(2026, 8, 11));         // 美股逐盤 → 落後
        latestByCode.put("N225", INCIDENT_TODAY_TPE.minusDays(1));   // 非美股 4 日容忍 → 不落後
        latestByCode.put("DAX", INCIDENT_TODAY_TPE.minusDays(9));    // 非美股 → 落後
        stubRepo();

        scheduler.runSelfHeal(INCIDENT_INSTANT, INCIDENT_TODAY_TPE);

        verify(macroHistoryService).refreshUsIndexDaily("IXIC");
        verify(macroHistoryService).refreshUsIndexDaily("DAX");
        verify(macroHistoryService, never()).refreshUsIndexDaily("N225");
        verify(macroHistoryService, never()).refreshUsIndexDaily("SPX");
    }

    @Test
    @DisplayName("(c) 全部最新時自癒維持既有「皆為最新，略過」且零回補")
    void 自癒皆為最新時略過() {
        stubRepo();

        scheduler.runSelfHeal(INCIDENT_INSTANT, INCIDENT_TODAY_TPE);

        verify(macroHistoryService, never()).refreshUsIndexDaily(anyString());
        assertThat(messages(Level.INFO)).containsExactly("self-heal：海外指數日線皆為最新，略過");
    }

    // ---------- (d) 回補後仍落後要留下可查的 WARN ----------

    @Test
    @DisplayName("(d) 回補後仍落後：WARN 同時帶出指數／目前最新／應該要有哪一天")
    void 回補後仍落後產生WARN() {
        latestByCode.put("IXIC", LocalDate.of(2026, 8, 11));
        latestByCode.remove("SOX");
        stubRepo();

        scheduler.warnIfUsIndexStillStale("scheduled", INCIDENT_INSTANT);

        assertThat(messages(Level.WARN))
                .anySatisfy(m -> assertThat(m).contains("IXIC", "2026-08-11", "2026-08-12"))
                .anySatisfy(m -> assertThat(m).contains("SOX", "無資料", "2026-08-12"))
                .hasSize(2);
    }

    @Test
    @DisplayName("(d) 回補後已追上：不新增任何 WARN 噪音")
    void 回補後已追上不產生WARN() {
        stubRepo();

        scheduler.warnIfUsIndexStillStale("scheduled", INCIDENT_INSTANT);

        assertThat(messages(Level.WARN)).isEmpty();
    }

    // ---------- (e) 共用方法搬移後行為不變 ----------

    @Test
    @DisplayName("(e) 收盤前退回前一交易日、收盤後取當日")
    void 最近已完成美股交易日以美東收盤界定() {
        ZoneId ny = ZoneId.of("America/New_York");

        // ET 2026-08-13 09:58（開盤中，尚未收盤）→ 退回 8/12
        assertThat(marketDataService.mostRecentCompletedUsTradingDay(INCIDENT_INSTANT))
                .isEqualTo(LocalDate.of(2026, 8, 12));
        // ET 2026-08-13 15:59（收盤前一分鐘）→ 仍退回 8/12
        assertThat(marketDataService.mostRecentCompletedUsTradingDay(
                ZonedDateTime.of(2026, 8, 13, 15, 59, 0, 0, ny).toInstant()))
                .isEqualTo(LocalDate.of(2026, 8, 12));
        // ET 2026-08-13 16:00（收盤時刻）→ 取當日 8/13
        assertThat(marketDataService.mostRecentCompletedUsTradingDay(
                ZonedDateTime.of(2026, 8, 13, 16, 0, 0, 0, ny).toInstant()))
                .isEqualTo(LocalDate.of(2026, 8, 13));
    }

    @Test
    @DisplayName("(e) 遇週末與美股假日往回找上一個交易日")
    void 最近已完成美股交易日跳過週末與假日() {
        ZoneId ny = ZoneId.of("America/New_York");

        // 週六 2026-08-15 收盤後 → 8/15、8/14 依序檢查，取週五 8/14
        assertThat(marketDataService.mostRecentCompletedUsTradingDay(
                ZonedDateTime.of(2026, 8, 15, 18, 0, 0, 0, ny).toInstant()))
                .isEqualTo(LocalDate.of(2026, 8, 14));
        // 週日 2026-08-16 盤前 → 退回 8/15（六）再往前找到 8/14（五）
        assertThat(marketDataService.mostRecentCompletedUsTradingDay(
                ZonedDateTime.of(2026, 8, 16, 8, 0, 0, 0, ny).toInstant()))
                .isEqualTo(LocalDate.of(2026, 8, 14));
        // 2026-07-04 為週六，獨立紀念日順移至週五 7/3 休市 → 7/3 收盤後仍取 7/2（四）
        assertThat(marketDataService.isUsTradingDay(LocalDate.of(2026, 7, 3))).isFalse();
        assertThat(marketDataService.mostRecentCompletedUsTradingDay(
                ZonedDateTime.of(2026, 7, 3, 20, 0, 0, 0, ny).toInstant()))
                .isEqualTo(LocalDate.of(2026, 7, 2));
    }

    @Test
    @DisplayName("(e) 判準的兩個呼叫端同源：排程判定的『應該要有哪一天』與雷達完全一致")
    void 排程與雷達使用同一支方法() {
        latestByCode.put("IXIC", LocalDate.of(2026, 8, 11));
        stubRepo();

        LocalDate radarExpectation = marketDataService.mostRecentCompletedUsTradingDay(INCIDENT_INSTANT);
        // 雷達判 stale 的條件：latestEodDate < mostRecentCompleted；排程須得出同一結論
        assertThat(LocalDate.of(2026, 8, 11).isBefore(radarExpectation)).isTrue();
        assertThat(scheduler.staleUsIndexes(INCIDENT_INSTANT))
                .extracting(IndexDailyRefreshScheduler.StaleIndex::code)
                .contains("IXIC");
    }
}
