# [t474] 修正富邦技術指標安全視窗並驗證入庫與按鍵回補

**對應 Requirements:** Requirement 176（富邦技術指標安全視窗與即收即存）  
**前置任務:** t408、t471  
**Liquibase changeset:** 無

## 背景

2026-10-04 對 2330 的富邦唯讀探測顯示，2025-10-03～2026-10-02 首尾含 365 天的查詢回 17 個 AVAILABLE profile，2025-10-02～2026-10-02 首尾含 366 天與既有首尾含 421 天的查詢均回 `UPSTREAM_UNAVAILABLE`；這是實測結果，不能冒充官方文件承諾。每日同步與「補齊技術指標資料」回補皆固定查 `to.minusDays(420)`，因此有效 history 無法進入 `stock_technical_indicator`。既有 t408／t471 的 420 日視窗在本任務改為含首尾最多 365 日；固定 17 profile、官方來源與不可變事實契約維持。

## 要做什麼

- [ ] 474.1 將 Java 每日同步、Python 無日期預設及 Java/Python 受保護歷史讀取的安全範圍統一為 `from=to.minusDays(364)`（含首尾 365 天）；歷史路徑拒絕更長、未來、反向區間，前端不可自選日期、profile、指標參數。更新 Java 日期 convenience parser／port fallback 與 Python 每 profile row cap，讓跨服務 query identity 一致；仍保留 exact 17 Fubon profiles、`FUBON_SDK`、`D/W`、嚴格 payload/日期檢查與 4 MiB body cap。
- [ ] 474.2 管理者 job 對最多 30 檔、每檔最多 35 段，從最近已完成的台股交易日查 `[to.minusDays(364),to]`，下一段 `to=from.minusDays(1)`；維持 17 profiles、總 17,850 次 profile 請求及 24 小時期限。每段驗證後立即呼叫既有 `persistTechnical` 保存所有 AVAILABLE rows，`NO_DATA`／unavailable 不冒充 fact；相同 fact 再讀為 unchanged，不覆寫。只在 exact-17 完整 capture 建 members；按鍵 job 永不寫 Redis。兩個成功 schema 的連續全空視窗或上限停止仍回 `HISTORY_DEPTH_UNKNOWN`，不宣稱最早來源日已查明。
- [ ] 474.3 每日同步在收到且驗證跨服務 bundle 後立即呼叫既有 `persistTechnical`；可用 profile 的 rows 即使其他 profile 失敗也入庫，未完整 capture 不寫 members 或 Redis。writer 為 `FAILED` 或 `CONFLICT_NO_SOURCE_REVISION` 時結果標示失敗／部分成功，且不得投影未提交 capture；不可把 `UNCHANGED` 當成新增。job writer 失敗亦停止、保留先前成功事實，畫面 `factRows`/coverage/status 反映實際結果。
- [ ] 474.4 不新增技術公式、K 線推算、Yahoo facts、券商交易／帳戶呼叫、新公開 API 或 schema；沿用 append-only PostgreSQL fact writer、hash、capture provenance、auth/token/enablement 與 API log 安全格式。不得修改 `.env` 或輸出秘密。
- [ ] 474.5 實機 0050 首段的 MACD 回 `SCHEMA_INVALID` 時，先在 Python 嚴格 normalizer 補 Requirement 176 的固定階段代碼與最多 512 字元安全結構診斷；只記固定 profile id、預期欄位缺漏、未知欄位數、型別分類與筆數，不記未知欄位名稱、數值或完整 response。對外錯碼與 Java API log 固定格式不變。用同窗官方唯讀回應定位後，若能由官方文件及結構證據證實是相容 schema 差異，才作最小解析修正並加入回歸測試；不以推算值補出 MACD。
- [ ] 474.6 Backfill 對單標的 profile `SCHEMA_INVALID` 保留該 window 已提交的其他 profile facts、將該 profile 標為 `FAILED`、未知範圍保持 `HISTORY_DEPTH_UNKNOWN`，停止該標的舊日期窗口後繼續其他標的。對 profile response 前 client 以 `TECHNICAL_SCHEMA_INVALID` 回報的整體 wire／identity 失敗，該標的全部 profile 標為 `FAILED`、當窗不入庫、保留先前窗口事實，也繼續其他標的。`completedSymbols` 計處理至終態的標的；無後續全域失敗時 job 最終 `PARTIAL` 並保留首個單標的失敗 reason。後續若遇 429／quota、transport、deadline、writer failure 則停止全 job，job reason 改為全域錯碼，先前 schema 原因留在 coverage。加入兩種 0050 schema 失敗後下一標的可入庫、失敗範圍與事實計數回歸測試。
- [ ] 474.7 回補工作以同一鎖發布可觀察快照，確保 `windows`/`profileResults` 與終態 `status`/`completedAt` 在輪詢時成對一致；加入並發輪詢回歸測試及先 schema 後 429／transport 的三檔停止測試，確認 job reason 為全域錯碼、先前 schema 留在 coverage。前端將 `TECHNICAL_SCHEMA_INVALID` 文案改為「部分標的官方回應格式無法核實，該標的後續探查已略過；其餘標的照常處理。」或等義文字，不誤導為整個工作立即停止；全域錯碼則顯示對應停止文案。

## 驗證

先執行 `bash scripts/spec-check.sh`、`git diff --check`。執行 `python3 -m pytest -q fubon-broker-service/tests/test_task408_market_data.py` 與 `/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test`；加入聚焦測試證明 365 日 inclusive bound、366 日拒絕、backfill 相鄰視窗、partial rows persistence、writer 失敗結果與重跑冪等。

依 `.agents/skills/run-stack/SKILL.md` 比對 feature/main `.env`，只 rebuild/recreate 修改的 `fubon-broker-service` 與 `external-materials-service`，驗證健康、BFF 安全 GET；先對 2330 執行受控、唯讀的官方 technical API 探測，核對 365 日視窗的 17 個 profile 與來源日期，不從該 probe 宣稱已入庫。另以 0050 同一視窗受控唯讀重現，驗證診斷階段、每失敗 profile 至多一筆、訊息長度及原始值／未知 key／secret 不出現在 log；若官方證據不支持解析差異，確認仍回 `SCHEMA_INVALID`。接著以既有受管理者保護的按鍵 BFF 入口啟動正式 job（它會凍結最多 30 檔；沒有單檔寫入入口），輪詢 job ID；第一個視窗完成後以唯讀 SQL 核對 `stock_technical_indicator`、`fubon_technical_capture_member` 的 provider/profile/source date/hash/row count，確認 job 的 `factRows` 與資料庫相符，並確認 Redis 與雷達快照沒有因 backfill 改變。正式 job 可能需數小時，應回報已驗證的進度與可核對的 job ID，而非提前宣稱補齊。

## 完成報告

待實作後回填：改動檔案、測試結果、已部署影像與健康狀態、單檔/按鍵 job 的真實入庫筆數與最早／最晚 source date、尚未查明的歷史深度及偏差。
