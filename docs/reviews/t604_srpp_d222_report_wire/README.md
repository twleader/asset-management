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
