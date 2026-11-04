# [t546] 修正 Codex AI 流程跨資料庫探索

**對應 Requirements:** Requirement 190（管理者按台北日期查閱 90 天內實際 AI 執行，觀測不得阻擋報告）
**前置任務:** t494、t503
**Liquibase changeset:** 無

## 背景

2026-11-04 查證：獨立背景收集器仍運作，sender 無待傳批次，但固定讀取 `.codex/sqlite/codex-dev.db`；該檔最後台股執行為 11 月 2 日。11 月 3、4 日的新排程已記在同目錄 `codex.db`，原 rollout 存在，故畫面缺少 Codex 紀錄。兩檔歷史分開，不能只改讀新檔而失去 90 天內舊資料。

## 要做什麼

- [x] 546.1 正常探索及當日 terminal-failure priority 共用唯讀來源查詢，只接受固定 `.codex/sqlite/codex.db`、`.codex/sqlite/codex-dev.db`。至少一檔存在即可；兩檔都存在時查聯集，不掃任意檔名，不以 mtime 或最新列猜來源。每個存在的檔都須經既有 safe_source、mode=ro、query_only 與必要欄位驗證。symlink（含斷鏈）、非一般檔、無法開啟、schema 不符都不得靜默改讀另一檔。
- [x] 546.2 使用既有三個 exact automation ID、created_at 的台北日／90 日視窗與參數化查詢。跨檔候選必須在 SQLite 以唯讀 ATTACH／UNION 形成單一候選集合後施全域 created_at DESC、thread_id ASC 與 LIMIT；正常探索返回至多本輪候選上限、priority 返回至多 25 列，不可各取 25 列再於記憶體裁切。只存在一檔時沿用同一全域查詢契約。每個 ATTACH URI 必須明示 mode=ro，所有來源先完成安全與 schema 驗證。wrap 只能在聯集 cursor 後全空時一起執行，不可個別來源提早 wrap。相同 thread_id、automation_id、created_at 由 UNION 去重，身份不含 DB 檔名。
- [x] 546.3 為有限候選按 thread_id 在每個來源交叉查驗身份；同 thread_id 的 automation_id 或 created_at 不同時整輪該探索 fail closed，輸出固定錯誤碼，不猜哪筆正確。錯誤不推進探索 cursor、不建立新 envelope。查驗不得因不同日期、排程不在 allowlist 或超出本頁而漏掉衝突。既有非衝突資料的 run/event UUID、frozen state、pending、ACK 及序號不變。
- [x] 546.4 保持背景每 60 秒、25 run／64MiB／單來源 32MiB／5 秒上限；terminal priority 的獨立 cursor、最多 25 候選、最多一筆 source／2.5 秒上限不變。正常探索與 priority 均涵蓋新舊資料庫；dry-run 零 state／spool 寫入。
- [x] 546.5 增加回歸測試：新檔單獨存在、舊檔單獨存在、兩檔日期分開、重複去重、多頁與全體 wrap、cursor 相同時間排序、跨頁／跨日期／非 allowlist 衝突、壞檔／schema／symlink 拒絕，以及 priority 從新檔取得真實來源。驗證 source DB bytes 不變；不新增依賴。
- [x] 546.6 更新操作文件。規格審查後才實作；測試與唯讀真實來源 dry-run 通過後，commit／no-ff merge／push 到 main，再重啟原 observer LaunchAgent。這次僅修改 host Python 收集器，沒有 Docker image 內程式變更，故驗收以同一 host job 重啟、collector/sender receipt、DB 與既有 BFF 唯讀查詢為準，不重建無變更容器。
- [x] 546.7 使用原 state／ACK，不刪除、不重置、不使用 --replay、不人工組造 POST；只讓修正版原 collector 解析漏讀的真實來源，原 sender 持久化 ACK。核對 11 月 3、4 日及新檔中 11 月 2 日晚間的真實來源已入庫；保留 trace 原有完整度與報告 UNCONFIRMED，不將工具紀錄當成金融報告成功。不改 SRPP、金融 API／Swagger、規則包、投資排程、寄信或交易。

## 驗證

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p test_collect_srpp_ai_flow.py
bash scripts/spec-check.sh
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-11-04 --consumer Codex --dry-run
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-11-03 --consumer Codex --dry-run
```

正式合併後，從乾淨 main 檢查既有 installer ownership，重啟同 label observer，讓原 collector／sender 自行寫入。唯讀查詢 `ai_flow_run`／`ai_flow_event` 及既有已登入 ADMIN BFF 日期列表／detail，對照 exact thread_id、slot、event count、pending／ACK；不得輸出 token 或工具參數。背景輪巡若仍在舊頁，可執行正式 collector 的既有 `--once --date <日期> --consumer Codex`（不是 replay），保留原 state 及 UUID。

## 完成報告

- 已修改收集器、回歸測試、操作文件及本任務檔；正常探索與當日 priority 共用固定兩庫的唯讀聯集，全域候選上限、全體 wrap、跨庫身份衝突拒絕及原 UUID／state／ACK 語意均保留。
- 獨立規格審查兩輪：第一輪發現跨庫候選總量上限問題，修為 SQL 全域 LIMIT；第二輪 critical／major／minor 均 0。獨立架構查證亦均 0；機械檢查 BLOCK 0／CHECK 0，包含 117 張表 schema 與真實 DB 逐位元一致。
- 187 項測試全通（原 175 項加 12 項回歸），包含兩庫 mode=ro 在 query_only 關閉後仍拒寫。11 月 2、3、4 日真實 dry-run 均取得原 rollout，不寫 state 或 HTTP。
- 使用者已明確授權將本次四檔推送至既有公開 origin。實作提交 `871e566966b5bb63a00add4576bc7f9f5210fff9`、main no-ff merge `f6a5d7c31a1fb40aa21aaa40ce4e66a781664767` 已推送，兩 remote refs 精確回讀一致。
- 正式主機驗收：先確認乾淨 main 的 collector／sender bytes 與已審查 feature 相同及原 LaunchAgent ownership，暫停原 job，再保留原 state／ACK 依序使用原 collector 的三個日期 once 與原 sender 的預設有界輪次，最後恢復同一 plist／label。沒有使用 replay、刪 state、人工組造 POST、重跑金融報告或重建無變更容器。
- 共補入 1,865 筆事件（11/2：158、11/3：572、11/4：1,135），23 批全部收到持久化 ACK、重播 0、待傳 0；補收前既有 701 筆 run／event metadata 與 payload hashes 全保持。11/2 Codex 現 4 次、11/3 現 4 次、11/4 現 2 次；Claude 原筆數保持。
- 已唯讀回讀 DB 及頁面所用 business read API：今日 ALL 2、MORNING 1、NOON 1、US 0；早盤 573 nodes／571 edges、FULL_OBSERVED，午盤 562 nodes／560 edges、PARTIAL_OBSERVED。兩輪 verifiedReportOutcome 都維持 UNCONFIRMED，完整 detail 的 toolCall 均為 null，沒有把參數帶入一般投影。原 observer 已重新運作，新 sender receipt 無錯誤且待傳為 0。
- 驗收限制：瀏覽器雖已有登入 AI-flow 分頁，但禁止 AppleScript JavaScript，故未更改其安全設定、未偽造 session，也未宣稱完成瀏覽器 BFF／自動點擊驗收；已用真實 DB 與同源業務查詢投影確認恢復。原始公開摘要中不合閉合格式者仍以 PUBLIC_DECISION_INVALID 拒收，未改寫或臆造內容；不影響上述真實工具事件補收。午盤原有部分紀錄狀態如實保留。
- 完整本機驗收 receipt 保留於 `/private/tmp/ai-flow-546-runtime.json`、`/private/tmp/ai-flow-546-readback-before.json` 與 `/private/tmp/ai-flow-546-readback-after.json`，未提交原始 trace、參數、token 或私人回應。
