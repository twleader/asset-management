# 第 2 輪審查處理紀錄（D-234 t623–t626）

工作樹：/private/tmp/asset-d234-calc-migration（branch claude/srpp-d234-calc-migration）。只改 spec。8 項全部屬實，沒有誤判；每項都對照程式碼核過。`bash scripts/spec-check.sh` 結果：BLOCK 0／CHECK 0。

## [Major] t623 建倉腿沒有收盤後守門，AFTER_CLOSE_INITIALIZATION 包會被 envelope validator 拒絕
- 核對：`SrppReportProducerScheduler.java:54-55,74,85-92`（14:00 後走 afterClose，`withAfterCloseContext` 寫入 publication-context）；`SrppReportEnvelopeValidator.java:33-46`（`validatePublicationContext` 對每個 module 的 data 執行 `requireNonExecutable`）。屬實。
- 修改：
  - spec/tasks/t623_srpp_d234_satellite_build_legs.md:95，新增 623.13a。publication-context 的 mode 為 AFTER_CLOSE_INITIALIZATION 時，兩條腿都是 BLOCKED 0，`blockingReasons` 第一碼是 `AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE`，計算結果為 0 時後面接原 reasons。腿內 SATELLITE_BUILD 收據為 BLOCK。facts 與模組收據保留純計算結果。判定時機在 PASS 前提與 sharedFunds 之前，帶此碼的腿不會成為 626 的補價候選。OpenAPI 只改 description。
  - t623:116（623.17），BFF 須接受此碼。
  - t623:133（623.22），在 `SrppReportProducerSchedulerTest` 加測試：用 `withAfterCloseContext` 處理過的 Frozen、計算結果為正，整包須通過 `SrppReportEnvelopeValidator.validate`；拿掉 publication-context 時腿為 ACTIONABLE。
  - t623:142，focused mvn 指令補上測試類。
- t625 與 t626 的判斷：
  - t625 不需新規則。D215_FX 只會把 ACTIONABLE 腿降成 BLOCKED，不會把 BLOCKED 升回 ACTIONABLE；ceiling 寫在收據 values 的 facts，不在 requireNonExecutable 檢查的鍵上。這點寫在 spec/tasks/t625_srpp_d215_fx_preference_on_bond_and_vt_legs.md:139，回歸測試在 t625:225。
  - t626 需要新規則。pre-capacity 重判（redecided）可能取回正數原量，所以 spec/tasks/t626_srpp_order_plan_v2_recompute.md:83（626.5）新增：收盤後初始化包沒有補價候選、不做 redecided、零讀取，所有 BUY 的 final 為 0 並帶此碼，另附測試。626.17 在新碼清單加入此碼。
- Requirement 與 design：spec/requirements.md:7295 新增 Requirement 220 AC 6（原 6、7 改為 7、8）；spec/design.md:14299；Requirement 223 寫在 requirements.md:7328 與 design.md:14342。

## [Major] report-reservations 會永久保留建倉腿；VT 以 USD 入帳卻扣 TWD；H_c 被吃光
- 核對：
  - `SrppReportReservationCapture.java:36-52`：每條 ACTIONABLE 腿都轉成 ACTIVE row，`linkedOrderId` 與 `linkedFillId` 都是 null。
  - `SrppReportRepositoryPostgresTest.java:141`。
  - `SrppReportModuleCalculator.java:72` 的 reportBondInput、:99 的 topup reservedFunds／reservedEquity、:151 的 reservationClass（策略後備清單不含兩個建倉策略）。
  - 以上皆屬實。
- 修改（採方案 (a)）：
  - t623:97，新增 623.14a。`readReportSource` 排除 strategy 為 SATELLITE_BUILD_D143 或 SATELLITE_BUILD_D234 的腿。其他 row 的 reservationId 不變；legacy `read()` 與 reservationClass 不改。
  - t623:102，新增測試：`SrppReportReservationCaptureTest`（只留債券 row、reservationId 不變），以及 `SrppReportRepositoryPostgresTest`（多日累積不會增加 TWD／USD reservedFunds、VT 不進 USD 池、缺 `VT:美股` 分類時 reservationVerified 仍為 true）。
  - 已確認 `SrppReportReservationCapture` 不在 formula V3 manifest 的來源清單內。
- t626:129（626.11），說明 package reservedFunds 不再包含先前包的 009826／VT 腿，以及本包腿與已成交腿各自的處理方式。
- Requirement 與 design：requirements.md:7295（AC 6 後半）；design.md:14299（跨包預留）；design.md:14344。

## [Major] 626.9 BOND 補價 frame 沒有重算 market:台股:<code> validation
- 核對：t625 的 D215_FX PASS 前提要求 market 來源為 VERIFIED。`SrppReportExtraCaptureAdapter.assess` 在 quote 為 null 或身分不符時回 UNAVAILABLE。屬實。
- 修改：
  - t626:119（626.9 BOND 子項）：對每個換入新報價的 `market:台股:<code>`，以 `assess(刷新 body, tradingDate, refreshAt)` 重算 validation、coverage、sha256、revision、capturedAt；fx、policy 沿用凍結值；仍非 VERIFIED 時 fail-closed。
  - t626:215，補測試：package 時 UNAVAILABLE、刷新後 VERIFIED，重跑腿的 D215_FX 為 PASS 並得正數量；刷新後仍 UNAVAILABLE 則為 0 加 `D230_FRESH_QUOTE_REQUIRED`。
  - design.md:14342。

## [Minor] t626 報告 golden 仍指向 SRPP main；release 鏈漏列重產 golden
- 核對：`generate-srpp-report-golden.py:279` 會寫入 `policyBundleSha256` 與 `originalCalculatorHashes`；`check-srpp-report-authority.py:166,171`；t622 的驗證區塊有三支 golden。屬實。
- 修改：
  - t626:221，root 改為 D-234 SRPP worktree，並加註合併後改用 main。
  - t623:124（623.19），在 formula V3 之前加入以 D-234 root 重產 report-same-input、long-short-exit、state-topup golden，並逐一 `--check`。
  - requirements.md:7297（AC 8）；design.md:14303。

## [Minor] 626.20／決定 7 前提不完整：emergencyValue 也恆為 null
- 核對：`DecisionInputCaptureAdapter.java:130` 最後一個參數傳 null；`SrppReportFubonOverlay.java:29` 沿用；`DailyDecisionCalculator.java:68` 在 null 時令 gap=null。屬實。
- 修改，前提一律改為「子帳來源與 emergencyValue 兩者都要有來源」：
  - t626:35（決定 7）
  - t626:76（626.5）
  - t626:174（626.20 前提，合成 Input 帶非 null emergencyValue 且 gap>0）
  - t626:180（測試，另加一案：子帳已驗證但 emergencyValue 為 null 時分支不觸發）
  - t626:208（情境 22）
  - t626:236（完成報告條款）
  - requirements.md:7328；design.md:14342

## [Minor] riskAffected 未定義、v2 coverage 無統計規則
- 核對：v1 `PublicSrppProjectionService.validateLeg`／LegOutcome（bff :212-240）與 `SrppProjectionService` 的 coverage（:50-51,127）。屬實。
- 修改：
  - t626:107（626.8）：定義 riskAffected。它是布林值，凡增加股票風險的 BUY（非 B 結尾、非 SGOV）即為 true，與是否被歸 0、處於哪種模式都無關；SELL 與債券 BUY 為 false。同處寫明 BFF 重驗規則。
  - t626:159（626.16）：outcome 依優先序判定，依序為 NOT_EVALUATED、UNAVAILABLE（列出證據缺口碼）、COMPLETE；partialCount 為 0；BFF 以 `validateLegV2` 用同一規則重算，計數不符時回 502。另列測試。
  - t626:215：riskAffected 的正例與 hostile 測試。
  - requirements.md:7330；design.md:14344。

## [Minor] BELOW_MIN_ORDER_SHARES 只改 min_shares_per_order 造不出來
- 核對：SRPP `execution_policy.py` `global_build_vt`。pace_ok 時 `room["day"]` 一定存在，日上限 5 會先記 DAILY_SHARE_LIMIT。屬實。
- 修改：t623:130（623.20）。專用 policy 副本設 min=5.5，日／週上限調到大於 6（例如 10、30），並讓月剩額使最小 room 落在 [5.5, 6)（例如 5.8）。case 內保存完整 policy。

## [Minor] 626.11 靜默捨棄 D-234 §5.3 配置群組上限
- 核對：topup 的 groupCap／equityCap（`SrppReportModuleCalculator.java:103` 的 `aggregate_hard_cap`／`normal_equity_cap`）。屬實。
- 修改：
  - t626:139：說明理由。各腿原計算器已套用上限，v2 只縮不放；補價的 009826 腿上限來自 t623 計算器的停止比重與節奏；redecided 的 base 已過群組上限；跨 module 合計仍是 SRPP consumer 的硬閘門。
  - t626:140：列為已知差異。
  - t626:169（626.19）：列為第三項 SRPP 待辦。
  - design.md:14344；requirements.md:7328。
