# 2026-09-28 全專案審查與修正紀錄

## 範圍與停止規則

基準 `191c0a45ab113aeccd22284904c8a0f4ecc3642b`（origin/main）。原工作目錄落後 95 commits，保留其未追蹤 `.codex/`，另建 `codex/project-review-20260928` 隔離工作目錄。main、其他 worktree 與待改路徑的未提交／未合併衝突已檢查。

依 project-review-loop，由 `gpt-6-luna`／`max` 完成 **1 輪全專案唯讀審查，確認 3 項不同根因**。第 1 輪不超過 5 項，依技能修正後停止，不再做全專案複審，未觸發第 5 輪自審。另按專案 SDD 做 2 次規格切片審查：第一次發現新 spec 把交易紀錄錯誤文案寫成與其他端點相同，修正後第二次 critical／major／minor 均 0。此規格缺陷已在實作前修正，不計為原專案第 4 項缺陷。

## 覆蓋表

| 範圍 | 證據與界線 |
|---|---|
| 規格／架構 | CLAUDE.md、active requirements/design/tasks；排除凍結 archive、vendor 與 generated 的逐行語意審查，generated 另做契約一致性檢查 |
| backend／BFF | 路由、權限、owner／tenant headers、資料查詢與錯誤路徑、既有驗證覆蓋 |
| external-materials | producer／client 與資料來源邊界、持久化與cache相關現有驗證 |
| frontend | API、router、loader；執行完整 utils tests，未將靜態／單元測試當成登入瀏覽器驗收 |
| Fubon／Yuanta | adapter／SDK呼叫面、唯讀界線與隔離離線測試；未查驗專有SDK內部或券商權限 |
| schema／部署／公開契約 | Compose、db/schema.sql與即時pg_dump、14-route gateway/OpenAPI/Tailscale腳本、兩份run-stack |

Requirement 140 的公開 optional email 為使用者明確接受的設計，未誤判為漏洞。前端 package.json 沒列部分 loader tests，但其原任務明定另用 node --test 執行，僅記為維護建議；本次已全數執行。

## Finding ledger

| ID | 確認缺陷／證據（審查基準） | 影響／根因 | 修正與狀態 |
|---|---|---|---|
| F1 | Codex run-stack:96、Claude run-stack:80 將主 clone `.env` 複製到 main | 過期 secrets 可先覆蓋權威設定，之後比較也無法發現原差異 | 移除反向copy、動態main基準、無值比較與main→隔離驗收目錄方向；已修正，未做修後全專案複審 |
| F2 | Codex run-stack:25–50 列12路，Claude:12–34 列5路；兩者漏broker服務 | 操作清單落後Compose與14-route OpenAPI，部署或盤點可漏項 | 兩版同步7個應用服務／2個資料儲存服務、14路與broker rebuild mapping；已修正，未做修後全專案複審 |
| F3 | LatestAssetsPublicService:83、PublicPortfolioAdviceService:85、PublicTradingRadarService:103、PublicTransactionHistoryService:83 沒有configuredAdmin deadline | owner lookup卡住會等gateway60秒，不能及時回各自的503；共用client無response timeout | 4 service／5入口補5秒lookup deadline並保留各自Unavailable；修前5個測試全部重現hang，修後10個邊界／HTTP測試PASS；未做修後全專案複審 |

狀態含義：修正已由指定測試／機械檢查驗證的部分，與獨立全專案再次審查不同。不得據此宣稱全專案零缺陷。

## 驗證紀錄

- 初始現有測試總計 4,898：4,897 通過、1 跳過、0 failures／errors。backend 2,530（1 Linux檔案系統語意測試在macOS跳過）、BFF 569、external-materials 784、frontend 136、Fubon 817、Yuanta 62。
- Java 使用 Temurin 21；PostgreSQL整合測試以Testcontainers隔離執行。Python測試容器無網路且未掛券商secrets；Fubon只掛唯讀spec fixtures。
- 初次frontend缺依賴、Fubon缺fixture mount均為測試環境問題，補妥後上述測試通過；不當作產品finding。
- F3修前新增的5個virtual-time測試全因未收到期限結果失敗，修後virtual-time＋真實controller/advice HTTP共10項通過。測試斷言5秒以前無錯、5秒取消lookup、零資料downstream，以及每個端點既有503title/detail。
- OpenAPI版本1.15.1；14路、schema與status集合不變。已由YAML產生本repo及SRPP Swagger兩份鏡像，byte-identical檢查PASS。SRPP只同步該文件，不提交其餘專案工作。
- 修後完整BFF 579項全部通過（包含新增10項）；其餘未變更服務維持上述基線證據，合計4,908項中的4,907通過、1跳過。
- spec-check最終BLOCK 0／CHECK 0；獨立diff-scoped架構查核critical／major／minor均0，並記錄architecture gate。這是專案要求的本次差異查核，不是第二輪全專案審查。
- feature BFF已重建／recreate且healthy，五個GET=200、非法email=400、SRPP probe=409 POLICY_UNSUPPORTED；runtime JAR四個修正class SHA256與feature target/classes一致，內嵌OpenAPI1.15.1。
- main `.env` 缺少現行容器已使用的 `API_ERROR_LOG_INGEST_TOKEN`；確認BFF/business值相同後，僅於部署程序記憶體注入。main `.env`未改、未產生新token，六項有效BFF設定與既有容器一致。
- 依先驗收後落地順序，main merge／remote readback與main二次部署結果由任務完成回覆提供。

## 限制

此審查以各active區域與關鍵資料流程為範圍，不宣稱逐行證明所有程式。未實際操作登入後瀏覽器、未測試專有券商SDK內部或真實帳戶權限、未藉中斷正式business重現逾時；失敗路徑由隔離stub驗證。實際Tailscale Serve唯讀盤點另找到13個handlers，缺已由source/gateway定義的SRPP daily-context；此項為部署驗收新發現的既有漂移，將依既有14路契約補齊，不混算為首輪靜態審查的3項缺陷。最後設定讀回與私有路由probe結果由任務完成回覆提供。既有單一Linux限定測試的跳過保留為驗證限制。
