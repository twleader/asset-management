# t310 交易雷達 V13 停止點紀錄

**停止日期：** 2026-08-10（Asia/Taipei）
**狀態：** STOPPED／不可宣稱完成
**範圍：** 本紀錄只固定目前證據邊界，不代表 V13 已核准升版或可部署。

依目前決策，從本停止點起不再新增 production implementation、升版 V13、部署或以未驗證資料產生交易結論。後續若要恢復，必須先逐項補足下列證據並更新本紀錄；不得把 focused tests 當成完整驗證。

## 已取得但有限的驗證

- backend 定向回歸：127/127 通過。
- external-materials-service 定向回歸：20/20 通過。
- 上述數字只代表已選定的定向測試範圍；尚未完成完整 backend regression、frontend build、Docker rebuild/recreate、服務健康檢查、真實資料抽查或真正樣本外 holdout 驗證。

## 未完成／不可宣稱項目

1. **Joint fold 的每 horizon 證據仍需驗證。** 目前 joint fold per-horizon 的 train-date、sigma profile 與 candidate selection 還沒有完成獨立、可重現的 leakage／邊界驗證；不得宣稱每個 horizon 都已使用自身 fold 證據且結果可直接 promotion。
2. **Treasury production freshness 與 V12 safety fallback 尚未完成整合驗證。** 權威日曆與 freshness 邏輯已有定向測試，但 production wiring、長假／日曆未知情境及 V12 fallback 在真實服務中的行為尚未以完整回歸與運行中證據確認；不得宣稱已安全部署。
3. **PE／PB／yield component provenance 尚未完整落到 UI／Excel。** 後端與部分輸出欄位已有 provenance 方向，但三個 valuation components 的 provider、as-of、缺漏原因與 applicability 尚未完成 UI 展示、Excel 欄位／資料列等長及真實輸出核對；不得宣稱前後端匯出完整一致。
4. **V13 尚未以完整回歸、Docker 與真正 holdout 驗證。** 尚未完成 full regression、Docker image rebuild/recreate、實機 API／health/page 檢查及真實 market data／樣本外 holdout；V13 不得 promotion、切換 production 或作為已驗證交易建議。

## 目前 gate

- `spec-review` marker 仍是 stale；恢復實作前必須重新執行 spec review 並處理 findings。
- focused test 通過不等於完整驗證，也不解除上述四項 gate。
- 本停止點之後的任何修改，若未先更新對應證據與狀態，均不得寫成「完成」「已部署」「可交易」。
