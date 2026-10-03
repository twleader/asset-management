# [t471] 交易雷達啟動官方技術指標歷史回補

**對應 Requirements:** Requirement 173（雷達管理者啟動核准官方技術指標歷史回補）  
**前置任務:** t470  
**Liquibase changeset:** 先盤點既有 schema；無必要變更則無

## 背景

今日交易雷達頁需要一個管理者操作入口，啟動技術指標來源資料回補並顯示逐標的／profile 結果。既有 `FubonTechnicalIndicatorSyncService` 使用 `fubon-broker-service` 的固定技術 profile 路徑與有限近期視窗，不能視為富邦證券或交易所直供、也不能作為十年歷史支援證據。不得把 K 線資料自行計算成技術指標；其他供應商（含 Fugle）在取得使用者明確確認前屬範圍外。

## 要做什麼

- [ ] 471.1 先核對富邦證券、TWSE、TPEx 直接提供的 technical-history API；列出 host、官方文件、固定 profile、date window、成功／空集合／錯誤 schema、歷史深度與配額。若沒有可證實的來源，停止其 runner 實作，只交付來源缺口證據，按鈕須明確顯示無可用官方來源，不得改用 Fugle 或推算。
- [ ] 471.2 若有合格來源，確認每 profile 真的回傳來源日期與指標值；確認既有 `stock_technical_indicator` writer 能保留其真實 provider/profile/date/payload 且 immutable。若 schema 不能保存真實來源識別，實作並驗證最小 Liquibase/writer 擴充後才寫入該來源 facts；不偽裝成 FUBON provider，不新增衍生計算。若 471.1 找不到合格來源，則不新增 schema 或 writer。
- [ ] 471.3 實作管理者受保護的頁面專屬 BFF 及 async job。啟動需通過 admin authorization、明確 feature flag、來源相符 readiness、internal token 與配額 gate；Fubon enablement 僅用於富邦來源。一次僅一個 active job。job scope 凍結最多 30 個交易雷達台股代碼，從最近已完成日期起分段向前查詢，不設十年目標或上限，只寫官方成功回應的日期。每標的最多 35 個 420 日視窗，視窗內回傳全部核准 profile；最多 17,850 個 profile-level SDK request，總期限 24 小時。若先遇兩個連續、符合成功 schema 的全 profile 空窗亦可停止；此種停止、用盡預算或期限都須以 `HISTORY_DEPTH_UNKNOWN`／partial 結束並列出已查範圍。429／配額不足／transport 或解析錯誤保留對應 FAILED 或來源不可用分類並停止，不得當成空資料。狀態須可輪詢、短期保留、同一 job 可安全續跑；HTTP/BFF 不得直接對外行情 host 發送請求。
- [ ] 471.4 加入「補齊技術指標資料」button，顯示 queued/running/partial/completed/failed、處理進度、symbol/profile 官方日期 coverage 及 NO_DATA／SOURCE_UNAVAILABLE／UNSUPPORTED／HISTORY_DEPTH_UNKNOWN／FAILED 分類（定義依 Requirement 172）；不能以部分成功顯示全部完成。未確認來源時清楚顯示目前沒有可用的核准官方指標來源。
- [ ] 471.5 保持零 live Redis、雷達快照、帳戶及交易副作用；禁止使用 candles、本地演算法或非核准供應商資料填補。資料寫入僅使用既有 immutable facts 與 idempotency；重複啟動需拒絕或回傳相同 active job。

## 驗證

**來源盤點：**保留官方 API 文件與實際 response schema 證據；若沒有核准來源，驗證按鈕回報來源不可用且 DB facts 無變化。若有，對單一標的／profile 做受控唯讀 probe，按官方 schema 驗證日期與值後才擴大回補。

**程式驗證：**`bash scripts/spec-check.sh`、`git diff --check`、相關 backend/BFF/external/frontend 測試與建置；不得為驗證而呼叫真實下單或帳戶 API。

**執行驗收：**依 `.claude/skills/run-stack/SKILL.md` 從 feature worktree rebuild/recreate 受影響服務，在登入管理者的雷達頁檢視按鈕、啟動／進度／終態與 coverage；以唯讀 SQL 比對 source date、provider、profile、hash 及 idempotency，並確認 Redis／雷達快照無變化。正式批次只可在官方來源資格及單標 probe 已通過後執行，報告不得把來源未提供日期列為已補齊。
