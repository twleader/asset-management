package com.steven.assets.service;

import com.steven.assets.model.StockHolding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingRadarNotificationTransitionTest {

    private final TradingRadarNotificationTransition transition =
            new TradingRadarNotificationTransition();

    @Test
    void firstEvaluationOnlyBuildsBaseline() {
        var result = transition.evaluate(
                false, null, null,
                "EXIT_CANDIDATE", "OVERSOLD_WATCH",
                Set.of("EXIT_CANDIDATE"), Set.of("OVERSOLD_WATCH"));

        assertFalse(result.shouldNotify());
    }

    @Test
    void enteringSelectedStatesNotifiesButSameStateDoesNotRepeat() {
        var entered = transition.evaluate(
                true, "HOLD", "NONE",
                "EXIT_CANDIDATE", "OVERSOLD_WATCH",
                Set.of("EXIT_CANDIDATE"), Set.of("OVERSOLD_WATCH"));
        var unchanged = transition.evaluate(
                true, entered.nextAction(), entered.nextCounterTrend(),
                "EXIT_CANDIDATE", "OVERSOLD_WATCH",
                Set.of("EXIT_CANDIDATE"), Set.of("OVERSOLD_WATCH"));

        assertTrue(entered.actionEntered());
        assertTrue(entered.counterTrendEntered());
        assertFalse(unchanged.shouldNotify());
    }

    @Test
    void leavingAndReenteringSelectedStateNotifiesAgain() {
        var left = transition.evaluate(
                true, "EXIT_CANDIDATE", "OVERSOLD_WATCH",
                "HOLD_CAUTION", "NONE",
                Set.of("EXIT_CANDIDATE"), Set.of("OVERSOLD_WATCH"));
        var reentered = transition.evaluate(
                true, left.nextAction(), left.nextCounterTrend(),
                "EXIT_CANDIDATE", "OVERSOLD_WATCH",
                Set.of("EXIT_CANDIDATE"), Set.of("OVERSOLD_WATCH"));

        assertFalse(left.shouldNotify());
        assertTrue(reentered.shouldNotify());
    }

    @Test
    void enteringUnselectedStateDoesNotNotify() {
        var result = transition.evaluate(
                true, "HOLD", "NONE",
                "WAIT", "OVERSOLD_WATCH",
                Set.of("EXIT_CANDIDATE"), Set.of("TRIAL_CANDIDATE"));

        assertFalse(result.shouldNotify());
    }

    @Test
    void heldMappingUsesOnlyPositiveSharesForTheSameStockAndMarket() {
        List<StockHolding> holdings = List.of(
                StockHolding.builder().stockCode("009804").market("台股")
                        .shares(new BigDecimal("1000")).build(),
                StockHolding.builder().stockCode("2330").market("台股")
                        .shares(BigDecimal.ZERO).build());

        assertTrue(TradingRadarNotificationService.isHeld(holdings, "009804", "台股"));
        assertFalse(TradingRadarNotificationService.isHeld(holdings, "009804", "美股"));
        assertFalse(TradingRadarNotificationService.isHeld(holdings, "2330", "台股"));
    }

    // --- Task 301：通知冷卻純函式（同一語意群，冷卻只擋 email，不影響本檔既有的轉入語意測試） ---

    @Test
    void cooldownActiveIsFalseWhenNeverNotified() {
        assertFalse(TradingRadarNotificationService.cooldownActive(null, Instant.now()));
    }

    @Test
    void cooldownActiveWithin59MinutesIsTrue() {
        Instant now = Instant.now();
        assertTrue(TradingRadarNotificationService.cooldownActive(
                now.minus(Duration.ofMinutes(59)), now));
    }

    @Test
    void cooldownActiveAfter61MinutesIsFalse() {
        Instant now = Instant.now();
        assertFalse(TradingRadarNotificationService.cooldownActive(
                now.minus(Duration.ofMinutes(61)), now));
    }

    /** 恰 60 分為邊界：{@code compareTo(NOTIFY_COOLDOWN) < 0} 排除相等，冷卻視為已過。 */
    @Test
    void cooldownActiveAtExactly60MinutesIsFalse() {
        Instant now = Instant.now();
        assertFalse(TradingRadarNotificationService.cooldownActive(
                now.minus(Duration.ofMinutes(60)), now));
    }
}
