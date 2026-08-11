# t310 交易雷達 V13 停止點紀錄

**停止日期：** 2026-08-10（Asia/Taipei）
**恢復日期：** 2026-08-11（Asia/Taipei）
**狀態：** EVIDENCE_SPEC_DRAFTED／SPEC_REVIEW_LIMIT_REACHED／production V13 仍未核准
**範圍：** 原停止點仍固定 2026-08-10 的證據邊界；本次只恢復 t314–t316 所定義的 joint-fold、provenance、完整回歸、Docker 與真實 holdout 證據工作，不代表 V13 已核准升版或可發布。

2026-08-11 已依使用者指示恢復對分析有幫助的證據閉環；先通過重新執行的 spec review，再實作與驗證。production `RULE_VERSION` 維持 V12，除非真實 holdout 全部通過且另有明確發布核准；不得把 focused tests 當成完整驗證，也不得用未驗證資料產生交易結論。

## 2026-08-11 三輪審查結果

- Round 1：critical 3／major 4／minor 1；已修正規劃端點現況、V12 safety gate、Task 313 同步、Docker／holdout／frontend test／future Treasury curve 與 Javadoc 契約。
- Round 2：critical 0／major 2／minor 1；已修正 VALUATION 整組不適用 projection、weakening boolean invariant 與 `Optional.of(false)` 休市語意。
- Round 3：critical 0／major 2／minor 0；已修正 t309 的逐 component provider/date 契約，以及 Requirement 57／design 的條件式 V13 發布文字。
- 使用者指定最多三輪，故不執行 Round 4，也不寫入 spec-review pass marker。t314–t316 留作已收斂但**尚未獨立複審通過**的 implementation-ready draft；production code、Docker rebuild 與真實 promotion holdout 維持停止。

## 已取得但有限的驗證

- backend 定向回歸：127/127 通過。
- external-materials-service 定向回歸：20/20 通過。
- 上述數字只代表已選定的定向測試範圍；尚未完成完整 backend regression、frontend build、Docker rebuild/recreate、服務健康檢查、真實資料抽查或真正樣本外 holdout 驗證。

## 未完成／不可宣稱項目

1. **Joint fold 的每 horizon 證據仍需驗證。** 目前 joint fold per-horizon 的 train-date、sigma profile 與 candidate selection 還沒有完成獨立、可重現的 leakage／邊界驗證；不得宣稱每個 horizon 都已使用自身 fold 證據且結果可直接 promotion。
2. **Treasury production freshness 與 V12 safety fallback 尚未完成整合驗證。** 權威日曆與 freshness 邏輯已有定向測試，但 production wiring、長假／日曆未知情境及 V12 fallback 在真實服務中的行為尚未以完整回歸與運行中證據確認；不得宣稱已安全部署。
3. **PE／PB／yield component provenance 尚未完整落到 UI／Excel。** 後端與部分輸出欄位已有 provenance 方向，但三個 valuation components 的 provider、as-of、缺漏原因與 applicability 尚未完成 UI 展示、Excel 欄位／資料列等長及真實輸出核對；不得宣稱前後端匯出完整一致。
4. **V13 尚未以完整回歸、Docker 與真正 holdout 驗證。** 尚未完成 full regression、Docker image rebuild/recreate、實機 API／health/page 檢查及真實 market data／樣本外 holdout；V13 不得 promotion、切換 production 或作為已驗證交易建議。

## 恢復後 gate

- t314–t316 必須重新通過 `spec-review`；marker 未更新前不得實作。
- focused test 通過不等於完整驗證，也不解除上述四項 gate。
- 本停止點之後的任何修改，若未取得對應 t314–t316 證據，均不得寫成「完成」「已部署」「可交易」。真實報告若為 `REJECTED`／`INSUFFICIENT`，保留 V12 並列出原因即為正確結案。
