package com.steven.assets.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/** 純函式狀態轉入判斷：首次 baseline，不因同一狀態持續而重複通知。 */
@Component
public class TradingRadarNotificationTransition {

    public Result evaluate(
            boolean initialized,
            String lastAction,
            String lastCounterTrend,
            String currentAction,
            String currentCounterTrend,
            Set<String> selectedActions,
            Set<String> selectedCounterTrends) {
        if (!initialized) {
            return new Result(false, false, currentAction, currentCounterTrend);
        }
        boolean actionEntered = currentAction != null
                && !currentAction.equals(lastAction)
                && selectedActions.contains(currentAction);
        boolean counterTrendEntered = currentCounterTrend != null
                && !currentCounterTrend.equals(lastCounterTrend)
                && selectedCounterTrends.contains(currentCounterTrend);
        return new Result(actionEntered, counterTrendEntered, currentAction, currentCounterTrend);
    }

    public record Result(
            boolean actionEntered,
            boolean counterTrendEntered,
            String nextAction,
            String nextCounterTrend
    ) {
        public boolean shouldNotify() {
            return actionEntered || counterTrendEntered;
        }
    }
}
