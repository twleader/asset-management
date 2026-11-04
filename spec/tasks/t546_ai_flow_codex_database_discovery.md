# [t546] 修正 Codex AI 流程跨資料庫探索

**對應 Requirements:** Requirement 190（管理者按台北日期查閱 90 天內實際 AI 執行，觀測不得阻擋報告）
**前置任務:** t494、t503
**Liquibase changeset:** 無

## 背景

2026-11-04 查證：獨立背景收集器仍運作，sender 無待傳批次，但固定讀取 `.codex/sqlite/codex-dev.db`；該檔最後台股執行為 11 月 2 日。11 月 3、4 日的新排程已記在同目錄 `codex.db`，原 rollout 存在，故畫面缺少 Codex 紀錄。兩檔歷史分開，不能只改讀新檔而失去 90 天內舊資料。

## 要做什麼

- [ ] 546.1 正常探索及當日 terminal-failure priority 共用唯讀來源查詢，只接受固定 `.codex/sqlite/codex.db`、`.codex/sqlite/codex-dev.db`。至少一檔存在即可；兩檔都存在時查聯集，不掃任意檔名，不以 mtime 或最新列猜來源。每個存在的檔都須經既有 safe_source、mode=ro、query_only 與必要欄位驗證。symlink（含斷鏈）、非一般檔、無法開啟、schema 不符都不得靜默改讀另一檔。
- [ ] 546.2 使用既有三個 exact automation ID、created_at 的台北日／90 日視窗與參數化查詢。跨檔候選必須在 SQLite 以唯讀 ATTACH／UNION 形成單一候選集合後施全域 created_at DESC、thread_id ASC 與 LIMIT；正常探索返回至多本輪候選上限、priority 返回至多 25 列，不可各取 25 列再於記憶體裁切。只存在一檔時沿用同一全域查詢契約。每個 ATTACH URI 必須明示 mode=ro，所有來源先完成安全與 schema 驗證。wrap 只能在聯集 cursor 後全空時一起執行，不可個別來源提早 wrap。相同 thread_id、automation_id、created_at 由 UNION 去重，身份不含 DB 檔名。
- [ ] 546.3 為有限候選按 thread_id 在每個來源交叉查驗身份；同 thread_id 的 automation_id 或 created_at 不同時整輪該探索 fail closed，輸出固定錯誤碼，不猜哪筆正確。錯誤不推進探索 cursor、不建立新 envelope。查驗不得因不同日期、排程不在 allowlist 或超出本頁而漏掉衝突。既有非衝突資料的 run/event UUID、frozen state、pending、ACK 及序號不變。
- [ ] 546.4 保持背景每 60 秒、25 run／64MiB／單來源 32MiB／5 秒上限；terminal priority 的獨立 cursor、最多 25 候選、最多一筆 source／2.5 秒上限不變。正常探索與 priority 均涵蓋新舊資料庫；dry-run 零 state／spool 寫入。
- [ ] 546.5 增加回歸測試：新檔單獨存在、舊檔單獨存在、兩檔日期分開、重複去重、多頁與全體 wrap、cursor 相同時間排序、跨頁／跨日期／非 allowlist 衝突、壞檔／schema／symlink 拒絕，以及 priority 從新檔取得真實來源。驗證 source DB bytes 不變；不新增依賴。
- [ ] 546.6 更新操作文件。規格審查後才實作；測試與唯讀真實來源 dry-run 通過後，commit／no-ff merge／push 到 main，再重啟原 observer LaunchAgent。這次僅修改 host Python 收集器，沒有 Docker image 內程式變更，故驗收以同一 host job 重啟、collector/sender receipt、DB 與既有 BFF 唯讀查詢為準，不重建無變更容器。
- [ ] 546.7 使用原 state／ACK，不刪除、不重置、不使用 --replay、不人工組造 POST；只讓修正版原 collector 解析漏讀的真實來源，原 sender 持久化 ACK。核對 11 月 3、4 日及新檔中 11 月 2 日晚間的真實來源已入庫；保留 trace 原有完整度與報告 UNCONFIRMED，不將工具紀錄當成金融報告成功。不改 SRPP、金融 API／Swagger、規則包、投資排程、寄信或交易。

## 驗證

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p test_collect_srpp_ai_flow.py
bash scripts/spec-check.sh
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-11-04 --consumer Codex --dry-run
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-11-03 --consumer Codex --dry-run
```

正式合併後，從乾淨 main 檢查既有 installer ownership，重啟同 label observer，讓原 collector／sender 自行寫入。唯讀查詢 `ai_flow_run`／`ai_flow_event` 及既有已登入 ADMIN BFF 日期列表／detail，對照 exact thread_id、slot、event count、pending／ACK；不得輸出 token 或工具參數。背景輪巡若仍在舊頁，可執行正式 collector 的既有 `--once --date <日期> --consumer Codex`（不是 replay），保留原 state 及 UUID。

## 完成報告

待實作及驗收後回填。
