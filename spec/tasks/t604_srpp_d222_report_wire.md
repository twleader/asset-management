# [t604] 修復 D-222 報告計算包遭 BFF 拒絕

**對應 Requirements:** Requirement 210（退休配置權威同步；Task 603 的 D-222 修訂）之報告事實傳輸相容性
**前置任務:** t603
**Liquibase changeset:** 無

## 背景

2026-11-18 兩個早報的 report-calculations 公開 GET 回502 UPSTREAM_INVALID。相同owner及identity的container內business純讀回200，ALLOCATION_GAP包含D-222新增protectedReserveTarget、referenceCashDelta、equityTarget，BFF封閉名稱表及OpenAPI未同步。正確行為是驗證通過後原bytes轉送；不是刪除事實或放寬所有名稱。

## 要做什麼

- [x] 604.1 BFF ALLOCATION_GAP固定允許新增三個名稱。protectedReserveTarget/referenceCashDelta單位TWD，equityTarget單位RATIO，subject皆null；已知protectedReserveTarget非負、equityTarget在0..1，referenceCashDelta允許正負。保留未知value=null與原quality契約，不放寬其他fact與hash/identity檢核。

固定傳輸名稱亦包含既有條件式 reserveSelectionLow52/reserveSelectionHigh52/reserveSelectionPosition52：subject限VOO、0050、00881，前兩者正數且VOO單位USD、台股TWD，位置RATIO在0..1；單一標的區間成功即可存在，不要求其餘標的或selector成功。marketAdjustmentReviewAmount subject同三標的、TWD非負；marketAdjustmentUnallocated subject null、TWD非負。未知值維持value=null、quality="UNAVAILABLE"且reason為非空字串；不允許quality=null。上述僅凍結事實傳輸，不表示selector取得容量接受或可交易，原missing/receipt/模組狀態原樣保留，不新增跨fact重算。

- [x] 604.2 OpenAPI八個固定enum/語義同步，原renderer重產asset兩份Swagger；跨SRPP metadata、bundle及authority由本輪release協調，不提前部署舊hash。無新路由、API版本schemaVersion、DB、公式、資料寫入或交易。
- [x] 604.3 合成package覆蓋八facts與單標的區間成功而其餘候選不足的PARTIAL案例、正負delta、未知value=null合法與quality=null拒絕、錯unit/subject/range及unknown name拒絕。用維護中保存的原始失敗包重現，不提交個人財務資料。部署只GET驗收不觸發producer、日報、寄信或交易。

## 驗證

`mvn -q -f bff/pom.xml -Dtest=SrppReportResponseValidatorTest,PublicSrppReportControllerTest test`

`ruby scripts/render-9090-openapi-docs.rb --asset-only --check`

`bash scripts/spec-check.sh`

完成獨立Codex spec/arch審查後依run-stack rebuild/recreate bff，需相同identity的公開9090 report-calculations回200並通過SRPP verifier；正式部署與新hash authority同步由release協調，不更改舊immutable包。

## 完成報告

2026-11-18 已完成兩專案 feature commit、main no-ff merge、push、insert-only registry 與實際部署。最終規則包為 `58674cfdd0d77af0e4ec439432cd1b883ec42d9e9fc0fc8fd1f30faf5b8046aa`，Swagger 為 `68d6dcb1c24bed5a80765877a0aa87fb0f2d70cdf9aa13a6278cdad02c59852d`。BFF 與 business-services 均完成必要 image rebuild/container recreate；消費端 schema 補正後第二次僅部署 business-services，保留已驗證 BFF image。

首次實機檢查抓到 SRPP consumer schema 的閉合 enum 仍少八個名稱，當時 HTTP200 但獨立驗證拒絕；已補 schema、明確的維護同步工具與正反回歸，再發布新 hash，不以第一次成功 HTTP 取代驗收。D-222 009826 三張收據與全候選檢核、D-207 證據／指紋交付順序亦於 SRPP 修正。金融政策數值與原有硬閘門未放寬，126 個 golden 案例金融輸出及 calculator source hashes 不變。

最終自然 producer 於台北 10:29:19 發布新 hash 包；10:30:06 Codex 與 10:30:08 Claude 同 owner、當日 09:05 slot 的公開 GET 均 HTTP200，完整 `verify_srpp_report_calculations.py` PASS，固定 calculationRunId 再讀 HTTP200 且原始 bytes 相同。Codex run `570e026d-fdf4-4488-a762-a25da441fdf4`、Claude run `223f8700-d02c-404b-8f60-00f63c815696`。各包仍如實為 8 PARTIAL、4 UNAVAILABLE、0 COMPLETE；這是修復的業務介面與證據完整性驗收，不是早報已補送或所有公式完整採用。

目標驗證：BFF 25 tests；SRPP 相容／建倉／guard／候選相關 121 tests、runtime 35 tests、consumer schema 同步 4 tests；政策與權威檢查、OpenAPI parity、registry 與 JAR bytes 核對通過。另一次廣泛 dividend pattern 誤納既有舊版 `test_four_dividend_policy`，其失敗未列為本次通過，也未擴張修正。

完整本機維護證據位於 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-18-morning-repair-release/`，最終業務讀回為 `consumer-schema-followup/business-api-readback.json`。本輪未補跑 SRPP 早報、未呼叫日報寄信或券商交易。服務重建後既有 MarketAnalysis self-heal 曾自動寄出股市分析通知，其觀測另存於維護證據，不冒稱整個服務期間沒有寄信。
