# [t250] 新增表單的「市場」預設跟隨當前市場 tab（交易紀錄頁 ＋ 已實現損益頁）

**對應 Requirements:** Requirement 49（資產交易紀錄——手動買賣流水帳與 Excel 手動／每日排程匯出）／Requirement 6（已實現損益追蹤——記錄每筆股票買賣損益並依年度彙總）
**前置任務:** 無
**Liquibase changeset:** 無（本任務不動資料庫）

## 背景

**現在的行為（錯的）：** `frontend/src/views/TransactionView.vue`（交易紀錄）與 `frontend/src/views/RealizedGainView.vue`（已實現損益）是同構的兩頁——明細表上方都有市場 tab（`el-tabs`，`v-model="marketFilter"`，四個 `el-tab-pane` 的 `name` 依序為 `''`／`台股`／`美股`／`英股`）做客戶端篩選，兩頁的 `resetForm()` 也都把表單初值寫死成 `market: '台股'`。結果是：**使用者切到「美股」tab 後按「新增」，開出來的表單市場仍是台股**（幣別連帶是 TWD），每記一筆美股交易都得多改兩個欄位。

**正確的行為：** 按「新增」開啟表單時，「市場」欄預設值＝**當前市場 tab**；tab 為「全部」（`marketFilter === ''`）時預設 `台股`（維持既有行為）。幣別隨之連動（`美股`／`英股` → `USD`，其餘 → `TWD`）。

**重現條件：** 開啟「資產管理 → 交易紀錄」（或「已實現損益」）→ 點市場 tab「美股」→ 按「新增交易紀錄」→ 市場欄顯示台股／台灣股市。

**範圍界線（使用者明示）：** 兩頁都要改，行為一致。本任務**不改**其他任何頁面、不動後端。

## 要做什麼

### 共同約定（兩頁都適用，先讀完再動手）

- **預設值只有一處真相，而那一處已經在正確的時機被呼叫**：每頁新增一支 `defaultMarket()`，回傳 `marketFilter.value || '台股'`，只由該頁的 `resetForm()` 取用。**這樣就足以讓「切 tab 後按新增拿到新值」成立**——兩頁的 `openCreateDialog()` 第一行本來就是 `resetForm()`（`TransactionView.vue:527`、`RealizedGainView.vue:654`），預設值必定在**開啟當下**重新求值；dialog 上的 `@closed="resetForm"`（`TransactionView.vue:226`、`RealizedGainView.vue:256`）只是關閉時清場，不是預設值的來源。**不得**在 `openCreateDialog()` 內再寫一份 `form.market = ...` 覆寫：預設值若散在「關閉時重置」與「開啟時覆寫」兩處，日後必然演化成兩個不同答案。
- **幣別必須在 `resetForm()` 內一併算好，不能倚賴 watch**：兩頁既有
  `watch(() => <form>.market, (m) => { <form>.currency = (m === '美股' || m === '英股') ? 'USD' : 'TWD' })`。
  **Vue 的 watch 只在被監聽的值「真的改變」時才觸發**，同值寫回不會 trigger。tab 停在「美股」時，`resetForm()` 把同一個 `美股` 寫回 `market`＝值沒變＝watch 不觸發，此時若 `currency` 仍是寫死的 `'TWD'`，表單就會停在「美股＋TWD」而且**不會自我修正**——這不是短暫的中間狀態，是永久錯值。**可重現的路徑**：tab 點「美股」→ 按新增（`market` 台股→美股，watch 觸發，`currency=USD`）→ **不改任何欄位**直接關閉（`@closed` → `resetForm`，美股→美股，watch 不觸發，`currency` 被寫回 `TWD`）→ 再按新增 → 得到「美股＋TWD」。故 `resetForm()` 內必須直接以同一條判斷式算出 `currency` 初值（驗收第 9／10 條專門釘這條路徑）。**既有的 watch 保留不動**（使用者在表單內手動改市場時仍需要它），且**不得**另外發明第二套市場→幣別對照（例如加入 `MarketType` 主檔的幣別欄位查詢），兩處必須是同一組字面值判斷。
- **只影響新增**：`openEditDialog()` **一行都不准改**。編輯既有紀錄一律帶入該列自己的 `row.market`，與 tab 無關。
- **tab 切換不得回頭改動已開啟的表單**：預設值只在 `resetForm()` 求值一次。**不得**新增 `watch(marketFilter, ...)` 去同步已開啟表單的市場欄——那會在使用者已手動改選市場後把他的輸入洗掉。
- **不改市場下拉的資料來源**：`TransactionView` 的市場 `el-select` 由 `marketOptions`（BFF 聚合的 `MarketType` 主檔，`{ code, label: displayName }`，例 code `美股` / label `美國股市`）驅動；`RealizedGainView` 則是三個寫死的 `el-option`（`台股`／`美股`／`英股`）。後者確實違反 CLAUDE.md「禁止 Enum 寫死」，但那是既有債，改它要動該頁 BFF payload 與載入流程，與本次「一行預設值」不是同一個爆炸半徑，**本任務不處理、也不得順手改**。
- **不需要對照表**：兩頁 tab 的 `name` 與 `MarketType.code` 同為 `台股`／`美股`／`英股` 三個字面值（`DataInitializer` seed 的 code 即為此三值，displayName 才是「台灣股市」／「美國股市」／「英國股市」），`marketFilter` 的值可直接當 `market` 用。
- **已知限制，本任務不處理**：市場 tab 是寫死的四個 pane，而 `TransactionView` 的 `marketOptions` 只含 `active=true` 的市場（BFF → `InstitutionService` 的 `findByActiveTrueOrderBy...`）。若某市場在設定頁被停用，tab 仍在，此時表單 `el-select` 會退回顯示原始 code（例「英股」而非「英國股市」），送出的值仍是合法的 `MarketType.code`、後端照收（兩頁的 `market` 欄皆無必填或白名單驗證）。此風險在改動前就存在（原本寫死的 `'台股'` 被停用時同理），本任務只是多一條觸發路徑，根因是 tab 寫死。**不得為此在本任務加驗證或改 tab 為資料驅動。**
- **不新增前端測試框架**：`frontend/package.json` 的 `scripts` 只有 `dev`／`build`／`preview`，全庫沒有 vitest／jest 與任何前端測試檔。本任務**不得**為這兩行改動引入前端測試堆疊；驗收改以建置 ＋ 部署後的 10 條實機操作（見「驗證」段）。

### 逐項

- [ ] 250.1 **`frontend/src/views/TransactionView.vue`**。現況（`resetForm` 在檔案第 516–524 行附近）：

  ```js
  const resetForm = () => {
    editingId.value = null
    Object.assign(txForm, {
      transactionType: '買', assetType: '股票', assetName: '', assetCode: '',
      market: '台股', currency: 'TWD', channel: '', tradeDate: '', notes: '',
      sharesStr: '', priceStr: '', amountStr: '', exchangeRateStr: ''
    })
    formRef.value?.clearValidate()
  }
  ```

  改為（`defaultMarket` 定義在 `resetForm` 之前；`marketFilter` 宣告於同檔第 353 行，已在作用域內）：

  ```js
  // Task 250：新增表單的市場預設＝當前市場 tab（'' ＝全部 → 台股）；幣別沿用與 watch 同一條規則
  const defaultMarket = () => marketFilter.value || '台股'
  const defaultCurrency = (m) => (m === '美股' || m === '英股') ? 'USD' : 'TWD'

  const resetForm = () => {
    editingId.value = null
    const market = defaultMarket()
    Object.assign(txForm, {
      transactionType: '買', assetType: '股票', assetName: '', assetCode: '',
      market, currency: defaultCurrency(market), channel: '', tradeDate: '', notes: '',
      sharesStr: '', priceStr: '', amountStr: '', exchangeRateStr: ''
    })
    formRef.value?.clearValidate()
  }
  ```

  既有 `watch(() => txForm.market, ...)`（第 394–396 行）**改寫成呼叫 `defaultCurrency(m)` 或維持原樣皆可，但語意必須與 `defaultCurrency` 完全相同**；`txForm` 的 `reactive({...})` 初值（第 387–391 行）維持 `market: '台股'` 不動（元件初始化時 `marketFilter` 必為 `''`，兩者結果相同，不必為此加一層求值順序上的耦合）。

- [ ] 250.2 **`frontend/src/views/RealizedGainView.vue`**。現況（`resetForm` 在檔案第 629–635 行附近）：

  ```js
  const resetForm = () => {
    editingId.value = null
    Object.assign(gainForm, {
      assetName: '', assetCode: '', market: '台股', currency: 'TWD', broker: '', tradeDate: '',
      sharesStr: '0', salePriceStr: '0', proceedsStr: '0', investmentCostStr: '0'
    })
  }
  ```

  改為（`marketFilter` 宣告於同檔第 581 行，在 `resetForm` 之前，作用域無虞）：

  ```js
  // Task 250：新增表單的市場預設＝當前市場 tab（'' ＝全部 → 台股）；幣別沿用與 watch 同一條規則
  const defaultMarket = () => marketFilter.value || '台股'
  const defaultCurrency = (m) => (m === '美股' || m === '英股') ? 'USD' : 'TWD'

  const resetForm = () => {
    editingId.value = null
    const market = defaultMarket()
    Object.assign(gainForm, {
      assetName: '', assetCode: '', market, currency: defaultCurrency(market), broker: '', tradeDate: '',
      sharesStr: '0', salePriceStr: '0', proceedsStr: '0', investmentCostStr: '0'
    })
  }
  ```

  `gainForm` 的 `reactive({...})` 初值（第 514–517 行）維持不動；`openEditDialog()` 內的 `gainForm.market = row.market || '台股'`（第 663 行）**不動**。

- [ ] 250.3 **不得產生的副作用（自查清單）**：
  - 兩頁的 `filteredRecords` computed（依 `marketFilter` 過濾明細）**未被改動**。
  - 兩頁的 `submit()` 送出 payload 的欄位組成**未改變**（仍送 `market` 字串，後端 DTO 不動）。
  - 未新增任何 `bffApi` 呼叫、未動 `frontend/src/api/index.js`。
  - 未改動任何 `backend/`／`bff/`／`external-materials-service/` 檔案，無 Liquibase changeset，`SchedulePublicBffController.JOBS` 不需異動（本任務未新增 `@Scheduled`）。

## 驗證

前端建置。**worktree 內沒有 `node_modules`**（它在主 repo 且被 gitignore），直接 `npm run build` 會 `vite: command not found`；先連結過去，建完移除：

```bash
ln -s /Users/steven/Project/asset-management/frontend/node_modules frontend/node_modules && /Users/steven/.nvm/versions/node/v22.21.0/bin/node frontend/node_modules/.bin/vite build frontend; rm frontend/node_modules
```

> `root` 必須用**位置參數**（`vite build frontend`）。本專案 vite 5 的 `build` 子命令**不吃 `--root`**，寫成 `--root frontend`（其他任務檔的舊寫法）會直接 `CACError: Unknown option --root` 中止。

spec 機械檢查：

```bash
bash scripts/spec-check.sh
```

部署（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate）。**worktree 內沒有 `.env`**，而 compose 會先 interpolate 整份 `docker-compose.yml` 才挑 service，所以就算只 build `frontend` 也會以 `required variable ADMIN_EMAIL is missing a value` 中止；`--env-file` 救不了（`env_file` 是相對 compose 檔解析），必須先複製一份進來：

```bash
cp /Users/steven/Project/asset-management/.env .env
```

> 本專案全機只有一套 `asset-management-*:latest` 映像、多個 worktree 並行，誰最後 build 誰生效。**merge 之後請改從 main 的 worktree（`/Users/steven/Project/asset-management-main`）重建**，只在本 worktree build 會被其他 session 洗掉。

```bash
docker compose -p asset-management build --no-cache frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate frontend
```

**不要用 grep bundle 內容來驗證這次的部署。** 本次變更沒有引入任何新字面值（`台股`／`美股`／`英股`／`USD`／`TWD` 全是既有的），`defaultMarket`／`marketFilter` 都是 setup 內的 local const、prod build 一律被 minify 改名，因此任何 grep 都無法區分新舊 bundle（實測舊 bundle 同樣命中）。唯一能機械判定的訊號是 chunk 的 content hash 有沒有變——build 前後各跑一次，兩支 chunk 的檔名必須不同：

```bash
docker exec asset-frontend ls /usr/share/nginx/html/assets/ | grep -E 'TransactionView|RealizedGainView'
```

實機操作驗證（瀏覽器開 http://localhost，共 10 條，兩頁各 5 條）：

1. 交易紀錄頁 → 市場 tab 點「美股」→ 按「新增交易紀錄」→ **市場欄顯示「美國股市」、幣別顯示 USD**。
2. 交易紀錄頁 → tab 點「全部」→ 新增 → **市場「台灣股市」、幣別 TWD**（既有行為未變）。
3. 交易紀錄頁 → tab 在「美股」→ 新增 → 手動把市場改回台股 → 關閉 dialog → 再按新增 → **市場回到美股**（預設每次重新求值，且前一次的手動選擇沒有殘留）。
4. 交易紀錄頁 → tab 在「美股」→ 對一筆**台股**既有紀錄按編輯 → **市場顯示台灣股市**（編輯不受 tab 影響）。
5. **（釘 currency 那一行，缺了它前四條全過也驗不出漏做）** 交易紀錄頁 → tab 在「美股」→ 新增 → **不改任何欄位**直接關閉 → 再按新增 → **市場美股且幣別仍是 USD**。若幣別變成 TWD，代表 `resetForm()` 沒有自己算 `currency`、而是誤信 watch 會補。
6–10. 已實現損益頁重複上述五條（該頁市場下拉顯示的是 `台股`／`美股`／`英股` 三個字面值，非 `MarketType.displayName`）。

## 完成報告

**實作日期：** 2026-07-29

**修改檔案（2，皆為前端；零後端／零 DB／零 BFF 變更）**
- `frontend/src/views/TransactionView.vue`：新增 `defaultMarket()`／`defaultCurrency(m)`，`resetForm()` 改用兩者
- `frontend/src/views/RealizedGainView.vue`：同上（`gainForm`）

**與原計畫的偏差（2 處）**

1. **兩頁的 `watch(() => <form>.market, ...)` 改為呼叫 `defaultCurrency(m)`**（任務檔 250.1 原寫「改寫或維持原樣皆可」，選了改寫）。理由：市場→幣別的字面值判斷因此全頁只有一份，避免日後只改其中一處而讓「新增時的幣別」與「手動改市場後的幣別」分歧。無 TDZ 疑慮——watch 未設 `immediate`，callback 只可能在 setup 同步執行結束後才觸發，屆時 `defaultCurrency` 已初始化。
2. **驗證段的 vite 指令修正為位置參數**：原先照抄其他任務檔的 `vite build --root frontend` 實測直接 `CACError: Unknown option --root` 中止，本專案 vite 5 的 `build` 子命令只吃位置參數，已改為 `vite build frontend` 並在該處加註。

**驗證輸出**
- 前端 `vite build`：`✓ built in 4.19s`，無錯誤（`dist/assets/index-DHL_3uT3.js` 等產出正常；chunk size 警告為既有）
- `scripts/spec-check.sh`：0 BLOCK / 0 CHECK
- spec 對抗式審查（獨立 subagent）：critical 0 / major 3 / minor 4，**7 條全數修正**——(a) 驗證段補 `cp .env`（worktree 無 `.env`，compose interpolate 階段就會失敗）；(b) 刪除恆為通過的 bundle grep，改為比對 chunk content hash；(c) **修正 watch 技術理由**：原寫「pre-flush 造成短暫停在 TWD」是錯的，真正的失效是「同值寫回不 trigger watch」造成的**永久**錯值，並補上專門釘這條路徑的驗收第 5／10 條；(d) 補記 `openCreateDialog()` 本身就會呼叫 `resetForm()`（需求成立的真正保證）；(e) 修正 frontend `--no-cache` 的理由；(f) 補記市場被停用時的已知限制；(g) Requirement 6 的 AC 拆成兩條，只讓新行為掛 Task 250

**尚未執行**：Docker image rebuild ＋ container recreate ＋ 10 條實機操作驗證（本專案共用同一套 stack，依「merge 後從 main 的 worktree 重建」規則進行）。
