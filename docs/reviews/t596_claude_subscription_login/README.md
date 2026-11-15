# Task596：Claude 訂閱重新登入與過期攔截

憑證過期或未登入時，主要管理者本人會看到站內登入提示；按下開始登入後開啟 Claude CLI 產生的官方授權頁，回貼完整授權碼完成原生登入。系統不收帳號密碼或 API key，不保存授權碼或登入網址。

登入與費用驗證分開：登入成功只更新本機狀態及工具清單，不會清除 Claude 費用查詢的限流／退避狀態。仍需取得當次有效的訂閱費用證據才可使用模型；不明時不呼叫模型。Codex 與本機模型不被 Claude 登入鎖阻擋。

## 已完成的驗證

- 規格機械檢查 BLOCK 0／CHECK 0，獨立規格審查 PASS、0 findings，spec gate `b28a038cfad3`。
- Python 125、backend 87、BFF 16、frontend 202 項測試，共 430 項通過；前端正式建置通過。測試來源內容雜湊見 tests.json。
- 主要管理者本人、同源與 CSRF、所有登入回應 no-store、敏感錯誤排除紀錄、授權碼單次提交與清空、owner 切換及離頁隔離均有驗證。
- 原生 callback 僅綁 loopback 的原始碼證據以本機 Claude 2.1.325 為準；未實作每次啟動的 socket 強制檢查，CLI 更新須重新進行實機綁定與清理驗證。

- 第一輪架構審查 PASS（minor 1）：兩層 DTO 時間欄位已改 Instant，並以正式 BFF application context 驗證仍輸出 ISO8601 字串；第二輪複驗 PASS、0 findings，arch gate `550965effd85`。

## 部署驗收

feature 來源的三個服務已 rebuild + recreate，host 已切至對應來源。正式網域 TLS、有效環境設定與掛載、兩筆既有模擬結果 hash、9090 既有唯讀接口均通過。

原生 Claude 2.1.325 啟動 → 產生官方 URL → 取消通過：callback 實際只綁 127.0.0.1，取消後 process group/socket 均清除，現有 Claude/Codex 憑證、token、registry 與 backoff bytes/mode 均保持原樣，0 usage／0 model／0 code 提交。

正式前端 assets 的 13 項瀏覽器情境全部通過（登入與 BFF 回應採合成資料）：過期僅一次提示、明確點擊才開官方頁、彈出受阻替代連結、授權碼單次提交即清空、只輪詢登入狀態、成功單次重載、保留費用拒絕、取消／逾時、owner 切換晚回應及其他管理者／代管拒絕。0 真實 API 寫入或推論，無頁面錯誤或非預期請求。

目前本機 Claude 登入有效；費用查詢仍依供應商 Retry-After 退避至臺北時間 2026-11-16 02:06:58.423195，此前不查 usage、不呼叫 Claude 模型。登入成功的模擬測試不代表供應商已解除限流。main 合併後重新建置與驗證結果另見最終回報。
