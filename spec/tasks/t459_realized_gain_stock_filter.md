# [t459] 已實現損益明細新增股票篩選下拉

**對應 Requirements:** Requirement 168（已實現損益明細依股票代號暫時篩選）
**前置任務:** Task 458 已完成交易日期雙向排序
**Liquibase changeset:** 無

## 背景

「已實現損益」頁目前可依年度與市場檢視資料，無法直接縮小到特定股票；「交易紀錄」頁已有股票多選下拉可供比照。此任務在 `RealizedGainView` 年度／市場範圍內新增股票多選下拉與暫時列篩選。

`RealizedGainResponse` 目前有 `assetCode`、`assetName`、`market`，沒有 `assetType`。因此選項只由非空白代號列建立，使用 `assetCode + market` 作為唯一 key；不新增 DTO/API/DB 欄位、不推測資產類型。沒有代號的舊列不出現在選項中，且使用者選股票時仍保持可見。

## 要做什麼

- [x] **459.1 股票選項。** 新增純函式 `frontend/src/utils/realizedGainStockFilter.js`。只從目前 selected-year records 與 market tab 範圍內收集 `assetCode` 非空白的列；依 `assetCode + market` 去重、以 `assetName（assetCode）` 顯示並按 `zh-Hant` 排序。同代號不同市場必須是不同選項。
- [x] **459.2 多選過濾與失效清理。** 多選為聯集；空選取等同全部顯示。選取非空時，只有 `assetCode` 非空白列依 `assetCode + market` 命中選取 key，空白代號列恆常保留。範圍變更或資料重載後，移除失效選取但保留仍有效項目；無可用選項時下拉隱藏。
- [x] **459.3 頁面接線。** 在 `frontend/src/views/RealizedGainView.vue` 市場 tabs 與明細表之間新增與 `TransactionView` 一致的 Element Plus `el-select multiple`（collapse tags、tooltip、clearable、placeholder「全部」）與「股票：」標籤。過濾順序固定為 selected-year → market → stock → 既有交易日期排序。`filteredStats` 依股票篩選後列計算；年度卡片仍顯示全年摘要，匯出仍涵蓋所有年度，既有排序與 CRUD／雙擊行為維持。
- [x] **459.4 邊界與測試。** 篩選只在前端操作已載入 records，不新增 API/BFF/DTO/資料庫、I/O、持久化狀態或券商呼叫。將 helper 單元測試與 view 接線斷言加到目前 `npm test` 已執行的 `frontend/src/utils/realizedGainDateSort.test.js`，不得修改 `frontend/package.json`（保留 Task 346 工作區）。覆蓋選項去重／排序、跨市場隔離、空代號恆常保留、空選取、聯集選取、失效 key 清理及既有日期排序接線。

## 驗證

```bash
cd frontend && npm test && npm run build
```

依 `.agents/skills/run-stack/SKILL.md` 從完成 merge 的 main worktree 重建並 recreate `frontend`；使用已登入頁面確認「股票」下拉出現在市場 tabs 下方，單選／多選／清除與切市場皆更新明細和統計，空代號列持續顯示，交易日期排序箭頭仍能雙向排序，Excel 匯出範圍未改變。不得呼叫券商 API 或寫入任何券商端資料。

## 完成報告

完成。`realizedGainStockFilter.js` 負責選項建立、跨市場 key、多選過濾及失效選取清理；`RealizedGainView.vue` 將股票篩選接在年度／市場範圍後、日期排序前，篩選後統計依可見列計算。`realizedGainDateSort.test.js` 涵蓋去重與排序、跨市場隔離、空代號保留、空選取／聯集、失效 key 清理、無選項時隱藏下拉及日期排序接線。

驗證：`frontend && npm test` 81/81 通過；Docker production build 已完成並從 feature worktree 重建 `frontend`、`bff`。容器 `asset-frontend` 正常提供頁面，`asset-bff` healthy 且 actuator 為 `UP`；API Gateway 的 `/api/quotes` 回傳 58 筆 JSON array。前端部署 chunk 已確認含「股票：」標籤；映像 SHA-256 前綴：frontend `472038b4`、bff `8596df12`。沒有修改 `frontend/package.json`，Task 346 worktree 保持原狀。

驗收限制：本次沒有可用的已登入瀏覽器 session；已開啟 `/realized-gains` 頁面供登入後查看，匿名功能 API 正確回 401，因此未宣稱已完成登入後的互動點選驗收。沒有呼叫券商 API，也沒有改動 BFF/API、資料庫、持久化狀態或來源資料。
