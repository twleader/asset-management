# [t509] 警示通知收件人名稱欄位

**對應 Requirements:** Requirement 23（通知收件人可選填名稱，並在通知設定頁顯示與編輯）
**前置任務:** 無
**Liquibase changeset:** `v1.156.0-notification-recipient-name.sql`

## 背景

警示通知設定目前只顯示 email。當同一帳號要維護多位收件人時，畫面無法提供可讀的識別名稱；新增與編輯對話框也只能修改 email。使用者要求在 Email 與狀態之間加入可編輯的「名稱」欄位。名稱只能是設定畫面的顯示資料，不能改變實際寄送的 email、收件人關聯或任何通知行為。

## 要做什麼

- [ ] 509.1 新增 Liquibase formatted SQL changeset `v1.156.0-notification-recipient-name.sql`，以 `ALTER TABLE notification_recipient ADD COLUMN IF NOT EXISTS name VARCHAR(100)` 新增 nullable 欄位；不得加預設值、不得回填既有資料、不得修改 email 複合唯一鍵。既有列保留 `name = NULL`，不可從 email 猜測名稱。同步在 `db.changelog-master.yaml` 的 `v1.155.0-stock-alert-purpose.sql` include 後加入此檔，沿用 `relativeToChangelogFile: false`；不得改用 `includeAll`，否則 Liquibase 不會套用新欄位。
- [ ] 509.2 `NotificationRecipient` 新增 `name` 欄位，映射 `name`、長度 100、nullable=true。`NotificationRecipientDto.Response`、`CreateRequest`、`UpdateRequest` 均新增 `String name`；兩個 request 的 name 套用 `@Size(max = 100)`，email 仍是必填且使用原本的驗證與正規化。
- [ ] 509.3 `NotificationRecipientService.create` 與 `update` 必須共用同一名稱正規化：trim 前後空白後，空字串寫入 null；create 與 update 都保存該值。email 的 trim + lowercase、同 owner email 去重、TenantGuard、非 Gmail 改 email 時關閉日曆邀請、啟停、刪除與所有 alert／radar／每日分析寄送行為均不得改變。`toResponse` 回傳名稱。
- [ ] 509.4 `NotificationSettingsView` 表格在 Email 和 狀態 中間新增「名稱」欄；name 為 null 時只顯示 `—`。新增與編輯對話框都提供可選名稱輸入框，`maxlength` 為 100、固定 placeholder 為「請輸入收件人名稱」，送出 `name` 與 email。沿用既有 `bffApi.notificationSettings` 與 `/api/bff/notification-settings/recipients/**` passthrough，不新增路由、端點或其他頁面的資料來源。共用 `NotificationRecipientDto.Response` 將 additive `name` 帶到 StockAlert／每日分析既有收件人選擇 API，但選擇器仍只顯示／使用 email；名稱不得當成寄送身分，亦不得出現在 email digest 或 iCalendar 內容。
- [ ] 509.5 新增或擴充後端 service 單元測試，證明 create／update 都會 trim 名稱、空白名稱正規化為 null，且 Response 保留 name；測試不得啟動 Spring context 或連資料庫。
- [ ] 509.6 Liquibase 套用後，以以下流程重產 schema 檔，確認 `notification_recipient.name character varying(100)` 為 nullable；`name` 是新增欄位而非新表，表數仍須以實測值回寫，不能假設其數字。不可直接把 pg_dump 重導向 `db/schema.sql`，以免檔頭被截斷：

  ```bash
  docker exec asset-postgres pg_dump -U assets -d assets --schema-only --no-owner --no-privileges \
    | grep -v '^\\restrict\|^\\unrestrict' > /tmp/notification-recipient-fresh-schema.sql
  notification_schema_header_end=$(( $(grep -nxF -- '-- PostgreSQL database dump' db/schema.sql | head -1 | cut -d: -f1) - 2 ))
  head -n "$notification_schema_header_end" db/schema.sql > /tmp/notification-recipient-schema-header.sql
  cat /tmp/notification-recipient-schema-header.sql /tmp/notification-recipient-fresh-schema.sql > db/schema.sql
  grep -c '^CREATE TABLE' db/schema.sql
  bash scripts/tests/schema-sql-drift-test.sh
  ```

  以 `apply_patch` 把檔頭的「產生當下表數」改為上一行實測值，並確認 `notification_recipient.name` 是 nullable、長度 100。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
notification_feature_worktree=$(git rev-parse --show-toplevel)
notification_main_worktree=$(git worktree list --porcelain | awk '/^worktree /{p=$2} /^branch refs\/heads\/main$/{print p; exit}')
test -f "$notification_feature_worktree/.env"
test -f "$notification_main_worktree/.env"
cmp -s <(sort "$notification_feature_worktree/.env") <(sort "$notification_main_worktree/.env")
(cd "$notification_feature_worktree" && docker compose -p asset-management build business-services)
(cd "$notification_feature_worktree" && docker compose -p asset-management up -d --no-deps --force-recreate business-services)
docker inspect --format '{{.State.Health.Status}}' asset-business-services | grep -x healthy
docker exec asset-postgres psql -U assets -d assets -Atqc "SELECT is_nullable || ':' || character_maximum_length FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'notification_recipient' AND column_name = 'name'" | grep -x 'YES:100'
(cd "$notification_feature_worktree" && docker compose -p asset-management build frontend)
(cd "$notification_feature_worktree" && docker compose -p asset-management up -d --no-deps --force-recreate frontend)
docker inspect --format '{{.State.Health.Status}}' asset-frontend | grep -x healthy
docker exec asset-frontend sh -c "grep -l '請輸入收件人名稱' /usr/share/nginx/html/assets/*.js"
bash scripts/tests/schema-sql-drift-test.sh
```

以上是 merge 前對 feature worktree 的驗收。功能 commit 完成、以 `--no-ff` merge 到 main 並推送後，正式 redeploy 只能在乾淨且已 `fetch origin && merge --ff-only origin/main` 的 `$notification_main_worktree` 執行相同的 affected-service build/recreate；不得從 feature worktree 或複製 `.env` 到 main。登入後到「警示通知設定」確認列表欄位順序為 Email、名稱、狀態，新增名稱後儲存並重載仍顯示該名稱；將名稱改成空白後儲存並重載顯示 `—`。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
