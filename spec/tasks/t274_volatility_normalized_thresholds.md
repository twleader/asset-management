# [t274] V12 季線乖離以自身波動正規化，作為 V13 候選特徵

**對應 Requirements:** Requirement 57／65
**前置任務:** t273（共用回測框架）、t298／t299（V12 BIAS 分量與 2/98 分位過渡路徑）
**下游整合:** t308（樣本外 promotion 與單一 V13 發布）
**Liquibase changeset:** 無

## 背景

V12 已沒有舊版 `extensionOf()`／`BIAS_SATURATION`；乖離由短／中期 BIAS components 評分，`timingOf` 則以固定門檻或 `ma60BiasPercentile` 2/98 路徑判斷極端。舊任務檔以 V9 與已刪除方法描述，不能直接實作。

固定百分比對低波動債券 ETF 幾乎永不觸發，對高波動股票又過度頻繁。以既有 `DistributionAdjustedPriceService` 還原權息／分割序列、先排除非正價格、暖機 240 根後量測，bias 標準差約從 00719B 的 1.60 到 2327 的 21.37，顯示同一絕對門檻無法跨標的比較。但 normalized bias 仍不能解決債券 ETF 因窄幅 9 日區間而停用 KD 的獨立死區；不得把這部分改善歸功於本任務。

本任務只建立 production/backtest 共用的 volatility-normalized candidate，不單獨升版或部署。正式 sigma 下限、倍數、分量形狀與是否啟用，一律由 t308 calibration/holdout 決定。

**依賴邊界：** 本任務先提供不依賴證據 gate 的 `returnStdDev60Ratio` 純函數與 provenance；它不讀 t309 的 confidence/action 結果。t309 只消費本任務的 sigma observation 來計算 downside 與 evidence freshness，故實作順序為 t273 → t274 → t309，沒有 t274↔t309 循環依賴。

## 要做什麼

- [ ] **274.1 共用 realized volatility。** 在 `RadarInputAssembler` 對同一 adjusted completed-price sequence 計算最近 60 個有效日報酬的樣本標準差 `returnStdDev60Ratio`；它採比例口徑，例如日波動 2% 存 `0.02`。不足 61 根、任一非正價、非有限值或原始 sigma `<=0` 時回 null。production 與 backtest 都只能使用這個欄位；移除 `BacktestService.sigmaAt` 等第二份公式。live 價不進 sigma 視窗。
- [ ] **274.2 Normalized bias candidate。** 先算 `biasRatio=ma60BiasPercent.movePointLeft(2)`，再算 `effectiveSigmaRatio = rawSigmaRatio < sigmaFloorRatio ? sigmaFloorRatio : rawSigmaRatio` 與 `normalizedMa60Bias=biasRatio/effectiveSigmaRatio`。原始 sigma 必須先 `>0`；常數序列的 0 不得被 floor 救回，須走 unavailable/fallback。`sigmaFloorRatio` 不是拍腦袋常數，候選集合須取 calibration 期間 rolling sigma 分布的具名低百分位。固定例 `ma60BiasPercent=10`、`sigma=0.02` 必須等於 `5`，不可算成 500。每筆輸出 raw bias、raw/effective sigma、floor、normalized value、as-of 與 missing reason。
- [ ] **274.3 取代而非疊加。** t308 若 promotion 通過，`timingOf` 的極端乖離側只使用 normalized path；V12 `ma60BiasPercentile` 保留 API／回測揭露，不得與 normalized path 以 OR 疊加。sigma 缺漏時才回退 V12 固定絕對門檻，標 `volatilityFallback=true`、降低 PRICE evidence confidence；缺漏不得藉分位路徑繞過。
- [ ] **274.4 BIAS 評分候選。** 短／中期既有 BIAS components 改用 normalized contribution 的候選形狀，但不新增因子、不得與 raw BIAS 雙重計分。candidate parameters 包含 saturation sigma multiple；正式 contribution 在 t308 promotion 前保持現行 V12 值，候選結果只進 backtest/debug report。
- [ ] **274.5 不改 KD dead-zone。** `narrowKdBand`、K/D 計算與門檻不在本任務射程。回測報告須逐 profile 顯示「因 KD N/A 而無法進極端態」的比例，避免把 normalized bias 未觸發誤判為失效。
- [ ] **274.6 單一 V13 整合。** 本任務不得改 `RULE_VERSION`、通知 baseline 或前端 fallback；只有 t308 選出 holdout 非劣參數並完成其餘 V13 項目後，才一次升 `TW_RULES_V13`。若候選未通過，欄位仍可揭露，但正式 action/score 保持未啟用並列明原因。

### t308 候選與 promotion 契約

- calibration grid 至少涵蓋 sigma floor percentile、extreme upper/lower multiple、BIAS saturation multiple；只能用 calibration 區段選值。
- holdout 與 expanding walk-forward 使用逐 decision instant 的 rolling inputs，禁止以全期間 sigma 分布選 floor。
- 候選至少按市場、asset class 與 bond term 分層輸出 n、coverage、paired median、pooled mean、downside；不可只報整體平均。
- 樣本不足，或 paired median 與 pooled mean 皆惡化且 downside 未改善時，candidate rejected；不得為通過而手調區間。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

單元測試至少直接證明：

- adjusted sequence 的 60 日 sample sigma 與手算相同；不足、非正價與常數序列行為明確；
- production/backtest 使用同一 assembler 值，無第二份 sigma 公式；
- scale invariance：整段價格乘正常數不改 bias/sigma/normalized result；
- normalized path 啟用時，2/98 percentile 不再影響 timing/action；
- sigma 缺漏只回固定門檻且降低 confidence；
- candidate disabled 時，V12 score/action bit-identical；
- t308 full-engine rerun 能對每組參數重新產生完整 action，而非只替既有訊號換標籤。

## 完成報告

（回填各市場／profile sigma coverage、calibration grid、holdout/walk-forward 結果、選定或 rejected 參數、KD dead-zone 比例、V12 對照與 V13 最終啟用狀態。）
