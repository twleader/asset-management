VERDICT: CHANGES_REQUESTED

## 判定

critical: 0   major: 2   minor: 0

## Findings

### [Major] 新增重複的 Requirement 214 標題，造成編號與兩份文件計數不一致

位置：`spec/requirements.md:7180`；相關計數宣告位於 `CLAUDE.md`、`spec/steering/structure.md`。

證據：呼叫端提供的 spec-check 已確認以下三項 BLOCK，依判準直接採認，未重跑或重查：

```text
BLOCK │ Requirement 214 重號：在編號空間內出現 2 次
BLOCK │ CLAUDE.md 宣稱 201 個 Requirements，實際 202
BLOCK │ spec/steering/structure.md 宣稱 201 個 Requirements，實際 202
```

本輪新增的標題為 `### Requirement 214 補充：D-222 報告計算傳輸相容性（Task 604）`，而任務檔 `spec/tasks/t604_srpp_d222_report_wire.md:3` 明確將本次工作歸屬既有 Requirement 214。

被違反的出處：本輪判準的「機械證據壓過判斷」與「內部一致性」；`CLAUDE.md` 的 SDD 審查規範明列重號與計數漂移為機械查核項目。

修法：將補充內容納入既有 Requirement 214，以不會被辨識為另一個 Requirement 的子標題呈現，例如 `#### Task 604：D-222 報告計算傳輸相容性`。修正後重新執行 spec-check，確認三項 BLOCK 一併消除；不要只將兩份文件的計數改成 202，留下重號。

### [Major] 傳輸對齊只列三個名稱，漏掉同一 producer 已存在的其他 D-222 facts

位置：`spec/tasks/t604_srpp_d222_report_wire.md:13`、`spec/tasks/t604_srpp_d222_report_wire.md:14`、`spec/design.md:14197`。

證據：

- `backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java:52` 在 D-222 projection 存在時呼叫 `reserveSelectionFacts(...)`。
- 同檔 `:56` 在個別標的區間資料通過檢核後，會直接輸出 `reserveSelectionLow52`、`reserveSelectionHigh52`、`reserveSelectionPosition52`。這三者的輸出發生在 `:57` 的全部區間／FX／capacity 缺口判定之前，因此其他候選資料不足，不會撤回已加入的區間 facts。
- 同檔 `:59` 在額外條件成立時還會輸出 `marketAdjustmentReviewAmount`、`marketAdjustmentUnallocated`。
- `bff/src/main/java/com/steven/assets/bff/publicsrpp/SrppReportResponseValidator.java:21` 的 ALLOCATION_GAP 名稱表沒有上述五個名稱，`:130` 對不在表中的名稱直接 `fail()`。
- `docs/openapi/docker-external-api.yaml:5045` 起的 `SrppReportFactAllocationGap.name.enum` 同樣沒有上述名稱。
- `bff/src/main/java/com/steven/assets/bff/publicsrpp/PublicSrppReportService.java:77` 驗證失敗後，`:80` 轉為 `UPSTREAM_INVALID`。

依本輪 604.1／604.2 僅增加三個名稱，即使完成指定修復，含既有區間 facts 的合法 producer 包仍會遭拒。這是依現有控制流程推導的條件式失敗，並非宣稱已在正式環境觀察到上述五個名稱。

被違反的出處：本輪「可查證性」與「內部一致性」判準；任務檔 `:9` 要求合法 business 回應驗證通過後原 bytes 轉送，`CLAUDE.md:404` 起要求公開回應具有完整、可驗證的 OpenAPI 契約。

修法：在 spec 中逐項列出同一 ALLOCATION_GAP producer 現有的 D-222 fact 名稱及適用條件，將上述五個名稱的 BFF／OpenAPI 傳輸契約一併界定，仍使用固定白名單。明定區間 facts 的標的 subject、TWD／USD／RATIO 單位與數值邊界，保留 selector 的缺口與無容量接受語意；新增「部分標的區間成功、其他候選不足」的合成包案例。這些修正限於既有輸出的傳輸與驗證，不需要更改公式或啟用任何 selector／交易能力。

## 已查無發現

- **可查證性其餘項目：** 以 `rg -an` 查到 business 在 `SrppReportModuleCalculator.java:45` 確實產生本輪列出的三個 facts；兩個指定測試類均存在。未將預定新增的驗證規則誤判為不存在的現況。
- **內部一致性其餘項目：** requirements、design、task 對三個 facts 的單位、subject、負 delta 與 equityTarget 範圍一致；task／design 均載明 protectedReserveTarget 已知值非負，無額外發現。
- **Controller 越層：否。** 規格未要求 Controller 讀 Repository、計算或 upsert；現有 `PublicSrppReportController` 只委派 service。
- **DTO 不可變性：不適用。** 本輪未規劃新增或修改 DTO。
- **新頁面與專屬 BFF：不適用。** 本輪無新增頁面。
- **跨頁 BFF、直打資源或裸 fetch：否。** 本輪未描述任何前端呼叫變更。
- **自行發明跨頁例外：否。** 未以共用慣例主張例外。
- **同義值共用 business API：是。** 沿用既有 report-calculations，未另建計算來源。
- **服務依賴方向：無新增違反。** 未要求前端直連 business、business 外呼行情或 external-materials 反向呼叫 business。
- **資料冗餘與衍生值持久化：否。** 本輪明定不變更資料庫或既有不可變包。
- **業務分類硬編 enum：否。** 此處是 wire fact identifier 白名單，未新增可管理的投資業務分類。
- **Endpoint 命名：不適用。** 無新增路由。
- **資料來源：** 本輪只驗證並轉送既有包，未新增即時價／收盤價來源或寫死排程。schema 同步與 OpenAPI 現況採認呼叫端提供的 PASS 證據，未重查。

## 自我挑戰

- **保留第一項：** 三項 BLOCK 都已由呼叫端證實；合併為同一 finding，是因新增重複 Requirement 標題同時造成計數增加。沒有將「補充」當作可忽略機械閘門的理由。
- **保留第二項：** 已排除「這些名稱只是未實作設計」的可能：五個名稱均出現在現有 `m.fact(...)` 呼叫中。尤其三個區間 facts 在全部候選通過前就已加入，不能用後續 FX／capacity 缺口推定它們不會輸出。未將此靜態證據擴張為正式環境重現結果。
- **撤下「只重建 BFF 必然漏部署 business」的疑慮：** 任務檔 `:14`、`:25` 已將新 hash／authority 同步交由既有 release 鏈協調；不能僅因本地驗證段列出 BFF，就斷言 release 必定違規。
- 未執行測試、renderer、spec-check、API 或部署命令；本報告未修改任何檔案。

## 無法查證

本輪未提供原始失敗包或早報 HTTP receipts，因此無法獨立確認任務背景所述「兩個早報公開 GET 502、相同 identity 的 business GET 200」。這不影響上述機械 BLOCK 與程式控制流程證據。

