# [t471] 交易雷達啟動官方技術指標歷史回補

**對應 Requirements:** Requirement 173（雷達管理者啟動核准官方技術指標歷史回補）  
**前置任務:** t470  
**Liquibase changeset:** 先盤點既有 schema；無必要變更則無

## 背景

今日交易雷達頁需要一個管理者操作入口，啟動技術指標來源資料回補並顯示逐標的／profile 結果。既有 `FubonTechnicalIndicatorSyncService` 使用 `fubon-broker-service` 的固定技術 profile 路徑與有限近期視窗，不能視為富邦證券或交易所直供、也不能作為十年歷史支援證據。不得把 K 線資料自行計算成技術指標；其他供應商（含 Fugle）在取得使用者明確確認前屬範圍外。

## 要做什麼

- [ ] 471.1 先依富邦證券 → TWSE／TPEx → Yahoo 順序核對各來源是否直接提供 technical-history API；列出 host、官方文件、profile、date window、成功／空集合／錯誤 schema、歷史深度與配額。來源順序只適用於有直接提供技術指標值、且文件及 response schema 均可驗證的 API；行情 API／Yahoo 圖表 K 線不算。沒有找到文件但也沒有文件明示不支援時記錄 capability=`NOT_DOCUMENTED`／`NOT_ENABLED`，不得冒稱 `UNSUPPORTED`，不推算、不用非官方內部端點。
- [ ] 471.2 若有合格來源，確認每 profile 真的回傳來源日期與指標值；確認既有 `stock_technical_indicator` writer 能保留其真實 provider/profile/date/payload 且 immutable。若 schema 不能保存真實來源識別，實作並驗證最小 Liquibase/writer 擴充後才寫入該來源 facts；不偽裝成 FUBON provider，不新增衍生計算。若 471.1 找不到合格來源，則不新增 schema 或 writer。
- [ ] 471.3 實作管理者受保護的頁面專屬 BFF 及 async job。啟動需通過 admin authorization、明確 feature flag、來源相符 readiness、internal token 與配額 gate；Fubon enablement 僅用於富邦來源。一次僅一個 active job。job scope 凍結最多 30 個交易雷達台股代碼，從最近已完成日期起分段向前查詢，不設十年目標或上限，只寫直接來源成功回應的日期。Fubon 每標的最多 35 個 420 日視窗、每視窗最多 17 個核准 profile request，最多 17,850 個 Fubon SDK profile request；此數字不外推給交易所或 Yahoo。每個其他來源 adapter 啟用前必須另訂有限 request/window/time budget。全 job 總期限 24 小時。若先遇兩個連續、符合成功 schema 的全 profile 空窗亦可停止；此種停止、用盡預算或期限都須以 `HISTORY_DEPTH_UNKNOWN`／partial 結束並列出已查範圍。單一來源遇 429、quota、transport/HTTP 或 schema/identity failure 時保存該來源的真實失敗分類並停止該來源，若下一層有已核准 adapter 則繼續 fallback；不得把失敗當成 NO_DATA。persistence failure 與 job-wide deadline 停止全 job。狀態須可輪詢、短期保留、同一 job 可安全續跑；HTTP/BFF 不得直接對外行情 host 發送請求。
- [ ] 471.4 加入「補齊技術指標資料」button，顯示 queued/running/partial/completed/failed、處理進度、symbol/profile/source/date coverage 及 NO_DATA／SOURCE_UNAVAILABLE／UNSUPPORTED／HISTORY_DEPTH_UNKNOWN／FAILED 分類（定義依 Requirement 172）；不能以部分成功顯示全部完成。來源查詢依富邦 → 交易所 → Yahoo 優先序逐筆 fallback，只保存第一個直接提供且經驗證的來源事實，來源與來源的錯誤狀態分別顯示。未確認有可用的直接指標 API 時清楚顯示目前沒有可用來源。
- [ ] 471.5 保持零 live Redis、雷達快照、帳戶及交易副作用；禁止使用 candles、本地演算法或非核准供應商資料填補。資料寫入僅使用既有 immutable facts 與 idempotency；重複啟動需拒絕或回傳相同 active job。
- [ ] 471.6 修正按鈕啟動與狀態輪詢對本頁 Axios 已解包 response data 的讀取方式；已接受且包含 `jobId` 的 job 必須進入狀態顯示與輪詢，不能被誤判為缺少 `jobId`。
- [ ] 471.7 每次富邦 outbound invocation 最多寫一筆 `FUBON_API`／「技術指標查詢」錯誤記錄。`message_header` 的 profile failure 格式為 `operation=FUBON_TECHNICAL_INDICATORS_READ symbol=<symbol> queryFrom=<date> queryTo=<date> profileFailures=[<profileId>:<reasonCode>,...]`；多 profile 失敗依固定 17 個 `TECHNICAL_PROFILES` manifest 順序合併一列。HTTP/transport/timeout/schema failure 發生於 profile response 前時用 `profileFailures=[] invocationFailure=<allowlistedReason> [httpStatus=<status>]`。profile allowlist 為 `sma_d_5,sma_d_10,sma_d_20,sma_d_60,sma_d_240,rsi_d_5,rsi_d_10,kdj_d_9_3_3,macd_d_12_26_9,bb_d_20,sma_w_5,sma_w_10,sma_w_20,rsi_w_5,rsi_w_10,kdj_w_9_3_3,macd_w_12_26_9`；reason allowlist 為 `RATE_LIMITED,HISTORY_BUDGET_EXHAUSTED,UPSTREAM_UNAVAILABLE,TECHNICAL_SCHEMA_INVALID`。cancel/stop 與 `InterruptedException` 遵守 Requirement 141/5156，不寫錯誤記錄。不得只記 `FAILED_OUTCOME`，亦不得記錄 token、credentials、request／response body；完整固定 manifest 失敗清單不得截斷。即使後續來源成功補到相同日期，錯誤記錄仍保留。覆蓋單一 profile 失敗、多 profile 不同原因、invocation-level failure、每次 outbound 最多一列及敏感資訊零洩漏。

## 驗證

**來源盤點：**保留官方 API 文件與實際 response schema 證據；若沒有核准來源，驗證按鈕回報來源不可用且 DB facts 無變化。若有，對單一標的／profile 做受控唯讀 probe，按官方 schema 驗證日期與值後才擴大回補。

**程式驗證：**`bash scripts/spec-check.sh`、`git diff --check`、相關 backend/BFF/external/frontend 測試與建置；不得為驗證而呼叫真實下單或帳戶 API。

**執行驗收：**依 `.claude/skills/run-stack/SKILL.md` 從 feature worktree rebuild/recreate 受影響服務，在登入管理者的雷達頁檢視按鈕、啟動／進度／終態與 coverage；以唯讀 SQL 比對 source date、provider、profile、hash 及 idempotency，並確認 Redis／雷達快照無變化。正式批次只可在官方來源資格及單標 probe 已通過後執行，報告不得把來源未提供日期列為已補齊。
