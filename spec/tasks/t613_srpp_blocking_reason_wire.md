# [t613] 修復早報共同缺源造成的重複阻擋原因 502

**對應 Requirements:** Requirement 187（不可變完整報告計算包）、215–217（D215/D226 獨立候選）
**前置任務:** t608、t609、t610
**Liquibase changeset:** 無

## 背景

2026-11-20 09:05 背景已產生 FINAL 包，但 D215/D226 多個 gate 共缺 topup-state，blockingReasons 重複，BFF 依既有 unique 契約拒絕整包為 502 UPSTREAM_INVALID。正確結果應保留 BLOCKED 與全部缺源收據，讓其他報告用途正常讀取。

## 要做什麼

- [ ] 613.1 在 SrppReportPublisher 的 wire adapter，shared 結果 canonical/hash/save 前與 consumer compose 後 canonical/hash/save 前深拷貝 modules，僅將每腿 blockingReasons 的完全相同字串依首次出現順序去重；其他欄位不改，不略過任何 gate。
- [ ] 613.2 adapter 不修補錯誤型別／缺少欄位，不放寬 BFF 原 unique/hash/schema 檢核；保留每 gate receipts、missing、reasonCodes、BLOCKED/UNAVAILABLE、數量與限價。金融 calculator、formula manifest、政策包、Swagger、owner、GET純讀與交易禁止邊界不變。
- [ ] 613.3 不 UPDATE/DELETE/重寫既有 immutable base/package，不以舊 run ID 冒充新結果；沿用 build identity/fingerprint 新包機制。normalizer 必須不修改 calculator 提供的原 module tree。
- [ ] 613.4 加入單元測試驗證 stable order、逐腿獨立、不改收據與數值、原輸出未改；用真 D215/D226 共同 topup-state 缺源案例驗證每 gate 仍輸出獨立 UNAVAILABLE 收據且所有腿 BLOCKED/零量。
- [ ] 613.5 透過真 PostgreSQL publisher 產生兩 consumer 新包，驗證相同輸入 replay 冪等、舊 bytes 不變、blocked 包 content hash 有效；輸出測試產物供 BFF 原 validator 逐欄驗證，另證明重加相同 blockingReason 並重簽 hash 仍被 BFF 拒絕。

## 驗證

先執行 scripts/spec-check.sh、scripts/codex-review.sh spec；實作後執行 新增的純函式單元回歸、SrppReportRepositoryPostgresTest、SrppReportMediumDipCalculatorTest、SrppNamedRadarSellCalculatorTest 及 BFF SrppReportResponseValidatorTest；傳遞 backend 測試輸出路徑執行跨層 round trip，再做完整 diff 架構審查。正式部署由父工作負責，以新映像容器與原精確 D226 hash API 回讀驗證，不手動 producer、不寄信、不交易。

## 完成報告

待實作驗收。
