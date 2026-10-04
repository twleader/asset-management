package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import java.util.ArrayList;
import java.util.List;

/** One conservative final-action veto after the existing evidence gate. */
public final class RadarOfficialSma20Gate {
    private static final String REASON = "同日富邦原始 SMA20 與本地完成價重播不一致，候選動作保守降級。 ";
    private RadarOfficialSma20Gate() {}

    public static TradingRadarEvidenceGate.GatedActions apply(
            TradingRadarEvidenceGate.GatedActions current,
            TradingRadarDto.OfficialSma20Verification verification, boolean held) {
        if (current == null || verification == null || !"CONFLICT".equals(verification.status())) return current;
        TradingRadarRuleEngine.Action fallback = held
                ? TradingRadarRuleEngine.Action.HOLD : TradingRadarRuleEngine.Action.WATCH;
        List<String> medium = new ArrayList<>(current.mediumDiagnostics());
        List<String> shortTerm = new ArrayList<>(current.shortDiagnostics());
        List<String> swing = new ArrayList<>(current.swingDiagnostics());
        TradingRadarRuleEngine.Action mediumAction = downgrade(current.mediumAction(), fallback, medium);
        TradingRadarRuleEngine.Action shortAction = downgrade(current.shortAction(), fallback, shortTerm);
        TradingRadarRuleEngine.Action swingAction = downgrade(current.swingAction(), fallback, swing);
        return new TradingRadarEvidenceGate.GatedActions(mediumAction, shortAction, swingAction,
                medium, shortTerm, swing, current.candidateMediumAction(), current.candidateShortAction(),
                current.candidateSwingAction());
    }

    private static TradingRadarRuleEngine.Action downgrade(TradingRadarRuleEngine.Action action,
                                                           TradingRadarRuleEngine.Action fallback,
                                                           List<String> diagnostics) {
        if (action == TradingRadarRuleEngine.Action.BUY_CANDIDATE
                || action == TradingRadarRuleEngine.Action.ADD_CANDIDATE
                || action == TradingRadarRuleEngine.Action.TRIAL_BUY
                || action == TradingRadarRuleEngine.Action.REDUCE_CANDIDATE
                || action == TradingRadarRuleEngine.Action.EXIT_CANDIDATE) {
            diagnostics.add(REASON);
            return fallback;
        }
        return action;
    }
}
