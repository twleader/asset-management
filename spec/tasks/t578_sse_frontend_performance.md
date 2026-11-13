# [t578] SSE 前端合併更新與儀表板刷新分離

**對應 Requirements:** Requirement 197（全頁共用 SSE、合法報價增量與儀表板估值）
**前置任務:** t506、t523
**Liquibase changeset:** 無

## 背景

main 277c45ff 包含 9df120da 的跨午夜修正。共用 livePrices 每筆有效事件立即通知每個頁面；Dashboard 的 valuationRefresh 為 1000ms，refreshMarketStatus 每次還會刷新 trend，造成連續行情反覆讀取估值和整張圖。使用者要求 500ms 合併前端更新、5 秒讀取估值、趨勢獨立刷新。

## 要做什麼

- [x] 578.1 只改 frontend：共用 createLivePriceStream 每筆事件先通過原 mergeLiveQuote（market/code/date/status/完整tuple/BigInt奈秒時間/close fence）並立即更新 latest；500ms 固定窗口依 market＋stockCode 合併 onQuote，同標的只發 cache 最新完整 tuple 一次；不同標的不遺失，最後窗口必送，持續事件不得延後窗口。HTTP overlay 與 SSE 同一 cache，比較結果較新者勝；pending 只存 key，maxQuotes 預設1000，evict 同時清 pending，flush 不得發送已 evict 的資料。timer 注入以便測試。
- [x] 578.2 隱藏、permanent CLOSED、最後 unsubscribe 清 timer/pending；已 unsubscribe 不收到 late callback，重新可見由原 catchup 補讀。保留單一 EventSource、source reference fence、bounded cache、官方收盤、null 比較值原子清空與昨日 LIVE 拒絕。不得新增後端／provider／broker I/O 或 DB／Redis 寫入。
- [x] 578.3 Dashboard 初始載入外的 SSE/onCatchup/5秒週期共用一個 interval=5000 的 createTrailingRefresh；背景 realtime 開始間距至少5000ms，同時最多一筆，忙碌只留一次 pending，失敗保留完整前值。週期不能直接呼叫 read 繞過排程。初始 panel、手動重試／切快照不受此背景限制。
- [x] 578.4 移除 refreshMarketStatus 內 trend read。trend 初始依原 panel 流程，背景用獨立 interval=60000 的 scheduler、60秒週期與重連／恢復可見 catchup，背景開始至少相距60000ms，不重疊；只於已有data、未loading時讀既有 dashboard trend BFF，preserve:true 保留失敗舊圖。onQuote／估值不可觸發 trend；隱藏／離頁清排程及 pending，abort估值與趨勢，panel loader 若需新 cancel 入口，必須遞增 generation、abort、清 loading 而保留 data，以擋不理abort的晚回應。首屏／換快照中因隱藏取消且data=null時記錄一次resume旗標，恢復可見僅補載一次初始trend（不受背景60秒限制）後清旗標；一般首屏error不設旗標且不自動無限重試。
- [x] 578.5 保留最新 snapshot/liveAssets ID／date identity、台北或市場今日、夏冬令跨日、歷史／future／CLOSE_PENDING／VERIFIED_CLOSE、同ID跨日bounded metadata/panel reload、換快照與晚HTTP guards。同ID rollover 的 panelLoader.select 新增可選excludePanels=['trend']（預設全panel不變），只重載快照相關panels，不修改trend的資料／generation、不request其scheduler；手動切快照仍完整select。不得簡化成只認台北日期，不改 SnapshotForm dirty/歷史語意、策略、五檔、API/Swagger或金融計算。
- [x] 578.6 擴充既有 livePrices.test.js 與 dashboardPanelLoader.test.js（若新增 cancel），以 fake timers 測多標的窗口、尾端、連續不飢餓、亂序/HTTP、eviction、unsubscribe/hidden/CLOSED；真實 Dashboard SFC 函式測 5秒觸發共用、non-overlap/pending、60秒趨勢獨立、隱藏/離頁清理及late response fence；首屏／換快照中隱藏後resume一次、不理abort舊回應不得覆寫；trend剛read5秒後realtime發現同ID跨日不再讀trend。保留所有既有跨日回歸，不以複製實作作測試替代。

## 驗證

```bash
bash scripts/spec-check.sh
cd frontend
npm test
node --test src/utils/dashboardPanelLoader.test.js
npm run build
```

先依 run-stack 在此 feature 與 main 比對設定（只輸出相同與否），僅以 main → feature 同步缺少部署設定／既有正式 TLS secrets；不印秘密、不改 main 設定。執行：

```bash
docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}'
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -fsSI http://localhost/
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:9090/api/quotes
```

核 frontend image與產物含本次變更，effective env/mounts與main一致；瀏覽器開啟儀表板查5秒估值／60秒趨勢（登入可用時），必要時用隔離 fake EventSource／fake clock 跑真實產物邏輯，不注入正式DB/Redis。盤中Redis→SSE→登入畫面未實測須記NOT_YET_EXECUTED。完成後以commit-merge-push：feature短中文commit＋push、乾淨main fetch/ff-only同步、no-ff merge＋push並核remote；合併後從乾淨main僅重建/recreate frontend使共用stack收斂到已合併版本。

## 完成報告

- 實作五個frontend檔：livePrices核心與測試、dashboardPanelLoader與測試、DashboardView。SSE先即時驗證更新cache，再500ms依key合併通知；固定時鐘覆蓋HTTP晚到、reentrant overlay、eviction、hidden/CLOSED/unsubscribe。Dashboard5秒自動估值與60秒trend分開；panel cancel以generation擋晚回，hidden首屏只resume一次，rollover exclude trend。main9df120da跨日修正與原guards保留，BFF/backend/producer/API/schema均未改。
- 規格獨立Codex第一輪指出hidden首屏恢復及rollover間接讀trend兩個Major，修正後第二輪PASS（0 findings）；Claude CLI因Credit balance is too low不可用，使用者明確同意主agent修正後續審查。獨立Codex架構第一輪PASS（0 findings），三production檔SHA與送審一致。
- 限定測試57/57、panel loader11/11；全npm test182/183，唯一失敗為修改前就有的openApiContract.test.js:67固定26與現行27金融路由不符（基準171/172同一failure）。該檔在另一worktree已有暫存修改，本輪不混入；沒有新增失敗。production build成功（既有chunk size warning），diff --check通過。
- feature run-stack限定驗收PASS：僅重建/recreate frontend，image sha256:afc84eb868ac898bffc0cd8eafee84a045b939d461c821a1d085f0e2215a2358。local dist共123檔與container bytes/hash逐檔一致，served index相同，HTTP200、BFF UP、9090quotes JSON array；effective四env鍵與兩TLS唯讀mount均main SAME，8080/8082無listener，其他容器ID不變，收尾image pin未變。frontend沒有healthcheck，正確狀態為running/serving。
- 登入瀏覽器UI／自然盤中Redis→SSE→畫面全鏈驗收為NOT_YET_EXECUTED：CUA browser host unavailable，native Chrome控制逾時；未注入正式DB/Redis假行情，不以HTTP200代替UI驗收。完整log與runtime receipt在執行session的/private/tmp/sse-performance-review/。
- Git與main收斂依已授權收尾流程執行：本任務八檔單一feature commit、no-ff merge、雙remote refs讀回；合併後僅從乾淨main重建/recreate frontend。精確merge SHA與main部署結果由本次交付回報提供。

