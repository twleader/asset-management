# Task 604：D-222 報告計算傳輸修復

2026-11-18 Codex/Claude 早報共同遇到report-calculations公開502。維護時以相同owner與原identity讀container內既有不可變包回200；本機現行BFF validator在FACT_NAMES檢查第130行拒絕。D-222 producer已輸出protectedReserveTarget、referenceCashDelta、equityTarget，但BFF/OpenAPI未同步。另將同producer現有五個條件式區間／選擇檢視facts一併補成固定白名單，避免來源完整後再失敗。

修復僅驗傳輸名稱、subject、unit及domain，不重算公式，不刪金融facts、不改不可變包。原missing/receipt/partial狀態與selector無API容量接受語意保留。

## 驗證

- 獨立spec review三輪：第一輪2 Major，第二輪1 Critical/1 Minor，全部修正，第三輪PASS、0 findings。修正者與實作者實際為Codex，未假稱Claude。
- 目標BFF測試25 tests（validator19、controller6），0 failures/errors。新增4個測試方法，含三reserve facts、正負/未知delta、三檔各自PARTIAL區間、audit-only review額、錯subject/unit/range/name及quality=null拒絕。
- 今日business原始包8,464,001 bytes保留不修改；修復前同bytes validator失敗於FACT_NAMES，修復後同bytes PASS。此為本機維護診斷，不能當新bundle/Swagger身份的正式日報採用。原始包含個人財務資料，僅保存於/tmp/srpp-502-20261118/internal.json，不提交Git。
- 獨立arch review兩輪皆PASS、0 findings；第二輪涵蓋完整12個tracked檔案差異與新task，包含生成物、SQL與golden靜態一致性。
- 試驗原始紀錄：/tmp/srpp-502-20261118/{before-validation.txt,after-validation.txt,implementation-tests.log}。
- 跨repo Swagger、runtime、policy hash、registry、container及精確新hash 9090讀回由本輪release協調收據另記；不得以本機測試取代正式部署。

## 最終發布與實機驗收（2026-11-18）

最終 bundle `58674cfdd0d77af0e4ec439432cd1b883ec42d9e9fc0fc8fd1f30faf5b8046aa`，Swagger `68d6dcb1c24bed5a80765877a0aa87fb0f2d70cdf9aa13a6278cdad02c59852d`。SRPP consumer schema 的八個新 fact 名稱在第一次實機驗證被抓出未同步，已補完整閉合契約與同步工具，再經獨立正反測試、新 hash/registry/部署鏈修正；保留第一次 HTTP200 但 schema 拒絕的收據。

10:29:19 自然 producer 發布最終新 hash 兩軌包，10:30:06／10:30:08 的 Codex／Claude 公開 GET 均 HTTP200、完整 consumer verifier PASS、固定 runId 重讀原始 bytes 相同。各自 8 PARTIAL／4 UNAVAILABLE 保留，不等同已補跑日報。兩專案 main 已落地推送，registry 新增且舊 row 未改；實際容器內 Swagger/model/manifest/SQL bytes 皆與合併來源一致。第二輪只重建 business-services，BFF 第一輪已驗 image 保留。

本機完整維護證據：`/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-18-morning-repair-release/`。最終讀回收據 `consumer-schema-followup/business-api-readback.json`；實作與測試詳 [Task 604](../../../spec/tasks/t604_srpp_d222_report_wire.md)。未補跑 SRPP 早報、未呼叫日報寄信或交易；既有 MarketAnalysis 自動通知另有如實觀測記錄。
