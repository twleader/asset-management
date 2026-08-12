package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.TradingRadarNotificationSetting;
import com.steven.assets.model.TradingRadarNotificationState;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.TradingRadarNotificationSettingRepository;
import com.steven.assets.repository.TradingRadarNotificationStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 收到台股價格事件後，背景評估逐檔通知設定；不依賴交易雷達頁是否開啟。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingRadarNotificationService {

    private static final String TW_MARKET = "台股";
    private static final String TAIEX_CODE = "0000";
    private static final String ALL_TW = "*\0台股";
    /** 洗版抑制窗：判斷性取值、無量測依據（Task 301 / Requirement 44）。只擋 email，不影響 last_action 基準。 */
    static final Duration NOTIFY_COOLDOWN = Duration.ofMinutes(60);

    private final TradingRadarNotificationSettingRepository settingRepo;
    private final TradingRadarNotificationStateRepository stateRepo;
    private final AssetSnapshotRepository snapshotRepo;
    private final TradingRadarService tradingRadarService;
    private final TradingRadarNotificationTransition transition;
    private final TradingRadarNotificationDispatcher dispatcher;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    /**
     * 自身 proxy：{@code runCycle} 需經 proxy 呼叫 {@code flushEvaluations} 才會套用 {@code @Transactional}
     * （同類自呼叫會繞過 Spring proxy）。以 ObjectProvider 延遲取得，避免建構期循環依賴。
     */
    private final ObjectProvider<TradingRadarNotificationService> self;

    public void queueEvaluation(String rawCode, String market) {
        if (rawCode == null || !TW_MARKET.equals(market)) return;
        String code = rawCode.trim().toUpperCase();
        pending.add(TAIEX_CODE.equals(code) ? ALL_TW : code + '\0' + market);
    }

    /**
     * 單一 2 秒節拍：先評估（交易性），commit 後在同一輪把該批通知派送出去。
     *
     * <p>刻意不讓 dispatcher 自帶獨立排程：兩個獨立計時器會讓 dispatcher 可能在
     * {@code flushEvaluations} 尚未 enqueue 完時就 drain，把同一輪的通知拆成兩封信，
     * 破壞「每位收件人一輪一封」的合併。派送置於交易之外，避免 SMTP I/O 撐長交易，
     * 也避免「信已寄出但交易回滾」造成下一輪重寄。</p>
     */
    @Scheduled(fixedDelay = 2_000L, initialDelay = 2_000L)
    public void runCycle() {
        try {
            self.getObject().flushEvaluations();
        } catch (Exception e) {
            log.warn("交易雷達通知評估失敗", e);
        }
        try {
            dispatcher.flush();
        } catch (Exception e) {
            log.warn("交易雷達通知派送失敗", e);
        }
    }

    /** 同輪多檔價格事件先合併，每個 setting 只評估一次。由 {@link #runCycle()} 經 proxy 呼叫。 */
    @Transactional
    public void flushEvaluations() {
        Set<String> keys = drainPending();
        if (keys.isEmpty()) return;

        Map<Long, TradingRadarNotificationSetting> settings = new LinkedHashMap<>();
        if (keys.contains(ALL_TW)) {
            settingRepo.findByActiveTrueAndMarket(TW_MARKET)
                    .forEach(setting -> settings.put(setting.getId(), setting));
        } else {
            for (String key : keys) {
                int separator = key.indexOf('\0');
                if (separator < 1) continue;
                String code = key.substring(0, separator);
                String market = key.substring(separator + 1);
                settingRepo.findByActiveTrueAndStockCodeAndMarket(code, market)
                        .forEach(setting -> settings.put(setting.getId(), setting));
            }
        }
        // 每輪出現的每個市場只組一次大盤（Task 302），取代逐檔 evaluateForNotification 各自重建。
        Map<String, TradingRadarService.MarketSnapshot> byMarket = new HashMap<>();
        settings.values().forEach(setting -> {
            TradingRadarService.MarketSnapshot snapshot =
                    byMarket.computeIfAbsent(setting.getMarket(), tradingRadarService::buildMarketSnapshot);
            evaluateSettingSafely(setting, snapshot);
        });
    }

    private Set<String> drainPending() {
        Set<String> drained = new LinkedHashSet<>(pending);
        pending.removeAll(drained);
        return drained;
    }

    private void evaluateSettingSafely(
            TradingRadarNotificationSetting setting, TradingRadarService.MarketSnapshot snapshot) {
        try {
            boolean held = isHeld(setting.getOwnerUserId(), setting.getStockCode(), setting.getMarket());
            TradingRadarDto.StockDecision decision = tradingRadarService.evaluateForNotification(
                    setting.getStockCode(), setting.getMarket(), held, snapshot);
            // Action semantics can change without changing V12 scoring/ruleVersion.  An invalid
            // baseline is rebuilt from the same post-gate StockDecision used by the page/export,
            // then exits before transition/cooldown/dispatcher side effects.
            boolean baselineValid = Boolean.TRUE.equals(setting.getInitialized())
                    && TradingRadarRuleEngine.RULE_VERSION.equals(setting.getRuleVersion())
                    && TradingRadarEvidenceGate.ACTION_POLICY_VERSION.equals(
                    setting.getActionPolicyVersion());
            if (!baselineValid) {
                setting.setInitialized(true);
                setting.setRuleVersion(TradingRadarRuleEngine.RULE_VERSION);
                setting.setActionPolicyVersion(TradingRadarEvidenceGate.ACTION_POLICY_VERSION);
                setting.setLastAction(decision.action());
                setting.setLastCounterTrendState(decision.counterTrendState());
                settingRepo.save(setting);
                return;
            }
            Set<String> actions = Set.copyOf(stateRepo.findStateCodes(
                    setting.getId(), TradingRadarNotificationState.TYPE_ACTION));
            Set<String> counterTrends = Set.copyOf(stateRepo.findStateCodes(
                    setting.getId(), TradingRadarNotificationState.TYPE_COUNTER_TREND));
            TradingRadarNotificationTransition.Result result = transition.evaluate(
                    true,
                    setting.getLastAction(),
                    setting.getLastCounterTrendState(),
                    decision.action(),
                    decision.counterTrendState(),
                    actions,
                    counterTrends);

            // 基準更新與 save 恆執行，不受下面的通知冷卻影響——冷卻只擋 email（Requirement 44）。
            setting.setInitialized(true);
            setting.setRuleVersion(TradingRadarRuleEngine.RULE_VERSION);
            setting.setActionPolicyVersion(TradingRadarEvidenceGate.ACTION_POLICY_VERSION);
            setting.setLastAction(result.nextAction());
            setting.setLastCounterTrendState(result.nextCounterTrend());
            settingRepo.save(setting);

            if (result.shouldNotify()) {
                List<String> entered = new ArrayList<>();
                Instant now = Instant.now();
                List<TradingRadarNotificationState> rows = stateRepo.findBySettingId(setting.getId());
                if (result.actionEntered() && notifyAllowed(
                        rows, TradingRadarNotificationState.TYPE_ACTION, decision.action(), setting.getId(), now)) {
                    entered.add(decision.actionLabel());
                }
                if (result.counterTrendEntered() && notifyAllowed(
                        rows, TradingRadarNotificationState.TYPE_COUNTER_TREND, decision.counterTrendState(),
                        setting.getId(), now)) {
                    entered.add(decision.counterTrendLabel());
                }
                // 兩狀態各自獨立判冷卻；同輪全部被抑制時本次不派送。
                if (!entered.isEmpty()) {
                    dispatcher.enqueue(setting.getId(), decision, entered);
                }
            }
        } catch (Exception e) {
            log.warn("交易雷達通知評估失敗 setting={} stock={} {}：{}",
                    setting.getId(), setting.getMarket(), setting.getStockCode(), e.getMessage());
        }
    }

    /**
     * 單一 entered 狀態是否放行本輪通知；放行時就地寫入 {@code lastNotifiedAt} 並 save（副作用）。
     *
     * <p>對應列理論上恆存在——{@link TradingRadarNotificationTransition} 已限定 entered 狀態
     * ∈ 訂閱集合，而訂閱集合就是這張表。查無時不拋錯中斷整輪，視同無冷卻並記警告。</p>
     */
    private boolean notifyAllowed(
            List<TradingRadarNotificationState> rows, String stateType, String stateCode,
            Long settingId, Instant now) {
        TradingRadarNotificationState row = rows.stream()
                .filter(r -> stateType.equals(r.getStateType()) && stateCode.equals(r.getStateCode()))
                .findFirst()
                .orElse(null);
        if (row == null) {
            log.warn("交易雷達通知冷卻找不到對應訂閱列 setting={} stateType={} stateCode={}",
                    settingId, stateType, stateCode);
            return true;
        }
        if (cooldownActive(row.getLastNotifiedAt(), now)) {
            return false;
        }
        row.setLastNotifiedAt(now);
        stateRepo.save(row);
        return true;
    }

    /** 純函式：冷卻窗內（{@code < NOTIFY_COOLDOWN}）回傳 true。從未寄過（null）恆放行。 */
    static boolean cooldownActive(Instant lastNotifiedAt, Instant now) {
        return lastNotifiedAt != null && Duration.between(lastNotifiedAt, now).compareTo(NOTIFY_COOLDOWN) < 0;
    }

    private boolean isHeld(Long ownerUserId, String stockCode, String market) {
        List<StockHolding> holdings = snapshotRepo.findLatestWithStocksByOwnerUserId(ownerUserId)
                .map(AssetSnapshot::getStocks)
                .orElse(List.of());
        return isHeld(holdings, stockCode, market);
    }

    static boolean isHeld(List<StockHolding> holdings, String stockCode, String market) {
        return holdings.stream()
                .anyMatch(holding -> sameHeldStock(holding, stockCode, market));
    }

    private static boolean sameHeldStock(StockHolding holding, String stockCode, String market) {
        return stockCode.equals(holding.getStockCode())
                && market.equals(holding.getMarket())
                && holding.getShares() != null
                && holding.getShares().signum() > 0;
    }
}
