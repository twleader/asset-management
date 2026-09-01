# [t411] 修正快照表單路由重用導致存檔錯位

**對應 Requirements:** Requirement 136（快照表單新增與更新走專屬 BFF；本任務新增的 AC 見該 Requirement 最後一條，Task 411）
**前置任務:** t410（快照表單存檔改走專屬 BFF；本任務不改動 t410 的 BFF passthrough 行為）
**Liquibase changeset:** 無

## 背景

2026-09-01，使用者在同一瀏覽器分頁內先開啟某筆快照的編輯頁（或新增頁），未重新整理即切換到「編輯 2026-09-01 這筆快照」（id=15，總資產 20,292,222.14）。因 `frontend/src/App.vue` 的 `<router-view>` 動態元件未加 `:key`，Vue Router 對同一元件路徑（`SnapshotFormView.vue`）在不同 `route.params.id` 之間預設會**重用同一個元件實例**、不重新掛載；`SnapshotFormView.vue` 的資料載入邏輯寫在 `onMounted` 內，只在元件首次掛載時執行一次，因此第二次進入的「編輯頁」畫面沿用了前一個頁面殘留的存款／基金／股票欄位值，只有日期欄位因 `route.params.id` 對應的初始賦值而巧合正確。

使用者按下「存檔」時，程式依當下 `isEdit.value`（由 `route.params.id` 判斷）走到 `bffApi.snapshotForm.create()`（新增分支），因為表單此時的狀態被判定為新增流程的殘留狀態，於是多存出一筆 id=30（2026-09-02，總資產 20,287,992.21）。原本 id=15（2026-09-01）沒有被覆蓋、沒有遺失，但資料庫多出一筆錯誤資料，且整個過程沒有任何錯誤訊息提示使用者。

正確行為：
1. 路由切換到同一元件路徑但不同資料（不同快照 id，或新增⇄編輯）時，畫面必須重新載入，不得殘留上一頁資料。
2. 即使第 1 點的根本修法失效，送出存檔前也要有獨立的一致性檢查能攔下「表單資料與當下網址不對應」的送出，並提示使用者而非靜默送出。
3. 本任務不修正/回填/刪除 t410 已交付的 BFF passthrough 行為；id=30 這筆因本 bug 產生的多餘資料由執行者以 SQL 檢視並手動清除，不算在自動化回歸範圍內（該筆是本次 bug 的產物，不是需要保留的業務資料）。

## 要做什麼

- [x] 411.1 `frontend/src/App.vue`：`<router-view v-slot="{ Component }">` 內的 `<component :is="Component">` 加上 `:key="route.fullPath"`，並在 `<script setup>` 用 `useRoute()` 取得 `route`。此為根本修法——同類路由切換一律強制整個元件卸載重掛，`SnapshotFormView` 的 `onMounted` 因此必重新執行。
- [x] 411.2 `frontend/src/views/SnapshotFormView.vue`：把原本寫在 `onMounted` 內的資料載入邏輯（銀行/券商選項、fund_master、既有快照 detail、股價批次載入、userEdited watcher 註冊等整段既有流程，內容不變）抽成具名 async function `loadFormData()`，`onMounted` 改呼叫 `loadFormData()`；並新增 `watch(() => route.params.id, ...)`，在偵測到 `route.params.id` 於元件存活期間變化時重新呼叫 `loadFormData()`。此為雙重防護——即使 411.1 的 `:key` 因未來的路由設定變動而失效，本頁仍能自行偵測並重新載入。重新呼叫時須先停止前一輪註冊的 userEdited watcher（用一個模組級變數持有 stop handle，呼叫前若存在先呼叫它）再重新註冊，避免重複註冊造成同一次使用者編輯觸發多次 `userEdited = true`（雖然行為上是冪等的，但仍應維持「同一時間只有一個作用中 watcher」的不變量）。**抽出 `loadFormData()` 時，函式內既有的執行順序不得改變**：`await Promise.allSettled([loadInstitutions(), loadFundMasters()])` → `watchEffect(...)`（fund 配息自動帶入）→ `isEdit.value` 分支載入 detail／新增預設值 → `await loadAllPrices()` + `startPriceAutoRefresh()` → 補抓美股匯率／缺名稱配息率 → `refreshStockSortables/refreshDepositSortables/refreshFundSortable()` → `await nextTick()` 才停止舊 watcher 並重新註冊 userEdited watcher，最後才寫入 `loadedFormKey.value`；這段時序是既有的「避免初始化噪音誤觸 userEdited」防呆機制，抽函式只是把它包進具名函式，不得因此重排或省略任何一步，也不得把 `stopUserEditedWatch`／`loadedFormKey` 兩個模組級變數宣告移到 `loadFormData()` 函式內部（它們必須在函式外、`<script setup>` 頂層宣告，供 `submit()` 與後續呼叫共享狀態）。
- [x] 411.3 新增 `loadedFormKey`（`ref(null)`），在 `loadFormData()` 成功完成（所有資料已 assign 進 `form`）之後，賦值為 `String(route.params.id ?? 'new')`，代表「表單目前資料實際對應哪個路由參數」。`submit()` 開頭比對 `loadedFormKey.value` 與 `String(route.params.id ?? 'new')`：不一致時，用 `ElMessage.error(...)` 提示使用者重新整理頁面後再儲存，並 `return`，不得呼叫任何 `bffApi.snapshotForm.create/update`。
- [ ] 411.4 資料善後：透過 `docker exec asset-postgres psql -U assets -d assets` 先 `SELECT` 確認 id=30 確實是 2026-09-02、`owner_user_id=1`、`total_assets=20287992.21` 且無其他有意義的子表資料被其他紀錄引用，再視情況一併刪除其 `stock_holding`／`fund_holding`／`bank_deposit` 等子表關聯列（若有 FK 依存需先刪子表再刪主表），最後刪除 `asset_snapshot` 的 id=30 這一列。刪除前後皆須用 `SELECT` 佐證，操作記錄留在完成報告。
- [ ] 411.5（必須在 411.4 完成之後執行）驗收：`npm run build`（frontend production build 通過）；依 `/run-stack` 以 `--no-cache` 重 build 並 recreate `frontend`（Vue 變更容易命中 layer cache 沒重跑 vite）；瀏覽器實際操作：登入 → 開啟 `/snapshots` 列表 → 進入編輯 2026-09-01（id=15，若已因 411.4 刪除 id=30 則列表應只剩 id=15 那筆 2026-09-01 快照）→ 不重新整理，切到「新增快照」頁 → 再切回「編輯 2026-09-01」→ 確認欄位值與 id=15 原始資料一致、不殘留新增頁的空白/預設狀態；並確認在切換過程中網路面板可見 `GET /api/bff/snapshot-form/{id}` 針對每次進入編輯頁都重新發出一次請求（證明重新掛載/重新載入確實發生，不是只看 UI 巧合正確）。

## 驗證

```bash
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
bash scripts/spec-check.sh
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -sI http://localhost/ | head -1
```

瀏覽器手動驗證步驟見 411.5；本任務前端邏輯目前沒有既有的 Vitest／元件測試框架可用（本專案前端測試策略以 production build 通過 + 瀏覽器手動驗證為既有慣例，`SnapshotFormView.vue` 及 `App.vue` 均無既有單元測試檔案），故不新增前端自動化測試，以瀏覽器實測取代。

## 完成報告

- **實作**：`frontend/src/App.vue` 的 `<router-view>` 動態元件加 `:key="route.fullPath"`（`useRoute()` 取得 `route`）。`frontend/src/views/SnapshotFormView.vue` 把 `onMounted` 內的載入邏輯抽成具名 `loadFormData()`，新增 `loadedFormKey`／`stopUserEditedWatch` 模組級狀態、`watch(() => route.params.id, ...)` 雙重防護、`submit()` 開頭的 `loadedFormKey` 與當下路由參數一致性檢查。
- **411.1–411.3**：已完成，程式碼見上述兩檔案 diff（commit `4918583f`，main 上 merge commit `0c6d300a`）。
- **411.4（資料善後，已完成）**：`docker exec asset-postgres psql` 確認 id=30 確為 `owner_user_id=1`、`snapshot_date=2026-09-02`、`total_assets=20287992.21`；子表 `fund_holding`（1 筆）／`stock_holding`（44 筆）／`bank_deposit`（16 筆）皆僅屬於該筆 snapshot，於同一交易內先刪三張子表再刪 `asset_snapshot` id=30，刪除後複查 `asset_snapshot` 僅剩 id=15（2026-09-01，20292222.14，owner 1）、id=18（owner 2）、id=29（owner 3）三筆 2026-09-01/09-02 附近快照，id=30 已不存在。
- **build／run-stack（已完成）**：`npm run build` 通過；`docker compose -p asset-management build --no-cache frontend` 成功、`up -d --no-deps --force-recreate frontend` 完成，`curl -sI http://localhost/` 回 200；`docker exec asset-frontend grep -l '頁面資料尚未完成載入' /usr/share/nginx/html/assets/*.js` 命中 `SnapshotFormView-DVmVQAXz.js`，證明部署的 bundle 確實含本次新增的一致性檢查文案。
- **411.5 瀏覽器實測（未能完成，誠實記錄）**：透過 Claude Browser 工具導覽至 `http://localhost/` 嘗試登入時，Google OAuth 回傳 `400 redirect_uri_mismatch`（`已封鎖存取權：這個應用程式的要求無效`），此為本次沙盒瀏覽器所在網域未被登記於該 OAuth Client 的合法 redirect URI 清單所致的環境限制，與本次程式碼變更無關，也非我方可在此環境修正（需要在 Google Cloud Console 註冊對應網域，屬使用者權限範圍）。因此**無法完成「登入 → 開編輯頁 → 切新增頁 → 切回編輯頁」的實際瀏覽器點擊驗證**，411.5 保持未勾選。已完成的替代查證：(a) production build 通過且 bundle 含新程式碼字串（見上）；(b) `arch-auditor` 唯讀查證本次 diff，確認 `stopUserEditedWatch` 正確 stop-before-rebind、`route.params.id` watch 在 `:key` 生效下屬預期的雙重防護死碼、無 watcher／timer／Sortable 實例洩漏（結論 critical:0 major:0 minor:0）；(c) `spec-auditor` 對 spec 文件的對抗式審查（原 major 2 項已修正）。建議下次有可用登入環境時，依 411.5 描述的步驟補做一次瀏覽器實測。
