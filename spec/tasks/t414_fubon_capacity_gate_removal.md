# [t414] 移除富邦 LIVE 與帳務同步的靜態互斥閘門

**對應 Requirements:** Requirement 138（LIVE、庫存、成交與銀行餘額同步可同時啟用，依真實 SDK 資源仲裁）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

FubonInventorySyncService 與 FubonTradeSyncService 目前在
FUBON_TW_LIVE_QUOTES_ENABLED=true 時直接回傳
INVENTORY_SYNC_CAPACITY_CONFLICT／TRADE_SYNC_CAPACITY_CONFLICT，完全不呼叫 broker adapter。
這是設定層永久拒絕，不是當下資源已滿。

真正資源在 Python SdkGateway：blocking native SDK call 共用 semaphore，帳務另有
_accounting_lock，報價仍有 240 calls/min 限額。Task 401 的控制契約仍有效：
每個 query 以一個 monotonic absolute deadline 經過 queue、session、login、retry 與
native dispatch，timeout/cancel 不得提早釋放仍在跑的 native slot。本任務將 slot 上限
由四個改為五個，並使 quote 路徑真正遵守這個契約。

移除 inventory 的 LIVE gate 也使其可以實際寫入 source row，因此完整手動快照 PUT 的
ownership 不能再把 LIVE=true 當作 PAYLOAD_OWNED；否則 stale payload 會覆寫
已可運作的同步來源。

## 要做什麼

- [ ] 414.1 從 FubonInventorySyncService、FubonTradeSyncService 移除只為 LIVE
  互斥存在的 liveQuotesEnabled 欄位、建構子參數與短路，並移除
  FubonOutcome.INVENTORY_SYNC_CAPACITY_CONFLICT、
  FubonTradeOutcome.TRADE_SYNC_CAPACITY_CONFLICT、其 reason mapping、fixture 與全部
  引用。自身 sync flag=false、DISABLED、MISCONFIGURED、calendar/owner gate 的 fail-closed
  語意不變；兩個 sync flag 與 LIVE 同為 true 時必繼續呼叫既有唯讀 adapter。
- [ ] 414.2 從 FubonSnapshotStockScopeOwnershipAdapter 移除只為舊互斥而存在的
  twLiveQuotesEnabled 欄位／建構子參數與判斷。當
  READY && inventory-sync-enabled、active configured-admin、同一 owner-latest snapshot
  和最終 effective date=Asia/Taipei 今日都成立時，不論 LIVE flag 都必為
  SOURCE_OWNED，完整 PUT 必保留 locked Fubon source rows；所有非合格 target 維持
  PAYLOAD_OWNED。更新 adapter 與完整 PUT 的 lock-race 測試，明確證明 LIVE=true 的
  合格 target 不會被 stale payload 覆寫。
- [ ] 414.3 在 SdkGateway 將 MAX_BLOCKING_CALLS 從 4 改為 5；保留
  _accounting_lock、240 calls/min quota、native thread 在 finally 才釋 slot 的行為。
  同步更新仍寫 MAX_BLOCKING_CALLS=4 的程式註解或測試說明，但不得調整
  EtfHoldingsService.MAX_CONCURRENCY。
- [ ] 414.4 以可傳遞的 monotonic absolute deadline 統一 SdkGateway 本次受影響的
  read chain。_run_bounded() 的 slot acquisition 最多等到 deadline 剩餘時間；取得後
  native worker 最多執行 min(既有每次 native call 上限, 剩餘 deadline)。到 deadline
  尚未取得 slot 時回 SDK_CALL_SATURATED；已持有 slot 的 native call 逾時仍由 worker
  finally 才釋放。QuoteService.read() 入口建立既有 ENDPOINT_TIMEOUT_SECONDS=30.0 的
  deadline；read_accounting_pair、read_filled_trades、read_bank_balance、read_settlement
  與 read_realized_gains 各在其 public reader 入口建立同為 30 秒、或接受上游傳入且更短
  的 deadline。它們經過 accounting lock、session lock、login、realtime init、accounting
  budget、auth-invalid retry／cleanup 及 SDK dispatch 時，都只能使用同一剩餘時間；取得
  lock／quota 後、真正 native invoke 前必再檢查 deadline。session/accounting lock 等到
  deadline 時以既有對應的 sanitized timeout reason 失敗，不得無界阻塞。既有
  login/init/quote/accounting 每次 native call 上限 5 秒、cleanup 2 秒維持不變，且不得
  在 queue、lock、login、retry 或 dispatch 後重設完整 timeout。
- [ ] 414.5 將 quote deadline 接入全鏈：傳入所有 _read_one／_fetch_one、
  asyncio.to_thread(SdkGateway.quote)、SdkGateway.quote、session/login/init 和最多一次
  auth retry。移除或改造獨立 PER_CALL_TIMEOUT_SECONDS 的 asyncio.wait_for，不得讓
  5／11 秒的內層計時器早於共同 deadline 把 queue 或 retry 誤判為 QUOTE_TIMEOUT。
  deadline／caller cancel 後，未 dispatch 的 shared flight 可確認不會晚啟動後才移除；
  已 dispatch 的 native call 必使同一 (purpose, stockCode) admitted key 與 native slot
  一直保留到 worker finally。先收到 failure 的 waiter 可拿 sanitized failure，但後來相同
  key 的 request 只能共用該安全 failure、不得另起並行 native call。需用 completion
  signal 或等價背景收尾保證這個生命週期，不能由取消 _read_one 的 finally 無條件 pop。
  Java 端既有 30 秒 transport timeout 不變。
- [ ] 414.6 quote 的 240/min quota／429 circuit 只可在 session、slot 與 deadline 全部
  通過、真正 native quote method 即將 invoke 的唯一 gate 記帳；slot／lock 排隊後到期、
  被取消或 circuit 已由另一工作開啟的 request 零 SDK dispatch 且零 quota debit。最多一次
  auth retry 的實際 native quote 仍各記一次；保留既有 240/min 上限與 60 秒、有效
  Retry-After 最多 600 秒的 circuit 邊界。
- [ ] 414.7 新增或調整隔離 Python tests，不用真人 SDK 或脆弱 sleep，覆蓋：slot 在
  deadline 前釋放後接手、slot 到 deadline 仍未釋放、native timeout 不提早釋 slot、
  auth-invalid 後 session 重建／retry 仍使用同一遞減 deadline，及 accounting reader 的
  session/accounting lock 期限。以 Event/latch 讓已 dispatch native call 卡住、使第一個
  quote caller deadline/cancel 後再讀相同 key，證明直到 worker 真結束都不會第二次
  dispatch。另覆蓋排隊到期零 quota debit，和等待時另一 call 開啟 circuit 後零 dispatch。
  後端測試須改寫原 capacity-conflict 案例為 adapter 確有互動的正面案例，且保留
  DISABLED/MISCONFIGURED 防線。
- [ ] 414.8 將 FubonBankBalanceSyncScheduler 的 cron 09:30→09:20、
  14:00→14:20；08:00、22:00 不變。新時間不得與既有 trade :00/:30 或 inventory
  :05/:35 重疊，並同步更新 scheduler 精確 cron 測試。
- [ ] 414.9 同步更新所有公開展示此排程的 BFF 文案：
  SchedulePublicBffController.JOBS 與 FubonApiInfoBffController 的
  「富邦交割銀行餘額同步」必顯示
  每日 08:00／09:20／14:20／22:00 及
  0 0 8 * * * / 0 20 9 * * * / 0 20 14 * * * / 0 0 22 * * *，並補齊兩者的測試。
- [ ] 414.10 不改 .env、feature flag 名稱或 FubonConfigState 三態；不新增 DB 表、
  Liquibase、公開 API、下單、改單、撤單、圈存、轉帳或其他金融副作用。不得手動呼叫
  富邦 broker endpoint；Docker 驗證只重建受影響服務並確認健康與部署內容。

## 驗證

~~~bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest=FubonInventorySyncServiceTest,FubonTradeSyncServiceTest,FubonSnapshotStockScopeOwnershipAdapterTest,FubonSnapshotLockPostgresTest,FubonBankBalanceSyncSchedulerTest -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -Dtest=SchedulePublicBffControllerTest,FubonApiInfoBffControllerTest -DextraArgLine=-Dnet.bytebuddy.experimental=true
(cd fubon-broker-service && PYTHONPATH=src python -m pytest -q)
! rg -n 'INVENTORY_SYNC_CAPACITY_CONFLICT|TRADE_SYNC_CAPACITY_CONFLICT' backend/src/main backend/src/test
~~~

重新建置並重建實際受影響服務；不得輸出 token、帳戶或秘密。

~~~bash
docker compose -p asset-management build --no-cache business-services fubon-broker-service bff
docker compose -p asset-management up -d --no-deps --force-recreate business-services fubon-broker-service bff
docker compose -p asset-management ps --format json
for svc in business-services fubon-broker-service bff; do
  cid="$(docker compose -p asset-management ps -q "$svc")"
  test -n "$cid"
  docker inspect --format '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}} {{.Image}}' "$cid"
done
~~~

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
