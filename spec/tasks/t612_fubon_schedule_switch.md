# [t612] 富邦唯讀排程 API 執行開關

**對應 Requirements:** Requirement218
**前置任務:** t386（富邦證 API 頁）、t469 系列富邦同步排程
**Liquibase changeset:** v1.171.0-fubon-schedule-switch.sql

## 背景
富邦官方委託擷取（`FubonSrppSourceScheduler`）每個台股交易分鐘呼叫 `POST /internal/orders/read`，使用者並不需要，且自 11/02 起從未成功。使用者要在「富邦證 API」頁自行決定每支排程是否執行。現有七支 business-services 排程只受環境變數閘控制，無法即時開關。

## 要做什麼
1. **Liquibase v1.171.0-fubon-schedule-switch.sql：** 建 `fubon_schedule_switch(schedule_key varchar(64) primary key, enabled boolean not null, updated_at timestamptz)`；seed `inventory`、`bank-balance`、`settlement`、`realized-gain`、`trade`、`etf-holdings` 為 true，`srpp-order-source` 為 false；以 `ON CONFLICT DO NOTHING` 保持冪等；加入 `db.changelog-master.yaml`。
2. **Backend：** `model/FubonScheduleSwitch`、`repository/FubonScheduleSwitchRepository`、`service/FubonScheduleSwitchService`（`state()` 回 ON／OFF／MISSING／UNREADABLE、`isEnabled` 為薄包裝，只有 ON 放行；`list`；`setEnabled`：未知 key 擲 NotFound→404，寫入 `updated_at`）、註解 `@FubonScheduleSwitched("key")` 與 `FubonScheduleSwitchAspect`（`@Around`，非 ON 時略過方法且不呼叫富邦：OFF 記 DEBUG，MISSING／UNREADABLE 記 WARN）。註解加到八個 `@Scheduled` 方法（七個 scheduler 類別，ETF 早晚兩個方法共用同一 key）：inventory→`FubonInventorySyncScheduler`、bank-balance→`FubonBankBalanceSyncScheduler`、settlement→`FubonSettlementSyncScheduler`、realized-gain→`FubonRealizedGainSyncScheduler`、trade→`FubonTradeSyncScheduler`、etf-holdings→`FubonEtfHoldingsSyncScheduler`（兩個 @Scheduled 方法皆加）、srpp-order-source→`FubonSrppSourceScheduler.observe`。不改各排程建構子；手動同步端點不受影響。
3. **還原 cron：** `FubonSrppSourceScheduler` 的 `@Scheduled(cron)` 還原為 `${fubon.srpp-source-cron:0 * * * * *}`，測試 `cronDefaultAndZoneMatchCatalog` 還原；是否執行由 `srpp-order-source` 開關決定（預設關）。
4. **Controller：** `GET /api/settings/fubon-schedule-switches`（已登入者）回 `[{scheduleKey, enabled, updatedAt}]`；`PUT /api/settings/fubon-schedule-switches/{scheduleKey}` body `{enabled:boolean}`（business 以 `Map` 接 body 並要求 `enabled` 為 JSON boolean，缺少、null、字串、數字、陣列、物件、非物件或無法解析的 body 一律 400——無效值拋 `IllegalArgumentException`（既有 `GlobalExceptionHandler` 映射 400；不可用 `ResponseStatusException`，會被全域 `Exception` 兜底轉 500），解析失敗由 controller 區域 `@ExceptionHandler(HttpMessageNotReadableException)` 直接回 400 `ProblemDetail`；BFF 同樣在轉送前回 400，皆不得解讀為 false），限 ADMIN（`CurrentUserContext.isAdmin()`，否則 `AdminRequiredException`），未知 key 404，回更新後列。
5. **BFF：** `FubonApiInfoDto` 新增 `scheduleKey`（無則 null）；`FubonApiInfoBffController` 依 sdkReference 對照：`sdk.accounting.inventories`、`sdk.accounting.unrealized_gains_and_loses`→`inventory`；`sdk.accounting.bank_remain`→`bank-balance`；`sdk.accounting.query_settlement`→`settlement`；`sdk.accounting.realized_gains_and_loses`→`realized-gain`；`sdk.stock.filled_history`→`trade`；`marketdata.rest_client.stock.ownership.etf_holdings`→`etf-holdings`；`sdk.stock.get_order_results`→`srpp-order-source`。`get_order_results` 改為已串接（`POST /internal/orders/read`、Cron `0 * * * * *`（程式再限制台北 09:00–14:00 交易日）、consumer 說明為 SRPP 官方委託來源擷取排程，預設關閉），同步更新 `FubonApiInfoBffControllerTest` 的已串接清單與計數。新增 `FubonScheduleSwitchBffController`（`/api/bff/fubon-api/schedule-switches`，GET 與 `PUT /{scheduleKey}` 以 WebClient 轉呼 business）；寫入限 ADMIN 沿 BFF SecurityConfig。不增 9090／Tailscale。
6. **Frontend：** `api/index.js` 的 `fubonApi` 增 `scheduleSwitches.get()`、`.put(key, enabled)`；`FubonApiView.vue` 加「排程開關」欄：有 `scheduleKey` 的列顯示 `el-switch`（非管理者 disabled），切換成功才更新、失敗還原並顯示錯誤；無則顯示「－」。marketdata 行情／技術指標／配息等 external-materials 驅動排程本期不納入，顯示「－」。
7. **不得：** 新增任何下單或券商寫入呼叫；改變手動同步端點；改動環境變數閘。

## 驗證
- 後端單元測試：`FubonScheduleSwitchServiceTest`（查無列／例外→false、未知 key 擲例外）、`FubonScheduleSwitchAspectTest`（關閉時不執行目標、開啟時執行、例外 fail-closed）、`FubonScheduleSwitchControllerTest`（非 ADMIN 擋、更新回傳，以載入正式 `GlobalExceptionHandler` 的 standalone MockMvc 驗證缺值／null／"true"／"false"／0／1／陣列／物件／非 JSON 皆 400 且不呼叫 `setEnabled`，合法 true／false 原值轉送；404 與 403 的 HTTP 映射由既有 `GlobalExceptionHandler` 負責）、`FubonSrppSourceSchedulerTest`（cron 還原）；既有七個 scheduler 類別的測試維持綠燈。
- Postgres 測試或 Liquibase 冪等檢查：重跑 changeset 不重複列、不覆蓋使用者已改的 enabled。
- BFF：`FubonApiInfoBffControllerTest` 擴充 scheduleKey 對照與 get_order_results 已串接；`FubonScheduleSwitchBffControllerTest`。前端 build 與純函式測試。
- 驗收：重建 business-services、bff、frontend 後，頁面開關可切換並即時生效；`srpp-order-source` 關閉時 `fubon_srpp_source_snapshot` 不再新增列；開啟後恢復每分鐘寫入。部署後依規範以 pg_dump 重產 `db/schema.sql`。
- 先 `scripts/spec-check.sh` 與 `scripts/codex-review.sh` spec，再實作，最後 arch 審查。

## 完成報告
2026-11-19：spec 第 1、2 輪 Codex 審查的 major／minor 皆已修（BFF 與 business 對無效 enabled 一律 400、計數改七個開關／七類別／八方法、日誌層級分 OFF=DEBUG 與 MISSING／UNREADABLE=WARN）；第 3 輪 major（`ResponseStatusException` 被全域兜底轉 500、測試漏載全域 advice）已改拋 `IllegalArgumentException` 並讓測試載入 `GlobalExceptionHandler`；arch 第 1 輪唯一 major（schema.sql 未重產）已於部署後重產（129 表、B10 PASS）。Backend 相關測試與 BFF 測試全綠；已重建 business-services／bff／frontend（compose 專案 `asset-management`），Liquibase v1.171.0 已套用，七列 seed 正確（`srpp-order-source` 為 false）。待觀察：下個台股交易日 09:00 起 `fubon_srpp_source_snapshot` 不應再新增列；於頁面開啟後應恢復每分鐘寫入。排程代理（Spring 實際攔截 `@Scheduled`）尚無整合測試，以上述隔日觀察為驗證。
