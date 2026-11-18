# [t604] 修復 D-222 報告計算包遭 BFF 拒絕

**對應 Requirements:** Requirement 210（退休配置權威同步；Task 603 的 D-222 修訂）之報告事實傳輸相容性
**前置任務:** t603
**Liquibase changeset:** 無

## 背景

2026-11-18 兩個早報的 report-calculations 公開 GET 回502 UPSTREAM_INVALID。相同owner及identity的container內business純讀回200，ALLOCATION_GAP包含D-222新增protectedReserveTarget、referenceCashDelta、equityTarget，BFF封閉名稱表及OpenAPI未同步。正確行為是驗證通過後原bytes轉送；不是刪除事實或放寬所有名稱。

## 要做什麼

- [ ] 604.1 BFF ALLOCATION_GAP固定允許新增三個名稱。protectedReserveTarget/referenceCashDelta單位TWD，equityTarget單位RATIO，subject皆null；已知protectedReserveTarget非負、equityTarget在0..1，referenceCashDelta允許正負。保留未知value=null與原quality契約，不放寬其他fact與hash/identity檢核。

固定傳輸名稱亦包含既有條件式 reserveSelectionLow52/reserveSelectionHigh52/reserveSelectionPosition52：subject限VOO、0050、00881，前兩者正數且VOO單位USD、台股TWD，位置RATIO在0..1；單一標的區間成功即可存在，不要求其餘標的或selector成功。marketAdjustmentReviewAmount subject同三標的、TWD非負；marketAdjustmentUnallocated subject null、TWD非負。未知值維持value=null、quality="UNAVAILABLE"且reason為非空字串；不允許quality=null。上述僅凍結事實傳輸，不表示selector取得容量接受或可交易，原missing/receipt/模組狀態原樣保留，不新增跨fact重算。

- [ ] 604.2 OpenAPI八個固定enum/語義同步，原renderer重產asset兩份Swagger；跨SRPP metadata、bundle及authority由本輪release協調，不提前部署舊hash。無新路由、API版本schemaVersion、DB、公式、資料寫入或交易。
- [ ] 604.3 合成package覆蓋八facts與單標的區間成功而其餘候選不足的PARTIAL案例，、正負delta、未知value=null合法與quality=null拒絕、錯unit/subject/range及unknown name拒絕。用維護中保存的原始失敗包重現，不提交個人財務資料。部署只GET驗收不觸發producer、日報、寄信或交易。

## 驗證

`mvn -q -f bff/pom.xml -Dtest=SrppReportResponseValidatorTest,PublicSrppReportControllerTest test`

`ruby scripts/render-9090-openapi-docs.rb --asset-only --check`

`bash scripts/spec-check.sh`

完成獨立Codex spec/arch審查後依run-stack rebuild/recreate bff，需相同identity的公開9090 report-calculations回200並通過SRPP verifier；正式部署與新hash authority同步由release協調，不更改舊immutable包。

## 完成報告

待驗收回填。
