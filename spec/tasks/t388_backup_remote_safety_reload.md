# [t388] 修復備份 remote 授權熱重載與錯帳號 fail-closed 守門

**對應 Requirements:** Requirement 15（資料庫備份／還原：為 `BackupService` 加入 source-fingerprint 熱重載、raw backing-root 身分驗證與可讀 503）；Requirement 52（Google Drive 輸出可用性自檢：僅限縮覆寫 DB 備份原本 startup-only 的 config 取捨，`GDriveOutput` 不變）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-08-29 已實測 `backup_setting.backup_enabled=true`，但 `backup_record` 與 Google Drive 的最後一份 daily 備份都停在 `2026-08-08 06:59`。當天 20:52 從畫面觸發手動備份，`pg_dump` 成功產生 9,031,085 bytes，但 rclone 上傳回 `invalid_grant`。

成因是 `BackupService` 只在啟動時把唯讀 `/etc/rclone/rclone.conf` 複製為 `/tmp/rclone.conf`，之後所有命令永遠用這份 writable snapshot。compose 已正確掛載 host 目錄 `${HOME}/.config/rclone:/etc/rclone:ro`，因此 host 執行 reconnect 後 `/etc/rclone/rclone.conf` 立即看得到新內容；失效點在「運行中 `BackupService` 從不重讀 source」，不是 bind mount 再次 dangling。

事故中第一次 reconnect 選到錯的 Google 帳號：raw `GoogleDriver:` 下不存在原有 `asset-management-backup/`。若這時直接對 `gdrive-crypt:backups/...` copy，rclone 會在錯帳號自動建立一棵新的假備份樹；後續 sync 還可能把錯帳號的空清單當真，清掉本地 `backup_record`。第二次 reconnect 改綁正確帳號 `shi.chihung@gmail.com` 後，已唯讀驗證 raw `GoogleDriver:asset-management-backup` 與 crypt `gdrive-crypt:backups/` 既有內容都存在。

正確修復是：`BackupService` 以「上次成功載入的 source content fingerprint」安全熱重載，並在每個 backup remote workflow 前先驗證既有 raw backing root 身分。不能拿 source 去比較 writable snapshot：rclone 會在 token 續期時自行改寫 `/tmp/rclone.conf`，這個差異是正常狀態，不是 reload 理由。

## 要做什麼

- [ ] **388.1 限縮後端範圍**：只修改 `business-services` 的備份 rclone config lifecycle／remote 守門／錯誤映射與對應測試。不改 `backup_setting` / `backup_record` schema，不新增 Liquibase，不改 API path、request/response DTO、BFF route、前端、compose mount、Dockerfile、備份檔名、retention 數字、交易日規則或任何 cron。現行三條 Spring 排程保持逐字不變：台股 `0 30 15 * * MON-FRI`、美股 `0 0 7 * * TUE-SAT`、每週 `0 0 5 * * SUN`，zone 皆為 `Asia/Taipei`。`SchedulePublicBffController.JOBS` 不改。`GDriveOutput` 使用的 `/tmp/rclone-output.conf`、L1/L2/L3 啟動自檢、九／十頁輸出的 best-effort 行為全都不在範圍。

- [ ] **388.2 將備份 rclone I/O 收斂到可測試的單一入口**：`BackupService` 仍負責 manual/scheduled/restore/sync/rotate 編排，但所有使用 `/tmp/rclone.conf` 的 rclone 命令必須共用同一個可替換的 process runner／remote session 邊界，不得留下可繞過守門的 private `ProcessBuilder` 快捷路徑。生產實作只允許白名單命令 `rclone lsf`、`copy`、`lsjson --files-only`、`deletefile`；使用者提供的 folder/filename 仍先走現有白名單與 path-traversal 驗證，不拼 shell command。

- [ ] **388.3 用 last-loaded source fingerprint 管理 writable snapshot**：
  - source 固定為 `/etc/rclone/rclone.conf`，writable snapshot 固定為 `/tmp/rclone.conf`。啟動時及每個會使用 backup remote 的 workflow 入口，都實際讀取 source 全部 bytes，對這份已讀 bytes 計算 SHA-256，與記憶體 `lastLoadedSourceFingerprint` 比較。workflow 入口因 fingerprint changed 所做的是主動同步 reload，**不消耗 auth recovery**；完成後才以 `recoveryConsumed=false` 進入 `INITIAL_GATE`。同一 workflow 可先有一次入口 reload，再於後續 auth failure 且 source 二次 changed 時有一次 recovery reload。
  - 第一次成功載入或 fingerprint 改變時，以**同一份 source bytes**寫 `/tmp` 同 filesystem 下的唯一 temp，在 move 前設 owner-only `rw-------` (`0600`)，以 `ATOMIC_MOVE + REPLACE_EXISTING` 原子安裝為 `/tmp/rclone.conf`。只有 move 成功才更新 `lastLoadedSourceFingerprint`。讀、hash、write、chmod、move 任一失敗，舊 writable snapshot 與舊 fingerprint 都不動，temp 必須清理。若平台不支援 atomic move，fail closed，不降級成直接覆寫目標檔。
  - **禁止** hash／比較 `/tmp/rclone.conf` 內容、mtime 或 inode 來決定 reload；rclone 自行 refresh token 後 writable 與 source 不同是正常。source fingerprint 沒變時必須保留 writable 現狀，不得把較舊 source 覆蓋回去。
  - source 不存在或不可讀不得讓 Spring 啟動失敗，但下一次 backup remote workflow 必須 503 fail closed；不得繼續用無法證明來自 last-loaded source fingerprint 的舊 snapshot 做寫入或刪除。

- [ ] **388.4 以單一 fair reentrant lock 保護 config 與 remote 併發**：這把 service-level lock 覆蓋 source read/fingerprint、temp install、`lastLoadedSourceFingerprint`、raw-root gate，以及所有使用 `/tmp/rclone.conf` 的 rclone 子行程完整生命期。任一 rclone 子行程尚未 exit 時不得 reload，避免舊行程續期寫回蓋掉新 snapshot。`syncFromRemote()` 移除四個 `CompletableFuture` rclone 呼叫，改為同一 verified session 內依序列舉 `manual/daily/weekly/monthly`。DB-only `listBackups()` 不取這把鎖。嵌套的 restore → auto-pre-restore 不得死鎖；可用 reentrant lock 或明確的 locked internal method，但只能存在一把鎖。lock 從 workflow 進 remote session 起持有到最後一支 rclone exit；持鎖期間其他執行緒不可能安裝 source，故實作不得設計跨執行緒 generation retry 分支。

- [ ] **388.5 每個 remote workflow 先驗證 raw backing-root 身分**：
  - 將 `GoogleDriver:asset-management-backup` 定義為與 crypt `gdrive-crypt:backups` 分開的固定安全常數。以 writable snapshot 執行唯讀 `rclone lsf --dirs-only --max-depth 1 GoogleDriver:`，將輸出當作逐行目錄名，只有 exact line `asset-management-backup/` 才通過。不得用 contains/prefix（`asset-management-backup-old/` 不算），不得只測 `gdrive-crypt:`，不得把 raw-root `directory not found` 當空清單，不得呼叫 mkdir／copy 自動建 root。
  - 守門適用於：手動備份、台股 daily、美股 daily、weekly、restore 的自救點上傳與備份下載、`POST /api/backups/sync`、備份成功後 rotate，以及 `PUT /api/backups/settings` 後觸發的三個 folder rotate。守門是 crypt copy/list/delete 之前唯一允許的 raw 列舉。auth reload 原子替換 writable snapshot 後，在 retry 任何 crypt 命令前必須重跑守門。
  - 守門失敗時 manual/scheduled 在主要 upload 前不寫 `backup_record`；restore 不下載、不執行 `pg_restore`；rotate **入口 gate** 未通過時該次 rotation 零 remote／DB mutation；sync 不執行任何 crypt `lsjson`，不 upsert，**不刪任何現有 `backup_record`**。raw root 通過後，sync 才可將個別 child folder 的 `directory not found` 視為該 folder 空清單；四個 child list 全部成功後才開始任何 DB mutation。

- [ ] **388.6 auth failure 依非遞迴 state machine 每個 remote workflow 最多自癒一次，且必須有 source-change 證據**：可 retry 的分類至少包含 `invalid_grant`、token expired／no refresh token、OAuth token fetch failure、HTTP 401 或 rclone 明確 auth error；`directory not found`、錯誤檔名、一般 I/O 不得假裝成 auth retry。入口主動 reload 完成後 state 才以 `recoveryConsumed=false` 開始；只有 gate／crypt auth failure 後 source 確實 changed 的 recovery reload 才改為 true。gate helper 與 crypt-command helper 不得遞迴或 loop／backoff：
  1. `INITIAL_GATE` auth fail 時，source unchanged 立即停止；changed 才 recovery reload 並進關閉自癒的 `FINAL_GATE`。final 是該 workflow 第二次且最後一次 gate；成功後可執行 crypt，但後續 auth fail 不再 recovery。
  2. initial gate 未消耗 recovery 而通過後，某支 target crypt command auth fail 時，source unchanged 立即停止；changed 才 recovery reload、跑一次關閉自癒的 `VALIDATION_GATE`，再把**同一 target command** retry 恰一次。validation／target retry／後續 command auth fail 都立即停止。
  3. 每個 workflow gate 總數最多 2。每支 target crypt command 最多 original＋retry 共 2 attempts；這不是全 workflow crypt 總數上限。sync 的前 N−1 支 list、rotate 的前 N−1 支 delete 若已成功，各自的一次呼叫不計入第 N 支 target attempts。第 N 支 retry 失敗後，後序 command 零呼叫。

- [ ] **388.6a rotate 必須逐筆 durable commit，maintenance 失敗不反轉主要成功**：主要備份 `copy` 成功後，先以獨立 transaction durable commit 新 `backup_record`，再開始 rotate。每個 folder rotation 入口 gate fail 時零 remote／DB mutation；通過後依既有排序逐筆處理，只有 exact `deletefile` 成功或明確回報該 object 已不存在，才以獨立 transaction 刪除該筆 DB row。第 N 筆 unavailable 在 auth state machine 無法自癒後立即停止：第 N 筆與後續 remote／DB row 保留，前 N−1 筆已成功刪除且不可 rollback。主要 backup 已成功時，rotate fail 僅 safe warning：manual 繼續回原成功 2xx，scheduled 算本次備份成功；`PUT /api/backups/settings` 亦先回既有成功設定語意，該 folder 停止後依既有契約繼續其他 folder。

- [ ] **388.7 新增 backup-specific 503 錯誤邊界**：以專用 `BackupRemoteUnavailableException` （或同等單一型別）表達 source 不可讀／snapshot 無法安裝／raw root 缺失／auth 無法自癒／其他 backup rclone unavailable。`GlobalExceptionHandler` 明確將它映射為 **503 Service Unavailable**，不得落入 generic 500，不得複用 `GDriveOutput` 的 429/best-effort 狀態欄語意。
  - 對使用者的訊息要分清 auth 與 root-missing。auth 指引包含：在 host 上對 `GoogleDriver:` reconnect，選擇正確 Google 帳號，確認 raw root 存在後直接重試；系統會依 source fingerprint 自動 reload，**DB 備份無需 recreate `business-services`**。root-missing 指引明寫 exact `GoogleDriver:asset-management-backup` 未找到、可能選錯帳號，且系統不會自動建立。可提供不含密密的 reconnect 指令形狀，但不得回 config 內容。
  - HTTP 直接觸發的 manual/sync/restore 在主要結果成功前遇上述錯誤回 503。manual 的 upload＋DB save 已 durable commit 後，rotation unavailable 不送入 handler，仍回原成功 2xx，只記清洗後 warning。三條 scheduled method 沒有 HTTP response，主要備份前失敗記清洗後 error；主要備份成功後的 rotate 失敗只記 warning，且本次備份仍算成功。`PUT /api/backups/settings` 後的 rotation 保留既有「儲存設定成功，單一 folder rotate 失敗只 warn 且其他 folder 繼續」契約；入口 auth/root 失敗的該 folder 必須零 remote／DB delete，逐筆途中失敗則保留先前已 commit 的成功刪除並立即停止該 folder。

- [ ] **388.8 從 process stderr 分類，但任何對外／日誌路徑都先清洗**：可在記憶體內用 raw stderr 判定 auth/root/rclone error，但不得把 raw 內容直接放進 exception message、cause、stack trace、log 或 `ProblemDetail`。清洗覆蓋至少：`access_token`、`refresh_token`、`client_id`、`client_secret`、`token = ...`、`password`、`password2`、Bearer value 與 JSON token object；最終摘要限長。不得 dump source/writable config，不得為診斷列出 secret-bearing section 內容。如果 exception 保留 cause，cause 也必須是已清洗的型態；不可在 scheduled catch 用 `log.error(..., e)` 重新把 raw cause 印出。

- [ ] **388.9 單元／整合測試必須可離線、可並發、不連真 Drive**：以 temp source/writable 與可編程的 process runner 驗證：
  - 首次 source 安裝後記住 fingerprint；writable 被模擬 rclone 改寫、source 未變時**不 reload**，writable 保留改寫後 bytes；source bytes 改變時才 reload；atomic move 失敗後舊 writable/fingerprint 不變且 temp 已清理。
  - 逐條驗精確 calls：initial gate auth unchanged＝gate 1／target crypt 0；gate auth changed＝gate 最多 2（initial＋final）；initial gate 正常通過後 target crypt auth changed＝gate 2（initial＋validation）、該 target attempts 2（original＋retry）。另做「入口 source changed 主動 reload後，target auth fail 且 source 再次 changed」：同 workflow reload 恰 2（entry 1＋recovery 1）、gate 最多 2、target attempts 2，證明 entry reload 不消耗 recovery。
  - 用 latch/barrier 讓至少兩個執行緒同時觸發：斷言同時間最多一支 backup rclone process、新 source 只被安裝一次、沒有 partial file／deadlock；第二個 workflow 只能在第一個釋放鎖後從 `INITIAL_GATE` 自己開始，不能沿用跨執行緒 retry 分支。
  - **錯帳號負測試**：raw dirs-only transcript 只回 `Documents/`、`asset-management-backup-old/` 或空清單，同時將 crypt copy/list/delete stub 設成「若被呼叫就會成功」；斷言 exact gate 回 503，crypt stub **零呼叫**，manual 零 `recordRepo.save`，sync 零 save/delete，rotate 零 remote delete/零 DB delete，restore 零 `pg_restore`。正確 raw line `asset-management-backup/` 才放行。此負測試使用 fake runner，**不得把真正 host 授權切到錯帳號**。
  - secret redaction：假 stderr 明列非空 sentinel `access_token`、`refresh_token`、`client_id`、`client_secret`、`token = {"access_token":"..."}`、`password`、`password2`、crypt password、Bearer value 與 `invalid_grant`；斷言 auth 分類仍正確，但 exception message、handler `ProblemDetail.detail` 與可擷取 log 都找不到任一 sentinel value，且 detail 包含 reconnect／exact-root／自動 reload 指引。只提 key 名稱但無 delimiter/value 的安全文案不得誤判。
  - 四 folder sync 序列執行；第 N 個 list 是 auth-failing target 時，前 N−1 支各呼叫一次，target 最多 original＋retry 兩次；retry 失敗後 N+1 起零呼叫且尚未作任何 DB mutation。個別 child folder missing 只在 raw root 已通過後視為空。
  - rotate 第 N 筆 delete 是 auth-failing target 時，前 N−1 支各一次且 remote object／DB row 均已刪除並 commit；target 最多 original＋retry 兩次，retry 失敗後第 N 筆及後續 object／row 均存在、後序 command 零呼叫。另測 exact object already missing 仍刪該 DB row；入口 gate fail 則所有 object／row 不動。manual upload＋record save 成功後 rotate fail 仍回 2xx，scheduled success counter／結果仍為成功且只留 safe warning。

- [ ] **388.10 運行中 Docker 驗收與真實手動備份 readback**：先依 `run-stack` 從 running container label 解析 active Compose project，並確認本次尚未 merge 時 build directory 是此 feature worktree；不得從另一 worktree 覆蓋共用 image。若 feature worktree 缺 `.env`，只可從 main workspace 複製現有 `.env`，不得生成、顯示或提交 secret。只 rebuild/recreate `business-services`；recreate 後必須 restart `bff`，等兩者 health，再從 bff restart 時點檢查 log 無 `Connection refused`／`500 Server Error`。用唯讀命令確認 `/etc/rclone/rclone.conf` 可實際讀取、`/tmp/rclone.conf` 權限是 `600`、raw root exact match 存在。
  - 點 UI 前先記錄驗收開始時間，以及當下最新 manual row 的 baseline `id`／`modified_at`。點一次「立即備份」後，DB 必須出現**恰一筆** `folder='manual' AND id > baseline_id` 的新 row；filename 非空、size > 0、`modified_at` 同時晚於驗收開始時間與 `2026-08-08 06:59:59`。
  - 再以 exact `gdrive-crypt:backups/manual/<filename>` 做獨立 `lsjson --files-only` readback；結果陣列必須恰一筆，該筆 `Name` 等於 DB filename、`Size` 等於 DB `size_bytes`。只有 HTTP 2xx 或只看最新一列都不算驗收。檢視近期 business／bff log，不得有 token/secret value、BFF connection refused，亦不得有「根目錄不存在但仍 Uploaded」的路徑。

## 驗證

```bash
set -euo pipefail

# 1. 機械 spec 與後端全測試（內含指紋／auth-retry／併發／錯帳號／redaction 負測試，全程離線）
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test

# 2. 依 running container 解析 active Compose project，並確認從尚未 merge 的 feature worktree build
build_worktree=$(git rev-parse --show-toplevel)
test -f "$build_worktree/spec/tasks/t388_backup_remote_safety_reload.md"
build_branch=$(git -C "$build_worktree" branch --show-current)
test -n "$build_branch"
test "$build_branch" != main
compose_project=$(docker inspect asset-business-services --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null \
  || docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)
active_compose_worktree=$(docker inspect asset-business-services --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' 2>/dev/null \
  || docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' 2>/dev/null || true)
test -n "$compose_project" || compose_project=asset-management
main_worktree=$(git worktree list --porcelain | awk '/^worktree /{p=$2} /^branch refs\/heads\/main$/{print p}')
test -n "$main_worktree"
if test ! -f "$build_worktree/.env"; then
  test -r "$main_worktree/.env"
  cp "$main_worktree/.env" "$build_worktree/.env"
fi
git -C "$build_worktree" check-ignore -q .env
printf 'compose_project=%s active_compose_worktree=%s build_worktree=%s branch=%s\n' \
  "$compose_project" "$active_compose_worktree" "$build_worktree" "$build_branch"

# 3. 只 rebuild/recreate business；business healthy 後 restart bff，再驗兩者 health 與 BFF DNS 路徑
docker compose -p "$compose_project" build --no-cache business-services
docker compose -p "$compose_project" up -d --no-deps --force-recreate business-services
for _ in $(seq 1 60); do
  test "$(docker inspect asset-business-services --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}')" = healthy && break
  sleep 2
done
test "$(docker inspect asset-business-services --format '{{.State.Health.Status}}')" = healthy
bff_restart_since=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
docker compose -p "$compose_project" restart bff
for _ in $(seq 1 60); do
  test "$(docker inspect asset-bff --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}')" = healthy && break
  sleep 2
done
test "$(docker inspect asset-bff --format '{{.State.Health.Status}}')" = healthy
docker exec asset-business-services wget -qO- http://127.0.0.1:8080/actuator/health | rg -F '"status":"UP"'
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health | rg -F '"status":"UP"'
bff_restart_logs=$(docker logs asset-bff --since "$bff_restart_since" 2>&1)
if printf '%s' "$bff_restart_logs" | rg -n 'Connection refused|500 Server Error'; then exit 1; fi

# 4. config lifecycle 與 raw-root 唯讀驗證（不輸出 config 內容）
docker exec asset-business-services sh -lc 'test -r /etc/rclone/rclone.conf && head -c 1 /etc/rclone/rclone.conf >/dev/null'
docker exec asset-business-services sh -lc 'test "$(stat -c %a /tmp/rclone.conf)" = 600'
docker exec asset-business-services rclone --config /tmp/rclone.conf lsf --dirs-only --max-depth 1 GoogleDriver: \
  | grep -Fx 'asset-management-backup/'

# 5. 點 UI 前先凍結驗收時間與 latest manual baseline；不得以「點完後再看最新一列」代替
acceptance_started_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
acceptance_started_at=$(TZ=Asia/Taipei date '+%Y-%m-%d %H:%M:%S')
baseline_row=$(docker exec asset-postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -AtF "|" -c "SELECT COALESCE((SELECT id FROM backup_record WHERE folder='\''manual'\'' ORDER BY id DESC LIMIT 1),0),COALESCE((SELECT to_char(modified_at,'\''YYYY-MM-DD HH24:MI:SS'\'') FROM backup_record WHERE folder='\''manual'\'' ORDER BY id DESC LIMIT 1),'\'''\'')"')
IFS='|' read -r baseline_id baseline_modified_at <<EOF
$baseline_row
EOF
case "$baseline_id" in ''|*[!0-9]*) exit 1 ;; esac
printf 'acceptance_started_at=%s baseline_id=%s baseline_modified_at=%s\n' \
  "$acceptance_started_at" "$baseline_id" "$baseline_modified_at"
state_file=/tmp/asset-backup-t388-verify.state
umask 077
printf '%s|%s|%s|%s|%s\n' "$acceptance_started_utc" "$acceptance_started_at" \
  "$baseline_id" "$baseline_modified_at" "$bff_restart_since" > "$state_file"
printf 'STOP：baseline 已保存於 %s；現在到已登入管理者 UI 恰好按一次「立即備份」。成功完成前不要執行下一段。\n' "$state_file"
```

**人工 UI 停點：到這裡必須停止。** 確認畫面上的「立即備份」只按一次且操作已結束後，才另行執行下方第二段；不可把兩段直接串成單一無人值守 script。

```bash
set -euo pipefail

state_file=/tmp/asset-backup-t388-verify.state
test -r "$state_file"
IFS='|' read -r acceptance_started_utc acceptance_started_at baseline_id baseline_modified_at bff_restart_since < "$state_file"
case "$baseline_id" in ''|*[!0-9]*) exit 1 ;; esac

# 6. UI 完成後必須只有一筆 id > baseline
new_count=$(docker exec -e BASELINE_ID="$baseline_id" asset-postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -v baseline_id="$BASELINE_ID" -c "SELECT count(*) FROM backup_record WHERE folder='\''manual'\'' AND id > :baseline_id"')
test "$new_count" = 1
new_row=$(docker exec -e BASELINE_ID="$baseline_id" -e STARTED_AT="$acceptance_started_at" asset-postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -AtF "|" -v baseline_id="$BASELINE_ID" -v started_at="$STARTED_AT" -c "SELECT id,filename,size_bytes,to_char(modified_at,'\''YYYY-MM-DD HH24:MI:SS'\''),modified_at > :'\''started_at'\''::timestamp,modified_at > TIMESTAMP '\''2026-08-08 06:59:59'\'' FROM backup_record WHERE folder='\''manual'\'' AND id > :baseline_id ORDER BY id"')
IFS='|' read -r latest_id latest_file db_size latest_modified after_start after_incident <<EOF
$new_row
EOF
test -n "$latest_file"
case "$db_size" in ''|*[!0-9]*) exit 1 ;; esac
test "$db_size" -gt 0
test "$after_start" = t
test "$after_incident" = t

# 7. 獨立 Drive readback：exact path 必須恰一筆，Name／Size 與新 DB row 相等
remote_json=$(docker exec asset-business-services rclone --config /tmp/rclone.conf \
  lsjson --files-only "gdrive-crypt:backups/manual/$latest_file")
remote_row=$(printf '%s' "$remote_json" | ruby -rjson -e \
  'd=JSON.parse(STDIN.read); abort unless d.length == 1; puts [d.length,d[0].fetch("Name"),d[0].fetch("Size")].join("|")')
IFS='|' read -r remote_count remote_name remote_size <<EOF
$remote_row
EOF
test "$remote_count" = 1
test "$remote_name" = "$latest_file"
test "$remote_size" = "$db_size"

# 8. 從驗收開始點掃描 business／bff 日誌；鍵名文案本身不算洩密，secret value 與 BFF refused 必須為零
for service in asset-business-services asset-bff; do
  service_logs=$(docker logs "$service" --since "$acceptance_started_utc" 2>&1)
  if printf '%s' "$service_logs" \
    | rg -n -i '(access_token|refresh_token|client_id|client_secret|password2?|authorization|token)[[:space:]"]*[=:][[:space:]"]*[^ ,}]+'; then exit 1; fi
  if printf '%s' "$service_logs" \
    | rg -n -i 'Bearer[[:space:]]+[A-Za-z0-9._~+/-]{8,}'; then exit 1; fi
done
bff_restart_logs=$(docker logs asset-bff --since "$bff_restart_since" 2>&1)
if printf '%s' "$bff_restart_logs" | rg -n 'Connection refused|500 Server Error'; then exit 1; fi

# 9. 最終再跑一次機械閘門，必須 BLOCK: 0 且 exit 0
bash scripts/spec-check.sh
rm -f /tmp/asset-backup-t388-verify.state
```

錯帳號負測試必須由離線 fake process runner 執行，不可為了驗收而改寫真實 `~/.config/rclone/rclone.conf`、切換真 Google 帳號，或對任一 remote 執行 mkdir/delete。若要驗證 host reconnect 熱重載，只能在使用者本來就需要 reconnect 且已確認選對帳號時觀察下一次 manual backup，不得為測試人為破壞健康授權。

## 完成報告

（實作者完成後回填：實際修改檔案、fingerprint／atomic move／鎖／raw-root 守門／auth retry／503／redaction 的測試輸出、Docker image/container provenance、手動備份的 DB＋Drive 雙 readback，以及與計畫的任何偏差與原因。）
