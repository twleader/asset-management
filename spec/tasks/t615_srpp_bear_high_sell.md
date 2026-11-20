# [t615] 提供 D-227 台股熊市高檔賣出門檻證據

**對應 Requirements:** Requirement 219（台股熊市高檔減碼門檻事實）
**前置任務:** t613
**Liquibase changeset:** 無

## 背景

熊市評估不要求牛市轉弱條件。資產端現有 CORE_SELL_GUARD 的 D-174 需要同 owner 的觀察帳本及牛市技術條件，無法獨立供熊市報告取得兩項門檻事實。

## 要做什麼

- [ ] 615.1 以正式執行規則 execution_policy.bear_high_sell_d227 啟用。必須核對 decision=D-227、market=台股、minimum_d_exclusive=80、minimum_week52_position_inclusive=0.80 的精確數值。舊 bundle 缺欄位時維持原輸出；新欄位無效則回報缺口。
- [ ] 615.2 對同 owner 當輪凍結資產中的唯一台股正持倉，使用既有市場來源驗證 exact symbol、market、資料版本、LIVE、日期、完整性及一小時資料有效期限；同來源證據須標明市場資料 ref。D 值允許 0–100，52 週區間位置允許 0–1，兩者均須有效數字。由純報告組裝器將本結果加入模組收據，D-174 觀察帳本缺口仍可產生獨立 D-227 觀察，缺資料則回報 UNAVAILABLE。
- [ ] 615.3 沿既有資料格式回傳逐標的 D227:<symbol> 收據，PASS 僅代表兩門檻成立，理由 D227_THRESHOLDS_MET_REGIME_AND_LLM_REQUIRED；未達為 BLOCK 與 D227_THRESHOLDS_NOT_MET；無來源或無法驗證一律 UNAVAILABLE，並列原因。以通用 receipts.values 回傳 d227DValue（PERCENT，0–100）及 d227Week52Position（RATIO，0–1），subject 均為同一台股代號。可核對收據引用 assets、policy 及市場 source；缺市場 source 時收據只引用存在的來源。SRPP verifier 獨立重播數字、來源與門檻。D-174 的封閉 data.facts、scope、模組覆蓋、收據與缺口保留原義。
- [ ] 615.4 同輪台股熊市確認、政策對該標的減碼／出清方向、LLM 綜合技術及外部環境後裁決與既有執行檢核皆由 SRPP 判定；本模組不發布交易腿、可賣量、限價或委託。美股、具名策略與其他模組維持現有規則。
- [ ] 615.5 更新 policy authority、正式投影、insert-only SQL 與計算測試對照資料。D-227 不改既有 V3 計算來源、正式公式指紋、catalog 或 BFF mirror，新 bundle 仍精確綁定 D-227 政策、SRPP runtime 及 verifier；本任務使用既有報告 envelope 與路由，不變更 Swagger 或資料表。兩專案完成 Git 及線上登錄後，由發布流程重建服務及實測 9090。
- [ ] 615.6 回歸兩門檻的邊界、來源不符與無 D-174 觀察狀態、重複台股持倉、排除未持有及美股、舊 bundle 行為及零交易腿。驗證 source refs 及精確 bundle。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest test
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP --check
```

正式發布另核對兩端 main refs、insert-only row、business-services 重建／重建後運行映像、JAR 內嵌 bytes 及從 SRPP 實測 9090；不補跑日報、寄信或下單。

## 完成報告

待實作及正式驗收。
