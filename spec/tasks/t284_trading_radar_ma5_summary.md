# [t284] 交易雷達個股收合列補顯示週線 MA5，並守住雙格式匯出

**對應 Requirements:** Requirement 43（今日交易雷達個股決策表呈現）＋ Requirement 48（交易雷達 Excel／JSON 匯出）
**前置任務:** t265（`weeklyMa` 已進 DTO）＋ t281（MA5 已進 Excel／JSON 匯出）
**Liquibase changeset:** 無

## 背景

使用者提供的「今日交易雷達」截圖中，個股決策表的收合列只有 `MA20／60／240`，要求新增 MA5，且匯出資料也必須有 MA5。

目前程式已完成大部分底層能力：`TechnicalIndicatorService.FullIndicators.weeklyMa` 會計算最近 5 根收盤均值；`TradingRadarDto.MarketSummary.weeklyMa` 與 `StockDecision.weeklyMa` 已回傳；大盤卡與個股展開列也已顯示「週線 MA5」。真正缺口只有 `frontend/src/views/TradingRadarView.vue` 的個股收合列均線摘要仍只讀 `monthlyMa`／`quarterlyMa`／`annualMa`。

匯出現況則已符合使用者要求：`TradingRadarExportService.marketSheet()` 與 `stockSheet()` 各有唯一一欄 `週線MA5`，讀 `weeklyMa` 並放在 `MA20` 前；Excel 使用 `NUM2`，JSON 由同一份 `ExportDoc` 產生同名 number。`TradingRadarDualFormatTest` 已驗證兩張分頁表頭與兩側 MA5 數值，但現有斷言不對稱：個股 MA5 的 Excel 格式／JSON number，以及大盤 MA5 的舊快照 JSON `null` 尚未被直接鎖住。本任務除守住既有契約外，也要補齊這些斷言，且不能因「也要匯出」而重複新增第二欄。

本任務推翻 t281「畫面不新增欄位，避免雷達表過寬」的舊範圍決定，但只把 MA5 放進既有的均線摘要欄，不新增獨立表格欄，因此保留原表格結構。

## 要做什麼

- [x] **284.1 個股收合列顯示四條均線**：修改 `frontend/src/views/TradingRadarView.vue` 的既有均線欄，標題由 `MA20／60／240` 改成 `MA5／20／60／240`；同一格固定依序輸出 `row.weeklyMa`、`row.monthlyMa`、`row.quarterlyMa`、`row.annualMa`，每個值皆呼叫 `fmtNumber(value, 2)`，中間沿用現有 `span.slash` 的全形斜線。四值與分隔線須包在 `<span class="ma-summary">`；`null` 必須由 `fmtNumber` 顯示 `—`，不得補 0。
- [x] **284.2 維持單列可讀性**：該欄 `min-width` 由 190px 調整為 **250px**；新增 `.ma-summary { display: inline-flex; align-items: center; white-space: nowrap; }`，以 `nowrap` 保證容器內四值不換行，250px 只作欄寬基準。不得另加第五個表格欄；大盤卡與個股展開列既有「週線 MA5」不改。
- [x] **284.3 不改規則語意與取值路徑**：不得在前端重算 MA5，不改 `TradingRadarDto`、`TechnicalIndicatorService`、BFF／business API、規則引擎、分數、動作、買進閘門或 `RULE_VERSION`。頁首「依大盤、MA20／60／240、KD 與連續兩日確認產生規則式決策」描述的是實際評分依據，維持原文，避免誤稱 MA5 已參與決策。
- [x] **284.4 匯出契約零回歸並補齊測試**：不修改 `TradingRadarExportService` 的欄位清單，不新增第二個 MA5 欄。頁首手動匯出、排程與 run-now 必須繼續共用既有 `ExportDoc`。擴充 `TradingRadarDualFormatTest`：(a) 個股 `週線MA5`（cell 16）的 data format 為 `#,##0.00`；(b) 個股 JSON `週線MA5` 是 number 且等於 fixture；(c) 舊快照的大盤 cell 11 與個股 cell 16 都是 `BLANK`；(d) 舊快照的大盤與個股 JSON `週線MA5` 都是 `null`。既有兩張分頁逐字表頭與兩側數值斷言維持，證明各只有唯一一欄且位於 `MA20` 前。
- [x] **284.5 同步規格與完成記錄**：更新 Requirement 43／48、design 與任務索引；完成後在本檔回填實際異動、測試、部署與偏差。

## 驗證

```bash
bash scripts/spec-check.sh
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml \
  -Dtest=TradingRadarDualFormatTest \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
```

```bash
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -fsS http://localhost/ >/dev/null
docker exec asset-frontend sh -c "grep -R -q 'MA5／20／60／240' /usr/share/nginx/html/assets"
```

畫面驗收：開啟「今日交易雷達」，不展開個股列即可看見欄名 `MA5／20／60／240`，且每列依序顯示四個數值。另以窄於桌面寬度的瀏覽器 viewport 檢查 `.ma-summary`，四個數值可因整欄空間不足而隨欄位水平捲動，但容器內不得斷成兩行。下載或排程產生的 Excel／JSON 維持既有 `週線MA5` 欄；不得為驗收另觸發會覆寫使用者正式輸出檔的匯出操作，匯出內容以 `TradingRadarDualFormatTest` 的隔離 fixture 驗證。

## 完成報告

- 實作：個股收合列沿用既有均線欄，新增 `weeklyMa` 並依 MA5／20／60／240 排列；欄寬調整為 250px，四值容器以 `inline-flex`／`nowrap` 維持單行。未新增獨立欄位，也未更動指標計算、DTO 或決策規則。
- 匯出：既有 Excel／JSON `週線MA5` 欄位未重複新增；補強 `TradingRadarDualFormatTest`，鎖定個股 Excel 數字格式與 JSON number/value，並補齊舊快照大盤／個股 Excel 空白及 JSON `null` 契約。
- 規格：`scripts/spec-check.sh` 為 `BLOCK: 0`、`CHECK: 0`；獨立對抗式審查第二輪 10/10，無 Critical／Major／Minor。
- 驗證：`TradingRadarDualFormatTest`、前端 production build、`git diff --check` 均通過；feature 版本以 Docker Compose 無快取重建並重建 `asset-frontend`，首頁回應 HTTP 200，bundle 可找到新欄名與 `nowrap`。
- 畫面：實際開啟「今日交易雷達」確認收合列順序、一般數值、千分位與 `null → —`；1024px viewport 下 `.ma-summary` 為單一行（`clientHeight = scrollHeight = 23`、所有子節點同一個 top），欄位空間不足時由表格水平捲動承接。
- 正式輸出保護：未為驗收觸發頁首匯出，避免覆寫正式輸出檔；匯出內容使用隔離 fixture 驗證。
- Git／部署：依使用者授權於本任務完成後執行 feature commit、`--no-ff` 合併至 `main`、push，並從 main worktree 再次重建與驗證；精確雜湊以 Git 歷史與最終交付訊息為準。
- 偏差：無功能偏差；實作時確認 MA5 計算、DTO 與雙格式匯出早已存在，因此本任務只修正收合列呈現並補齊匯出回歸測試。
