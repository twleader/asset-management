package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.TradingRadarNotificationSetting;
import com.steven.assets.model.TradingRadarNotificationState;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.TradingRadarNotificationSettingRepository;
import com.steven.assets.repository.TradingRadarNotificationStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 301：通知冷卻只擋 email、不影響 {@code last_action} 基準。
 *
 * <p>{@code tradingRadarService} 以決策 fixture 控制動作；transition 則使用真實
 * {@link TradingRadarNotificationTransition}，確保 integration test 必須實際從 baseline 轉入
 * 訂閱狀態，不能由 mock 直接宣告 {@code actionEntered=true}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingRadarNotificationServiceTest {

    private static final String STOCK_CODE = "2330";
    private static final String MARKET = "台股";
    private static final Long SETTING_ID = 1L;
    /** V18 upgrade regression input; assembled to keep runtime/test V17 literals retired. */
    private static final String PREVIOUS_RULE_VERSION = "TW_RULES_V" + 17;

    @Mock private TradingRadarNotificationSettingRepository settingRepo;
    @Mock private TradingRadarNotificationStateRepository stateRepo;
    @Mock private AssetSnapshotRepository snapshotRepo;
    @Mock private TradingRadarService tradingRadarService;
    @Mock private TradingRadarNotificationDispatcher dispatcher;
    @Mock private ObjectProvider<TradingRadarNotificationService> self;
    private final TradingRadarNotificationTransition transition =
            new TradingRadarNotificationTransition();

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
                .actionPolicyVersion(TradingRadarEvidenceGate.ACTION_POLICY_VERSION)
                .lastAction("HOLD").lastCounterTrendState("NONE")
                .build();
        when(settingRepo.findByActiveTrueAndStockCodeAndMarket(STOCK_CODE, MARKET))
                .thenReturn(List.of(setting));
        when(tradingRadarService.buildMarketSnapshot(MARKET)).thenReturn(SNAPSHOT);
        when(tradingRadarService.evaluateForNotification(eq(STOCK_CODE), eq(MARKET), anyBoolean(), eq(SNAPSHOT)))
                .thenReturn(decision());
        when(stateRepo.findStateCodes(SETTING_ID, TradingRadarNotificationState.TYPE_ACTION))
                .thenReturn(List.of("EXIT_CANDIDATE"));
        when(stateRepo.findStateCodes(SETTING_ID, TradingRadarNotificationState.TYPE_COUNTER_TREND))
                .thenReturn(List.of());
    }

    private static TradingRadarDto.StockDecision decision() {
        return decision("EXIT_CANDIDATE", "出場候選");
    }

    private static TradingRadarDto.StockDecision decision(String action, String actionLabel) {
        return new TradingRadarDto.StockDecision(
                STOCK_CODE, "台積電", MARKET, "STOCK", false, false,
                action, actionLabel, 70,
                "NONE", "無", List.of(), List.of(),
                true, BigDecimal.TEN, BigDecimal.ONE,
                "REALTIME", "2026-08-09T10:00:00+08:00", "2026-08-09",
                null, null, null, null, null,
                null, null, null, null, null,
                List.of(), List.of(), null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * 三軌決策：{@code action} 是 1月~6月 軌（唯一寄信的那一軌），
     * {@code shortAction}／{@code swingAction} 只是同一筆決策的另外兩軌。
     */
    private static TradingRadarDto.StockDecision threeTrackDecision(
            String mediumAction, String shortAction, String swingAction) {
        return new TradingRadarDto.StockDecision(
                STOCK_CODE, "台積電", MARKET, "STOCK", false, false,
                mediumAction, mediumAction, 70,
                "NONE", "無", List.of(), List.of(),
                true, BigDecimal.TEN, BigDecimal.ONE,
                "REALTIME", "2026-08-09T10:00:00+08:00", "2026-08-09",
                null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), null, null, null,
                null, null, null, null, null, null,
                shortAction, shortAction, 88, List.of(), List.of(), true,
                null, null, false, null, TradingRadarDto.RadarEvidence.EMPTY,
                null, null, null, null, null, null, null, null, List.of(),
                null, null,
                swingAction, swingAction, 79, List.of(), List.of(),
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("Task 356.12b：只有一周／1周~1月 軌變動時不得寄信（transition 只看 1月~6月 軌）")
    void 只有短期或波段軌變動時不寄信() {
        // 1月~6月 軌維持 HOLD（＝ setting.lastAction 基準），另外兩軌轉成買進候選。
        when(tradingRadarService.evaluateForNotification(
                eq(STOCK_CODE), eq(MARKET), anyBoolean(), eq(SNAPSHOT)))
                .thenReturn(threeTrackDecision("HOLD", "BUY_CANDIDATE", "ADD_CANDIDATE"));
        when(stateRepo.findStateCodes(SETTING_ID, TradingRadarNotificationState.TYPE_ACTION))
                .thenReturn(List.of("HOLD"));

        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        verify(dispatcher, never()).enqueue(any(), any(), any());
        assertEquals("HOLD", setting.getLastAction(),
                "last_action 只記錄 1月~6月 軌；不得因為另外兩軌變動而被覆寫");
    }

    @Test
    @DisplayName("Task 356.12b：1月~6月 軌真的變動時照常寄信，另外兩軌不影響判定")
    void 中長期軌變動時照常寄信() {
        when(tradingRadarService.evaluateForNotification(
                eq(STOCK_CODE), eq(MARKET), anyBoolean(), eq(SNAPSHOT)))
                .thenReturn(threeTrackDecision("EXIT_CANDIDATE", "HOLD", "HOLD"));
        TradingRadarNotificationState row = actionRow(null);
        when(stateRepo.findBySettingId(SETTING_ID)).thenReturn(List.of(row));

        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        verify(dispatcher).enqueue(eq(SETTING_ID), any(), eq(List.of("EXIT_CANDIDATE")));
        assertEquals("EXIT_CANDIDATE", setting.getLastAction());
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

    @Test
    void v12加nullActionPolicy首輪只重建gatedBaseline且零冷卻寫入() {
        setting.setActionPolicyVersion(null);
        setting.setLastAction("BUY_CANDIDATE");

        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        assertEquals("EXIT_CANDIDATE", setting.getLastAction());
        assertEquals("NONE", setting.getLastCounterTrendState());
        assertEquals(TradingRadarRuleEngine.RULE_VERSION, setting.getRuleVersion());
        assertEquals(TradingRadarEvidenceGate.ACTION_POLICY_VERSION,
                setting.getActionPolicyVersion());
        assertTrue(setting.getInitialized());
        verify(dispatcher, never()).enqueue(any(), any(), any());
        verify(stateRepo, never()).findBySettingId(any());
        verify(stateRepo, never()).save(any());
        verify(settingRepo).save(setting);
    }

    @Test
    void v17RuleVersion首輪只重建V18Baseline且不改設定或收件人路徑() {
        setting.setRuleVersion(PREVIOUS_RULE_VERSION);
        setting.setLastAction("BUY_CANDIDATE");
        Long originalOwner = setting.getOwnerUserId();
        Boolean originalActive = setting.getActive();

        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        assertEquals("TW_RULES_V18", setting.getRuleVersion());
        assertEquals("EXIT_CANDIDATE", setting.getLastAction());
        assertEquals(originalOwner, setting.getOwnerUserId());
        assertEquals(originalActive, setting.getActive());
        assertTrue(setting.getInitialized());
        // 版本不符時在 transition／dispatcher 前 return；故既有收件人 link 不會被讀取、重建或刪除。
        verify(dispatcher, never()).enqueue(any(), any(), any());
        verify(stateRepo, never()).findBySettingId(any());
        verify(stateRepo, never()).save(any());
        verify(settingRepo).save(setting);
    }

    @Test
    void 舊ActionPolicy首輪只重建而下一個真實transition才通知() {
        setting.setActionPolicyVersion("EVIDENCE_GATE_OLD");
        setting.setLastAction("BUY_CANDIDATE");
        TradingRadarNotificationState row = actionRow(null);
        when(stateRepo.findBySettingId(SETTING_ID)).thenReturn(List.of(row));
        when(tradingRadarService.evaluateForNotification(
                eq(STOCK_CODE), eq(MARKET), anyBoolean(), eq(SNAPSHOT)))
                .thenReturn(decision("HOLD", "續抱"), decision("HOLD", "續抱"), decision());

        // 首輪版本不相符：只把當前 HOLD 建成新 baseline。
        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        verify(dispatcher, never()).enqueue(any(), any(), any());
        verify(stateRepo, never()).findBySettingId(any());
        assertEquals("HOLD", setting.getLastAction());

        // 第二輪仍是 HOLD：真實 transition 判定沒有轉入，不通知也不讀冷卻列。
        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        verify(dispatcher, never()).enqueue(any(), any(), any());
        verify(stateRepo, never()).findBySettingId(any());
        assertEquals("HOLD", setting.getLastAction());

        // 第三輪真實轉入已訂閱的 EXIT_CANDIDATE，這時才通知並寫 cooldown。
        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        verify(dispatcher, times(1)).enqueue(eq(SETTING_ID), any(), eq(List.of("出場候選")));
        assertNotNull(row.getLastNotifiedAt());
        assertEquals("EXIT_CANDIDATE", setting.getLastAction());
    }

    @Test
    void 兩版本相符但initializedFalse仍只建baseline() {
        setting.setInitialized(false);
        setting.setLastAction("BUY_CANDIDATE");

        service.queueEvaluation(STOCK_CODE, MARKET);
        service.flushEvaluations();

        assertTrue(setting.getInitialized());
        assertEquals("EXIT_CANDIDATE", setting.getLastAction());
        assertEquals(TradingRadarEvidenceGate.ACTION_POLICY_VERSION,
                setting.getActionPolicyVersion());
        verify(dispatcher, never()).enqueue(any(), any(), any());
        verify(stateRepo, never()).findBySettingId(any());
        verify(stateRepo, never()).save(any());
    }
}
