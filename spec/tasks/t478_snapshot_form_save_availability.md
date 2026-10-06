# [t478] 快照表單存檔按鈕狀態與阻擋提示一致化

**對應 Requirements:** Requirement 136（快照表單新增／更新走專屬 BFF 並保留可讀錯誤）；Requirement 136 補充（所有可見存檔控制項必須如實反映可提交狀態）
**前置任務:** Task 450（快照表單四個 Panel 的完整載入與 canonical readback 鎖定）
**Liquibase changeset:** 無

## 背景

編輯既有快照時，四個資料 Panel 尚未全部 ready、其中一個讀取失敗，或 PUT 後 canonical readback 尚未完成時，`SnapshotFormView.submit()` 已正確拒絕寫入。可是既有基本／存款／股票／基金區塊標頭、三個市場展開券商列與頁尾的存檔按鈕沒有一致套用相同 disabled 條件；使用者可按見到藍色按鈕，但函式立刻 return，既沒有 BFF POST／PUT，也沒有訊息。這正是「常常無法存檔」的直接根因。

交易日期欄位在 `stock_holding.transaction_date` 與現有 request payload 都是 nullable；畫面上未選日期不是本任務的失敗原因，不能藉此把舊資料或既有輸入契約改成必填。

## 要做什麼

- [ ] 478.1 在 `frontend/src/views/SnapshotFormView.vue` 定義單一可提交判斷，涵蓋既有 `loading`、`formBlocked`、`canonicalReloadRequired`、`saving` 與 `loadedFormKey === routeKey()` 的正確語意。基本／存款／股票／基金區塊標頭、台股／美股／英股展開列與頁尾共八個「存檔」按鈕都必綁定該判斷；不可遺漏市場，也不可改動非存檔按鈕。
- [ ] 478.2 `submit()` 入口保留防禦性檢查：既有寫入正在進行時直接防重送；其餘不可提交狀態一律零 BFF 寫入，並顯示繁體中文可行動提示（資料載入中請等待；資料不完整、route identity 不一致或 canonical readback 未完成請先重試／完整重新載入）。不得將這些本地阻擋誤顯示為「儲存失敗」或「更新成功」。
- [ ] 478.3 保留 ready 狀態下的 `POST /api/bff/snapshot-form`、`PUT /api/bff/snapshot-form/{id}`、payload 組裝、canonical reload、錯誤 relay 與成功提示。不得新增或修改 BFF/business route、DTO、資料表、Liquibase、9090 route、行情／券商 I/O 或下單能力；不得用真實使用者快照寫入作為驗證資料。
- [ ] 478.4 擴充 `frontend/src/utils/snapshotFormViewLifecycle.test.js`，以編譯並執行真實 SFC setup 的既有 harness 證明：Panel 未 ready、canonical readback 鎖定或 route identity 不一致時直接呼叫 `submit()` 不會呼叫 create/update 且留下提示；ready 表單仍只送出一次正確的既有寫入。另以模板／渲染層級斷言八個存檔控制項均採同一 disabled 條件。

## 驗證

```bash
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend test
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
bash scripts/spec-check.sh
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -sI http://localhost/ | head -1
```

實際執行中的頁面必驗：在 Panel 載入中或完整讀回鎖定時，所有可見存檔按鈕均不可按；資料完整後按任一存檔按鈕會發出既有的 BFF PUT，並顯示既有成功或可讀後端錯誤。驗證不得建立、修改或刪除使用者快照。

## 完成報告

（實作者完成後回填：實際變更檔案、測試／build／Docker health 結果、實際頁面驗證，以及與本規格的偏差。）
