# [t371] 富邦庫存同步未實際可寫入時，手動快照 PUT 必須確實寫入富邦列

> **現況覆寫（Requirement 138／Task 414）：** `TW LIVE=true` 不再表示 inventory 沒有 writer。此 task 的 ownership matrix 中，合格的 `READY && inventory-sync-enabled` target 不論 LIVE flag 都是 `SOURCE_OWNED`；`INVENTORY_SYNC_CAPACITY_CONFLICT` 已移除，不能再用來決定 `PAYLOAD_OWNED`。

**對應 Requirements:** Requirement 90（Task 371 補充：僅「同步 capability 可寫入 + active configured-admin 的同一 owner-latest target + 本次 PUT 最終日期為台北今日」將富邦台股列視為 source-owned；其餘組態均 payload-owned）
**前置任務:** t352
**Liquibase changeset:** 無（只修正既有 `asset_snapshot`／`stock_holding` 的更新歸屬判斷，不新增或修改 schema）

## 背景

使用者在 `SnapshotFormView` 編輯「富邦證券」台股持股（例如 `00865B` 的交易日期、股數、持股成本），按「存檔」後 UI 顯示更新成功，但重新讀取資料庫仍是舊值。根因已確認在 `AssetService.updateSnapshot()`：它已正確先取得 snapshot 的 pessimistic row lock，卻在 lock 後**無條件**把 `(market='台股', broker.code='fubon')` 當作 server-owned scope 保留，並略過 request 內同 scope 的列。這個規則原本是為了避免正在執行的富邦庫存同步被較舊的完整表單 payload 蓋回；但目前實際富邦 inventory sync log 顯示 `DISABLED`，沒有同步 writer 會接管該列，於是手動列被靜默丟棄而 API 仍回成功。

「是否由同步來源擁有富邦台股 scope」不能再是永遠為真，也不能只看 `FubonConfigState.READY`：只有同步實際有能力寫入，且本次 lock 後更新的 target 正是 Fubon sync 會寫入的 active configured-admin latest snapshot，才可保留 source-owned。`AssetService` 也不能 import 或直接依賴 `integration.fubon` 的 concrete class。完整快照 PUT 的既有 HTTP method、DTO、BFF route、前端欄位與 aggregate 算法均是正確契約；本任務只把 scope ownership 改為依 config state、feature capability 與 immutable update target 的明確、可測試決策。

## 要做什麼

- [ ] **371.1 建立 service-side ownership port，讓核心 service 只依抽象。** 在 `com.steven.assets.service` 建立一個明確表示「本次完整 snapshot update 各 stock scope 的擁有者」的 port 與不可變 decision（名稱可等價，例如 `SnapshotStockScopeOwnershipPort` 與 `SnapshotStockScopeOwnership`）。port 必須先接收 lock 後建立的 immutable `SnapshotUpdateTarget(snapshotId, ownerUserId, effectiveSnapshotDate)`，再對 `(market, brokerCode)` 回答 `PAYLOAD_OWNED` 或 `SOURCE_OWNED`；其決策 snapshot 在一次 `updateSnapshot` transaction 中只能取得一次，後續每列一律使用同一個不可變結果。`AssetService` 只注入／使用這個 service-side abstraction，**不得** import、注入、`instanceof`、反射或以字串 class name 依賴 `FubonConfigState`、`FubonInventoryWriter` 或任何 `integration.fubon` concrete class。

- [ ] **371.2 由 Fubon integration 實作 capability-and-target-to-ownership 映射。** Fubon integration 的 adapter 實作 371.1 port；在 371.3 row lock、tenant／owner、unique gate 與 final effective date 都完成後被 port 呼叫時，adapter 是唯一一次讀取 `FubonConfigState.snapshot().state()` 與 `FUBON_INVENTORY_SYNC_ENABLED` effective value，並以 `SnapshotUpdateTarget` 與 `FubonInventorySyncService` preflight／`FubonInventoryWriter` **完全相同**的 owner-latest target predicate 產生 transaction-scoped immutable decision 的位置。不得讀取、注入或把 `FUBON_TW_LIVE_QUOTES_ENABLED` 保留為 adapter 欄位／建構子參數；LIVE 不進 decision。映射必須精確如下：

  | scope／state／effective flags／immutable target | ownership |
  | --- | --- |
  | `market='台股'` 且 `broker.code='fubon'`，`READY` + inventory sync=true + TW LIVE 任意，owner 為 active configured admin、`snapshotId` 為同一 owner-latest target、final effectiveSnapshotDate=Asia/Taipei 今日 | `SOURCE_OWNED` |
  | `market='台股'` 且 `broker.code='fubon'`，`DISABLED` 或 `MISCONFIGURED`（任意 flags／target） | `PAYLOAD_OWNED` |
  | `market='台股'` 且 `broker.code='fubon'`，`READY` + inventory sync=false（任意 TW LIVE／target） | `PAYLOAD_OWNED` |
  | `market='台股'` 且 `broker.code='fubon'`，capability flags 可用但 target 是 historical、owner 非 configured admin、`snapshotId` 非同一 owner-latest target，或 final effectiveSnapshotDate 非今日 | `PAYLOAD_OWNED` |
  | 其他 market／broker | `PAYLOAD_OWNED` |

  tracked default `FUBON_ENABLED=true`／`FUBON_TW_LIVE_QUOTES_ENABLED=true`／`FUBON_INVENTORY_SYNC_ENABLED=false` 屬第三列，不得保留 source rows。`DISABLED`、`MISCONFIGURED`、sync disabled 與任何 target 不合格都代表此完整手動 PUT 不可假定會有可用的富邦同步來源；不得嘗試補呼叫 SDK／internal inventory endpoint，也不得把 config read／capacity／target 判定失敗轉為「保守地忽略使用者輸入」。同一 transaction 不得重讀 state、flag 或 target eligibility，且 latest comparison 必須使用 final effectiveSnapshotDate，不得偷看變更前日期。

- [ ] **371.3 維持 lock-first，依 capability-and-target ownership 正確重建完整 PUT。** `AssetService.updateSnapshot()` 的第一個 **DB operation** 必須仍是既有 `AssetSnapshotMutationLock.lockById(id)` 對 `asset_snapshot` 的 `PESSIMISTIC_WRITE`；不得在它之前讀 child、broker、reference data 或用普通 `findById`。lock 成功後必須先完成 tenant／owner 驗證、snapshot-date unique gate 與 request 所決定的 final effectiveSnapshotDate 必要驗證／設定；以 `snapshotId`、ownerUserId、**final effectiveSnapshotDate** 建立 371.1 target，才經 port 取得 371.2 的一次性 decision。port decision 後才可解析／讀取 broker、children、reference data 並重建；同一 transaction 不得重新讀 state、flag 或 target eligibility，也不得用變更前日期比較 latest target。既有 deposits／funds 的完整 PUT 語意、stock master upsert 與 transaction rollback 語意不變。

  - **`PAYLOAD_OWNED`（沒有實際同步 writer 或非實際 target）**：`DISABLED`、`MISCONFIGURED`、`READY` + inventory sync disabled、historical target、non-configured-admin target、non-latest target，或 request 把原同步 target 改為非今日 final effectiveSnapshotDate 的富邦台股列，都必須和任一其他 stock row 一樣由完整 request 擁有。先移除 locked snapshot 的舊列，再把 request 中的富邦台股列 materialize 到 managed `snapshot.getStocks()`；`shares`、`investmentCost`、`currentValue`、`transactionType`、`transactionDate`、`transactionExchangeRate`、currency、dividend 欄位與 display order 都沿既有 full-update 規則寫入。若 full payload 未帶某一舊富邦列，該列照既有完整 replace 語意移除，不得偷偷保留。
  - **`SOURCE_OWNED`（唯一完整實際 target）**：只有 `READY && inventory-sync-enabled`（TW LIVE flag 任意），且 lock 後 target owner 是 `UserAdminService.configuredAdmin()` 的 ACTIVE configured admin、target `snapshotId` 與 `FubonInventorySyncService` preflight／`FubonInventoryWriter` 同一 owner-latest target 相同、final effectiveSnapshotDate 是 Asia/Taipei 今日，才保留 lock 後資料庫中的富邦台股列，並忽略 request 內同 scope rows；不得讓 stale UI payload 覆寫 shares、cost、current value、交易欄位或任何其他 source row 欄位。非富邦 request rows 仍照既有完整 PUT 語意重建。

- [ ] **371.4 保留同步與完整更新的並行安全。** `FubonInventoryWriter` 的局部 replace 與完整 PUT 必須繼續透過同一個 `AssetSnapshotMutationLock`，並以 snapshot id 的同一把 PostgreSQL row lock 串列化。只有 capability、active configured-admin owner、同一 owner-latest target、final effectiveSnapshotDate=台北今日四項均成立的完整 SOURCE_OWNED target，不論先取得 lock 的是 Fubon replace 或完整 PUT，第二個 transaction 都要在 lock 釋放後讀到最新 managed scope，絕不可由較舊 UI payload 復活或覆寫 Fubon source row；其他 broker／market rows也不得遺失。PAYLOAD_OWNED 組態沒有此 source target，完整 PUT 必須照 371.3 持久化富邦 rows。不得以 JVM mutex、sleep、retry loop 或移除 pessimistic lock 取代資料庫鎖。

- [ ] **371.5 既有 aggregate 與外部契約不得擴張。** 所有成功 mutation（包括沒有實際 writer 的 `DISABLED`／`MISCONFIGURED`／sync disabled 組態，及 capability 雖可用但 target 不合格時手動寫入的富邦列）完成後，一律只呼叫既有單一 `SnapshotAggregateCalculator`／既有 aggregate path 重算 totals；禁止在 `AssetService` 或 Fubon adapter 複製一套 total formula。不得新增 DB table／column／Liquibase、API endpoint、BFF route、request／response DTO 欄位、前端變更、排程、broker order capability 或外部 HTTP call。富邦 adapter 保持唯讀；本任務不改完整實際 target 的 inventory sync 行為。

- [ ] **371.6 自動化測試須以真實持久化與雙 transaction 證明行為。** 至少新增／調整下列測試，不能只 assertion DTO 或 mock `save()`：

  1. 對 `DISABLED` 與 `MISCONFIGURED` 分別建立含 `00865B`／`台股`／富邦 broker 的快照，送出完整 manual PUT，改變 `shares`、`investmentCost`、`transactionType`、`transactionDate`（並可帶不同 current value）。對 `READY` + tracked default `FUBON_ENABLED=true`／`FUBON_TW_LIVE_QUOTES_ENABLED=true`／`FUBON_INVENTORY_SYNC_ENABLED=false` 重複同一案例。三種沒有實際 writer 的組態 transaction commit 後都必須 `flush`、清 persistence context，重新以 repository 查 DB；逐欄證明新值真的持久化，且 totals 等於 final children 由既有 calculator 重算的值。
  2. 對 capability flags 可用（`READY` + inventory sync=true + TW LIVE 任意）但 target 為 historical snapshot、target owner 非 `UserAdminService.configuredAdmin()` 的 active configured admin、以及原本完整同步 target 被 request 改為非台北今日 final effectiveSnapshotDate，分別送出富邦完整 manual PUT；三者都必須 flush、clear、repository DB readback 證明 `PAYLOAD_OWNED` 與 shares／investmentCost／transactionType／transactionDate 確實更新。最後一案必須特別證明 owner-latest 判斷使用 request 改後的 final date，而非 lock 前或變更前 snapshotDate。
  3. 對 `READY` + inventory sync=true + TW LIVE=true，且 target owner 為 active configured admin、`snapshotId` 等於 Fubon preflight／writer 的同一 owner-latest target、final effectiveSnapshotDate=Asia/Taipei 今日的 seed Fubon source row，送出帶較舊／不同上述欄位的完整 manual payload；DB readback 必須證明 Fubon row 完整保留，request 中的 stale Fubon row 沒有寫入；同一 payload 的 non-Fubon row 仍依完整 update 被更新，totals 正確。
  4. port／adapter unit tests 固定 `DISABLED`／`MISCONFIGURED`／`READY` state、兩個 flag 的完整組合，以及 `SnapshotUpdateTarget(snapshotId, ownerUserId, effectiveSnapshotDate)` 的 configured-admin／owner-latest／historical／final-date 變體，釘住 371.2 的 capability-and-target matrix、一次讀取／immutable decision，並驗 `AssetService` 的 constructor／imports 僅依 service port 而非 Fubon integration concrete type。
  5. 使用真 PostgreSQL（Testcontainers）與兩個獨立 transaction、latch，只在 3 的完整 SOURCE_OWNED target 覆蓋「Fubon replace 先 lock、full update 後到」及反向順序。第二個 transaction 必須確實 blocked 到第一個釋鎖；最終 Fubon source row、non-Fubon row 與 snapshot totals 均正確。不得以 H2、單一 persistence context 或 mocked repository 偽造這個並行證據。

## 驗證

在專案根目錄執行；Java 使用專案指定的 Java 21 與 Maven。先跑範圍測試確認 `DISABLED`、`MISCONFIGURED`、tracked default、LIVE=true 的合格／不合格 target、以及 flags 可用但三種 non-target（historical／non-configured-admin／PUT 改為非今日日期）與唯一完整 SOURCE_OWNED target 的 ownership、資料庫 readback 與 lock race，再跑完整 backend suite。沒有真實富邦 credentials 時，所有驗證都使用 Fubon config/flag/target fixture／Testcontainers，不得為了測試啟用 SDK 或寫入真實券商帳戶。

```bash
bash scripts/spec-check.sh

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml \
  -Dtest=AssetServiceTest,FubonSnapshotLockPostgresTest test

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test

docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management restart bff
docker inspect asset-business-services asset-bff \
  --format '{{.Name}} image={{.Image}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
docker exec asset-business-services wget -qO- http://127.0.0.1:8080/actuator/health
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
```

驗收紀錄必須列出：`DISABLED`、`MISCONFIGURED`、tracked default、LIVE=true 但 target 不合格、flags 可用但 historical target、non-configured-admin target、PUT 改為非今日 final date 的 payload-owned DB readback；LIVE=true 且完整合格 SOURCE_OWNED target（capability + configured admin + same owner-latest + final date today）的 stale-payload 保護；兩種 lock ordering 的「第二 transaction 等待」證據；以及 final non-Fubon rows／snapshot totals。確認 `AssetService` 對 Fubon integration 沒有直接 import 或 concrete injection，且 docker 重建後 business-services／BFF 均健康。不得把「HTTP 回 200」或 mock interaction 當作 DB 寫入成功的唯一證據。

## 完成報告

（實作者完成後回填：實際修改檔案、port 與 Fubon adapter 的最終名稱、DISABLED／MISCONFIGURED／tracked default／LIVE=true 合格或不合格 target／historical target／non-configured-admin target／PUT 改非今日 final date／完整 SOURCE_OWNED target 的各項測試與 DB readback 結果、PostgreSQL 兩種 lock ordering 證據、Maven／Docker 輸出，以及與本規格任何偏差及原因。）
