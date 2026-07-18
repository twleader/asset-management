package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarNotificationDto;
import com.steven.assets.model.NotificationRecipient;
import com.steven.assets.model.TradingRadarNotificationRecipient;
import com.steven.assets.model.TradingRadarNotificationSetting;
import com.steven.assets.model.TradingRadarNotificationState;
import com.steven.assets.repository.NotificationRecipientRepository;
import com.steven.assets.repository.TradingRadarNotificationRecipientRepository;
import com.steven.assets.repository.TradingRadarNotificationSettingRepository;
import com.steven.assets.repository.TradingRadarNotificationStateRepository;
import com.steven.assets.security.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 每檔交易雷達狀態通知設定 CRUD；所有 HTTP 存取皆 owner-scoped。 */
@Service
@RequiredArgsConstructor
public class TradingRadarNotificationSettingService {

    private static final String TW_MARKET = "台股";

    private final TradingRadarNotificationSettingRepository settingRepo;
    private final TradingRadarNotificationStateRepository stateRepo;
    private final TradingRadarNotificationRecipientRepository recipientLinkRepo;
    private final NotificationRecipientRepository recipientRepo;
    private final TenantGuard tenantGuard;

    @Transactional(readOnly = true)
    public TradingRadarNotificationDto.Response get(String rawCode, String market) {
        String code = normalize(rawCode, market);
        TradingRadarNotificationSetting setting = settingRepo.findByStockCodeAndMarket(code, market)
                .orElse(null);
        if (setting != null) tenantGuard.assertOwned(setting.getOwnerUserId());
        return toResponse(code, market, setting);
    }

    @Transactional
    public TradingRadarNotificationDto.Response save(
            String rawCode,
            String market,
            TradingRadarNotificationDto.Request request) {
        String code = normalize(rawCode, market);
        if (request == null) throw new IllegalArgumentException("通知設定不可為空");

        LinkedHashSet<String> actions = validActions(request.actionStates());
        LinkedHashSet<String> counterTrends = validCounterTrends(request.counterTrendStates());
        LinkedHashSet<Long> recipientIds = distinctIds(request.recipientIds());
        boolean active = Boolean.TRUE.equals(request.active());
        if (active && actions.isEmpty() && counterTrends.isEmpty()) {
            throw new IllegalArgumentException("啟用通知時至少選擇一個狀態");
        }
        if (active && recipientIds.isEmpty()) {
            throw new IllegalArgumentException("啟用通知時至少選擇一位收件人");
        }

        Set<Long> allowedRecipientIds = recipientRepo.findByIdIn(recipientIds).stream()
                .map(NotificationRecipient::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!allowedRecipientIds.containsAll(recipientIds)) {
            throw new IllegalArgumentException("通知設定包含不屬於目前使用者的收件人");
        }

        TradingRadarNotificationSetting setting = settingRepo.findByStockCodeAndMarket(code, market)
                .orElseGet(() -> TradingRadarNotificationSetting.builder()
                        .ownerUserId(tenantGuard.requireCurrentUserId())
                        .stockCode(code)
                        .market(market)
                        .build());
        if (setting.getId() != null) tenantGuard.assertOwned(setting.getOwnerUserId());
        setting.setActive(active);
        setting.setInitialized(false);
        setting.setLastAction(null);
        setting.setLastCounterTrendState(null);
        TradingRadarNotificationSetting saved = settingRepo.save(setting);

        stateRepo.deleteBySettingId(saved.getId());
        actions.forEach(state -> stateRepo.save(TradingRadarNotificationState.builder()
                .settingId(saved.getId())
                .stateType(TradingRadarNotificationState.TYPE_ACTION)
                .stateCode(state)
                .build()));
        counterTrends.forEach(state -> stateRepo.save(TradingRadarNotificationState.builder()
                .settingId(saved.getId())
                .stateType(TradingRadarNotificationState.TYPE_COUNTER_TREND)
                .stateCode(state)
                .build()));

        recipientLinkRepo.deleteBySettingId(saved.getId());
        recipientIds.forEach(recipientId -> recipientLinkRepo.save(
                TradingRadarNotificationRecipient.builder()
                        .settingId(saved.getId())
                        .recipientId(recipientId)
                        .build()));
        return toResponse(code, market, saved);
    }

    private TradingRadarNotificationDto.Response toResponse(
            String code,
            String market,
            TradingRadarNotificationSetting setting) {
        List<String> actions = setting == null ? List.of() : stateRepo.findStateCodes(
                setting.getId(), TradingRadarNotificationState.TYPE_ACTION);
        List<String> counterTrends = setting == null ? List.of() : stateRepo.findStateCodes(
                setting.getId(), TradingRadarNotificationState.TYPE_COUNTER_TREND);
        List<Long> recipientIds = setting == null ? List.of()
                : recipientLinkRepo.findRecipientIds(setting.getId());
        List<TradingRadarNotificationDto.RecipientOption> recipients = recipientRepo
                .findAllByOrderByCreatedAtAsc().stream()
                .map(recipient -> new TradingRadarNotificationDto.RecipientOption(
                        recipient.getId(), recipient.getEmail(), Boolean.TRUE.equals(recipient.getActive())))
                .toList();
        return new TradingRadarNotificationDto.Response(
                code,
                market,
                setting != null,
                setting != null && Boolean.TRUE.equals(setting.getActive()),
                actions,
                counterTrends,
                recipientIds,
                actionOptions(),
                counterTrendOptions(),
                recipients);
    }

    private List<TradingRadarNotificationDto.Option> actionOptions() {
        return Arrays.stream(TradingRadarRuleEngine.Action.values())
                .map(action -> new TradingRadarNotificationDto.Option(
                        action.name(), TradingRadarService.actionLabel(action)))
                .toList();
    }

    private List<TradingRadarNotificationDto.Option> counterTrendOptions() {
        return List.of(
                TradingRadarRuleEngine.CounterTrendState.OVERSOLD_WATCH,
                TradingRadarRuleEngine.CounterTrendState.TRIAL_CANDIDATE).stream()
                .map(state -> new TradingRadarNotificationDto.Option(
                        state.name(), TradingRadarService.counterTrendLabel(state)))
                .toList();
    }

    private String normalize(String rawCode, String market) {
        if (!TW_MARKET.equals(market)) throw new IllegalArgumentException("交易雷達通知目前只支援台股");
        if (rawCode == null || rawCode.isBlank()) throw new IllegalArgumentException("股票代號不可為空");
        String code = rawCode.trim().toUpperCase();
        if ("0000".equals(code)) throw new IllegalArgumentException("台股大盤不可建立個股通知設定");
        return code;
    }

    private LinkedHashSet<String> validActions(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            try {
                result.add(TradingRadarRuleEngine.Action.valueOf(value).name());
            } catch (Exception e) {
                throw new IllegalArgumentException("不支援的主規則狀態：" + value);
            }
        }
        return result;
    }

    private LinkedHashSet<String> validCounterTrends(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            if (value == null) throw new IllegalArgumentException("逆勢狀態不可為空");
            TradingRadarRuleEngine.CounterTrendState state;
            try {
                state = TradingRadarRuleEngine.CounterTrendState.valueOf(value);
            } catch (Exception e) {
                throw new IllegalArgumentException("不支援的逆勢狀態：" + value);
            }
            if (state == TradingRadarRuleEngine.CounterTrendState.NONE) {
                throw new IllegalArgumentException("NONE 不是可通知的逆勢狀態");
            }
            result.add(state.name());
        }
        return result;
    }

    private LinkedHashSet<Long> distinctIds(List<Long> values) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        if (values != null) values.stream().filter(java.util.Objects::nonNull).forEach(result::add);
        return result;
    }
}
