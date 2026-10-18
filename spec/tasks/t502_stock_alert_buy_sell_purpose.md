# [t502] 股票警示新增買進／賣出用途與 Email 揭露

**對應 Requirements:** Requirement 194（每個股票警示明確標示買進或賣出用途，並在通知信與日曆摘要保留用途和條件的對應）
**前置任務:** t253、t248 已完成
**Liquibase changeset:** v1.155.0-stock-alert-purpose.sql

## 背景

股票警示目前只保存觸發條件，例如價格、均線或 KD；使用者在收到 Email 時無法分辨該提醒要用作買進觀察還是賣出觀察。同一支條件方向不能可靠推導用途：例如「低於月線」可能是買進等待點，也可能是風險退出提醒。因此新欄位必須由使用者明確指定，舊資料不可擅自分類。

範圍只限警示設定、觸發歷史、警示清單與通知內容。用途是資訊呈現，不得新增或呼叫任何券商下單、買賣、改單、撤單、排程交易或快捷交易能力；不變更寄送對象、寄送時段、24 小時冷卻、價格／技術條件、Email 主旨或走勢圖。

## 要做什麼

- [ ] 502.1 建立 `backend/src/main/resources/db/changelog/changes/v1.155.0-stock-alert-purpose.sql`，並在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 最後 include。changeset 必須可重複套用：第一個欄位 changeset 以 `ADD COLUMN IF NOT EXISTS` 在 `stock_alert`、`stock_alert_group`、`stock_alert_trigger` 加 `purpose VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED'`；第二個 constraints changeset 必須標記 `splitStatements:false`，以單一 `DO $$ ... $$`、三段 `pg_constraint` existence guard 建立 `ck_stock_alert_purpose`、`ck_stock_alert_group_purpose`、`ck_stock_alert_trigger_purpose`，各自只允許 `BUY`、`SELL`、`UNSPECIFIED`。不可把 `ADD CONSTRAINT` 寫成不存在的 `IF NOT EXISTS` 語法，或讓 Liquibase 將 `DO` 區塊切斷。既有列、既有 trigger、既有群組及其成員都保留 `UNSPECIFIED`，不得根據條件、價格或指標 UPDATE 成 BUY/SELL。
- [ ] 502.2 擴充 `StockAlert`、`StockAlertGroup`、`StockAlertTrigger` entity 與 `StockAlertDto.Request`、`GroupRequest`、`Response` 的字串欄位 `purpose`。三個 entity 的 builder 必須以 `@Builder.Default` 提供 `UNSPECIFIED` 相容預設，讓既有 create-drop／直接持久化 fixture 不因 migration 未執行而違反 non-null；正式 create/update 仍由集中 service 驗證，只接受完整且大小寫精確的 `BUY` 或 `SELL`。null、空白、`UNSPECIFIED`、未知值都以既有 client-error 路徑拒絕。清單／讀取 response 可回傳舊資料的 `UNSPECIFIED`。不新增 controller、BFF、gateway、OpenAPI 或公開路由。
- [ ] 502.3 單一警示的 `purpose` 是其自身用途；複合警示只有群組的 `purpose` 可由使用者設定。建立與更新群組時，把相同值同步寫給該群組成員的 `stock_alert` 列以維持 non-null schema。群組成員 id 走既有單一 alert update、delete、toggle-active 或 reorder 時，service 必須在任何寫入前拒絕整個請求；只有 group endpoint 可以改群組及其成員，成員不得在 API 或 UI 成為可獨立設定用途的第二來源。單一與群組觸發時，將當下有效用途存入新 `StockAlertTrigger.purpose`。只要 trigger 列仍存在，手動補發只能採其儲存的用途，不得回查目前設定覆寫；既有 FK cascade 刪除 alert/group 時仍會刪掉 trigger 與收件人，本任務不新增刪除後補發或保留模型，已送出的信不會被改寫。
- [ ] 502.4 保持 `StockAlertService.buildLabel` 與 `buildGroupLabel` 為純條件文案，不能把「買進／賣出」塞進去，避免觀察清單、匯出及其他共用顯示改變。將用途另帶入 `AlertNotificationDispatcher.PendingTrigger`：live enqueue 讀目前單一／群組用途，歷史 `toPending` 只讀 `StockAlertTrigger.purpose`。保留既有 label fallback、recipient 分組、market 寄送時間、cooldown、圖表與 Email subject 行為。
- [ ] 502.5 同一股票的 digest 卡片必須以穩定出現順序建立 `用途 -> 去重條件文案` 對應，HTML Email 顯示可辨識的「買進：條件…；賣出：條件…」格式；任何 `UNSPECIFIED` snapshot（含 migration 後仍 active 的 legacy alert live 觸發）顯示「未設定：條件…」。不得把混合方向只壓成一個目的或失去哪條條件屬於哪個用途。日曆摘要使用同一份用途／條件映射；手動補發仍不附日曆邀請，但其 Email 正文同樣依 trigger snapshot 顯示用途。
- [ ] 502.6 修改 `frontend/src/views/StockAlertView.vue`：混合清單在既有警示條件和建立時間之間新增「買進／賣出」欄，`BUY` 顯示「買進」、`SELL` 顯示「賣出」、`UNSPECIFIED` 顯示「未設定」。單一與群組新增／編輯 dialog 都有一個群組層級的必選用途控制項；新表單的用途初始為空，未選時不可送出。編輯 legacy `UNSPECIFIED` 時如實顯示未設定、要求重新選擇才能儲存。群組內 individual condition 不可有用途控制項。API payload 在既有 create/update 呼叫附上 `purpose`。
- [ ] 502.7 補齊或擴充後端測試，至少覆蓋：單一與群組 input 的合法 BUY/SELL、所有非法用途拒絕、legacy `UNSPECIFIED` response 與仍 active legacy alert 的 live 通知、群組成員同步、群組成員 id 不能走單一 update/delete/toggle/reorder、單一／群組 trigger snapshot、更新用途後歷史補發仍用舊用途，以及同一股票混合 BUY/SELL 時的 Email／日曆文字仍保留用途與條件對應。為 notification dispatcher 的用途映射建立測試，必須透過 HTML 與日曆共用的 mapping／format helper 驗證混合方向、同用途去重、`UNSPECIFIED` 與歷史 resend snapshot 的完整輸出；下方 Maven selector 必須涵蓋 dispatcher 的所有測試，不能只靠未執行到 digest 的 `AlertCalendarLineTest`。也要擴充既有的 `FubonEtfHoldingsPostgresTest`：在不跑 Liquibase 的 create-drop 路徑，分別以未明確指定 `purpose` 的 `StockAlert`、`StockAlertGroup`、`StockAlertTrigger` 做實際 `saveAndFlush` 與讀回，並斷言三者皆為 `UNSPECIFIED`；必要時注入既有 repository 並滿足現有 FK 欄位，不得只靠 builder 或 in-memory assertion。保留既有條件 label／calendar fallback 測試語意。前端至少以 build 驗證 template、payload 與用途映射可編譯。
- [ ] 502.8 migration 套用後以正在運行的 `asset-postgres` schema-only dump 重產 `db/schema.sql`：先輸出 `/tmp/fresh-schema.sql`，再保留現有檔頭並合併 dump；更新表數行（表數若未改變必須仍與實際值一致）。不得手寫或只局部修改 schema dump。再執行 drift test，回 0 才能收尾。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='StockAlert*Test,AlertCalendarLineTest,AlertNotificationDispatcher*,FubonEtfHoldingsPostgresTest'
(cd frontend && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm run build)
bash scripts/tests/schema-sql-drift-test.sh
bash scripts/spec-check.sh
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
docker exec asset-postgres psql -U assets -d assets -Atc "SELECT conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conname IN ('ck_stock_alert_purpose', 'ck_stock_alert_group_purpose', 'ck_stock_alert_trigger_purpose') ORDER BY conname"
curl -fsS 'http://127.0.0.1:9090/api/public/market-index?market=TWSE&range=1m' | python3 -c 'import json,sys; json.load(sys.stdin); print("market-index JSON received")'
curl -sI http://localhost/ | head -1
```

實際部署前必須先確認正在使用的 Compose project、build 來源與 main `.env`；merge 前在 feature worktree 驗收，merge 後只從同步且乾淨的 main rebuild/recreate `business-services` 與 `frontend`，並等待 backend health 與 BFF recovery。確認服務 health、登入後股票警示清單／單一與群組儲存資料含用途、以及以測試／安全方式驗證的 digest 內容；不得寄真實 Email 或進行交易。完成後只 stage 本任務相關的全部檔案、保留無關髒檔，feature branch 以短中文 commit，main 以 `--no-ff` merge、push feature/main 並讀回 remote ref。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
