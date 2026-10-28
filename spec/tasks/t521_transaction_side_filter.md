# [t521] 交易紀錄新增買／賣篩選

**對應 Requirements:** Requirement 166（交易紀錄明細的暫時顯示篩選，新增與股票條件並用的買／賣選項）
**前置任務:** t457
**Liquibase changeset:** 無

## 背景

使用者在交易紀錄畫面要求，除股票篩選外，還能篩選買／賣。現行 TransactionView 已載入年度 summaries，僅做年度、市場、股票的顯示篩選；交易列已有 transactionType「買」「賣」，不需新 API 或資料分類。

## 要做什麼

- [x] 521.1 在股票篩選旁新增「交易類型」單選（可存取名稱清楚），選項「全部／買／賣」，預設空字串為全部，買／賣值完全沿用既有 transactionType。控制項不依 stockOptions.length 隱藏，股票下拉自身仍在無股票選項時隱藏；使用可換行的布局，窄畫面不超出卡片。
- [x] 521.2 在既有年度＋市場→股票顯示管線後，對已載入列取買／賣交集；於既有 transactionStockFilter.js 加入純函式 filterByTransactionType，空值回原 records，其餘精確比較 transactionType，不改列、物件或順序。股票篩選對基金恆通過的規則保留，但基金也必須符合買／賣條件；不用股票資產類型代替買／賣判定。
- [x] 521.3 stockOptions 及失效股票 key 清理仍只由年度＋市場範圍建立，不因買／賣縮減股票選項或清除股票選取。年度、市場及資料 reload 保留買／賣，頁面重載歸零；全部年度卡片、各年度摘要、手動／排程匯出、CRUD、雙擊分析及所有 API 行為不變。此為具名的已載入明細顯示例外，禁止新增後端、DTO、DB、外部請求或持久化篩選狀態。
- [x] 521.4 有 marketYearRecords 但篩完零列時顯示「沒有符合篩選條件的交易紀錄」；範圍本來無列仍用既有「尚無記錄，請點擊『新增』新增第一筆」提示。新增少量聚焦測試覆蓋全部／買／賣、基金與股票條件交集、年度／市場組合、原順序及來源不變；保留既有股票篩選測試。瀏覽器以實際 served JS＋隔離 fixture 驗證選項、交集、清除、年度／市場切換保留、無股票情境、空結果及窄畫面，所有 API 攔截，不儲存真資料、不執行匯出排程或金融操作。

## 驗證

```bash
bash scripts/spec-check.sh
node --test frontend/src/utils/transactionStockFilter.test.js
npm --prefix frontend run build
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -fsSI http://localhost/
```

獨立 spec／架構審查，部署前只比較並驗證 frontend 設定與 main 一致、不輸出秘密。先驗收 feature，完成 commit／no-ff merge／push 及遠端 SHA 回讀，再從乾淨 main 重建 frontend 並確認新畫面；不重啟其他服務。

## 完成報告

- TransactionView 新增「交易類型：全部／買／賣」，與年度、市場、股票交集顯示；只將股票控制項依選項存在與否隱藏。空範圍與無符合條件的提示分開，篩選列可換行，卡片、匯出、CRUD、API 不變。
- transactionStockFilter 增加純函式精確比較既有 transactionType；四個聚焦回歸案例加原有14個，18/18通過，前端 production build 成功、git diff --check 無錯誤。
- 獨立 spec 與架構審查皆 critical 0／major 0／minor 0。實際瀏覽器驗收發現空字串 model 顯示預設 placeholder；主 agent 補上「全部」並由獨立架構審查再次確認，沒有改變篩選邏輯。
- feature frontend 已單服務 rebuild／force-recreate，HTTP 200，served TransactionView-CP0mC-Oq.js 含新選單與預設全部，frontend effective 設定／TLS 與 main 一致；驗收前後 image 固定 75f2b3a238565047458e87ae3d82ff30b97743ef95b15278f79adc181c071ca1。
- 實際 served JS＋隔離 fixture strict browser：13項 PASS，唯一 ARIA 控制項存在，0 failures／blocked／page errors。涵蓋預設全部／三選項、買賣對股票基金篩選、股票交集、年度市場保留、股票選項與選取穩定、空結果切回全部、純基金範圍、真正空範圍、摘要及請求不受篩選影響、重載重設，以及900px窄畫面控制項留在卡片內。8個已知GET皆由瀏覽器隔離回應，未讀寫真實資料或執行匯出。
- 瀏覽器操作使用正常可見 select wrapper、各自 aria-controls listbox，等待下拉關閉；未使用強制點擊，未放寬原斷言。寬窄截圖均已檢視。main 合併推送與乾淨 main 重部署為後續收尾，不以本段 feature 證據代替。
- 同步 requirements／design 與 CLAUDE 的具名純顯示例外；無新增後端、分類、依賴、DB 或環境設定變更。
