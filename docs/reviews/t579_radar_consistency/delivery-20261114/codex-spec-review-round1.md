VERDICT: CHANGES_REQUESTED

## 判定
critical: 0   major: 2   minor: 0

## Findings

### [Major] OpenAPI 升版已有契約測試 BLOCK，必須同步修正版本斷言

位置：`scripts/tests/docker-external-api-openapi-test.rb:245`、`:1302`；本輪 Task 579.5。

證據：呼叫端提供的 spec-check 已明確回報：

```text
BLOCK │ 9090 gateway/OpenAPI contract 失敗
BLOCK: 1   CHECK: 1
```

本輪規格要求 OpenAPI `1.27.3 → 1.27.4`。指定 repo 的上述兩處版本斷言仍為 `1.27.3`，錯誤訊息則仍寫歷史版本 `1.26.0`。

違反出處：本輪判準「BLOCK 就是已證實的缺陷」及 Task 579.5 的版本同步要求。此項直接採用提供的機械證據，未重跑檢查。

修法：同步更新兩處斷言及錯誤訊息，核對 unified renderer 測試的版本期望、重產兩份 mirror，再重跑所列契約檢查，取得零 BLOCK 的新輸出。

### [Major] 正式部署與 commit／merge／push 的順序互相衝突

位置：本輪 Task 579「驗證」末段；對照 `CLAUDE.md:510`、`:512`、`:544`。

證據：本輪任務要求：

> 「僅重建business-services並recreate，檢查實際雷達版本/行為」  
> 「驗收後才commit-merge-push」

但 `CLAUDE.md:510` 明定兩專案完成 commit、no-ff merge、push、remote-ref readback **之後**才能執行 registry SQL；`:512` 要求從乾淨 main 重建；`:544` 更明列「雙 repo 落地 → registry → 乾淨 main rebuild／recreate → 實機回讀」。既有 `spec/tasks/t577_radar_completed_structure_v25.md:52` 亦採此順序。

違反出處：本輪自行要求遵循的 Task504 受控發布鏈。照末句執行會形成循環依賴，或使作者在落地前替換正式容器。

修法：明確拆成「發布前測試／離線 build」與「發布後 runtime 驗收」。正式順序寫為：靜態驗證完成 → 雙 repo commit／no-ff merge／push／remote readback → insert-only registry → 乾淨 main rebuild／recreate → 精確身分與雷達行為回讀。合法時窗尚未到的驗收保留 `NOT_YET_EXECUTED`。

## 已查無發現

- **可查證性：** 已以 `rg -n -a` 核對 `TradingRadarRuleEngine.actionFor`、`StockInput`、`TimingState`、`kdHeatOf`、`highZone`、Fubon 相容身分及 production／candidate 門檻傳遞。現況識別字存在；V26 視為未來契約，無發現。
- **策略一致性：** 已查 production 的 `8%／15%`（引擎 `:269–270`）、candidate 參數傳入（`:2363–2368`）、EXTREME_OVERSOLD 優先保護（`:3367–3373`）及完成日 5% 計分提醒（`:2837–2845`）。除上述發布順序外，無新增已證實矛盾。
- **後置閘門：** 已查 V25 結構分支、`TradingRadarService:1803–1810` 的 evidence／SHORT 分鐘 K，以及 `:554–555` 的官方 SMA20 閘門。本輪要求保留，無發現。
- **架構鐵則逐項：**
  1. Controller 直接讀 Repository、外呼或處理領域邏輯：否。
  2. DTO 使用可變物件或 setter：否，本輪不改 DTO。
  3. 新頁面缺專屬 BFF：不適用，未新增頁面。
  4. 跨頁 BFF、直打 business 或裸 fetch／EventSource：否。
  5. 自創跨頁共用例外：否。
  6. 同義值新增不同 business API：否。
  7. 違反服務依賴方向或直連外部行情：否。
  8. 新增冗餘欄位或持久化衍生值：否。
  9. 新增硬編碼業務分類：否。
  10. 新 endpoint 命名違規：不適用，未新增 endpoint。
- **資料來源：** 本輪明定不改資料來源、DB、排程及交易邊界；未以 changelog 推定 schema 現況，無發現。

## 自我挑戰

- 保留第一項：它是提供材料中的明確 BLOCK；錯誤訊息寫舊版號不影響失敗事實，也不能據此忽略。
- 保留第二項：一般流程雖要求先驗收再收尾，但本次改動 Swagger 身分，Task504 已明文規定正式發布順序；任務必須清楚區分離線驗證與正式 runtime 驗收。
- 未將「main 尚無 V26／Task579」列為缺陷：這是實作前審查，未來識別字合法；本輪審查對象是提供的快照。
- 未將 schema CHECK 升為 finding：缺少容器只代表無法查證，不能證明 schema 漂移。

## 無法查證

- 呼叫端回報 `asset-postgres` 不在運行，`db/schema.sql` 與 DB 的同步狀態仍未知。
- 指定 repo 為乾淨 main `42bace14`，未包含 Task579。該任務引用依提供材料，未虛構實體檔名或行號。
- 全程唯讀；未執行測試、寫入通過紀錄、呼叫 POST、重建容器或變更 Git。