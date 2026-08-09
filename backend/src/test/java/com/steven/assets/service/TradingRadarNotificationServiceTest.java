package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.TradingRadarNotificationSetting;
import com.steven.assets.model.TradingRadarNotificationState;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.TradingRadarNotificationSettingRepository;
import com.steven.assets.repository.TradingRadarNotificationStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 301：通知冷卻只擋 email、不影響 {@code last_action} 基準。
 *
 * <p>{@code transition}／{@code tradingRadarService} 皆為 mock，固定回傳「動作轉入 EXIT_CANDIDATE」
 * 的情境；轉入語意本身已由 {@link TradingRadarNotificationTransitionTest} 覆蓋，這裡只驗證冷卻本身
 * 的抑制／放行與 {@code lastNotifiedAt} 寫入。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingRadarNotificationServiceTest {

    private static final String STOCK_CODE = "2330";
    private static final String MARKET = "台股";
    private static final Long SETTING_ID = 1L;

    @Mock private TradingRadarNotificationSettingRepository settingRepo;
    @Mock private TradingRadarNotificationStateRepository stateRepo;
    @Mock private AssetSnapshotRepository snapshotRepo;
    @Mock private TradingRadarService tradingRadarService;
    @Mock private TradingRadarNotificationTransition transition;
    @Mock private TradingRadarNotificationDispatcher dispatcher;
    @Mock private ObjectProvider<TradingRadarNotificationService> self;

    /** flushEvaluations() 每輪每市場只組一次（Task 302）；本檔只驗冷卻，snapshot 內容本身不重要。 */
    private static final TradingRadarService.MarketSnapshot SNAPSHOT =
            new TradingRadarService.MarketSnapshot(TradingRadarRuleEngine.MarketRegime.NEUTRAL, false, Instant.now());

    private TradingRadarNotificationService service;
    private TradingRadarNotificationSetting setting;

    @BeforeEach
    void setUp() {
        service = new TradingRadarNotificationService(
                settingRepo, stateRepo, snapshotRepo, tradingRadarService, transition, dispatcher, self);
        setting = TradingRadarNotificationSetting.builder()
                .id(SETTING_ID).ownerUserId(1L).stockCode(STOCK_CODE).market(MARKET)
                .active(true).initialized(true).ruleVersion(TradingRadarRuleEngine.RULE_VERSION)
                .lastAction("HOLD").lastCounterTrendState("NONE")
                .build();
        when(settingRepo.findByActiveTrueAndStockCodeAndMarket(STOCK_CODE, MARKET))
                .thenReturn(List.of(setting));
        when(tradingRadarService.buildMarketSnapshot(MARKET)).thenReturn(SNAPSHOT);
        when(tradingRadarService.evaluateForNotification(eq(STOCK_CODE), eq(MARKET), anyBoolean(), eq(SNAPSHOT)))
                .thenReturn(decision());
        // actionEntered=true／counterTrendEntered=false：每個案例只需操心單一狀態的冷卻。
        when(transition.evaluate(anyBoolean(), any(), any(), any(), any(), anySet(), anySet()))
                .thenReturn(new TradingRadarNotificationTransition.Result(true, false, "EXIT_CANDIDATE", "NONE"));
    }

    private static TradingRadarDto.StockDecision decision() {
        return new TradingRadarDto.StockDecision(
                STOCK_CODE, "台積電", MARKET, "STOCK", false, false,
                "EXIT_CANDIDATE", "出場候選", 70,
                "NONE", "無", List.of(), List.of(),
                true, BigDecimal.TEN, BigDecimal.ONE,
                "REALTIME", "2026-08-09T10:00:00+08:00", "2026-08-09",
                null, null, null, null, null,
                null, null, null, null, null,
                List.of(), List.of(), null, null, null,
                null, null, null, null, null, null);
    }

    private static TradingRadarNotificationState actionRow(Instant lastNotifiedAt) {
        return TradingRadarNotificationState.builder()
                .id(9L).settingId(SETTING_ID)
                .stateType(TradingRadarNotificationState.TYPE_ACTION).stateCode("EXIT_CANDIDATE")
                .lastNotifiedAt(lastNotifiedAt)
                .build();
    }

    @Test
    void 首次轉入無冷卻紀錄時通知並寫入lastNotifiedAt() {
        TradingRadarNotificationState row = actionRow(null);
        when(stateRepo.findBySettingId(SETTING_ID)).thenReturn(List.of(row));

        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        verify(dispatcher).enqueue(eq(SETTING_ID), any(), eq(List.of("出場候選")));
        assertNotNull(row.getLastNotifiedAt());
        assertEquals("EXIT_CANDIDATE", setting.getLastAction());
    }

    @Test
    void 冷卻窗內再轉入同狀態不通知但基準仍更新() {
        Instant recentlyNotified = Instant.now().minus(Duration.ofMinutes(5));
        TradingRadarNotificationState row = actionRow(recentlyNotified);
        when(stateRepo.findBySettingId(SETTING_ID)).thenReturn(List.of(row));

        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        verify(dispatcher, never()).enqueue(any(), any(), any());
        assertEquals(recentlyNotified, row.getLastNotifiedAt(), "冷卻中不得覆寫 lastNotifiedAt");
        assertEquals("EXIT_CANDIDATE", setting.getLastAction(), "last_action 基準不受冷卻影響");
        verify(settingRepo).save(setting);
    }

    @Test
    void 冷卻窗外再轉入時恢復通知() {
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(61));
        TradingRadarNotificationState row = actionRow(longAgo);
        when(stateRepo.findBySettingId(SETTING_ID)).thenReturn(List.of(row));

        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        verify(dispatcher).enqueue(eq(SETTING_ID), any(), eq(List.of("出場候選")));
        assertTrue(row.getLastNotifiedAt().isAfter(longAgo));
    }
}
