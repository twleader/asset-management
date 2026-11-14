# [t590] 正式熊市五批與情境同步自回檔15%啟動

**對應 Requirements:** Requirements 187／193（正式SRPP authority與計算）、208（同版設定與情境呈現）
**前置任務:** t588、t589
**Liquibase changeset:** 無

## 背景

使用者已明確確認：圖中的熊市五批門檻由20/25/30/35/40%整組改為15/20/25/30/35%，預算比例仍8/12/20/25/35%；正式規則也必須15%能啟動，並處理與既有D212的15%牛市回檔路徑銜接，避免雙算資金。只修改表格不足：MonthlySignals硬寫20%且ReserveStrategy未將values.bearLadder套入實際policy，仍會等20%才執行。

## 要做什麼

1. 以D-219記錄此獲授權規則更新，沿D-218基準增加policy/model版本；目前D-218為2.66.0/3.22.0，本次2.67.0/3.23.0。只更新版本metadata及本任務明訂規則；高股息兩日止穩、D217有限設定、D193稅法與其它原規則不回退。
2. 正式`execution_policy.dynamic_allocation_policy_d141.bear_ladder_d141`五列為drawdown=[.15,.20,.25,.30,.35]、share_of_window_budget=[.08,.12,.20,.25,.35]，同一鎖定窗口預算且比例合計1。catalog的bearLadder仍沿這個同版model來源唯讀，不另建重複情境梯表；預設頁面和真正計算必須相同。
3. 正式D202自各買進標的上市市場之TWSE/SPX、近24個月最高已完成收盤算回檔；`regime_benchmark_d202.bear_drawdown`與D212對應`d202_bear_drawdown`同步.15，仍需已驗證belowMA200及RISK_OFF才是該熊市窗口。deep_bear_drawdown=.30、已完成日/來源/MA斜率等證據要求及其它bull條件不放寬。回檔15%是達到起點，非單憑跌幅給無條件買進許可。
4. 保留D212原本的互斥分流，不新增並行可相加預算：AI週期NOT_PRESENT（證據完整且未出現破壞）只走原D212修正窗口，原open15/close10、其30/25/20/15/10%預算、保護現金與其它條件全保留；PRESENT或UNVERIFIED只在drawdown<=-.15且belowMA200=true、risk_off=true、benchmark verified時走REBALANCE_BEAR_WINDOW，否則NONE/UNAVAILABLE。未知不得假造NOT_PRESENT，任何單次routing只能返回一個window。既有pending、earmark、確認成交、資金與dedup/cooldown證據仍計入，不能把未發生買進當已成交或替另一窗口補空資料。
5. 其它熊市買方硬閘門維持：資金/保護現金、債券最多2/3、總債券底線、股票總量與各組/單檔上限、實際深度/行情/雷達、已驗止穩與必要風險條件、批次遞增與五交易日冷卻。深熊30%及正式60%/70%分段不改；不足則等待，不能因首梯15%增加窗口預算或取消上限。跳空跨多梯也只能依序建議下一批，最多五批，不一次釋出全梯。
6. MonthlySignals新增可指定且驗證為finite 0<drawdown<1的熊市門檻；保持相容預設.20，但所有本次同版模擬呼叫端需傳來源參數（monthly_signal_proxy.bear_drawdown_initial=.15及/或同版首梯.15），不可執行時仍硬卡.20。保留至少11期暖機、下降10月趨勢與index低於MA的risk_off；與策略相關的分支使用明示同版門檻，不留下標為新規則卻硬寫舊20的訊號。
7. ReserveStrategy建立執行policy時真正copy values.bearLadder，首梯/預算與回應assumptions一致；full monthly engine也從同版來源傳參數。此V3仍是月頻aggregate情境，不是正式逐日或逐檔回測；原aggregate bearEquityCap近似不在本次偷偷改為另一套cap演算法，不宣稱與正式60/70分段等價。生活預算分支與策略訊號的門檻須揭露其來源，維持原底線金額與保護額，不藉本次變更取消資金保護。
8. Java正式階梯計算沿同版model，不另寫15常數；加精確邊界測試，實際符合資格與容量時14.99%沒有首批、15%可提首批8%，次梯20%及後三梯正確，冷卻/資金不足仍等待。Python與Java同一來源政策都要驗證15起，不以UI文字代替可執行證據；AI完整/破壞/未知三種路由、缺證據與兩條路徑的唯一選擇必須覆蓋。
9. 同步正式目前權威說明，清楚區分兩套都15%但不同資金規則/權重；較新D219取代「熊市五批20%起」之現行數字，歷史決策原文保留為歷史。policy guard驗證確切五梯、D202/D212相同15及原互斥路由不變；不得為通過而放寬其它guard。既有正式golden只容許獲授權門檻造成的差異，必保存差異路徑，D218其它金融輸出維持。
10. 本任務與t589整體完成獨立架構審查與受控雙repo發布；authority/model/catalog/export/contract/registry一版到底。兩repo commit/no-ff merge/push且remote精確回讀後才insert-only登錄與乾淨main原.env部署，重建必要服務並核JAR、worker與實際API；不改DB schema或執行正式報告/下單/寄信。今天非交易日，正式報告採用不得偽稱已執行。

- [x] 590.1 更新五梯、D202/D212相同15、版本與現行權威，原互斥分流和所有非目標條件保留。
- [x] 590.2 月頻真正使用15%門檻與同一梯表，保留暖機/趨勢/資金保護及近似範圍。
- [x] 590.3 Python/Java資格、階梯、路由與保護條件邊界，回歸及golden差異證據。
- [x] 590.4 與t589完成測試、獨立審查、雙repoGit、登錄、部署與精確API回讀。

## 驗證

新增`test_bear_fifteen.py`，並擴增現有Java `SrppReportStateTopupCalculatorTest`的15%邊界測例（新測例待本任務實作）。用合成行情/固定市場路徑驗14.99/15/19.99/20/25/30/35、下跌趨勢/未暖機/未驗基準/缺MA等不通過、首批8%/後續權重/總預算/冷卻/跳空一次一批、兩套分流唯一選擇、原資金底線。完整新V3情境與原full engine對門檻/來源一致，不要求整條兩模型路徑等價。

```bash
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/test_bear_fifteen.py
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/test_correction_dip.py
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/test_settings_cashflow_v2.py
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/test_settings_simulation.py
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/policy_bundle.py check --purpose daily --consumer Codex
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/daily_runtime_contract.py check
```

Asset以`SrppReportStateTopupCalculatorTest`及原report state/topup/same-input golden相關測試驗同版source，並跑t589原Settings/ShortTerm範圍；前端186測試與build、實際catalog五梯/5檔債券及年度稅明細瀏覽器操作、純worker合成HTTP試算（不得vendor）與部署JAR/classpath匹配。規則包/Swagger三mirror及export --check必須全通過；業務API若NON_TRADING_DAY/CONTEXT_NOT_READY須回報真實狀態，不以health/registry冒充financial acceptance。

## 完成報告

2026-11-14 已完成實作、獨立審查、619 項測試、雙專案 no-ff merge／push、insert-only 登錄與四服務正式部署。完成報告與精確身份、測試及實機證據見 [t589／t590 部署驗收紀錄](../../docs/reviews/t589_t590_srpp_settings/release-20261114/README.md)。

本段及勾選為純完成狀態回填（SKIP_SPEC_GATE），未修改已審查契約。精確新 bundle 的 9090 診斷為 409 NON_TRADING_DAY，正式報表自然採用仍為 NOT_YET_EXECUTED。正式資產 LOCAL、真 AI 與 Google OIDC 完整登入未執行；實際部署 worker 合成 1,000 路徑、owner 安全草稿／純讀及編譯介面驗收通過，具體範圍與限制見紀錄。
