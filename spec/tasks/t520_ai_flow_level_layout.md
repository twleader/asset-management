# [t520] AI 流程同階水平對齊與就地分支排版

**對應 Requirements:** Requirement 190（AI 判斷流程圖的縮放、拖曳與長流程可讀性；本次為純呈現修正）
**前置任務:** t504、t508
**Liquibase changeset:** 無

## 背景

使用者指出同一階的判斷必須排在同一條水平線。既有實際事件依 sequence 垂直排，公開候選及公開決策摘要的 synthetic 節點則集中在所有實際事件下方，選項逐項向下排，導致連線跨越整張長圖。修正只影響畫面座標，不改公開事件、選項、關係或資料來源。

本輪明確取代 Task504 舊有「synthetic 統一 append 最底、options 固定 x=1180 縱排、實際圖節點座標不動」的呈現約束；仍保留其關係／identity／選項來源契約。只對已載入且已驗證的可見圖座標排版，屬渲染行為，不排序／過濾業務資料、不重新計算金融或事件事實。既有時間軸及 API x/y 不變，畫面圖使用另外計算的 presentation x/y。

## 要做什麼

- [x] 520.1 保留 `mapAiFlowGraphData` 的摘要驗證、公開欄位 allowlist、synthetic identity、候選採用改接 inbound edge 規則與點選回原 event 的行為。對完成映射的可見圖做純呈現的穩定分層：有向無環圖的層級為有效前驅最大層級加一，根為第零層；所有同層節點 y 相同，依輸入穩定順序左右排列，節點間距足以容納現有 150×48 方框及標籤。圖的位置不代表觀測外的新因果或執行證明。
- [x] 520.2 同一公開候選／決策 hub 的全部 options 必須同 y、不同 x，留在該 hub 下一階，不再 append 到整張原圖最底。synthetic options 沿用既有 mapper，只有所屬 hub 為前驅，固定在 hub 階加一；一般多父節點採最大前驅層級加一。公開決策候選仍全部顯示，不偽造未記錄選項或採用結果。多父節點、分支匯合、孤立節點及空圖均有確定座標。缺端點邊及自連線不得參與分層；邊仍保留原輸出。若意外有環，確定性 fallback 排剩餘節點且不無限迴圈，不宣稱它是有效因果圖。
- [x] 520.3 圖回傳有限的 `graphWidth`、`graphHeight` 及明確繪圖範圍；Vue 以固定像素間距顯示，外框支援上下／左右捲動，窄螢幕不得把同階選項擠成重疊方框。保留 SVG、拖曳、縮放與比例重設，短圖和單列圖也不得被自動拉伸或壓扁。連線、label、relation、事件／timeline／slowSteps 順序與狀態不變，圖／tooltip 不含完整理由、refs 或參數。
- [x] 520.4 沿用本頁 ADMIN 專屬 BFF 與既有 API；不新增 API、依賴套件、backend/BFF/DB/collector/SRPP 或金融行為。新增／調整聚焦測試驗證分叉同階、匯合次序、候選水平同階、穩定輸出、有限且不重疊座標、長流程分支就近與隱私／點選回歸。從實際 Docker served app 以隔離 read-only fixture 驗證寬／窄畫面與縮放，不寫入真實事件、不觸發報告、寄信或交易。

## 驗證

```bash
bash scripts/spec-check.sh
node --test frontend/src/utils/aiFlowView.contract.test.js
npm --prefix frontend run build
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -fsSI http://localhost/
```

部署先比對 main 與 feature 的環境設定，僅輸出一致與否；完成獨立架構審查與實際瀏覽器驗收後 commit、no-ff merge、push 並驗遠端 SHA。已 merge 後再從乾淨 main rebuild/recreate frontend，確認正式 served app 與同階排版。

## 完成報告

- 修改三個前端檔案：公開圖映射增加穩定 DAG 分層，240px 橫距、120px 層距；回傳 plotBounds／寬高，Vue 固定像素畫布並限制溢出於圖框。保留原事件、邊、摘要驗證、公開欄位及 synthetic 點選 identity。同步修訂兩份共用規格的舊縱排條款。
- 獨立 spec 審查及架構審查均為 critical 0／major 0／minor 0；spec-check 為 BLOCK 0／CHECK 0，git diff --check 無錯誤。
- frontend AI Flow contract tests 21/21 通過；Vite production build 成功。獨立審查另以 ECharts SVG SSR 驗單點、單列、單欄與多父圖，座標 transform 皆為一比一且有限。
- Feature frontend 單服務 build／force-recreate 完成，HTTP 200；served AiFlowView-BAzVEdMz.js 含同階及左右捲動提示。frontend effective 設定與 main 一致，未修改環境檔或其他服務。
- 實際 served JS 配隔離瀏覽器 fixture 驗收 14 項全通過：同階判斷、三組候選水平對齊、長鏈可讀間距、縮放／重設、兩種 synthetic 點選真事件明細、32 候選同列／順序／可捲至末端，以及窄畫面同列與圖內溢出。原版以同一 harness 可重現 7 項排版失敗；驗收所有 API 由瀏覽器隔離回應，零變更型請求、未知請求及 page error，未讀寫真實報告。
- 前 image 為 f069a3b22d035301c0c6f52c12f731d306326a2e6230c98ee20adda40df4501d，feature 驗收 image 為 ce9a83deb949fae4307ef02a07cbbc2f16eb9b2fbdfb75b76be7ad244ad56297；驗收後重查一致。main 合併推送與乾淨 main 再部署屬後續收尾，不能以本段 feature 證據代替。
- 無功能範圍偏差。窄畫面驗收使用既有 sidebar 收合按鈕；未擴大修正原有全站 header。
