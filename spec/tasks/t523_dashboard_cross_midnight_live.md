# [t523] 修正儀表板跨午夜美股即時更新

**對應 Requirements:** Requirement 197（全頁即時行情一致更新）的 Task 523 日期與身份修訂
**前置任務:** t506（共享行情 SSE）
**Liquibase changeset:** 無

## 背景

台北 2026-10-30 00:16，美東仍是 10/29。最新快照 id=15 已推進至 10/30，Redis/live-assets 的 GOOGL=285.17、VOO=706.935、VT=157.885，皆為 10/29 NASDAQ LIVE；畫面卻顯示 10/28 的 324.81／709.64／158.3388。DashboardView 與 SnapshotEnricher 都以快照日等於市場當地今日作閘門。BFF 凍結列還把 tradingDate 標為 10/30，使後續 10/29 LIVE 被 SSE 防倒退拒收。修復必須涵蓋初始投影與後續推播，不能只取消前端閘門。

## 要做什麼

- [ ] 523.1 新增純身份／日期 helper，支援注入 Clock：snapshot.id 與 liveAssets.snapshotId 均非空且相同、兩邊 snapshotDate 相同，且日期為台北今日或該市場當地今日才 eligible。前端再驗 selectedSnapshotId 為 metadata 最新 ID。缺 ID、錯 ID、歷史、future 或錯日期均 fail closed。不得改 SnapshotEnricher.isCurrentBasedate 的共用編輯頁規則。
- [ ] 523.2 DashboardPanelService 首屏和舊 summary 用同源 live-assets 的真正報價 tuple；eligible 的 10/30 快照美股仍保留真正 10/29 tradingDate/source/updatedAt/quoteStatus。CLOSE_PENDING 空價、VERIFIED_CLOSE、unknown/null 與既有 mergeLiveQuote 防倒退均保留，不改 quote source、producer、Redis/DB 或金融計算。
- [ ] 523.3 Dashboard holdings／stock-values／KPI 的 overlay 使用各 panel 自己的 snapshot/liveAssets，避免 holdings 慢回導致其他 panel 不能更新。共用 LiveAssetsOverlay 最新列採同一 helper，Dashboard 趨勢與歷年資產最新列同口徑；歷史其餘列凍結。
- [ ] 523.4 長開頁面遇同一最新 ID 日期已向前推進到台北今日時，以既有 pure-read metadata/panel API 做 bounded reload，每日期最多一個進行中的重載。AbortSignal/generation 阻擋歷史切換後的晚回應；不盲改日期，不循環重載。
- [ ] 523.5 Java 固定 Clock 與前端真 SFC handler 測試涵蓋 2026-10-29T16:16:00Z、冬令午夜、GOOGL/VOO/VT 首屏真 tuple 與 SSE 前進、一般台股、歷史／錯缺 ID／future、CLOSE_PENDING 及跨日長開頁競態。只新增必要回歸，不用 regex 代替行為測試。
- [ ] 523.6 通過獨立 spec 與 diff-scoped arch 審查、適當 tests/build，再由 run-stack subagent 重建並 recreate BFF/frontend；設定比對 main 且不印秘密。通過後 commit-merge-push，以短中文 feature commit、main --no-ff merge 並 push；再從乾淨 main 重建這兩個服務收斂。禁止通知、交易、正式假行情注入或修改 SRPP/API/Swagger/排程。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
cd frontend
PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm test
PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm run build
```

由 run-stack 從正確 worktree 及 main 正式設定執行：

```bash
docker compose -p asset-management build bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate bff frontend
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:9090/api/quotes -o /tmp/dashboard-live-quotes.json
curl -fsSI http://localhost/
```

報價只讀回既有市場資料並記 source/time/price；登入畫面無可用 session 時如實記錄瀏覽器驗收限制，使用真 handler/BFF 回歸與已部署產物查證，不偽造登入。確認部署產物含新 helper 與前端處理，BFF healthy、9090 JSON array、frontend HTTP 200；最後重讀 image identity 防其他部署覆蓋。

## 完成報告

2026-10-30 完成實作及 feature 正式服務驗收；Git 收尾與乾淨 main 收斂由後續 commit-merge-push 收據確認，本報告不預先宣稱尚未執行的發布成功。

- 新增 BFF LatestSnapshotLiveEligibility 與前端 dashboardLivePriceEligibility，同時驗最新快照身份、台北業務日與市場日期；Dashboard panels/legacy summary 保留真實交易日 tuple，LiveAssetsOverlay 最新列共用同規則。編輯頁及 SnapshotEnricher 原規則未修改。
- 每個 panel 使用自己的 snapshot/liveAssets，長開頁遇日期前進有界純讀重載；保留歷史、取消／generation、非 category allocation context、收盤待補與 frozen close 漲跌。歷史／future frozen tuple 不再 seed 共用即時快取。共用 stream 僅新增可注入日期函數供固定時鐘驗證，production 預設及原 merge fences 不變。
- 獨立 spec 審查 critical/major/minor 均 0，功能規格 gate aab66703a51f；最終 diff-scoped arch 審查均 0，implementation gate 50a29f088dd6。機械 spec-check BLOCK 0/CHECK 0，git diff --check 通過。
- BFF full suite 118 類、930 tests 通過；末次 summary 空資料防護與回歸補強後再跑 targeted 4 類65 tests 通過。最終 frontend 全套160/160與 production build通過，僅原有 chunk-size warning。真 SFC/stream 回歸涵蓋三檔報價、冬夏跨午夜、panel獨立、歷史與錯缺身份、shared-cache fence、pending、allocation context、rollover取消及 frozen close tuple 配對。
- root 另使用實際9090 assets資料執行真 SFC getPriceCell：GOOGL284.91、VOO706.60、VT157.76均取到當時同一live-assets的真實美東10/29 tuple與台北10/30快照。這是實際資料搭配畫面函式驗證，不冒稱已登入瀏覽器驗收。
- run-stack 使用主模型繼承備援（派工工具不提供gpt-5.6-terra）。main→feature同步缺少的ignored部署設定，effective environment／唯讀TLS掛載與main一致；僅build/recreate bff/frontend。BFF image8cfb3c76084d及frontend9f0eba9862ef、running jar helper與DashboardView-BeEH7c9d.js讀回一致。BFF healthy/UP、frontend HTTP200、9090 US quotes HTTP200 JSON array，GOOGL285.37／VOO706.9199／VT157.815為驗收當時cache值；交易日10/29、台北00:40:01–04 LIVE。其餘7服務container IDs/images不變，8080/8082無host listener。
- 未有可用登入session，Dashboard realtime回401；真實登入UI操作驗收保留未驗證，不繞過auth。不新增行情抓取／排程／API／Swagger／SRPP變更、金融計算、郵件、正式假行情或券商操作。
