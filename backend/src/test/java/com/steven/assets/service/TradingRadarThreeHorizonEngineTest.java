package com.steven.assets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 356.1／356.5／356.6／356.7a／356.8：三軌評分、五個新因子與三軌保護。
 *
 * <p>本檔的核心不變式：<b>三軌各自建立 {@code Accumulator}</b>，缺值因子不進 {@code sumW}
 * 而由其餘因子重新正規化，<b>不得以 0 冒充缺值</b>。每一個「缺值重分配」測試都以
 * 「缺值 vs 該因子貢獻恰為 0」兩臂對照：若實作是以 0 冒充，兩臂會得到相同分數。</p>
 */
class TradingRadarThreeHorizonEngineTest {

    private final TradingRadarRuleEngine engine = new TradingRadarRuleEngine();

    // ═══ 356.1：三軌契約 ══════════════════════════════════════════════════════

    @Test
    @DisplayName("356.1e 資料不完整時三軌都填 NO_TRADE，swing 不得是 null 物件")
    void incompleteDataFillsAllThreeTracksWithNoTrade() {
        var result = engine.evaluateStock(new TradingRadarRuleEngine.StockInput(
                true, new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO,
                new TradingRadarRuleEngine.Indicators(null, null, null, null, null),
                null, null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL,
                false, null, null, null, null, null, null, null));

        assertThat(result.score()).isNull();
        assertThat(result.shortScore()).isNull();
        assertThat(result.swingScore()).isNull();
        assertThat(result.action()).isEqualTo(TradingRadarRuleEngine.Action.NO_TRADE);
        assertThat(result.shortAction()).isEqualTo(TradingRadarRuleEngine.Action.NO_TRADE);
        assertThat(result.swingAction()).isEqualTo(TradingRadarRuleEngine.Action.NO_TRADE);
        assertThat(result.swingReasons()).isNotNull().isEmpty();
        assertThat(result.swingRisks()).anyMatch(risk -> risk.contains("1周~1月"));
        // 三軌同組（皆為「無法判定」）故不算分歧。
        assertThat(result.horizonConflict()).isFalse();
    }

    @Test
    @DisplayName("356.1c 三軌動作分組不全相同即為 horizonConflict")
    void horizonConflictComparesAllThreeTracks() {
        var aligned = engine.evaluateStock(base().build());
        assertThat(actionGroupOf(aligned.action()))
                .isEqualTo(actionGroupOf(aligned.swingAction()));
        assertThat(aligned.horizonConflict()).isEqualTo(
                actionGroupOf(aligned.action()) != actionGroupOf(aligned.shortAction())
                        || actionGroupOf(aligned.action()) != actionGroupOf(aligned.swingAction()));

        var split = engine.evaluateStock(shortTermOverboughtLongTermIntact(false));
        assertThat(split.horizonConflict()).isEqualTo(
                actionGroupOf(split.action()) != actionGroupOf(split.swingAction())
                        || actionGroupOf(split.action()) != actionGroupOf(split.shortAction()));
    }

    // ═══ 356.7a：三軌各自 Accumulator，不是一個分數切三刀 ═════════════════════════

    @Test
    @DisplayName("356.7a 一周超買／1月~6月結構完好時三軌分數與動作確實不同")
    void shortOverboughtWithIntactLongTermStructureSplitsAllThreeTracks() {
        var result = engine.evaluateStock(shortTermOverboughtLongTermIntact(false));

        assertThat(result.shortScore()).isLessThan(result.swingScore());
        assertThat(result.swingScore()).isLessThan(result.score());
        // 若實作退化為「單一分數套三組門檻」，三軌分數必定相同，上面兩條就會失敗。
        assertThat(List.of(result.shortAction(), result.swingAction(), result.action()))
                .as("三軌動作應兩兩不同，實得 short=%s swing=%s medium=%s",
                        result.shortAction(), result.swingAction(), result.action())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("356.7a／驗證段 held 不影響三軌分數，只改 action 類別")
    void heldFlagChangesActionLabelsButNeverTheThreeScores() {
        var held = engine.evaluateStock(shortTermOverboughtLongTermIntact(true));
        var watched = engine.evaluateStock(shortTermOverboughtLongTermIntact(false));

        assertThat(held.score()).isEqualTo(watched.score());
        assertThat(held.swingScore()).isEqualTo(watched.swingScore());
        assertThat(held.shortScore()).isEqualTo(watched.shortScore());
    }

    @Test
    @DisplayName("356.7a 三軌分數恆落在 [0,100]")
    void allThreeScoresStayInsideZeroToHundred() {
        for (var input : List.of(
                base().build(),
                shortTermOverboughtLongTermIntact(true),
                allPositiveWeeklyAndCandle(),
                allNegativeWeeklyAndCandle())) {
            var result = engine.evaluateStock(input);
            assertThat(result.score()).isBetween(0, 100);
            assertThat(result.swingScore()).isBetween(0, 100);
            assertThat(result.shortScore()).isBetween(0, 100);
        }
    }

    // ═══ 356.5b／356.5d：日K 棒因子 ═══════════════════════════════════════════

    @Test
    @DisplayName("356.5d 全幅非正（漲跌停鎖死）→ 日K 棒因子缺值且 risks 有揭露")
    void lockedLimitCandleDisablesTheDailyCandleFactorWithDisclosure() {
        var locked = engine.evaluateStock(base()
                .dailyCandle(candle("30", "30", "30", "30")).build());
        var missing = engine.evaluateStock(base().build());

        assertThat(locked.risks()).anyMatch(risk -> risk.contains("沒有價格區間"));
        // 三分量全缺 → 因子 null → 與「根本沒有 K 棒」的分數完全相同（權重同樣被重分配）。
        assertThat(locked.score()).isEqualTo(missing.score());
        assertThat(locked.swingScore()).isEqualTo(missing.swingScore());
        assertThat(locked.shortScore()).isEqualTo(missing.shortScore());
    }

    @Test
    @DisplayName("356.5b high < low 的倒置髒列同樣三分量全缺，不得拿到滿貢獻")
    void invertedHighLowCandleIsAlsoUnavailable() {
        var inverted = engine.evaluateStock(base()
                .dailyCandle(candle("12", "10", "20", "19")).build());
        var missing = engine.evaluateStock(base().build());

        assertThat(inverted.risks()).anyMatch(risk -> risk.contains("沒有價格區間"));
        assertThat(inverted.score()).isEqualTo(missing.score());
        assertThat(inverted.shortScore()).isEqualTo(missing.shortScore());
    }

    /**
     * 356.5d 兩種缺值原因的文案不得互相冒充。
     *
     * <p>{@code RadarInputAssembler.candleAt} 只在<b>索引越界</b>時回 {@code null}：DB 列存在但
     * {@code high}／{@code low} 為 null（三欄皆 nullable、無任何 CHECK）時，引擎拿到的是一個
     * <b>非 null</b> 的 {@code CandleInput}，三個分量同樣全缺。此時若沿用「漲跌停鎖死或整日
     * 單一成交價」那句，使用者會讀到一句可查證為假的話——那天既沒鎖死，也不是只成交一筆。</p>
     */
    @Test
    @DisplayName("356.5d 有列但 high／low 為 null → OHLC 資料缺漏句，不得說成漲跌停鎖死")
    void missingHighLowIsDisclosedAsIncompleteOhlcNotAsALockedLimit() {
        var incompleteOhlc = engine.evaluateStock(base()
                .dailyCandle(new TradingRadarRuleEngine.CandleInput(
                        bd("30"), null, null, bd("31"))).build());
        var missing = engine.evaluateStock(base().build());

        assertThat(incompleteOhlc.risks())
                .as("O/H/L 缺漏必須揭露成 OHLC 資料缺漏")
                .anyMatch(risk -> risk.contains("OHLC 資料缺漏"));
        assertThat(incompleteOhlc.risks())
                .as("高低價根本沒有值，宣稱「沒有價格區間（漲跌停鎖死或整日單一成交價）」是假陳述")
                .noneMatch(risk -> risk.contains("沒有價格區間"));
        // 因子仍是 null（三分量全缺）→ 權重重分配後與「根本沒有 K 棒」同分。
        assertThat(incompleteOhlc.score()).isEqualTo(missing.score());
        assertThat(incompleteOhlc.swingScore()).isEqualTo(missing.swingScore());
        assertThat(incompleteOhlc.shortScore()).isEqualTo(missing.shortScore());
    }

    @Test
    @DisplayName("356.5d 全幅非正（四欄俱全）→ 沒有價格區間句，不得說成 OHLC 資料缺漏")
    void lockedLimitIsDisclosedAsMissingRangeNotAsIncompleteOhlc() {
        var locked = engine.evaluateStock(base()
                .dailyCandle(candle("30", "30", "30", "30")).build());

        assertThat(locked.risks()).anyMatch(risk -> risk.contains("沒有價格區間"));
        assertThat(locked.risks())
                .as("四欄俱全，宣稱「OHLC 資料缺漏」同樣是假陳述")
                .noneMatch(risk -> risk.contains("OHLC 資料缺漏"));
    }

    @Test
    @DisplayName("356.5b 缺 open 而全幅為正 → 只剩收盤位置分量，因子仍成立")
    void missingOpenLeavesTheClosePositionComponentAlive() {
        // H=20 L=10 C=20 → closePosition 1.0 → 貢獻 +1；open 缺值故實體與下影線缺值。
        var strongClose = engine.evaluateStock(base()
                .dailyCandle(new TradingRadarRuleEngine.CandleInput(
                        null, bd("20"), bd("10"), bd("20"))).build());
        var missing = engine.evaluateStock(base().build());

        assertThat(strongClose.risks()).noneMatch(risk -> risk.contains("沒有價格區間"));
        assertThat(strongClose.shortScore())
                .as("一周軌的日K 棒權重最高（0.06），單一可用分量也必須改變分數")
                .isNotEqualTo(missing.shortScore());
    }

    @Test
    @DisplayName("356.5b 十字線（high > low 且 close == open）實體方向為 0，不是缺值")
    void dojiWithRealRangeContributesZeroBodyDirection() {
        // H=20 L=10 O=C=12：收盤位置 0.2 → (0.2−0.5)×2 = −0.6、實體 0（十字線）、
        // 下影線 0.2 → (0.2−0.25)×4 = −0.2；三分量平均 −0.266667。
        //
        // ⚠️ 原本用的是 O=C=15（三分量平均 +1/3）。Task 360 把 base() 的 KD/J 貢獻由 0.5
        // 壓到 0（K=60>D=40 → 標準 J 位置 −1.0），base() 的 sigma 因此由 0.588 降到 0.406，
        // 恰與 +1/3 相近：兩臂的 50+50σ 變成 69.744 與 70.303，被 score() 的 Math.round
        // 吃成同一個整數，斷言退化成恆等。改用收在區間下緣的十字線讓兩臂重新可判別；
        // 受測性質（close == open 時實體為 0、整根 K 棒仍計入 sumW 而非缺值）完全不變。
        var doji = engine.evaluateStock(base()
                .dailyCandle(candle("12", "20", "10", "12")).build());
        var missing = engine.evaluateStock(base().build());

        assertThat(doji.risks()).noneMatch(risk -> risk.contains("沒有價格區間"));
        assertThat(doji.shortScore()).isNotEqualTo(missing.shortScore());
    }

    @Test
    @DisplayName("356.5b 長下影線為正貢獻、長上影線為負貢獻")
    void longLowerShadowScoresAboveLongUpperShadow() {
        // 長下影線：O=18 H=20 L=10 C=19 → 位置 0.9／實體 +1／下影線 0.8
        var lower = engine.evaluateStock(base()
                .dailyCandle(candle("18", "20", "10", "19")).build());
        // 長上影線：O=12 H=20 L=10 C=11 → 位置 0.1／實體 −1／下影線 0.1
        var upper = engine.evaluateStock(base()
                .dailyCandle(candle("12", "20", "10", "11")).build());

        assertThat(lower.shortScore()).isGreaterThan(upper.shortScore());
        assertThat(lower.swingScore()).isGreaterThan(upper.swingScore());
        assertThat(lower.score()).isGreaterThan(upper.score());
    }

    // ═══ 356.6e：週K 缺值揭露 ════════════════════════════════════════════════

    @Test
    @DisplayName("356.6e 完成週不足 60 根 → 四組週K 因子全缺且 risks 寫出目前根數")
    void insufficientCompletedWeeksDisablesAllFourWeeklyFactorsWithDisclosure() {
        var tooFew = engine.evaluateStock(base()
                .weekly(weekly(59).build()).build());
        var absent = engine.evaluateStock(base().build());

        assertThat(tooFew.risks()).anyMatch(risk ->
                risk.contains("週K 完成週不足 60 根") && risk.contains("目前 59 根"));
        assertThat(absent.risks()).anyMatch(risk ->
                risk.contains("週K 完成週不足 60 根") && risk.contains("目前 0 根"));
        assertThat(tooFew.score()).isEqualTo(absent.score());
        assertThat(tooFew.swingScore()).isEqualTo(absent.swingScore());
    }

    @Test
    @DisplayName("356.6e 剛好 60 根完成週即可用，且不再輸出不採計揭露")
    void exactlySixtyCompletedWeeksEnablesTheWeeklyFactors() {
        var usable = engine.evaluateStock(base()
                .weekly(weekly(60).ma5("100").ma10("100").ma20("100").build()).build());

        assertThat(usable.risks()).noneMatch(risk -> risk.contains("週K 完成週不足"));
        assertThat(usable.reasons()).anyMatch(reason -> reason.contains("週MA20"));
    }

    // ═══ 356.7a：五個新因子的缺值重分配（缺值 ≠ 貢獻 0） ═══════════════════════

    @Test
    @DisplayName("356.7a 日K 棒缺值是重分配權重，不是計 0")
    void dailyCandleMissingRedistributesInsteadOfScoringZero() {
        // 缺 open、H=20 L=10 C=15 → 只剩收盤位置分量且恰為 0.5 → 貢獻正好 0。
        var zeroContribution = engine.evaluateStock(base()
                .dailyCandle(new TradingRadarRuleEngine.CandleInput(
                        null, bd("20"), bd("10"), bd("15"))).build());
        var missing = engine.evaluateStock(base().build());

        assertMissingIsNotZeroFill(missing, zeroContribution, "日K 棒");
    }

    @Test
    @DisplayName("356.7a 週線趨勢缺值是重分配權重，不是計 0")
    void weeklyTrendMissingRedistributesInsteadOfScoringZero() {
        // 現價 == 週MA5 == 週MA10 == 週MA20 → positionOf 三項皆 0 → 因子貢獻正好 0。
        var zeroContribution = engine.evaluateStock(base()
                .weekly(weekly(60).ma5("120").ma10("120").ma20("120").build()).build());
        var missing = engine.evaluateStock(base().build());

        assertMissingIsNotZeroFill(missing, zeroContribution, "週線趨勢");
    }

    @Test
    @DisplayName("356.7a 週線動能缺值是重分配權重，不是計 0")
    void weeklyMomentumMissingRedistributesInsteadOfScoringZero() {
        // k == d == 50、osc == 0、rsi5 == rsi10 == 50 → 三分量皆 0 → 貢獻正好 0。
        // （Task 360 起 J 位置分量改由 k／d 現算標準 J：k == d == 50 → J == 50 → 0；
        //  j9 已不進評分鏈，此處保留 j9("50") 只是不動既有語料。）
        var zeroContribution = engine.evaluateStock(base()
                .weekly(weekly(60).k("50").d("50").j9("50").osc("0")
                        .rsi5("50").rsi10("50").build()).build());
        var missing = engine.evaluateStock(base().build());

        assertMissingIsNotZeroFill(missing, zeroContribution, "週線動能");
    }

    @Test
    @DisplayName("356.7a 週線乖離缺值是重分配權重，不是計 0")
    void weeklyBiasMissingRedistributesInsteadOfScoringZero() {
        var zeroContribution = engine.evaluateStock(base()
                .weekly(weekly(60).bias10("0").bias20("0").build()).build());
        var missing = engine.evaluateStock(base().build());

        assertMissingIsNotZeroFill(missing, zeroContribution, "週線乖離");
    }

    @Test
    @DisplayName("356.7a 週K 棒與量能缺值是重分配權重，不是計 0")
    void weeklyCandleVolumeMissingRedistributesInsteadOfScoringZero() {
        // 週K 棒 H=20 L=10 O=C=15 → 位置 0、實體 0；週漲跌 0 → 量價確認 0。
        var zeroContribution = engine.evaluateStock(base()
                .weekly(weekly(60).candle(candle("15", "20", "10", "15"))
                        .changePercent("0").volumeRatio("1").build()).build());
        var missing = engine.evaluateStock(base().build());

        assertMissingIsNotZeroFill(missing, zeroContribution, "週K 棒與量能");
    }

    /**
     * 缺值必須與「貢獻恰為 0」得到<b>不同</b>的分數，且後者更靠近 50。
     *
     * <p>這正是「重分配」與「以 0 冒充」的分界：以 0 冒充時 {@code sumW} 會變大而
     * {@code sumWC} 不變，加權平均被稀釋往 0（即分數往 50）靠；真正的重分配則完全不動分數。</p>
     */
    private void assertMissingIsNotZeroFill(
            TradingRadarRuleEngine.StockResult missing,
            TradingRadarRuleEngine.StockResult zeroContribution,
            String label) {
        for (String track : List.of("一周", "1周~1月", "1月~6月")) {
            int missingScore = trackScore(missing, track);
            int zeroScore = trackScore(zeroContribution, track);
            assertThat(missingScore)
                    .as("%s 因子缺值時（%s 軌）不得等於「貢獻計 0」——缺值必須重分配權重", label, track)
                    .isNotEqualTo(zeroScore);
            assertThat(Math.abs(zeroScore - 50))
                    .as("%s 因子貢獻為 0 時（%s 軌）應把加權平均稀釋往 50", label, track)
                    .isLessThan(Math.abs(missingScore - 50));
        }
    }

    private int trackScore(TradingRadarRuleEngine.StockResult result, String track) {
        return switch (track) {
            case "一周" -> result.shortScore();
            case "1周~1月" -> result.swingScore();
            default -> result.score();
        };
    }

    // ═══ 356.6a／356.14b：文案 ═══════════════════════════════════════════════

    @Test
    @DisplayName("356.6a 同一份 reasons＋risks 不得出現兩則含「週線」字樣的句子")
    void neitherTrackEmitsTwoSentencesContainingTheWordWeeklyLine() {
        var result = engine.evaluateStock(base()
                .weeklyMa("110")
                .weekly(weekly(60).ma5("100").ma10("100").ma20("100").build())
                .build());

        for (List<String> lines : List.of(
                merged(result.reasons(), result.risks()),
                merged(result.swingReasons(), result.swingRisks()),
                merged(result.shortReasons(), result.shortRisks()))) {
            long weeklyLineSentences = lines.stream()
                    .filter(text -> text.contains("週線")
                            && (text.contains("最新價位於") || text.contains("最新價貼近")))
                    .count();
            assertThat(weeklyLineSentences)
                    .as("「週線」是日K 5 日 SMA 的舊措辭，V15 起均線位置句只能出現「日線 MA5」"
                            + "與「週MA5／週MA10／週MA20」，實得：%s", lines)
                    .isZero();
        }
        assertThat(merged(result.reasons(), result.risks()))
                .anyMatch(text -> text.contains("日線 MA5"));
        assertThat(merged(result.reasons(), result.risks()))
                .anyMatch(text -> text.contains("週MA5"));
    }

    // ═══ 356.8b：四項保護在三軌各自成立 ═══════════════════════════════════════

    @Test
    @DisplayName("356.8b 完成日下跌時三軌都不得出現買進／加碼")
    void stillFallingClosesTheBuyGateOnAllThreeTracks() {
        var falling = engine.evaluateStock(base()
                .completedChangePercent("-1")
                .weekly(weekly(60).ma5("100").ma10("100").ma20("100")
                        .k("60").d("40").j9("55").osc("1")
                        .rsi5("55").rsi10("55").bias10("-5").bias20("-5")
                        .candle(candle("100", "125", "95", "120"))
                        .changePercent("3").volumeRatio("1.5").build())
                .dailyCandle(candle("110", "125", "105", "124"))
                .build());

        assertThat(falling.score()).isGreaterThanOrEqualTo(75);
        assertNoBuyOnAnyTrack(falling);
    }

    @Test
    @DisplayName("356.8b 完成日漲幅達 5% 時三軌都不得追高買進")
    void chasedDailyMoveClosesTheBuyGateOnAllThreeTracks() {
        assertNoBuyOnAnyTrack(engine.evaluateStock(base()
                .completedChangePercent("6").build()));
    }

    @Test
    @DisplayName("356.8b KD 過熱時三軌都不得買進")
    void overheatedKdClosesTheBuyGateOnAllThreeTracks() {
        assertNoBuyOnAnyTrack(engine.evaluateStock(base().k("95").d("90").build()));
    }

    @Test
    @DisplayName("356.8b 大盤 stale 時三軌都不得買進")
    void staleMarketClosesTheBuyGateOnAllThreeTracks() {
        assertNoBuyOnAnyTrack(engine.evaluateStock(base().marketStale(true).build()));
    }

    @Test
    @DisplayName("356.8b 極端超賣時三軌一律不殺低")
    void extremeOversoldProtectsAllThreeTracksFromSellingTheLow() {
        var held = engine.evaluateStock(extremeOversold(true));
        var watched = engine.evaluateStock(extremeOversold(false));

        assertThat(held.timingState())
                .isEqualTo(TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD);
        for (var action : List.of(held.action(), held.swingAction(), held.shortAction())) {
            assertThat(action).isEqualTo(TradingRadarRuleEngine.Action.HOLD_CAUTION);
        }
        for (var action : List.of(watched.action(), watched.swingAction(), watched.shortAction())) {
            assertThat(action).isEqualTo(TradingRadarRuleEngine.Action.WAIT);
        }
    }

    @Test
    @DisplayName("356.8b 高檔只有一項轉弱時三軌都不得獲利了結")
    void profitTakingNeedsTwoWeakeningEvidencesOnAllThreeTracks() {
        var oneEvidence = engine.evaluateStock(extremeOverbought(true, false));
        var twoEvidences = engine.evaluateStock(extremeOverbought(true, true));

        assertThat(oneEvidence.profitTakingConfirmed()).isFalse();
        for (var action : List.of(oneEvidence.action(), oneEvidence.swingAction(),
                oneEvidence.shortAction())) {
            assertThat(action).isNotEqualTo(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE);
        }
        assertThat(twoEvidences.profitTakingConfirmed()).isTrue();
        for (var action : List.of(twoEvidences.action(), twoEvidences.swingAction(),
                twoEvidences.shortAction())) {
            assertThat(action).isEqualTo(TradingRadarRuleEngine.Action.REDUCE_CANDIDATE);
        }
    }

    @Test
    @DisplayName("356.8c 新因子只進分數，不進五個極端態判定")
    void newFactorsNeverChangeTheFiveSharedStateJudgements() {
        var without = engine.evaluateStock(base().build());
        var with = engine.evaluateStock(allNegativeWeeklyAndCandle());

        assertThat(with.score()).isNotEqualTo(without.score());
        assertThat(with.timingState()).isEqualTo(without.timingState());
        assertThat(with.kdHeat()).isEqualTo(without.kdHeat());
        assertThat(with.profitTakingConfirmed()).isEqualTo(without.profitTakingConfirmed());
        assertThat(with.kdDeadCross()).isEqualTo(without.kdDeadCross());
        assertThat(with.longTermBroken()).isEqualTo(without.longTermBroken());
    }

    // ═══ 356.10a／356.10a-2：大盤 ════════════════════════════════════════════

    @Test
    @DisplayName("356.10a-2 大盤 dataComplete 不受週K 影響")
    void marketDataCompleteIsNeverDecidedByWeeklyBars() {
        var withoutWeekly = engine.evaluateMarket(market(null, null));
        var withWeekly = engine.evaluateMarket(market(
                weekly(60).ma10("9000").ma20("9000").k("60").d("40").osc("1")
                        .changePercent("1").volumeRatio("1.2").build(), null));

        assertThat(withoutWeekly.regime())
                .isNotEqualTo(TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE);
        assertThat(withoutWeekly.score()).isNotNull();
        assertThat(withWeekly.regime())
                .isNotEqualTo(TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE);
        assertThat(withoutWeekly.risks()).anyMatch(risk -> risk.contains("大盤週K 尚未建立"));
    }

    @Test
    @DisplayName("356.10a 大盤週線與日K 棒確實加減分且缺值時揭露不採計")
    void marketWeeklyAndDailyCandleChangeTheScoreAndDiscloseWhenMissing() {
        var neutral = engine.evaluateMarket(market(null, null));
        var bullish = engine.evaluateMarket(market(
                weekly(60).ma10("9000").ma20("9000").k("60").d("40").osc("1")
                        .changePercent("1").volumeRatio("1.5").build(),
                candle("9500", "10200", "9400", "10150")));
        var bearish = engine.evaluateMarket(market(
                weekly(60).ma10("11000").ma20("11000").k("40").d("60").osc("-1")
                        .changePercent("-1").volumeRatio("1.5").build(),
                candle("10100", "10200", "9400", "9450")));

        assertThat(bullish.score()).isGreaterThan(neutral.score());
        assertThat(bearish.score()).isLessThan(neutral.score());
        assertThat(neutral.risks()).anyMatch(risk -> risk.contains("不採計日K 棒收盤位置"));
        assertThat(bullish.reasons()).anyMatch(reason -> reason.contains("週MA10"));
    }

    // ═══ 360.7d：J 值極性修正確實接進日線與週線兩條路徑 ════════════════════════
    //
    // kdJContribution() 與 weeklyMomentumContribution() 都是 private 實例方法、分量值也不外露
    // （公開路徑只吐 score／action），且本次唯一允許新增的可測進入點是純函數
    // TradingRadarRuleEngine.standardJPosition（在 TradingRadarRuleEngineTest 直測）。
    // 故這兩條路徑「有沒有真的改到」只能由分數方向反推。
    //
    // ⚠️ 不得用「K>D vs K<D」的反向比較：jPosition 與 direction = signum(K−D) 是同一個
    // averageAvailable 的兄弟分量，對稱例值（60/40 vs 40/60）下兩者分量和逐位相等
    // （+1 與 −1.0 相消、−1 與 +1.0 相消），該斷言在正確實作下也必然失敗。
    // 改以「同向、不同動能幅度」固定 direction 與 position，只變動能量級。

    @Test
    @DisplayName("360.7d-1 日線：同為 K>D 時動能越強，一周軌分數越低（不追價）")
    void dailyStrongerMomentumLowersShortScore() {
        // 兩組 KD 均值皆為 50 → position = 0；皆 K>D → direction = +1。差別只有動能量級。
        // 正確實作：jPosition 分別為 clampUnit((50−60)/50) = −0.20 與 clampUnit((50−150)/50) = −1.00。
        // 誤寫回 3D − 2K：分別為 +0.20 與 clampUnit((50−(−50))/50) = +1.00，方向相反 → 本測試必紅。
        var mild = engine.evaluateStock(base().k("52").d("48").build());
        var strong = engine.evaluateStock(base().k("70").d("30").build());

        assertThat(mild.shortScore()).isNotNull();
        assertThat(strong.shortScore()).isNotNull();
        // 實測 75 → 70（SW_KD_J = 0.12，J 佔該因子 1/4）。
        assertThat(strong.shortScore()).isLessThan(mild.shortScore());
        assertThat(strong.swingScore()).isLessThan(mild.swingScore());
        assertThat(strong.score()).isLessThan(mild.score());
    }

    @Test
    @DisplayName("360.7d-1 週線：同為週K>週D 時動能越強，一周軌分數越低（週線漏改的唯一防護）")
    void weeklyStrongerMomentumLowersShortScore() {
        // 日線 k／d 固定為 base() 的 60／40 不變，只動週線的 k／d。
        // ⚠️ 週線 J 只佔週線動能因子的 1/3（本 fixture 的週MACD／週RSI 皆缺值），
        // 而 SW_WEEKLY_MOMENTUM 只有 0.03，故一周軌的實測落差僅 70 → 69（未取整前 1.11 分）。
        // 落差雖 > 1 分（`Math.round` 下 |Δ| ≥ 1 即保證整數不同），但餘裕很小：
        // 三軌都斷言，一來加大防護，二來日後若某軌因權重調整而退化成恆等，會立刻變紅而不是靜默失效。
        var mild = engine.evaluateStock(base()
                .weekly(weekly(60).k("52").d("48").build()).build());
        var strong = engine.evaluateStock(base()
                .weekly(weekly(60).k("70").d("30").build()).build());

        assertThat(mild.shortScore()).isNotNull();
        assertThat(strong.shortScore()).isNotNull();
        assertThat(strong.shortScore()).isLessThan(mild.shortScore());
        assertThat(strong.swingScore()).isLessThan(mild.swingScore());
        assertThat(strong.score()).isLessThan(mild.score());
    }

    @Test
    @DisplayName("360.7d-2 j9 已退出評分鏈：只改日線與週線的 j9，三軌分數逐位不變")
    void j9NoLongerAffectsAnyHorizonScore() {
        // 固定 k／d 與其餘所有輸入，只把日線 ExtendedIndicators 與週線的 j9 換成差異極大的值。
        // 修正前 j9 直接進 kdJContribution／weeklyMomentumContribution，此斷言必紅；
        // 修正後兩處都改讀 k／d，j9 對 score 再無任何通道。
        var lowJ9 = engine.evaluateStock(base()
                .extended(extended("0", "1", "50", "50", "0", "0", "50"))
                .weekly(weekly(60).k("60").d("40").j9("0").osc("1")
                        .rsi5("50").rsi10("50").build())
                .build());
        var highJ9 = engine.evaluateStock(base()
                .extended(extended("100", "1", "50", "50", "0", "0", "50"))
                .weekly(weekly(60).k("60").d("40").j9("100").osc("1")
                        .rsi5("50").rsi10("50").build())
                .build());

        assertThat(lowJ9.shortScore()).isNotNull();
        assertThat(highJ9.shortScore()).isEqualTo(lowJ9.shortScore());
        assertThat(highJ9.swingScore()).isEqualTo(lowJ9.swingScore());
        assertThat(highJ9.score()).isEqualTo(lowJ9.score());
    }

    // ═══ helper ═════════════════════════════════════════════════════════════

    private void assertNoBuyOnAnyTrack(TradingRadarRuleEngine.StockResult result) {
        for (var action : List.of(result.action(), result.swingAction(), result.shortAction())) {
            assertThat(action)
                    .isNotEqualTo(TradingRadarRuleEngine.Action.BUY_CANDIDATE)
                    .isNotEqualTo(TradingRadarRuleEngine.Action.ADD_CANDIDATE);
        }
    }

    private static List<String> merged(List<String> reasons, List<String> risks) {
        return java.util.stream.Stream.concat(reasons.stream(), risks.stream()).toList();
    }

    private int actionGroupOf(TradingRadarRuleEngine.Action action) {
        return switch (action) {
            case BUY_CANDIDATE, ADD_CANDIDATE, TRIAL_BUY -> 1;
            case HOLD, WATCH, HOLD_CAUTION, WAIT -> 2;
            case REDUCE_CANDIDATE, EXIT_CANDIDATE, AVOID -> 3;
            case NO_TRADE -> 4;
        };
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static TradingRadarRuleEngine.CandleInput candle(
            String open, String high, String low, String close) {
        return new TradingRadarRuleEngine.CandleInput(bd(open), bd(high), bd(low), bd(close));
    }

    /**
     * 一周超買、1月~6月結構完好：短線指標（KD／RSI／MACD／完成日漲跌）全數轉弱，
     * 長線指標（MA60／MA240／週線）全數完好。兩組的權重跨軌方向相反，故三軌分數必然拉開。
     */
    private TradingRadarRuleEngine.StockInput shortTermOverboughtLongTermIntact(boolean held) {
        return base()
                .held(held)
                .k("94").d("96")
                .extended(extended("98", "-2.5", "92", "92", "9", "9", "3"))
                .completedChangePercent("6")
                .volumeRatio("0.5")
                .weeklyMa("125")
                .weekly(weekly(60).ma5("100").ma10("95").ma20("90")
                        .k("60").d("40").j9("50").osc("2")
                        .rsi5("45").rsi10("45").bias10("-6").bias20("-8")
                        .candle(candle("100", "125", "98", "124"))
                        .changePercent("4").volumeRatio("1.5").build())
                // 短線 K 棒收黑（收在區間下緣、實體向下、幾乎無下影線）：一周軌的日K 棒權重
                // 是 1月~6月 軌的六倍，這一根正是把三軌拉開到三個動作分層的關鍵之一。
                .dailyCandle(candle("124", "125", "105", "106"))
                .build();
    }

    private TradingRadarRuleEngine.StockInput allPositiveWeeklyAndCandle() {
        return base()
                .weekly(weekly(60).ma5("80").ma10("80").ma20("80")
                        .k("70").d("30").j9("10").osc("5")
                        .rsi5("10").rsi10("10").bias10("-30").bias20("-30")
                        .candle(candle("100", "125", "95", "124"))
                        .changePercent("5").volumeRatio("2").build())
                .dailyCandle(candle("110", "125", "105", "124"))
                .build();
    }

    private TradingRadarRuleEngine.StockInput allNegativeWeeklyAndCandle() {
        return base()
                .weekly(weekly(60).ma5("200").ma10("200").ma20("200")
                        .k("30").d("70").j9("95").osc("-5")
                        .rsi5("95").rsi10("95").bias10("30").bias20("30")
                        .candle(candle("124", "125", "95", "96"))
                        .changePercent("-5").volumeRatio("2").build())
                .dailyCandle(candle("124", "125", "105", "106"))
                .build();
    }

    /** KD 深度超賣 ＋ 季線乖離 −25% → EXTREME_OVERSOLD。 */
    private TradingRadarRuleEngine.StockInput extremeOversold(boolean held) {
        return base()
                .held(held)
                .price("70")
                .k("10").d("12")
                .ma20Confirmation(TradingRadarRuleEngine.Confirmation.BELOW)
                .ma60Confirmation(TradingRadarRuleEngine.Confirmation.BELOW)
                .ma240Confirmation(TradingRadarRuleEngine.Confirmation.BELOW)
                .ma60BiasPercent("-25")
                .completedChangePercent("-3")
                .build();
    }

    /**
     * KD 過熱 ＋ 季線乖離 +25% → EXTREME_OVERBOUGHT。
     *
     * @param secondEvidence 是否同時提供第二項轉弱證據（KD 高檔死亡交叉之外再加 OSC &lt; 0）
     */
    private TradingRadarRuleEngine.StockInput extremeOverbought(
            boolean held, boolean secondEvidence) {
        return base()
                .held(held)
                .k("90").d("92")
                .previousK("95").previousD("90")
                .ma60BiasPercent("25")
                .extended(secondEvidence
                        ? extended("95", "-1", "80", "80", "5", "5", "10")
                        : extended("95", "1", "80", "80", "5", "5", "10"))
                .build();
    }

    private static TradingRadarRuleEngine.ExtendedIndicators extended(
            String j9, String osc, String rsi5, String rsi10,
            String bias10, String bias20, String wr9) {
        return new TradingRadarRuleEngine.ExtendedIndicators(
                bd(j9), null, null, null, null, null, null, bd(osc),
                bd(rsi5), bd(rsi10), bd(bias10), bd(bias20), null, bd(wr9));
    }

    private TradingRadarRuleEngine.MarketInput market(
            TradingRadarRuleEngine.WeeklyInput weekly,
            TradingRadarRuleEngine.CandleInput dailyCandle) {
        // 刻意不讓基準大盤停在 clamp 天花板：全部站上均線＋兩日確認的大盤本來就是 100 分，
        // 那樣「加分有沒有生效」永遠測不出來（加了也還是 100）。
        return new TradingRadarRuleEngine.MarketInput(
                bd("10000"), bd("0.5"),
                new TradingRadarRuleEngine.Indicators(
                        bd("10100"), bd("9600"), bd("9400"), bd("60"), bd("50")),
                TradingRadarRuleEngine.Confirmation.MIXED,
                TradingRadarRuleEngine.Confirmation.MIXED,
                bd("0.5"), null, null, null, null, null, false, true,
                dailyCandle, weekly);
    }

    private static WeeklyBuilder weekly(int completedWeeks) {
        return new WeeklyBuilder(completedWeeks);
    }

    /** {@code WeeklyInput} 有 16 個 component，逐測試手寫太容易錯位，故以 builder 建。 */
    private static final class WeeklyBuilder {
        private final int completedWeeks;
        private TradingRadarRuleEngine.CandleInput candle;
        private BigDecimal ma5;
        private BigDecimal ma10;
        private BigDecimal ma20;
        private BigDecimal k;
        private BigDecimal d;
        private BigDecimal j9;
        private BigDecimal osc;
        private BigDecimal rsi5;
        private BigDecimal rsi10;
        private BigDecimal bias10;
        private BigDecimal bias20;
        private BigDecimal volumeRatio;
        private BigDecimal changePercent;

        private WeeklyBuilder(int completedWeeks) {
            this.completedWeeks = completedWeeks;
        }

        WeeklyBuilder candle(TradingRadarRuleEngine.CandleInput value) { candle = value; return this; }
        WeeklyBuilder ma5(String value) { ma5 = bd(value); return this; }
        WeeklyBuilder ma10(String value) { ma10 = bd(value); return this; }
        WeeklyBuilder ma20(String value) { ma20 = bd(value); return this; }
        WeeklyBuilder k(String value) { k = bd(value); return this; }
        WeeklyBuilder d(String value) { d = bd(value); return this; }
        WeeklyBuilder j9(String value) { j9 = bd(value); return this; }
        WeeklyBuilder osc(String value) { osc = bd(value); return this; }
        WeeklyBuilder rsi5(String value) { rsi5 = bd(value); return this; }
        WeeklyBuilder rsi10(String value) { rsi10 = bd(value); return this; }
        WeeklyBuilder bias10(String value) { bias10 = bd(value); return this; }
        WeeklyBuilder bias20(String value) { bias20 = bd(value); return this; }
        WeeklyBuilder volumeRatio(String value) { volumeRatio = bd(value); return this; }
        WeeklyBuilder changePercent(String value) { changePercent = bd(value); return this; }

        TradingRadarRuleEngine.WeeklyInput build() {
            return new TradingRadarRuleEngine.WeeklyInput(
                    candle, ma5, ma10, ma20, k, d, j9, osc, rsi5, rsi10,
                    bias10, bias20, volumeRatio, changePercent,
                    LocalDate.of(2026, 8, 14), completedWeeks);
        }
    }

    private static StockBuilder base() {
        return new StockBuilder();
    }

    /**
     * 基準個股：價 120、MA20 110／MA60 100／MA240 90、三個兩日確認皆 ABOVE、
     * 大盤 RISK_ON 非 stale、完成日漲 1%、K 60／D 40。所有 optional 因子預設缺值，
     * 由各測試按需開啟——這樣「因子有沒有進 sumW」的差異才可觀測。
     */
    private static final class StockBuilder {
        private boolean held = true;
        private BigDecimal price = bd("120");
        private BigDecimal completedChangePercent = bd("1");
        private BigDecimal k = bd("60");
        private BigDecimal d = bd("40");
        private BigDecimal previousK = bd("55");
        private BigDecimal previousD = bd("45");
        private BigDecimal ma60BiasPercent;
        private BigDecimal weeklyMa;
        private BigDecimal volumeRatio;
        private boolean marketStale;
        private TradingRadarRuleEngine.Confirmation ma20Confirmation =
                TradingRadarRuleEngine.Confirmation.ABOVE;
        private TradingRadarRuleEngine.Confirmation ma60Confirmation =
                TradingRadarRuleEngine.Confirmation.ABOVE;
        private TradingRadarRuleEngine.Confirmation ma240Confirmation =
                TradingRadarRuleEngine.Confirmation.ABOVE;
        private TradingRadarRuleEngine.ExtendedIndicators extended;
        private TradingRadarRuleEngine.CandleInput dailyCandle;
        private TradingRadarRuleEngine.WeeklyInput weekly;

        StockBuilder held(boolean value) { held = value; return this; }
        StockBuilder price(String value) { price = bd(value); return this; }
        StockBuilder completedChangePercent(String value) {
            completedChangePercent = bd(value); return this;
        }
        StockBuilder k(String value) { k = bd(value); return this; }
        StockBuilder d(String value) { d = bd(value); return this; }
        StockBuilder previousK(String value) { previousK = bd(value); return this; }
        StockBuilder previousD(String value) { previousD = bd(value); return this; }
        StockBuilder ma60BiasPercent(String value) { ma60BiasPercent = bd(value); return this; }
        StockBuilder weeklyMa(String value) { weeklyMa = bd(value); return this; }
        StockBuilder marketStale(boolean value) { marketStale = value; return this; }
        StockBuilder volumeRatio(String value) { volumeRatio = bd(value); return this; }
        StockBuilder ma20Confirmation(TradingRadarRuleEngine.Confirmation value) {
            ma20Confirmation = value; return this;
        }
        StockBuilder ma60Confirmation(TradingRadarRuleEngine.Confirmation value) {
            ma60Confirmation = value; return this;
        }
        StockBuilder ma240Confirmation(TradingRadarRuleEngine.Confirmation value) {
            ma240Confirmation = value; return this;
        }
        StockBuilder extended(TradingRadarRuleEngine.ExtendedIndicators value) {
            extended = value; return this;
        }
        StockBuilder dailyCandle(TradingRadarRuleEngine.CandleInput value) {
            dailyCandle = value; return this;
        }
        StockBuilder weekly(TradingRadarRuleEngine.WeeklyInput value) {
            weekly = value; return this;
        }

        TradingRadarRuleEngine.StockInput build() {
            return new TradingRadarRuleEngine.StockInput(
                    held, price, bd("1"), completedChangePercent,
                    new TradingRadarRuleEngine.Indicators(
                            bd("110"), bd("100"), bd("90"), k, d),
                    previousK, previousD,
                    ma20Confirmation, ma60Confirmation, ma240Confirmation,
                    TradingRadarRuleEngine.InstrumentType.EQUITY,
                    TradingRadarRuleEngine.MarketRegime.RISK_ON,
                    marketStale,
                    null, ma60BiasPercent, null, null, null, null, null, null,
                    weeklyMa, extended, volumeRatio,
                    TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE,
                    dailyCandle, weekly);
        }
    }
}
