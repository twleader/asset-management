# [t480] SRPP 日報寄送 9090 API

**對應 Requirements:** Requirement 180（以固定 owner／收件人安全、冪等地寄出 SRPP 已驗證的核心或附錄日報）
**前置任務:** 無
**Liquibase changeset:** v1.143.0-srpp-daily-report-mail.sql

## 背景

SRPP 在本機對日報 HTML/plain 完成內容驗證。此任務提供一次一封的 SMTP 寄送 API，讓核心與附錄獨立呼叫；後端不重做內容驗證與交易判斷，只以固定 renderer 重算並驗證 SRPP 提供的 hash。

## 固定契約

本節是本任務唯一的實作契約，不需查附件或其他 spec。POST body 是 JSON object：`idempotencyKey`（精確 ASCII `[A-Za-z0-9][A-Za-z0-9._:-]{0,199}` 的單一 URI path segment）、`profile`（精確 `core` 或 `appendix`）、`expectedHtmlSha256`、`expectedTextSha256`、`facts`。slash、percent encoding、semicolon、query 語意、空白或非 ASCII key 在 POST/GET 都是 400 `INVALID_IDEMPOTENCY_KEY`；URL fragment 不會送到伺服器，SDK/client 不得把它當作 key 一部分或期待伺服器拒絕它。Nginx `client_max_body_size`、BFF 和 business `spring.codec.max-in-memory-size` 全部設為 1MB；256 KiB／1 MiB body 都可通過、1 MiB+1 必為 413。完整 JSON value 在 canonicalize 前必拒絕任一層級 duplicate JSON member 為 400 `DUPLICATE_JSON_MEMBER`，再以 RFC 8785 JCS canonical UTF-8 bytes 計 `request_sha256`；BFF 必須 relay 原 JSON bytes，business 只以這個 digest 判斷同 body，不能以 raw transport bytes 或 Java DTO 序列化取代。未知 JSON 欄可保留但不渲染。

`facts` 必填 trim 後非空文字欄為 `trading_date`、`slot`、`consumer`、`generated_at`、`policy_bundle_sha256`、`input_snapshot_sha256`、`decision_id`、`conclusion`；payload 另有同值 `decision_id`、可缺省的 `subject_suffix`、`summary` array、`tables` array。檢查順序固定：key → profile（未知為 `INVALID_PROFILE`）→上列 required（`FACT_REQUIRED:<field>`，其中 decision id 空為 `DECISION_ID_REQUIRED`）→ slot 只可 `09:05|11:40`（`INVALID_SLOT`）→ consumer 只可 `Codex|Claude`（`INVALID_CONSUMER`）→ payload decision id 不同（`CORE_APPENDIX_DECISION_MISMATCH`）→兩個 expected hash 都是小寫 `[a-f0-9]{64}`（`INVALID_HASH:<field>`）→ facts 指定文字含 `\\{\\{[^{}]+\\}\\}`（`FACT_PLACEHOLDER_UNFILLED`）→ suffix 必須為 `(?: 重跑-\\d{6}(?:-\\d+)?)?(?: 未完成)?`（`INVALID_SUBJECT_SUFFIX`）→ summary 非 array／任一項 placeholder（`SUMMARY_REQUIRED:<profile>`／`SUMMARY_PLACEHOLDER_UNFILLED:<profile>`）→ tables 非 array 或 core 少於 4、appendix 少於 6（`TABLES_REQUIRED:<profile>`／`MINIMUM_TABLES_REQUIRED:<profile>:<n>`）。

每張 table 的 title 必須是非空文字（`TABLE_TITLE_REQUIRED`）；columns 是非空文字陣列（`TABLE_COLUMNS_REQUIRED`）；rows 是 array（`TABLE_ROWS_REQUIRED`）；每 row 都是 array 且寬度等於 columns（`TABLE_ROW_WIDTH_MISMATCH`）；任一 title／column／cell stringify 後含 placeholder 是 `TABLE_PLACEHOLDER_UNFILLED`。以上皆為第一個失敗即 400 `application/problem+json`，且不寄信、不留 key。

緊急 00865B 表：移除所有空白後含 `00865B子帳與今日建議` 的表必須恰好一張，否則 `EMERGENCY_00865B_TABLE_REQUIRED`。columns 必須以 `子帳用途`、`券商`、`持有.*(?:單位|市值)`、`目標.*缺口`、`今日建議.*買進後歸屬|買進後歸屬` 找到五欄（否則 `EMERGENCY_00865B_COLUMNS_REQUIRED`），rows 必為兩列（`EMERGENCY_00865B_TWO_SUBACCOUNTS_REQUIRED`）。用途含緊急備用金／策略配置各一列，兩列 purpose 必含 00865B、broker 分別含國泰證券／富邦證券，五欄 trim 後非空，且不含 `{{`、`}}`、ASCII word `TODO|TBD`，否則 `EMERGENCY_00865B_ACCOUNT_ASSIGNMENT_INVALID`。兩 action 都要含 `買進後歸屬|買後歸屬`、自己的用途與券商標籤（國泰／富邦），並有數量 `\\d[\\d,]*(?:\\.\\d+)?\\s*(?:張|股|元)` 或 `不買進|無買進|停止買進|不可執行`，否則 `EMERGENCY_00865B_ACTION_ATTRIBUTION_REQUIRED`。任一整列含 `UNVERIFIED|無法核對|無法確認` 時兩 action 必含 `不可執行|不得給可執行買進|停止買進`（否則 `EMERGENCY_00865B_UNVERIFIED_NOT_BLOCKED`），且 summary／所有 table 全文不得命中 `00865B.{0,80}(?:買進|加碼).{0,40}[1-9][\\d,]*\\s*(?:張|股|元)` 或反向的 `(?:買進|加碼).{0,40}00865B.{0,80}[1-9][\\d,]*\\s*(?:張|股|元)`（否則 `EMERGENCY_00865B_UNVERIFIED_BUY`）。

subject 固定為 `[SRPP 每日持股市場交易建議-{核心或附錄}] ({consumer}) {trading_date} {slot}{subject_suffix}`。escape 等於 `html.escape(str(value), quote=False)`：只替換 `&`、`<`、`>`。HTML constants 完全固定：`CANVAS=background:#ffffff;color:#000000;font-family:Arial,'PingFang TC',sans-serif;font-size:14px;line-height:1.6;`，`TITLE=color:#000000;font-weight:700;margin:16px 0 6px 0;`，`TABLE=role="presentation" cellspacing="0" cellpadding="0" border="0" style="border-collapse:collapse;width:100%;background:#ffffff;color:#000000;"`，`TH=background:#e2e8f0;color:#000000;font-weight:700;text-align:left;border:1px solid #94a3b8;padding:6px 8px;`，`TD=background:#ffffff;color:#000000;text-align:left;border:1px solid #94a3b8;padding:6px 8px;`。以 LF join：div、18px subject p、conclusion p、單行生成時間/policy/input p、summary p、每表 title+table open、th、thead close、tr/td、table close，最後 `</div>\n`，整體尾端一個 LF。plain 順序是 subject、空行、結論、產生時間、兩 hash、空行、summary；每表再是空行、title、每欄、每列 cells、列後空行，LF join 並尾端 LF。兩個 UTF-8 SHA-256 不同回 409 `RENDER_HASH_MISMATCH`，body 帶 expected／actual 四 hashes。

DB changeset 必建 `srpp_daily_report_mail`：`idempotency_key varchar(200) primary key`、`request_sha256 char(64) not null`、`state varchar(24) not null check (state in ('PROCESSING','SUBMITTING','SENT','FAILED','OUTCOME_UNKNOWN'))`、`lease_id uuid`、`lease_expires_at timestamptz`、`message_id varchar(998)`、`sent_at timestamptz`、`from_address varchar(320)`、`to_address varchar(320)`、`subject varchar(512)`、`html_sha256 char(64)`、`text_sha256 char(64)`、`created_at timestamptz not null`、`updated_at timestamptz not null`，以及 `(state,sent_at)` index。新列先 commit 90 秒 PROCESSING；同 key/digest SENT 回 stored 200 和 `idempotentReplay=true`，不同 digest 是 409 `IDEMPOTENCY_KEY_CONFLICT`。有效 PROCESSING 的 POST/GET 都是 409 `IN_PROGRESS`；過期 PROCESSING 只由同 digest POST 用 `state=PROCESSING AND lease_expires_at < now` CAS 換 lease，CAS loser 必重讀目前狀態。sender call 前必用 `state=PROCESSING AND lease_id=?` CAS 成 SUBMITTING；有效 SUBMITTING 的 POST/GET 都是 409 `IN_PROGRESS`；過期 SUBMITTING 的 POST/GET 都用 `state=SUBMITTING AND lease_expires_at < now` CAS 寫成永久 OUTCOME_UNKNOWN，然後回 409 同碼。SUBMITTING 永不 reclaim／重寄；任何 sender call 後的 exception、crash 或結果不明均轉 OUTCOME_UNKNOWN。FAILED 僅是可證未進 sender call 的 pre-submit technical failure；同 digest POST 只用 `state=FAILED` CAS 新 PROCESSING lease，loser 重讀。成功只可用 `state=SUBMITTING AND lease_id=?` CAS 寫 SENT，任何舊 lease 不得覆寫。Tests 覆蓋 FAILED retry race、crash-before-SUBMITTING、expired PROCESSING、two-worker CAS race、SUBMITTING active/expired/crash。`SrppDailyReportMailRetentionScheduler` runs daily 03:17 Asia/Taipei and deletes only SENT with `sent_at < now - 7d`.

Only a nonblank `Authorization: Bearer <SRPP_DAILY_REPORT_SERVICE_TOKEN>` receives 9090 access; it maps only to configured owner (default `tw.leader@gmail.com`). BFF uses a distinct nonblank `SRPP_DAILY_REPORT_INTERNAL_TOKEN` in `X-SRPP-Daily-Report-Internal-Token`; business verifies it constant-time on its exact internal path. `MAIL_USERNAME` and EmailService `resolveFrom()` must equal owner, and `SRPP_DAILY_REPORT_RECIPIENTS` must be exactly one address (default `shi.chihung@gmail.com`), otherwise 503 `MAIL_DISABLED`. SMTP failure is 502 `MAIL_SEND_FAILED`. Mail is root `multipart/alternative` with `text/plain; charset=UTF-8` before `text/html; charset=UTF-8`, no ics/CID/attachment, and returns Message-ID. Success and GET-SENT return `{status:'SENT',messageId,sentAt,from,to:[recipient],subject,htmlSha256,textSha256,idempotentReplay}`; missing key is 404.

## 要做什麼

- [ ] **480.1 路由與認證。** 實作精確 POST／GET 路由，不可匿名、wildcard 或 public alias；Bearer token 僅映射一個固定 owner。同步 gateway 1 MiB、5/30/60 timeout、BFF filter/security、frontend deny、Tailscale、route catalog、OpenAPI、兩個 contract tests 和 renderer。
- [ ] **480.2 設定 fail closed。** `SRPP_DAILY_REPORT_RECIPIENTS` 必須剛好一個合法固定地址；MAIL_USERNAME、EmailService resolveFrom、owner 必須相同，缺任一項回 503 MAIL_DISABLED，不能 fallback。
- [ ] **480.3 驗證與 renderer。** 完整實作本檔「固定契約」列出的 validation／00865B error code。renderer 逐字採 fixed styles、LF、`& < >` only escape、固定 subject 與 UTF-8 hash；hash mismatch 不寄信、不占 key。
- [ ] **480.4 冪等與排程公開列表。** 依本檔固定欄位建立 changeset／schema；key unique 保存 JCS request hash、完整 state machine 與 success snapshot，保留 SENT 7 日，same replay、conflict、in-progress、unknown、SMTP failure 都依本檔固定契約。`SrppDailyReportMailRetentionScheduler` 的 Asia/Taipei 03:17 cron 必同步登錄 `SchedulePublicBffController.JOBS`，不可只有後端執行而公開排程清單遺漏。
- [ ] **480.5 SMTP。** 新增不吞例外、回 Message-ID 的 EmailService adapter，根 MIME 為 multipart/alternative，plain 先於 HTML，無 ics/CID/attachment；controller 只 HTTP DTO，service／repository／port 分層，BFF strict relay。
- [ ] **480.6 測試。** 覆蓋 golden renderer、每個 error code、UNVERIFIED 禁買、hash mismatch、token、disabled、replay/conflict/in-progress、SMTP failure、GET、MIME 及 1 MiB 邊界；更新 OpenAPI/Tailscale parity，並斷言公開排程清單包含 03:17 retention job、數量與 cron 不漂移；stack 實測只寄固定測試收件人。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
ruby scripts/render-9090-openapi-docs.rb --check
bash scripts/tests/schema-sql-drift-test.sh
```

依 run-stack 流程從 main `.env` rebuild/recreate business-services、bff、api-gateway；驗證未認證為 401、hash mismatch 不寄送、成功可 GET/replay、原始 MIME 是 plain 在 HTML 前的 multipart/alternative。

## 完成報告

（實作者完成後回填實際檔案、驗證輸出與偏差。）
