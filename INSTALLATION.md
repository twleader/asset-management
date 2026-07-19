# 資產管理系統搬機與安裝手冊

本手冊適合第一次接手本系統的人，目標是把舊電腦上的系統、資料與排程完整搬到另一台電腦，並用 Docker 重建。

本手冊以「單機、由 `http://localhost` 使用」為主。若要開放給區域網路或網際網路使用，請先完成 HTTPS、Firewall 與反向代理設定，不要直接把目前的服務連接埠暴露到公網。

---

## 1. 先理解：只複製程式碼是不夠的

完整搬機包含以下四類內容：

| 內容 | 用途 | 是否在 Git 裡 |
|---|---|---|
| 專案程式碼 | 前端、後端、BFF、爬蟲與 Docker 設定 | 是 |
| `db/init/01_dump.sql` | 真實 PostgreSQL 資料，包括資產、設定與歷史行情 | **否**，刻意忽略 |
| `.env` | 資料庫密碼、Google OAuth、郵件及 AI 金鑰 | **否**，刻意忽略 |
| rclone 與匯出檔案 | Google Drive 加密備份設定、排程產出的 Excel／JSON | **否** |

> **重要資安提醒**：`01_dump.sql`、`.env`、`rclone.conf` 都含敏感資訊，不可 commit、push、寄一般 Email 或放入未加密雲端空間。請使用加密隨身碟、加密磁碟映像或公司核准的秘密傳輸方式。

---

## 2. 建議的搬機順序

1. 在舊電腦確認程式版本，並把應保留的程式變更 merge／push。
2. 在舊電腦做最後一次資料庫匯出。
3. 匯出完成後停止舊系統，避免新舊兩台同時寄信、備份或執行排程。
4. 安全移交資料庫、秘密設定及必要的輸出檔案。
5. 在新電腦安裝 Docker 與 Git，clone 正確版本。
6. 放入設定與資料庫 dump，第一次啟動全部服務。
7. 完成登入、資料、備份、排程與輸出目錄驗收。
8. 新系統穩定使用一段時間後，再處理舊電腦。

建議預留 30～60 分鐘的停機搬移時段。第一次下載映像與建置可能另外需要 10～30 分鐘，視網路與電腦速度而定。

---

## 3. 舊電腦：搬移前準備

### 3.1 確認正在使用哪一版程式

在專案目錄執行：

```bash
cd /Users/steven/Project/asset-management
git status --short
git branch --show-current
git rev-parse HEAD
git log -1 --oneline
```

請把分支名稱與完整 commit ID 記在搬機紀錄中。

- 正式環境建議使用 `main`。
- 若目前跑的是尚未合併的功能分支，請先完成必要的 commit、merge 與 push；否則新電腦 clone `main` 後不會有該功能。
- `git status --short` 若有輸出，代表仍有未提交檔案。先確認每一項是否需要保留，不要直接換機。

### 3.2 確認舊系統目前健康

```bash
docker compose -p asset-management ps
```

正常時應看到六個服務：

| 服務 | 容器名稱 | 正常狀態 |
|---|---|---|
| PostgreSQL | `asset-postgres` | `running (healthy)` |
| Redis | `asset-redis` | `running (healthy)` |
| Business Services | `asset-business-services` | `running (healthy)` |
| External Materials | `asset-external-materials-service` | `running (healthy)` |
| BFF | `asset-bff` | `running (healthy)` |
| Frontend | `asset-frontend` | `running`；此服務未設 healthcheck |

若資料庫或 Business Services 不健康，先解決問題再匯出，避免把未知狀態搬到新電腦。

### 3.3 匯出最後一份 PostgreSQL 資料

```bash
./scripts/db-export.sh
ls -lh db/init/01_dump.sql
```

這個檔案是完整的 schema + data dump，也包含 Liquibase 執行紀錄。新電腦會先匯入它，再由 Liquibase 補上程式版本新增的變更。

可另外產生檔案指紋，確認傳輸前後檔案相同：

```bash
shasum -a 256 db/init/01_dump.sql
```

Linux 可改用：

```bash
sha256sum db/init/01_dump.sql
```

> `db/init/01_dump.sql` 含真實個人財務資料，而且已被 `.gitignore` 排除。請直接放入安全的搬機媒體，**不可用 `git add -f` 上傳**。

### 3.4 準備需要安全移交的檔案

至少準備：

- `db/init/01_dump.sql`
- 專案根目錄的 `.env`
- `~/.config/rclone/rclone.conf`（要保留 Google Drive 加密備份時）
- 應用程式排程輸出的 Excel／JSON 目錄
- rclone crypt 密碼的獨立備份；遺失後無法解密舊備份

排程輸出目錄的主機根路徑由 `.env` 的 `EXPORT_OUTPUT_DIR_HOST` 決定。實際要複製的是系統頁面中選擇的子資料夾，例如預設爬蟲輸出 `Project/SRPP/data/input`；不需要盲目複製整個使用者家目錄。

不需要搬移：

- `asset-redis-data`：Redis 是可重建的快取。
- `frontend/node_modules`、各 Java `target`：Docker 會重新建置。
- `data/*.mv.db`：Docker 正式執行路徑使用 PostgreSQL，這些是舊的 H2 本機資料。
- Docker volume 的內部目錄：請用 SQL dump 搬移，不要直接複製 volume 檔案。

### 3.5 最後切換前停止舊系統

最後一份 dump 完成後：

```bash
docker compose -p asset-management stop
```

`stop` 只停止容器，不會刪除舊資料。新系統通過驗收前，請保留舊電腦及其 Docker volume 作為回復點。

> 不要讓新舊兩套完整系統長時間同時運行。兩邊會使用相同的 Gmail、AI、Google Drive 與排程設定，可能造成重複寄信、重複分析或備份紀錄互相干擾。

---

## 4. 新電腦：安裝必要工具

### 4.1 必要軟體

- Git
- Docker Desktop（macOS／Windows），或 Docker Engine + Compose Plugin（Linux）
- 可存取此私人 GitLab repository 的帳號或 SSH key

全部服務都由 Docker 建置，因此只為了執行系統時，主機**不需要另外安裝** Java、Maven、Node.js、npm、PostgreSQL 或 Redis。

建議硬體：

- 記憶體至少 8 GB，若同時做開發建議 16 GB 以上
- 可用磁碟空間至少 10 GB，歷史資料與 Docker 映像增加時需更多空間
- 系統時區設為 `Asia/Taipei`

確認工具可用：

```bash
git --version
docker --version
docker compose version
```

Windows 建議啟用 WSL2，並在 WSL 的 Linux 目錄中 clone 與執行本專案，不要直接用舊版 Windows CMD 執行 Bash 腳本。

### 4.2 取得程式碼

SSH 方式：

```bash
mkdir -p ~/Project
cd ~/Project
git clone git@gitlab.com:mysaas4/asset-management.git
cd asset-management
git switch main
git pull --ff-only origin main
git log -1 --oneline
```

未設定 SSH key 時，可在有權限的情況下使用 HTTPS：

```bash
git clone https://gitlab.com/mysaas4/asset-management.git
```

`git log -1` 顯示的 commit 應與舊電腦的搬機紀錄一致。若刻意搬移特定分支，請將 `main` 換成已 push 的該分支名稱。

---

## 5. 新電腦：放入設定與資料

以下操作都在新電腦的專案根目錄進行。

### 5.1 建立 `.env`

若已從舊電腦安全移交 `.env`，將它放在專案根目錄，然後修改電腦相關路徑。若要重新設定：

```bash
cp .env.example .env
```

請填妥下列內容：

| 變數 | 是否必要 | 說明 |
|---|---|---|
| `POSTGRES_DB` | 必要 | 建議維持 `assets` |
| `POSTGRES_USER` | 必要 | 建議維持 `assets` |
| `POSTGRES_PASSWORD` | 必要 | 使用強密碼，不可保留範本的「請填入密碼」 |
| `GOOGLE_CLIENT_ID` | 正常登入必要 | Google OAuth Web 用戶端 ID |
| `GOOGLE_CLIENT_SECRET` | 正常登入必要 | Google OAuth 用戶端密鑰 |
| `MAIL_USERNAME` | 選用 | Gmail 通知寄件帳號；留空會停用寄信 |
| `MAIL_PASSWORD` | 選用 | Gmail 16 碼應用程式密碼，不是登入密碼 |
| `NOTIFICATION_FROM` | 選用 | 留空時使用 `MAIL_USERNAME` |
| `ANTHROPIC_API_KEY` | 選用 | 留空時 AI 分析安全跳過 |
| `ANTHROPIC_MODEL` | 選用 | 未指定時使用專案預設模型 |
| `FINMIND_TOKEN` | 選用 | FinMind token；可自行加在 `.env` |
| `EXPORT_OUTPUT_DIR_HOST` | **必要檢查** | 新電腦上可寫入的絕對路徑 |

`EXPORT_OUTPUT_DIR_HOST` 不可沿用別人的家目錄。例如：

```dotenv
# macOS
EXPORT_OUTPUT_DIR_HOST=/Users/alice

# Linux 或 WSL（擇一填寫，不要兩行同時保留）
EXPORT_OUTPUT_DIR_HOST=/home/alice
```

注意事項：

- `.env` 的等號兩側不要加空白。
- 不要把 `.env` commit 到 Git；專案已刻意忽略它。
- 本機以 HTTP 執行時，`SESSION_COOKIE_SECURE` 應維持未設定或 `false`。
- 只有在 HTTPS 已正確終止時才設定 `SESSION_COOKIE_SECURE=true`，否則登入 cookie 不會送回。

### 5.2 設定 Google OAuth 登入

Docker 版從 `http://localhost` 進入，Google Cloud Console 的 OAuth Web 用戶端至少要加入以下重新導向 URI：

```text
http://localhost/login/oauth2/code/google
```

若正式使用網域與 HTTPS，另加入：

```text
https://你的網域/login/oauth2/code/google
```

登入後，固定管理者帳號是 `tw.leader@gmail.com`；其他 Gmail 帳號第一次登入後會等待管理者核准。

### 5.3 放入資料庫 dump

將舊電腦匯出的檔案放到：

```text
db/init/01_dump.sql
```

確認檔案存在且大小合理：

```bash
ls -lh db/init/01_dump.sql
shasum -a 256 db/init/01_dump.sql
```

檔案指紋應與舊電腦相同。

> PostgreSQL 只會在 volume 第一次初始化時自動執行 `db/init/*.sql`。因此一定要在第一次啟動 PostgreSQL **之前**放好 dump。

### 5.4 準備 rclone 設定

Compose 固定掛載 `~/.config/rclone/rclone.conf`。即使暫時不用 Google Drive 備份，也請先建立這個檔案，避免 Docker 因來源不存在而把它誤建成資料夾：

```bash
mkdir -p ~/.config/rclone
touch ~/.config/rclone/rclone.conf
```

若要沿用既有備份，請用舊電腦的真實 `rclone.conf` 覆蓋空檔。若要重新建立，可先在主機安裝 rclone，再執行：

```bash
rclone config
rclone lsd gdrive-crypt:
```

設定中必須有專案使用的 `gdrive-crypt` remote。rclone crypt 密碼要另外保存在密碼管理器與離線備份中。

macOS Docker Desktop 通常可直接讀取使用者檔案。原生 Linux 若 Business Services 日誌出現 `Permission denied`，請讓容器內的非 root 使用者對該檔有唯讀權限；不要為了省事對整個家目錄使用 `chmod -R 777`。

### 5.5 準備排程輸出目錄

確認 `.env` 指定的根目錄存在、目前使用者可寫入，而且 Docker Desktop 已允許分享該路徑。再把舊電腦中需要保留的 Excel／JSON 子資料夾複製到相同相對位置。

例如新電腦設定：

```dotenv
EXPORT_OUTPUT_DIR_HOST=/Users/alice
```

資料庫內的爬蟲子路徑若為 `Project/SRPP/data/input`，實際主機路徑就是：

```text
/Users/alice/Project/SRPP/data/input
```

不需要把資料庫中的相對子路徑改成 `/Users/alice/...`；系統刻意只存相對路徑，以便跨電腦搬移。

---

## 6. 第一次啟動與資料匯入

### 6.1 啟動前檢查

```bash
docker compose -p asset-management config --quiet
test -f .env && echo '.env OK'
test -f db/init/01_dump.sql && echo 'database dump OK'
test -f "$HOME/.config/rclone/rclone.conf" && echo 'rclone config OK'
```

三個檔案都確認後，開始建置與啟動：

```bash
docker compose -p asset-management up -d --build
```

第一次會下載 PostgreSQL、Redis、Java、Node 與 Nginx 映像並編譯三個 Java 服務及前端，畫面暫時沒有反應不代表失敗。請等待指令結束。

### 6.2 查看啟動狀態

```bash
docker compose -p asset-management ps
```

PostgreSQL 第一次啟動時會匯入 dump；接著 Business Services 檢查 Liquibase，External Materials、BFF、Frontend 再依序啟動。若仍顯示 `starting`，等待約 30～90 秒後再查一次。

也可確認 BFF：

```bash
curl -fsS http://localhost:8080/actuator/health
```

預期結果包含：

```json
{"status":"UP"}
```

### 6.3 開啟系統

瀏覽器前往：

```text
http://localhost
```

使用 Google 登入。若直接呼叫受保護的 `/api/...` 得到 `401`，在沒有登入 cookie 時是正常現象，不代表 BFF 故障。

---

## 7. 搬機完成驗收

請由接手人逐項確認，不要只看到首頁就判定完成。

### 7.1 系統與登入

- [ ] 六個 Docker 服務皆為 running，該有 healthcheck 的服務皆為 healthy。
- [ ] `http://localhost` 可開啟，畫面樣式與功能正常。
- [ ] 管理者 Google 帳號可登入。
- [ ] 若有其他使用者，其狀態與權限仍正確。

### 7.2 核心資料

- [ ] 最新資產快照日期與舊電腦一致。
- [ ] 銀行、券商、存款、股票、基金與已實現損益資料存在。
- [ ] 觀察清單、警示條件、通知收件人仍存在。
- [ ] 歷史股價、匯率、股利、指數等圖表有資料。
- [ ] 各頁面的排程時間、啟用狀態與輸出路徑正確。

可用下列唯讀指令確認快照筆數不是意外歸零：

```bash
docker compose -p asset-management exec postgres sh -lc \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT COUNT(*) AS snapshot_count FROM asset_snapshot;"'
```

### 7.3 外部整合

- [ ] Gmail 通知設定可用；需要時執行一次實際測試寄送。
- [ ] AI 分析頁不是 `NOT_CONFIGURED`，或確認本來就刻意停用。
- [ ] 「備份／還原資料」頁可同步 Google Drive 清單並完成一次手動備份。
- [ ] 排程匯出可在新電腦指定的目錄產生檔案。
- [ ] 公開資訊爬蟲可在設定的子路徑寫出 JSON。

### 7.4 切換完成

- [ ] 記錄新電腦的 commit ID、搬移時間、dump 指紋與驗收人。
- [ ] 確認舊電腦仍維持停止，避免重複排程。
- [ ] 保留舊電腦原始資料與加密搬機包至少 7 天，或依組織備份政策辦理。

---

## 8. 常見問題與處理方式

### 問題 A：首頁打不開

先看狀態與最近日誌：

```bash
docker compose -p asset-management ps
docker compose -p asset-management logs --tail=200 frontend bff business-services
```

常見原因是 80 或 8080 已被其他程式使用。macOS／Linux 可查：

```bash
lsof -nP -iTCP:80 -sTCP:LISTEN
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

### 問題 B：Google 顯示 `redirect_uri_mismatch`

確認 Google Cloud Console 已登記完全相同的：

```text
http://localhost/login/oauth2/code/google
```

協定、主機、連接埠與路徑都必須完全一致。修改 OAuth 設定後可能需要稍候才生效。

### 問題 C：系統能開，但資料全部是空的

最常見原因是 PostgreSQL 已先建立空 volume，之後才放入 `01_dump.sql`。先確認 dump 存在：

```bash
ls -lh db/init/01_dump.sql
```

若新電腦的空資料確定可以刪除，才能執行以下重建：

```bash
docker compose -p asset-management down -v
docker compose -p asset-management up -d --build
```

> **警告**：`down -v` 會永久刪除這台電腦目前的 PostgreSQL 與 Redis volume。只可在確認新機資料可捨棄、且安全 dump 仍存在時使用。不要把它當成一般停止指令。

### 問題 D：修改 `.env` 的資料庫密碼後無法連線

`POSTGRES_PASSWORD` 只在 PostgreSQL volume 第一次建立時生效。若已有資料 volume，單純改 `.env` 不會同步修改資料庫內的密碼。

- 搬機初期且資料可重建：確認 dump 後用上一節的 `down -v` 重新初始化。
- 已正式使用且有新資料：不要刪 volume，請由資料庫管理者正確修改 PostgreSQL 角色密碼。

### 問題 E：備份頁無法連 Google Drive

```bash
docker compose -p asset-management logs --tail=200 business-services
docker compose -p asset-management exec business-services \
  rclone --config /tmp/rclone.conf lsd gdrive-crypt:
```

檢查 `rclone.conf` 是否存在、可讀，remote 名稱是否正確，以及 crypt 密碼是否與舊備份一致。

### 問題 F：排程顯示成功，但主機找不到輸出檔

依序確認：

1. `.env` 的 `EXPORT_OUTPUT_DIR_HOST` 是新電腦的絕對路徑。
2. Business Services 與 External Materials 使用同一個根目錄掛載。
3. Docker Desktop 已允許分享該路徑。
4. 頁面儲存的是相對子路徑，不是舊電腦的絕對路徑。

修改 `.env` 後要重建受影響容器：

```bash
docker compose -p asset-management up -d --force-recreate business-services external-materials-service
```

### 問題 G：程式碼已更新，但畫面還是舊版

先確認 checkout 的 commit：

```bash
git branch --show-current
git log -1 --oneline
```

再從這個專案目錄無快取重建並重建容器：

```bash
docker compose -p asset-management build --no-cache \
  business-services external-materials-service bff frontend
docker compose -p asset-management up -d --force-recreate \
  business-services external-materials-service bff frontend
```

最後重新開啟瀏覽器頁面。若仍不一致，可確認目前容器是由哪個專案目錄建立：

```bash
docker inspect asset-frontend \
  --format '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}'
```

顯示的路徑必須是你剛才更新並建置的 checkout。

### 問題 H：某個服務一直 unhealthy

查看該服務最近 200 行日誌：

```bash
docker compose -p asset-management logs --tail=200 postgres
docker compose -p asset-management logs --tail=200 business-services
docker compose -p asset-management logs --tail=200 external-materials-service
docker compose -p asset-management logs --tail=200 bff
```

不要一看到 unhealthy 就執行 `down -v`；先保留資料並從第一個出錯的服務往後排查。

---

## 9. 日常啟停、更新與備份

啟動：

```bash
docker compose -p asset-management up -d
```

停止但保留資料：

```bash
docker compose -p asset-management stop
```

查看狀態：

```bash
docker compose -p asset-management ps
```

查看最近日誌：

```bash
docker compose -p asset-management logs --tail=200
```

更新正式版前，先做資料庫備份，再更新程式：

```bash
./scripts/db-export.sh
git switch main
git pull --ff-only origin main
docker compose -p asset-management up -d --build
docker compose -p asset-management ps
```

一般日常操作不要執行 `docker compose down -v`。

---

## 10. 搬機紀錄範本

建議把以下內容存放在不含密碼的維運紀錄中：

```text
搬移日期：
來源電腦：
目的電腦：
Git branch：
Git commit：
資料庫 dump 檔名：
資料庫 dump SHA-256：
EXPORT_OUTPUT_DIR_HOST：
舊系統停止時間：
新系統啟用時間：
Google 登入驗收：通過／不通過
核心資料驗收：通過／不通過
Google Drive 備份驗收：通過／不通過／未啟用
通知寄信驗收：通過／不通過／未啟用
排程輸出驗收：通過／不通過／未啟用
驗收人：
備註：
```

這份紀錄不可包含 `.env` 內容、密碼、API key、OAuth secret 或 rclone crypt 密碼。
