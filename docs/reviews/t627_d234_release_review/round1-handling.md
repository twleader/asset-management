# 第 1 輪處理
1. [Major] order-plan v2 golden 舊 hash：已以 D-234 SRPP root 重產，只變 srppSources trade_blocker_policy.py hash，22 案逐位元不變，--check PASS；四組 D-234 golden --check 已列入 t627 驗證段。
2. [Minor] 已補 policy_manifest.json 檔案 sha256；最終 bundle 的 SRPP commit 於 627.3 落地時記錄。
另：內嵌 D-234 後 39 個測試因 fixture 缺 satellite 來源失敗，已改共用 fixture 走 producer 同一 helper，後端失敗集合回到基準 28 個。
