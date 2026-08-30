# 富邦整合階段性交接（2026-08-30）

## 本次停點

使用者因額度將盡，要求停止展開工作、保存後續事項，先 commit → no-ff merge → push。本次交付是 **Task401–406 規格草案與審查交接**，不是功能完成或啟用。

- 本階段未修改 production、tests、migration、Compose、`.env`、憑證或既有財務資料，未重新部署服務。
- 六份新 task 共61個 checkbox 均未完成。兩位獨立 reviewer 已審第一輪；修正後仍須第二輪審查，**未記錄 spec-review-pass，不得直接開始實作**。
- API 目錄目前仍為52筆／15已串接／37未串接；來源報表完整實作及驗收後才可改成17已串接。排程64筆（business27／external37）不變。
- 原 Task394／395 的資產在途款與 realized_gain 入帳仍未完成。新的來源報表不等於完成舊財務入帳；不得提前刪除原 Claude branches。

## 使用者已確認的範圍

1. 富邦官方是可靠來源；個人資料只寫入 `tw.leader@gmail.com`。專用 `FUBON_SYNC_OWNER_EMAIL` 不得 fallback 至其他管理者，也不得建立或升級帳戶。
2. 寫入時專用 owner 必須是既存 ACTIVE ADMIN，並與 configured-admin 是同一人；preflight 與資料庫交易內都驗證。ADMIN_EMAIL 改成別人只能停止同步，不能轉移資料。
3. 原始交割與已實現損益可另存 typed 來源報表。券商未提供成本、淨收款、fillId 或完整未交割覆蓋時，不得補造、猜 ledger 對應或冒稱已計入資產。
4. 報表只允許專用 ACTIVE owner 本人讀取；BFF 必須比較 OIDC actual principal 與 effective tenant，拒絕代看，不能只相信 effective user。
5. 使用者已允許在本隔離 feature 修改重疊路徑；其他 worktree 一律保留，不 stash/reset/改寫。每個完整工作單元驗收後 commit、no-ff merge、push，最後從 main 重建驗證。
6. 不使用券商下單、改撤單、匯款或任何金融副作用功能。此輪不真人 SDK/login/query，不啟用 flags，不執行 manual sync/subscription/rescan POST，不重播曾拒絕的 route alias／method matrix／security probe。

## 接續順序

| 工作 | 範圍與接手方式 |
|---|---|
| [401](../../spec/tasks/t401_fubon_python_lifecycle_ports.md) | Python 窄 Protocol、安全 capture、共用 deadline、actual dispatch 配額、4個 native slot、100個 quote key、每 run 獨立 stream；同一實作者一起處理405／406的 Python 欄位。 |
| [402](../../spec/tasks/t402_fubon_market_ports_streams.md) | external ports／中立 DTO、有限 HTTP/SSE、commit 後 Redis 投影、O(1) index repair、technical codec。 |
| [403](../../spec/tasks/t403_fubon_catalog_quote_identity.md) | BFF 目錄篩選、Vue 過期請求取消、同股票五檔身分檢查、bridge LocalDate。 |
| [404](../../spec/tasks/t404_fubon_backend_io_ports.md) | business 市場 transport、readiness/calendar/name、ETF writer及 snapshot lock ports。 |
| [405](../../spec/tasks/t405_fubon_sync_owner_binding.md) | 專用 owner、fresh row lock、五種 batch 必填 strict boolean accountBindingExplicit。 |
| [406](../../spec/tasks/t406_fubon_accounting_source_reports.md) | 三張來源報表表、原子替換／遲到防護、本人 GET、獨立只讀頁面。 |

backend 的404→405→406須由同一實作者按序完成，避免共同 sync/writer/DTO 互相覆寫。Vue 依 CLAUDE.md 固定 Terra/high；其他 implementation 與 review agents 繼承主 agent。主 agent 不直接實作 production。

開始 code 前：完整讀 CLAUDE.md、六份 task及本交接；完成下節規格修正並重跑 spec-check，派**新的獨立** reviewer 進行第二輪，確認後才記錄 spec-review-pass。不要重用舊 Task399／400 的通過紀錄。

完成 code 後：全量隔離測試 → 完整 diff 獨立 arch review → feature Docker rebuild/recreate及 artifact／browser驗收 → commit/no-ff/push → main重建及相同證據。所有真實報表畫面測試須用隔離 fixture stack，不能往正式 owner DB 灌假資料。

## 第一輪審查與待處理事項

完整第一輪 diff 包含9檔及未追蹤 task全文，235899 bytes，SHA256：`acd52ef4919d13465a20c831fd6850917b7aff2680da4be3c88a24e43a8e2864`。

兩位reviewer合計critical0／major3／minor0。下列3項已依建議修正规格文字及可執行範例，**修正後尚未第二輪獨立審查**；實際程式仍未修正：

- **部署範例可能啟用 Fubon。** canonical main `.env` 的 FUBON_ENABLED 原值為 true，但執行中三個富邦服務皆為 false，來自前一輪 process override。所有 Docker build／recreate 範例必須明確帶 `FUBON_ENABLED=false`，不能只依 `--env-file` 或檢查舊容器。其餘 flags也須保留容器有效值；正式 `.env` 既有值不改。
- **來源觀測時點的競態。** 目前 Python observation()在 SDK accounting lock 釋放、normalization完成後才用 finished 產生 observedAt。舊回應若延後 normalization，可得到更新時間並覆蓋真正較新的回應。406須要求同一次 accounting capture 臨界區固定微秒時間，normalizer不可重新取代；以 latch測A舊回應晚完成、B新回應先存的順序。
- **owner鎖期限不能假稱已存在。** design曾寫依「既有短 writer 交易」限制，但 InventoryWriter.replace目前只有無 timeout 的 @Transactional。405須明定有界 writer／鎖等待期限及真PG timeout回滾測試，保留 snapshot row lock 為第一個 DB operation。

上述是規格／架構待修項，不代表已發生錯誤入帳或真人連線。此檔與修正後規格仍待第二輪獨立審查；不得宣稱規格或實作全部通過。

## 已有成果與本次驗證

- Task399／400 已於 `6aa219719c4d20d3ef405ba4b5cad75e1316df15` 合併：成交批次原子性、fresh snapshot／OSIV防護及官方 raw 日期修復。前輪1948 backend＋715 Python tests通過；不是本次重新執行的數量。
- 本 feature 已吸收 main `7b321eaf76780c3d0932e05d62639b11ae019070` 的「權限管理」選單變更。未來改 App.vue 必須保留這個新增群組。
- 本次未改動程式的基線測試：external **700 tests／115 suites**、BFF **242 tests／57 suites**，皆0 failures／errors／skips，Maven exit0。只能證明基線，不能當401–406實作驗收。
- 第一輪 `scripts/spec-check.sh` exit0、BLOCK0／CHECK0；13 public routes契約一致、schema與運行PG byte-identical、現況92表。406 migration預計新增3表，未執行，不能宣稱95表。
- 修正3項審查意見後，本階段再次執行 `scripts/spec-check.sh` 與 `git diff --check` 均exit0；spec-review status明確顯示「審查後 spec/ 又被改過，需重審」。此狀態刻意保留，下一輪不得拿機械檢查取代獨立審查。
- technical已交付的節流為90秒batch、2個symbol worker、每批最多20 attempts、共用3.1秒dispatch間距、KDJ→MACD→BB、shared history60/min、真429暫停、整輪30分鐘期限。不要誤說官方同API每分鐘只能查一次，也不承諾券商每次必回完整資料。

## Workspace、部署與保留項目

- feature：`/Users/steven/Project/asset-management-fubon-api-architecture-review`，branch `codex/fubon-api-architecture-review`。
- main：`/Users/steven/Project/asset-management-main`。root `/Users/steven/Project/asset-management` 是其他未完成工作，不修改它。
- canonical main `.env` 本次未改，SHA256 `dfcce5d98ad1a37ac397d0cee2abd5bbdc7ffd1621b4c8874bfd0725894007b5`。FUBON_SYNC_OWNER_EMAIL尚未新增；後續唯一授權環境編輯為此key，其他 OAuth、flags與secrets保留。
- Docker project為asset-management，canonical富邦secret根為main/secrets/fubon。部署前重查所有容器有效flag、mount及working_dir，不copy root或舊worktree的.env回main，不down、不刪volume。原.env的global true必須由部署process明確覆寫false；先驗生成設定，再驗重建後有效值。
- frontend最近由另一worktree重建；本次不改。下次從當時最新main重建，保留其既有TLS mount，不覆寫其他session成果。
- migration暫訂1.121.0；建檔前重查所有worktree／branch及已執行版號，防止他人佔用。schema只以db/schema.sql為準，migration實跑後再依標頭指令生成，禁止手造快照。
- 保留 `claude/fubon-api-feature-d5e6c5` (`f6017da7e8f9e0c1c5f9eb0d3fa9b3aafa2868fc`) 與 `claude/api-scheduled-queries-e32466` (`3e443028aa02a095901515b4d9cd05bb2039cdd6`)；UI session已刪不等於branch工作完成。
- 保留 stash `d04d2e2b77e93614edfe2b023887cb2a170cc506`。原refs備份 `/tmp/asset-takeover-20260830/original-session-heads.bundle`，SHA256 `524f1ba0f16d1f90449f98d9a404bc0685a7516e7e62624bca1f10a023ef3934`。

本機詳細證據位於 `/tmp/asset-takeover-20260830/fubon-refactor-continuation/`：round1 diff／manifest／spec-check、java-scope.md、financial研究、other-worktrees-before.json、runtime-config-baseline-round1.json、external-baseline-tests.log與bff-baseline-tests.log。暫存檔可能被清除；正式接續契約以已提交的六份task及本交接為準。前輪交付證據另在 `/tmp/asset-takeover-20260830/broad-fubon-audit/main-acceptance.json`。

測試固定Java21、真PG16／Redis7 Testcontainers、既有Python3.13 venv；保留POM timezone，Docker相容值用 `-DextraArgLine=-Dapi.version=1.44`，不要覆蓋argLine、skip suite或以H2冒充交易驗證。Frontend用既有node:test＋production build，不加測試依賴。
