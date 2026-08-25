# [t374] 將交易雷達 Blogger 發布目的地切換至 myrader.blogspot.com

**對應 Requirements:** Requirement 102（交易雷達公開 Blogger 發布只能使用固定的 `https://myrader.blogspot.com/`，並以重新授權安全切換）
**前置任務:** t366（初版 Blogger 輸出通道）
**Liquibase changeset:** v1.115.0-trading-radar-blog-destination-myrader.sql

## 背景

初版交易雷達 Blogger 發布固定使用 `https://twleader.blogspot.com/`。現在公開發布目的地改為
`https://myrader.blogspot.com/`，且 `shi.chihung@gmail.com` 是新目的地的 Blogger 管理者。

這不能只替換畫面文字。現有全域 OAuth 憑證中的 `blogId`／access token／refresh token 是舊站授權，
每位使用者的 `blog_enabled`、`blog_last_post_id`、`blog_last_post_url` 也可能令排程重用舊文章。若直接保留，
下一次發布可能對舊站執行 `PUT` 更新，或繼續使用舊 token。切換必須 fail-closed：部署後先完全停止 blog
發布，清除舊連接與文章追蹤，然後由主要管理者重新連接新站並明確重新啟用排程。

本任務不發布任何文章、不呼叫真實 Google/Blogger API，也不改變既有 OAuth callback 路徑、OAuth Client、
Google Cloud Console 設定或其他輸出通道。

## 要做什麼

- [ ] **374.1 新增版本化資料 migration。** 建立
  `backend/src/main/resources/db/changelog/changes/v1.115.0-trading-radar-blog-destination-myrader.sql`，
  並在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 將它緊接
  `v1.114.0-stock-intraday-order-book.sql` 註冊。changeset 只能做下列 SQL 的等價本機資料庫操作，
  不得在 migration、啟動 hook 或測試中呼叫 Google/Blogger：

  ```sql
  --liquibase formatted sql
  --changeset steven:v1.115.0-trading-radar-blog-destination-myrader

  ALTER TABLE blog_publish_credential
      ALTER COLUMN blog_url SET DEFAULT 'https://myrader.blogspot.com/';

  DELETE FROM blog_publish_credential;

  UPDATE trading_radar_export_setting
  SET blog_enabled = FALSE,
      blog_last_post_id = NULL,
      blog_last_post_url = NULL,
      blog_last_run_at = NULL,
      blog_last_status = NULL;
  ```

  master changelog 的新 include 必須是：

  ```yaml
  - include:
      file: db/changelog/changes/v1.115.0-trading-radar-blog-destination-myrader.sql
      relativeToChangelogFile: false
  ```

  `DELETE` 必須完整移除唯一的 `blog_publish_credential` 列，包含 `blog_id`、帳號標籤、所有 token、
  `needs_reconnect` 與連接時間；不可把舊 refresh token 標記後繼續嘗試使用。`UPDATE` 必須影響所有
  owner 的匯出設定，讓既有排程不會在切換後自動發布，且不得留下會令新站首次發布改走 `PUT` 的 post ID。

- [ ] **374.2 將後端固定目的地改為新 URL，沒有舊站 fallback。** 在
  `BlogOAuthService` 將 `BLOG_URL` 設為完整字串 `https://myrader.blogspot.com/`，使 OAuth callback 對
  `https://www.googleapis.com/blogger/v3/blogs/byurl?url=https://myrader.blogspot.com/` 查詢新 `blogId`。
  callback 成功儲存 credential 時必須顯式執行 `cred.setBlogUrl(BLOG_URL)`；不可只依賴 builder 預設值。
  在 `BlogPublishCredential` 將 `blogUrl` 的 `@Builder.Default` 與對應 Javadoc 改為新 URL。
  在 `TradingRadarBlogController` 將無 credential 時 `GET /blog-status` 的 `blogUrl` fallback 改為新 URL。
  同步修正所有後端 Javadoc／註解，使 production source 不再把舊站描述為目前目的地。

  新增 `BlogOAuthService.isCurrentDestinationConnected()`（或同名、同責任的 service 層唯讀判定），只有 id=1
  credential 的 `blogId`／`refreshToken` 非空、`needsReconnect=false`、且 `blogUrl` **精確**等於 `BLOG_URL`
  時才回 true。新增 `TradingRadarBlogSettingsService`，由它注入 `TradingRadarExportSettingRepository`、
  `BlogOAuthService` 與 `BlogPublishOutputSupport`，承接 `status(ownerId)` 聚合、configured-admin mutation
  授權與 `setEnabled(ownerId, enabled)` upsert。`setEnabled(true)` 必須在建立或儲存 owner setting 前委派
  上述 credential 判定；不合格時丟 `IllegalArgumentException`（既有全域 advice 回 400），且零
  `settingRepo.save`。`GET /blog-status` 的 `connected` 同樣重用此判定。`TradingRadarBlogController` 必須
  移除 `BlogPublishCredentialRepository`／`TradingRadarExportSettingRepository` 注入，僅轉譯 HTTP request/
  response 並委派 settings service。OAuth callback 成功時只儲存 credential，不得自動把任何 owner 的
  `blog_enabled` 設為 true。

- [ ] **374.3 將前端可見目的地改為新 URL。** 在 `frontend/src/views/TradingRadarView.vue` 將設定卡說明、
  `blogStatus.blogUrl` 初始／fallback 值、手動公開發布確認對話框與相關註解全部改為
  `https://myrader.blogspot.com/`。`frontend/src/api/index.js` 的 Blogger 說明註解也同步更新。
  保留既有權限、按鈕、確認框、OAuth 導向、API 路徑與排程 UI 行為；本任務不新增可輸入或切換任意 blog URL
  的欄位。

- [ ] **374.4 讓切換後的行為可驗證。** 更新
  `BlogOAuthServiceTest`，以 fake HTTP client 明確斷言 callback 的 `blogs/byurl` 呼叫使用新 URL，
  並斷言儲存的 credential `blogUrl` 是新 URL。更新 `BlogPublishServiceTest` 的 Blogger 回應示例／斷言為
  `https://myrader.blogspot.com/...`，使測試資料不再暗示舊目的地。測試不得連網、不得使用真實 token。
  新增 settings-service／交易雷達 Blog HTTP boundary 測試覆蓋：無 credential、`needsReconnect=true`、
  缺 `blogId`／`refreshToken`、或舊 URL 的 enable=true 都回 400 且 settings service 不寫 setting；完整
  新站 credential 才可寫入 true；callback 本身不得重新開啟 migration 已關閉的同步。

- [ ] **374.5 同步 schema 稽核基準線。** Liquibase 在實際 `asset-postgres` 套用本 changeset 後，以兩段
  暫存檔程序重產 schema-only dump，保留檔頭並更新「產生當下表數」；不可手動拼接 schema dump 或直接覆寫來源檔：

  ```bash
  docker exec asset-postgres pg_dump -U assets -d assets \
    --schema-only --no-owner --no-privileges \
    | grep -v '^\\restrict\|^\\unrestrict' > /tmp/fresh-schema.sql
  HDR=$(( $(grep -nxF -- '-- PostgreSQL database dump' db/schema.sql | head -1 | cut -d: -f1) - 2 ))
  head -n "$HDR" db/schema.sql > /tmp/schema-header.sql
  cat /tmp/schema-header.sql /tmp/fresh-schema.sql > db/schema.sql
  grep -c '^CREATE TABLE' db/schema.sql
  ```

  結果必須保留 pg_dump 的實際格式：
  `blog_url character varying(512) DEFAULT 'https://myrader.blogspot.com/'::character varying NOT NULL,`。

- [ ] **374.6 切換後的使用者流程。** migration 後 `GET /blog-status` 必須回新 URL 與
  `connected=false`；設定卡的同步開關必須為關閉；排程在重新連接與重新啟用前不得呼叫
  `BlogPublishService`。主要管理者登入 App 後，在 Google 同意畫面選擇
  `shi.chihung@gmail.com` 並確認它可管理 `myrader.blogspot.com`，成功 callback 取得新 `blogId` 後，
  設定仍必須保持關閉。既有的手動發布與排程開關語意彼此獨立：主要管理者可在明確確認後手動發布；
  排程發布則仍須由主要管理者另行明確啟用。兩種路徑的第一次實際發布都必須因
  `blog_last_post_id` 為空而對新 `blogId` 執行 `POST` 建立一篇文章。這個人工 OAuth／
  發布驗證由管理者於部署後執行，實作者不得代替使用者發布公開內容。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff
docker compose -p asset-management ps bff business-services frontend
docker exec asset-postgres psql -U assets -d assets -c "SELECT column_default FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'blog_publish_credential' AND column_name = 'blog_url';"
docker exec asset-postgres psql -U assets -d assets -c "SELECT count(*) AS credential_rows FROM blog_publish_credential; SELECT count(*) AS enabled_rows FROM trading_radar_export_setting WHERE blog_enabled; SELECT count(*) AS blog_tracking_rows FROM trading_radar_export_setting WHERE blog_last_post_id IS NOT NULL OR blog_last_post_url IS NOT NULL OR blog_last_run_at IS NOT NULL OR blog_last_status IS NOT NULL;"
bash scripts/tests/schema-sql-drift-test.sh
! rg -n 'twleader\.blogspot\.com' backend/src/main/java backend/src/test/java frontend/src db/schema.sql
```

驗收 DB 查詢必須分別顯示新 URL 預設值、`credential_rows = 0`、`enabled_rows = 0` 與
`blog_tracking_rows = 0`（對已套用 migration 的資料庫）。實際服務頁面需顯示新 URL、未連接與已關閉的
同步開關；business-services recreate 後必須先重啟 BFF、確認登入後 BFF path 沒有 upstream 500，再驗畫面。
此時不要按公開發布。完成重新連接後，才由管理者自行確認第一次發布建立於新站。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
