# [t518] 股票警示通知對象顯示收件人名稱

**對應 Requirements:** Requirement 23（股票警示收件人標籤顯示 email 與選填名稱）
**前置任務:** t509
**Liquibase changeset:** 無

## 背景

收件人已有可選名稱，但股票警示「通知對象」勾選清單仍只有 email，使用者要求顯示 `email [名稱]`。這次延伸名稱的顯示用途，取代原先僅限通知設定頁的限制；不改變實際收件人選取或寄送。

## 要做什麼

- [x] 518.1 只修改 `frontend/src/views/StockAlertView.vue` 的一般警示與複合警示新增／編輯對話框，兩處收件人勾選標籤均使用既有 API 回傳的 `name` 顯示 `email [名稱]`，email 後一個空格，使用半形方括號。
- [x] 518.2 name 缺少、null、空字串或純空白時只顯示 email，不顯示空括號、undefined、null 或占位名稱。名稱用 Vue 文字插值安全呈現，不可使用 v-html。保留既有停用提示並接在標籤之後。
- [x] 518.3 key/value 仍使用 id，保留全選、預設勾選、recipientIds 讀寫及既有啟停／寄信／日曆行為。既有 `/api/bff/stock-alert/**`、共用 Response 與資料來源完全沿用；不新增 API、後端、DB 或其他頁面變更。不得依 email 猜測名稱。
- [x] 518.4 完成前先建置 frontend Docker image 並 recreate；以真實 Vue 元件渲染驗證具名、缺名、停用與 HTML 特殊字元情境，以及一般／複合警示選取仍為 id。驗證只使用隔離的測試資料，不儲存警示、不寄信。

## 驗證

```bash
bash scripts/spec-check.sh
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -fsSI http://localhost/
docker inspect --format '{{.State.Status}}' asset-frontend
```

上述 Compose 須在 feature 驗收目錄使用與 main 一致的部署設定執行，只重建 frontend。從執行中的網站開啟股票警示頁，或使用隔離瀏覽器以受控 fixtures 載入該次部署的頁面；一般與複合警示均確認具名標籤、缺名 fallback、停用提示及 id 勾選。merge 後再從乾淨 main build/recreate 並核對實際 served chunk 含此變更。

## 完成報告

- 實際變更：只修改 `StockAlertView.vue` 的一般／複合警示標籤及資料註解，使用既有 name 顯示 `email [名稱]`；另加 checkbox 純樣式換行，避免較長 email／名稱超出對話框。未修改 API、DB、寄送或選取邏輯。
- 規格機械檢查：BLOCK 0／CHECK 0；獨立 spec review 為 critical 0／major 0，修正 frontend 無 healthcheck 的驗證指令。獨立架構複審 critical 0／major 0／minor 0，已記錄 arch-review-pass。
- feature frontend 已完成兩輪 image build＋container recreate（第二輪加入長標籤換行修正），HTTP 首頁／served chunk 皆 200；最終 image `sha256:e7cbeeddac4b4b8b20dfce1456d3d2423c01d72a93556d1b9828793c1cc0d234`。部署設定與 main 一致，其餘 8 個容器未變。
- 隔離 Chromium 驗收實際 served app：一般／複合警示的新增及編輯共 4 個對話框，各 8 種收件人 fixture，驗證具名、null、缺欄位、空字串、純空白、停用、HTML 特殊字元、100 字長名稱換行；全選與既有編輯選取、checkbox id value 全維持正確。pageErrors 0、API writes 0，未儲存警示、未寄信。
- 偏差：視覺驗收發現長標籤超出對話框，因此由主 agent 依低模型驗收收尾例外補純樣式換行，重新獨立架構審查、build/recreate 與完整 fixture 驗收均通過。
