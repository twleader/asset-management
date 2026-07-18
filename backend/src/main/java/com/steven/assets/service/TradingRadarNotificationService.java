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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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

    private final TradingRadarNotificationSettingRepository settingRepo;
    private final TradingRadarNotificationStateRepository stateRepo;
    private final AssetSnapshotRepository snapshotRepo;
    private final TradingRadarService tradingRadarService;
    private final TradingRadarNotificationTransition transition;
    private final TradingRadarNotificationDispatcher dispatcher;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    public void queueEvaluation(String rawCode, String market) {
        if (rawCode == null || !TW_MARKET.equals(market)) return;
        String code = rawCode.trim().toUpperCase();
        pending.add(TAIEX_CODE.equals(code) ? ALL_TW : code + '\0' + market);
    }

    /** 同輪多檔價格事件先合併，兩秒後每個 setting 只評估一次。 */
    @Scheduled(fixedDelay = 2_000L, initialDelay = 2_000L)
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
        settings.values().forEach(this::evaluateSettingSafely);
    }

    private Set<String> drainPending() {
        Set<String> drained = new LinkedHashSet<>(pending);
        pending.removeAll(drained);
        return drained;
    }

    private void evaluateSettingSafely(TradingRadarNotificationSetting setting) {
        try {
            boolean held = isHeld(setting.getOwnerUserId(), setting.getStockCode(), setting.getMarket());
            TradingRadarDto.StockDecision decision = tradingRadarService.evaluateForNotification(
                    setting.getStockCode(), setting.getMarket(), held);
            Set<String> actions = Set.copyOf(stateRepo.findStateCodes(
                    setting.getId(), TradingRadarNotificationState.TYPE_ACTION));
            Set<String> counterTrends = Set.copyOf(stateRepo.findStateCodes(
                    setting.getId(), TradingRadarNotificationState.TYPE_COUNTER_TREND));
            TradingRadarNotificationTransition.Result result = transition.evaluate(
                    Boolean.TRUE.equals(setting.getInitialized()),
                    setting.getLastAction(),
                    setting.getLastCounterTrendState(),
                    decision.action(),
                    decision.counterTrendState(),
                    actions,
                    counterTrends);

            setting.setInitialized(true);
            setting.setLastAction(result.nextAction());
            setting.setLastCounterTrendState(result.nextCounterTrend());
            settingRepo.save(setting);

            if (result.shouldNotify()) {
                List<String> entered = new ArrayList<>();
                if (result.actionEntered()) entered.add(decision.actionLabel());
                if (result.counterTrendEntered()) entered.add(decision.counterTrendLabel());
                dispatcher.enqueue(setting.getId(), decision, entered);
            }
        } catch (Exception e) {
            log.warn("交易雷達通知評估失敗 setting={} stock={} {}：{}",
                    setting.getId(), setting.getMarket(), setting.getStockCode(), e.getMessage());
        }
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
