VERDICT: CHANGES_REQUESTED

## 判定

critical: 0   major: 1   minor: 0

## Findings

### [Major] 隔離 fixture 修正漏列兩個必要開關，僅補金額欄位仍會中止生成

位置：`spec/design.md:14036`、`spec/tasks/t591_srpp_retirement_allocation_authority.md:17`。

證據：

- 新增規劃列出補零 `bridge_cash`／`bridge_pending`。
- `scripts/generate-srpp-state-topup-golden.py:101` 以 `TransitionLedger.__new__` 建立 reserveDeployment fixture，直接呼叫 `deploy_overdue_reserve()`，未初始化 `bridge_enabled`、`voo_fx_wait_proxy`。
- `srpp-simulator/runtime/scripts/transition_ledger.py:229` 呼叫 `equity_purchase_room()`；該方法於第155行直接讀取 `self.bridge_enabled`，後續第233行直接讀取 `self.voo_fx_wait_proxy`。兩個開關原本在 `__init__` 第42–43行設定，`__new__` fixture 會跳過它。
- 已另查 D220 候選來源 `/Users/steven/.codex/worktrees/retirement-allocation-audit/SRPP/scripts/transition_ledger.py`，上述依賴相同。因此只補兩個金額後，仍會因缺少開關發生 `AttributeError`。

被違反的出處：本輪「可查證性」判準，以及 Task591 第27行要求原 generator 完成22個測例與 `--check` 的驗證契約。

修法：兩處規劃一併明訂，僅在該合成無 bridge fixture 初始化：

- `bridge_cash=np.zeros(1)`、`bridge_pending=np.zeros(1)`。
- `bridge_enabled=False`、`voo_fx_wait_proxy=False`。

保留正式來源缺證據判定，不以 production 預設值或放寬檢查處理；由作者生成並查核原22個測例。

## 已查無發現

- 機械證據：採用提供的完整結果，`BLOCK: 0 / CHECK: 0`；未重跑。
- 可查證性：以 `rg` 核對 `expected_formal_document`、三支 golden generator、Ruby registration generator、formula generator及六個具名 Java 測試，均存在。
- 既有 Java 敘述：查得 `SrppReportModuleCalculator` 讀模型目標及高股息組上限，`SrppReportStateCalculator` 讀模型熊市階梯，無發現。
- 內部一致性：核對 Requirement210、Task591與新增 design 的目標、D220版本及離線／發布界線，除上述 fixture 遺漏外無發現。
- 前輪追蹤：前輪無 finding，本輪沒有待確認的修正項。

架構鐵則逐項核對：

1. Controller 直接讀 Repository／執行領域邏輯：否。
2. 新增或修改 DTO 違反不可變 record：否，本輪不新增 DTO。
3. 新頁面缺專屬 BFF：否，本輪不新增頁面。
4. 前端跨頁 BFF／直打資源／裸 fetch：否。
5. 自創跨頁共用例外：否。
6. 同義值另開 business API：否。
7. 違反服務依賴方向或新增外部行情直連：否。
8. 新增冗餘持久欄位或衍生值：否。
9. 新業務分類缺 DB／seed／設定介面：否，未新增此類分類。
10. 新 endpoint 違反命名規則：否，未新增 endpoint。

資料來源規劃未改即時價、收盤價或 DB schema；未發現新增違規。

## 自我挑戰

- 保留此 Major：這不是要求未來功能現在完成，而是新增 fixture 修正配方漏掉同一呼叫鏈必讀的狀態；已核對 generator、匯出 runtime及候選原始來源。
- 未把候選 artifact 尚未完整生成、未登錄或未部署列為缺陷，因本輪明確只準備離線結果。
- 未把 `bridge enabled=true` 判為正式執行授權；新增規格已明確保留 API 阻擋與零量。
- 本輪僅做靜態唯讀查證，未執行 generator、測試、API或任何寫入命令。