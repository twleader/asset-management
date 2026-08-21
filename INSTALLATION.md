# 資產管理系統安裝與使用手冊

這份手冊是給第一次安裝本系統、沒有資訊背景的一般使用者。只要依自己的電腦類型逐步操作，不需要先懂 Docker、Java 或資料庫。

本手冊建立的是一套全新的空白系統，不會帶入原作者或其他人的財務資料，也不是舊電腦搬家指南。

---

## 0. 先用 5 分鐘了解安裝流程

整個安裝分成六件事：

1. 安裝 Docker。
2. 取得並解壓縮本系統的正式安裝包。
3. 建立自己的 `.env` 設定檔。
4. 向 Google 申請登入用的 Client ID 與 Client Secret。
5. 用一行指令啟動系統。
6. 用你在 `ADMIN_EMAIL` 填寫的 Google 帳號登入。

### 本手冊會用到的名詞

| 名詞 | 白話說明 |
|---|---|
| Docker | 把本系統和它需要的軟體包在一起執行的工具。安裝 Docker 後，不必另外安裝 Java、Node.js、PostgreSQL 或 Redis。 |
| Docker Desktop | macOS／Windows 上有圖形介面的 Docker 應用程式，圖示是一隻鯨魚。 |
| 終端機 | 輸入文字指令的視窗。macOS 叫「終端機」，Windows 本手冊使用「Ubuntu」，Linux 直接用 Terminal。 |
| 專案根目錄 | 解壓縮後，能看到 `docker-compose.yml` 的那個資料夾。所有啟動指令都要在這裡執行。 |
| `.env` | 只屬於這台電腦的設定檔，內含密碼與 Google Secret，不可傳給別人。 |
| 容器 | Docker 中正在執行的一個服務。本系統正常時會有六個主要容器。 |

看到灰底指令區塊時：可以整段複製，貼進終端機，再按 Enter。指令前面的 `$` 若出現在其他網站範例中，不需要一起複製；本手冊的指令已省略 `$`。

---

## 1. 安裝前檢查表

### 1.1 電腦與帳號

- 記憶體至少 8 GB，建議 16 GB。
- 可用磁碟空間至少 15 GB。
- 64 位元、仍受原廠支援的 macOS、Windows 10/11 或 Ubuntu。
- 穩定的網路連線；第一次建置會下載數 GB 的檔案。
- 一個你能登入的 Google 帳號；它將成為這套安裝的主要管理者。
- 系統時區建議設為 `Asia/Taipei`。

### 1.2 必裝與選裝軟體

| 軟體 | 是否必裝 | 用途 |
|---|---|---|
| Docker Desktop（macOS／Windows）或 Docker Engine（Ubuntu） | **必裝** | 執行整套系統 |
| 瀏覽器 | **必裝** | 使用系統與設定 Google 登入 |
| Git | 選裝 | 只有透過 Git repository 取得或更新系統時才需要；收到 ZIP 不需要 |
| rclone | 選裝 | 只有使用 Google Drive 加密備份時才需要 |
| 文字編輯器 | 不必另裝 | 本手冊使用系統內建的 `nano` 編輯 `.env` |

只為了使用本系統，**不需要**另外安裝 Java、Maven、Node.js、npm、PostgreSQL 或 Redis。

### 1.3 請只從官方網站下載軟體

- [Docker Desktop for Mac 官方安裝說明](https://docs.docker.com/desktop/setup/install/mac-install/)
- [Docker Desktop for Windows 官方安裝說明](https://docs.docker.com/desktop/setup/install/windows-install/)
- [Microsoft WSL 官方安裝說明](https://learn.microsoft.com/windows/wsl/install)
- [Docker Engine for Ubuntu 官方安裝說明](https://docs.docker.com/engine/install/ubuntu/)
- [Git 官方下載頁](https://git-scm.com/downloads)
- [rclone 官方安裝說明](https://rclone.org/install/)

不要從不明下載站取得 Docker、Git 或 rclone。

---

## 2. 安裝 Docker：只看自己的作業系統

macOS 看 2.1，Windows 看 2.2，Ubuntu Linux 看 2.3。完成其中一節後直接跳到第 3 節。

### 2.1 macOS：安裝 Docker Desktop

#### 步驟 A：確認 Mac 晶片

1. 點畫面左上角蘋果圖示。
2. 點「關於這台 Mac」。
3. 查看「晶片」或「處理器」：
   - 顯示 Apple M1、M2、M3、M4 或後續 M 系列：選 **Apple silicon**。
   - 顯示 Intel：選 **Intel chip**。

#### 步驟 B：下載與安裝

1. 開啟 [Docker Desktop for Mac 官方頁面](https://docs.docker.com/desktop/setup/install/mac-install/)。
2. 下載符合晶片的版本。
3. 雙擊下載的 `Docker.dmg`。
4. 把 Docker 圖示拖到 Applications（應用程式）資料夾。
5. 到「應用程式」開啟 Docker。
6. 第一次啟動若出現安全性或管理者密碼提示，依畫面允許必要設定。
7. 閱讀並接受 Docker 的使用條款。
8. 等右上角鯨魚圖示穩定，Docker Desktop 顯示 Engine running。

Docker Desktop 支援的 macOS 版本會隨時間調整，請以官方頁面的 System requirements 為準。企業或政府單位也應先確認 Docker Desktop 授權是否適用。

#### 步驟 C：確認安裝成功

1. 按 `Command + 空白鍵`。
2. 輸入「終端機」或 `Terminal`，按 Enter。
3. 逐行執行：

```bash
docker --version
docker compose version
docker run --rm hello-world
```

前兩行會顯示版本；最後看到 `Hello from Docker!` 就成功。若顯示無法連線到 Docker daemon，先開啟 Docker Desktop 並等待 Engine running。

### 2.2 Windows 10／11：安裝 WSL 2 與 Docker Desktop

本手冊使用 WSL 2。它會在 Windows 裡提供一個 Ubuntu 終端機，之後所有本系統指令都在 Ubuntu 視窗執行，不使用舊版 CMD。

#### 步驟 A：安裝或更新 WSL

1. 按 Windows 鍵，輸入 `PowerShell`。
2. 在「Windows PowerShell」或「終端機」按右鍵，選「以系統管理員身分執行」。
3. 執行：

```powershell
wsl --install
```

4. 若系統要求，重新啟動電腦。
5. 重開後從開始功能表開啟「Ubuntu」。第一次開啟會請你建立 Linux 使用者名稱與密碼：
   - 使用者名稱可用小寫英文，例如 `alice`。
   - 輸入密碼時畫面不會顯示星號，這是正常的；輸入完按 Enter。
6. 在 Ubuntu 安裝本手冊會用到的基本工具：

```bash
sudo apt update
sudo apt install -y curl nano
```

7. 再回到 PowerShell 執行：

```powershell
wsl --update
wsl --version
```

Docker 官方目前要求 WSL 2.1.5 或更新版本；版本條件可能改變，請以 [Docker Windows 官方頁](https://docs.docker.com/desktop/setup/install/windows-install/) 為準。

#### 步驟 B：安裝 Docker Desktop

1. 從 [Docker Desktop for Windows 官方頁面](https://docs.docker.com/desktop/setup/install/windows-install/) 下載安裝程式。
2. 雙擊 `Docker Desktop Installer.exe`。
3. 一般個人電腦可選官方建議的 per-user 安裝模式。
4. 若畫面出現 `Use WSL 2 instead of Hyper-V`，保持勾選。
5. 完成後從開始功能表開啟 Docker Desktop。
6. 閱讀並接受使用條款。
7. 到 Docker Desktop 的 Settings：
   - General：確認使用 WSL 2 based engine。
   - Resources → WSL Integration：確認 Ubuntu 已啟用。
8. 等 Docker Desktop 顯示 Engine running。

若 WSL 安裝失敗並提到 virtualization，通常需要在 BIOS／UEFI 開啟硬體虛擬化。這會因電腦品牌而不同，請依電腦廠商說明操作；不確定時請找熟悉電腦的人協助，不要隨意修改其他 BIOS 設定。

#### 步驟 C：確認安裝成功

從開始功能表開啟 **Ubuntu**，不是 PowerShell，執行：

```bash
docker --version
docker compose version
docker run --rm hello-world
```

看到版本與 `Hello from Docker!` 就成功。

### 2.3 Ubuntu Linux：安裝 Docker Engine 與 Compose Plugin

以下適用 Docker 官方目前支援的 64 位元 Ubuntu 版本。Linux Mint 等衍生版可能能使用，但不在本手冊正式支援範圍；支援版本請查 [Docker Ubuntu 官方頁](https://docs.docker.com/engine/install/ubuntu/)。

1. 按 `Ctrl + Alt + T` 開啟終端機。
2. 移除可能衝突的舊套件。顯示「沒有安裝」可以忽略：

```bash
sudo apt remove -y docker.io docker-compose docker-compose-v2 docker-doc podman-docker containerd runc
```

3. 加入 Docker 官方軟體來源：

```bash
sudo apt update
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources > /dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt update
```

4. 安裝 Docker Engine、Buildx 與 Compose Plugin：

```bash
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

5. 讓目前使用者日後不必每次加 `sudo`：

```bash
sudo usermod -aG docker "$USER"
```

6. 登出 Ubuntu 再登入，或重新啟動電腦。這一步不可省略。
7. 開新終端機確認：

```bash
docker --version
docker compose version
docker run --rm hello-world
```

看到版本與 `Hello from Docker!` 就成功。若仍出現 permission denied，先確認已登出再登入，不要用 `chmod 777` 修改 Docker socket。

---

## 3. 取得正式安裝包

### 3.1 建議方式：版本化 ZIP

向系統提供者取得有明確版本號的正式 ZIP。提供者應同時告知：

- 版本號或 Git commit ID。
- 發行日期與更新說明。
- ZIP 的 SHA-256 指紋。
- 問題回報方式。

解壓縮後，應看到一個包含 `docker-compose.yml` 的資料夾。

#### macOS

1. 在 Finder 雙擊 ZIP 解壓縮。
2. 建議把資料夾移到自己的「文件」資料夾。
3. 開啟終端機，輸入 `cd` 和一個空白，先不要按 Enter。
4. 把解壓縮後的資料夾拖進終端機視窗，再按 Enter。

#### Windows

1. 在檔案總管對 ZIP 按右鍵，選「全部解壓縮」。
2. 從開始功能表開啟 Ubuntu，執行 `whoami`，記住顯示的 Linux 使用者名稱。
3. 在 Windows 檔案總管網址列輸入 `\\wsl$\Ubuntu\home\你的Linux使用者名稱`。
4. 把解壓縮後的整個專案資料夾複製到這裡。
5. 回到 Ubuntu，執行 `cd ~/資料夾名稱`。資料夾名稱若不確定，可先執行 `ls` 查看。

不要把專案長期放在 `/mnt/c/...` 直接建置；放在 WSL 的 Linux 家目錄通常較穩定且較快。

#### Ubuntu Linux

在檔案管理員解壓縮後，開啟終端機，輸入 `cd` 和一個空白，把資料夾拖入終端機，再按 Enter。

### 3.2 確認目前位置正確

在終端機執行：

```bash
pwd
ls
test -f docker-compose.yml && echo "位置正確"
```

看到 `位置正確` 才繼續。至少應有：

```text
docker-compose.yml
.env.example
backend/
bff/
external-materials-service/
frontend/
scripts/
```

### 3.3 選用：透過 Git 取得

只有提供者給你 repository 網址、且你有權限時才使用：

```bash
git clone 提供者給你的_repository_網址 asset-management
cd asset-management
```

macOS 執行 `git --version` 時若跳出安裝 Command Line Tools，依畫面安裝即可。Windows 的 Ubuntu 或 Ubuntu Linux 可用以下方式安裝 Git：

```bash
sudo apt update
sudo apt install -y git
```

### 3.4 安裝包安全檢查

正式安裝包不應包含：

- `.env`
- `db/init/01_dump.sql`
- `rclone.conf`
- 資產 Excel、公開資訊 JSON、備份檔或其他人的財務資料
- 真實 Email、API key、密碼或 OAuth secret

如果看到上述內容，請停止安裝並通知提供者。

---

## 4. 建立這台電腦的設定檔

以下指令都要在能看到 `docker-compose.yml` 的專案根目錄執行。

### 4.1 複製設定範本

```bash
cp .env.example .env
nano .env
```

`nano` 是文字編輯器：

- 用方向鍵移動。
- 修改完成按 `Ctrl + O`，再按 Enter 儲存。
- 按 `Ctrl + X` 離開。

`.env` 內含秘密，不可寄給別人、上傳 Git 或放進未加密的共用空間。

### 4.2 一定要填的設定

至少確認以下內容已換成你自己的值：

```dotenv
POSTGRES_DB=assets
POSTGRES_USER=assets
POSTGRES_PASSWORD=請換成至少24字元的資料庫密碼

ADMIN_EMAIL=你的完整Google帳號
GOOGLE_CLIENT_ID=你的Google_Client_ID
GOOGLE_CLIENT_SECRET=你的Google_Client_Secret

EXPORT_OUTPUT_DIR_HOST=${HOME}
```

填寫規則：

- 等號左右不要留空白。
- 不要保留「請填入」或「你的」等範例文字。
- `ADMIN_EMAIL` 必須是你稍後實際登入的完整 Google 帳號，英文字母大小寫不影響判定。
- `POSTGRES_PASSWORD` 建議用密碼管理器產生至少 24 字元；為避免 `.env` 解析問題，可使用英文字母、數字、底線與連字號。
- `EXPORT_OUTPUT_DIR_HOST=${HOME}` 會使用目前使用者的家目錄，通常不必修改。
- 本機使用 `http://localhost` 時，`SESSION_COOKIE_SECURE` 保持未設定或 `false`；只有完成 HTTPS 部署後才改成 `true`。

用以下指令確認家目錄：

```bash
echo "$HOME"
```

若要把排程輸出放在其他磁碟，`EXPORT_OUTPUT_DIR_HOST` 必須填這台電腦可寫入的絕對路徑。macOS 使用 `/Users/名稱/...`，Ubuntu／WSL 使用 `/home/名稱/...`，不可照抄別人的路徑。

### 4.3 先建立選用備份所需的空設定檔

即使暫時不用 Google Drive 備份，也先執行：

```bash
mkdir -p ~/.config/rclone
touch ~/.config/rclone/rclone.conf
```

這只建立一個空檔，不會連接 Google Drive。

---

## 5. 申請 Google 登入憑證

每一套自行安裝的系統都應建立自己的 Google OAuth 憑證，不可共用系統提供者的 Client Secret。Google Cloud 畫面名稱可能隨版本調整；若看到「Google Auth Platform」，其 Branding、Audience、Clients 分頁就是過去的 OAuth consent screen／Credentials 功能。

### 5.1 建立 Google Cloud Project

1. 開啟 [Google Cloud Console](https://console.cloud.google.com/)。
2. 用準備當主要管理者的 Google 帳號登入。
3. 點上方 Project 選擇器，選 New project／新增專案。
4. 專案名稱可填「我的資產管理系統」，建立後切換到該專案。

### 5.2 設定 OAuth 同意畫面

1. 開啟 Google Auth Platform，或「API 和服務 → OAuth consent screen」。
2. 填寫應用程式名稱與使用者支援 Email。
3. 個人 Gmail 通常選 External／外部。Google Workspace 組織可依管理政策選 Internal。
4. Audience 若維持 Testing／測試，將 `.env` 中的 `ADMIN_EMAIL` 加入 Test users；其他要登入的人也要逐一加入。
5. 本系統登入只需要基本的 `openid`、`profile`、`email` 身分範圍，不要自行加入讀取 Gmail、Drive 等額外 scope。

External 應用程式在 Testing 狀態只允許 Test users 使用；限制與上限請以 [Google 官方 Audience 說明](https://support.google.com/cloud/answer/15549945) 為準。

### 5.3 建立 Web application Client

1. 到 Clients／憑證。
2. 點 Create client／建立 OAuth 用戶端 ID。
3. Application type 選 **Web application**。
4. 名稱可填「資產管理系統本機」。
5. 在 Authorized redirect URIs 加入以下完整網址：

```text
http://localhost/login/oauth2/code/google
```

6. 建立後複製 Client ID 與 Client Secret。
7. 回到終端機執行 `nano .env`，填入：

```dotenv
GOOGLE_CLIENT_ID=剛才複製的Client_ID
GOOGLE_CLIENT_SECRET=剛才複製的Client_Secret
```

Redirect URI 的 `http`、主機、連接埠、大小寫、路徑與尾端斜線都必須完全一致，否則 Google 會顯示 `redirect_uri_mismatch`；詳見 [Google OAuth Web Server 官方說明](https://developers.google.com/identity/protocols/oauth2/web-server)。Client Secret 不可放入 ZIP、Git 或截圖公開。

若日後改用正式網域，必須先完成 HTTPS，再另外登記：

```text
https://你的網域/login/oauth2/code/google
```

---

## 6. 第一次啟動

### 6.1 啟動前自動檢查

確認 Docker Desktop 已是 Engine running；Ubuntu 確認 Docker service 正在執行。然後在專案根目錄執行：

```bash
docker compose -p asset-management config --quiet
test -f .env && echo ".env 存在"
test -f "$HOME/.config/rclone/rclone.conf" && echo "rclone 設定檔存在"
```

沒有錯誤，並看到後兩行「存在」才繼續。若第一行顯示 `請在 .env 設定 ADMIN_EMAIL`，回第 4 節修正；若顯示 Google 或資料庫設定缺漏，也先修正 `.env`。

### 6.2 建置並啟動七個服務

```bash
docker compose -p asset-management up -d --build
```

第一次會下載基礎映像並編譯程式，依電腦與網路速度可能需要 10～30 分鐘。畫面暫時沒有新文字不一定是當機，請先等待；不要關閉 Docker Desktop 或讓電腦休眠。

指令結束後等待約 1～2 分鐘，再執行：

```bash
docker compose -p asset-management ps
```

正常狀態：

| 容器名稱 | 預期狀態 |
|---|---|
| `asset-postgres` | running、healthy |
| `asset-redis` | running、healthy |
| `asset-business-services` | running、healthy |
| `asset-external-materials-service` | running、healthy |
| `asset-bff` | running、healthy |
| `asset-api-gateway` | running、healthy |
| `asset-frontend` | running；此服務沒有 healthcheck |

第一次空白安裝會由 Liquibase 自動建立資料表與基礎設定，不需要原作者的資料庫 dump。

再檢查入口服務：

```bash
curl -fsS http://127.0.0.1:9090/api/quotes
```

看到 JSON array（可為空）代表本機 API gateway 與 quote upstream 正常。

Docker 外部 API 的唯一本機入口為 `http://127.0.0.1:9090`，只有九條 exact route——
八條唯讀 GET：`/api/quotes`、`/api/quotes/one`、`/api/public/market-index`、
`/api/assets/latest`、`/api/public/exchange-rate/usd-twd`、
`/api/public/market-analysis/today`、`/api/public/portfolio-advice/latest`、
`/api/public/trading-radar/today`，
以及一條寫入 POST：`/api/public/crawler-data/rescan`（免登入觸發重新搜尋，
經 business 端 30 秒全域冷卻節流）。BFF 8080 與 external-materials 8082
不再發布到 host。

`trading-radar/today` 固定讀取 `.env` 主要管理者的持倉／觀察清單、動作與證據；
任何能到達本機 loopback 或獲准 Tailscale identity 的人都可免登入讀取，請勿將
9090 暴露到公網。九支 API 的完整 OpenAPI 3.1 契約在
`docs/openapi/docker-external-api.yaml`；Swagger UI 與 YAML 本身並沒有掛在 9090。

### 6.3 開啟系統並登入

1. 用瀏覽器開啟 [http://localhost](http://localhost)。
2. 選擇 Google 登入。
3. **一定要使用 `.env` 的 `ADMIN_EMAIL` 帳號登入。**
4. 第一次登入後，該帳號會自動建立為 `ADMIN`、`ACTIVE`，並在使用者管理頁標示「主要管理者」。
5. 主要管理者不可被停用或調降角色。

其他 Google 帳號第一次登入會是「待核准」，這是正常的。主要管理者登入後到「使用者管理」核准即可。

---

## 7. 第一次使用建議順序

1. 確認主要管理者能進入首頁與使用者管理頁。
2. 到設定頁檢查銀行、券商、存款類型、市場類型及資產分類。
3. 輸入或匯入自己的第一份資產資料。
4. 檢查首頁總資產與各分類數字。
5. 再依需要啟用 Email、AI、排程匯出及 Google Drive 備份。
6. 啟用備份後先做一次手動備份並確認能看到檔案。

不要一開始就啟用所有排程。先確認資料、時區、收件人與輸出路徑，避免誤寄 Email 或把檔案寫到錯誤位置。

---

## 8. 選用功能與軟體

### 8.1 Gmail 警示通知

這不是登入用的 Client Secret。若要由 Gmail 寄信：

1. 在寄件 Google 帳號開啟兩步驟驗證。
2. 依 [Google 官方應用程式密碼說明](https://support.google.com/accounts/answer/185833) 建立 16 碼應用程式密碼。
3. 在 `.env` 填入：

```dotenv
MAIL_USERNAME=寄件Google帳號
MAIL_PASSWORD=16碼應用程式密碼（不要空白）
NOTIFICATION_FROM=寄件Google帳號
```

4. 重新建立 Business Services：

```bash
docker compose -p asset-management up -d --force-recreate business-services
```

未設定時系統仍可使用，只是不寄通知。

### 8.2 Google Drive 加密備份與 rclone

只有要使用此功能才安裝 rclone。請依 [rclone 官方安裝說明](https://rclone.org/install/) 選擇自己的作業系統；macOS／Ubuntu／WSL 也可使用官方安裝指令：

```bash
sudo -v
curl https://rclone.org/install.sh | sudo bash
rclone version
```

接著執行：

```bash
rclone config
rclone lsd gdrive-crypt:
```

設定中必須有名稱為 `gdrive-crypt` 的 remote。`crypt` 密碼遺失後無法解密舊備份，請保存在密碼管理器與安全的離線位置。不要把 `~/.config/rclone/rclone.conf` 傳給別人。

若不使用 Google Drive 備份，保持第 4.3 節建立的空檔即可，不必安裝 rclone。

### 8.3 AI 市場分析與資產配置建議

向服務提供者申請自己的 API key，再填入：

```dotenv
ANTHROPIC_API_KEY=你的API_Key
ANTHROPIC_MODEL=發行版本建議的模型名稱
```

使用 AI 功能可能產生費用，也可能將分析所需的市場或理財條件送往外部服務。未填時相關功能安全停用，不阻擋系統啟動。

### 8.4 FinMind token

需要提高相關行情來源的使用額度時，在 `.env` 加入：

```dotenv
FINMIND_TOKEN=你的Token
```

未填時系統仍會使用其他可用資料來源，但部分資料可能受限。

### 8.5 Tailscale 私網 HTTPS（選用）

先安裝 Tailscale 並在 app 中登入同一個 tailnet；macOS 可使用
`brew install --cask tailscale-app`。確認上述九條本機 API 都健康後執行：

```bash
scripts/configure-tailscale-api-gateway.sh
```

腳本在任何 Serve reset 前，會為九支本機 API 各自保存 response headers/body：八條唯讀 GET 逐支
要求 HTTP 200、`application/json` 與既定 payload 契約，寫入用的 `crawler-data/rescan` 則只以 GET
驗證回 `405` 帶 `Allow: POST`（刻意不發 POST，避免每次執行都真的觸發一輪對外抓取）；任一路失敗即
停止且不會 reset。可先執行
`scripts/tests/configure-tailscale-api-gateway-test.sh` 驗證 Content-Type fail-closed 與九路成功流程。

腳本只會建立九條 path-scoped HTTPS `:9090`：quotes、quotes/one、market-index、
assets/latest、USD/TWD 公開匯率、crawler-data/rescan、market-analysis/today、
portfolio-advice/latest、trading-radar/today。不需要購買憑證、自簽憑證或再加 OAuth2；TLS 與
tailnet identity 由 Tailscale 管理。腳本不會啟用 Funnel，也不會建立 `/`、`/api/`
萬用代理或額外 handler，看到陌生 Serve handler 時也不會自動 reset。

---

## 9. 日常啟動、停止與查看狀態

開機後先啟動 Docker Desktop。到專案根目錄執行：

```bash
docker compose -p asset-management up -d
```

停止但保留全部資料：

```bash
docker compose -p asset-management stop
```

查看狀態：

```bash
docker compose -p asset-management ps
```

查看最近 200 行紀錄：

```bash
docker compose -p asset-management logs --tail=200
```

一般停止、重新啟動電腦或 `docker compose down` 不會刪除資料庫 volume。

---

## 10. 備份與更新

### 10.1 手動匯出資料庫

```bash
./scripts/db-export.sh
```

產生的 `db/init/01_dump.sql` 含完整私人資料。請保存在加密位置，不可放回發行 ZIP、上傳 Git 或傳給其他使用者。

### 10.2 更新前必做

1. 先匯出資料庫或在備份頁完成備份。
2. 閱讀提供者的版本說明與相容性提醒。
3. 另存自己的 `.env` 與 `rclone.conf`；不可用新版範本直接覆蓋。
4. 確認 Docker Desktop／Docker Engine 正常。
5. 依提供者的 ZIP 或 Git 方式更新程式。
6. 在更新後的專案根目錄執行：

```bash
docker compose -p asset-management config --quiet
docker compose -p asset-management up -d --build
docker compose -p asset-management ps
```

Liquibase 會自動套用新版資料庫結構，不要手動編輯歷史 migration。

### 10.3 更換主要管理者的注意事項

日後修改 `.env` 的 `ADMIN_EMAIL` 並重建 Business Services，會改變「不可停用／不可降級」的主要管理者帳號，但**不會把舊帳號的資產自動搬給新帳號**。舊帳號與它的資料仍會保留；新主要管理者可用管理者代看功能查看或管理。正式更換前請先備份，並確認新帳號已加入 Google OAuth Test users。

---

## 11. 常見問題

### 11.1 `docker: command not found`

- macOS／Windows：Docker Desktop 尚未安裝完成，或安裝後尚未重新開啟終端機。
- Windows：確認指令是在 Ubuntu 視窗執行，並在 Docker Desktop 開啟 Ubuntu 的 WSL Integration。
- Ubuntu：回第 2.3 節確認 `docker-ce-cli` 與 Compose Plugin 已安裝。

### 11.2 `Cannot connect to the Docker daemon`

- macOS／Windows：開啟 Docker Desktop，等到 Engine running。
- Ubuntu：執行 `sudo systemctl start docker`；若是權限錯誤，登出再登入以套用 docker 群組。

### 11.3 Compose 顯示必填設定錯誤

若看到 `請在 .env 設定 ADMIN_EMAIL`，執行：

```bash
nano .env
```

確認有且只有一行 `ADMIN_EMAIL=完整Google帳號`，沒有引號或提示文字。其他必填值也不可空白。

### 11.4 首頁打不開

```bash
docker compose -p asset-management ps
docker compose -p asset-management logs --tail=200 frontend bff business-services
```

先找第一個不是 running／healthy 的服務。不要一看到錯誤就刪除 volume。

若 80 或 9090 已被其他程式使用，macOS／Ubuntu 可查：

```bash
lsof -nP -iTCP:80 -sTCP:LISTEN
lsof -nP -iTCP:9090 -sTCP:LISTEN
```

### 11.5 Google 顯示 `redirect_uri_mismatch`

確認 Google Console 登記的是完全相同的：

```text
http://localhost/login/oauth2/code/google
```

請固定從 `http://localhost` 開啟，不要改用 `http://127.0.0.1`。Google 會把它們視為不同網址。

### 11.6 Google 顯示沒有權限或應用程式仍在測試

到 Google Auth Platform 的 Audience／Test users，加入正在登入的 Google 帳號。主要管理者必須加入，其他預計使用者也要加入。若填錯帳號，修正後可能需要等待幾分鐘再試。

### 11.7 主要管理者登入後卻顯示等待核准

1. 比對 `.env` 的 `ADMIN_EMAIL` 與瀏覽器實際選擇的 Google 帳號。
2. 執行 `docker compose -p asset-management config | grep ADMIN_EMAIL`，確認容器收到正確值。
3. 修改 `.env` 後執行：

```bash
docker compose -p asset-management up -d --force-recreate business-services
```

4. 登出系統，再用正確帳號重新登入。

不要直接用 SQL 修改角色；主要管理者規則由 `ADMIN_EMAIL` 統一判定。

### 11.8 修改資料庫密碼後無法連線

`POSTGRES_PASSWORD` 只在 PostgreSQL volume 第一次建立時初始化。已經有資料後，單純修改 `.env` 不會同步修改資料庫內的密碼。

有正式資料時不要刪除 volume。請還原原密碼，或由熟悉 PostgreSQL 的管理者同時修改資料庫角色密碼。

### 11.9 備份頁無法連 Google Drive

```bash
docker compose -p asset-management logs --tail=200 business-services
docker compose -p asset-management exec business-services \
  rclone --config /tmp/rclone.conf lsd gdrive-crypt:
```

檢查 `rclone.conf` 是否存在且可讀、remote 名稱是否為 `gdrive-crypt`、crypt 密碼是否正確。不要對整個家目錄執行 `chmod -R 777`。

### 11.10 排程找不到輸出檔

確認：

1. `.env` 的 `EXPORT_OUTPUT_DIR_HOST` 是這台電腦可寫入的路徑。
2. Docker Desktop 已允許分享該路徑。
3. 系統頁面選的是相對子路徑，不是別台電腦的絕對路徑。

修改後執行：

```bash
docker compose -p asset-management up -d --force-recreate \
  business-services external-materials-service
```

### 11.11 更新後仍看到舊畫面

```bash
docker compose -p asset-management build --no-cache \
  business-services external-materials-service bff frontend
docker compose -p asset-management up -d --force-recreate \
  business-services external-materials-service bff frontend
```

完成後重新整理瀏覽器，必要時關閉舊分頁再開啟。

### 11.12 某個服務一直 unhealthy

```bash
docker compose -p asset-management logs --tail=200 postgres
docker compose -p asset-management logs --tail=200 business-services
docker compose -p asset-management logs --tail=200 external-materials-service
docker compose -p asset-management logs --tail=200 bff
```

從第一個出錯的服務開始處理。把錯誤訊息、系統版本與 `docker compose ... ps` 結果交給提供者；不要把 `.env` 或完整資料庫一起傳送。

---

## 12. 隱私與安全

### 12.1 資料放在哪裡

資產、設定、警示與歷史資料主要存放在這台電腦的 Docker PostgreSQL volume。一般停止或重開機不會刪除。

### 12.2 可能連接的外部服務

- Google OAuth：確認登入身分。
- Gmail SMTP：使用者啟用後寄送通知。
- Anthropic API：啟用 AI 功能時，分析所需的市場或理財條件可能送往外部服務。
- 市場資料來源：查詢股票、基金、匯率與公開資訊。
- Google Drive／rclone：使用者主動啟用的加密資料庫備份。

若不接受某項外部資料處理，不要填入該服務的金鑰，也不要啟用該功能。

### 12.3 本機連線安全

預設網址是 `http://localhost`，本機 API 為 `http://127.0.0.1:9090`。請保持作業系統防火牆開啟，不要在路由器或公共網路開放 80、9090、5432；遠端只使用上述 Tailscale Serve，禁止 Funnel。

若要讓其他電腦或網際網路存取，必須另行規劃 HTTPS、網域、防火牆、反向代理、備份與更新責任；不要直接把本機連接埠對外公開。

---

## 13. 停用與移除

暫時停用並保留資料：

```bash
docker compose -p asset-management stop
```

移除容器但保留資料 volume：

```bash
docker compose -p asset-management down
```

只有確定所有資料與備份都不再需要時，才可執行：

```bash
docker compose -p asset-management down -v
```

**警告：** `down -v` 會永久刪除 PostgreSQL 與 Redis volume。一般更新、停止或故障排查都不需要使用它。

移除容器後，專案資料夾、`.env`、輸出檔與 rclone 設定仍在主機上；若也要刪除，請先確認已無任何需要保留的資料。

---

## 14. 系統提供者發行前檢查表

這一節是給準備散佈系統的人，不是一般安裝者的操作步驟。

### 14.1 發行內容

- [ ] `.env.example` 有 `ADMIN_EMAIL` 說明，但沒有真實帳號或 secret。
- [ ] 安裝包不含 `.env`、`db/init/01_dump.sql`、`rclone.conf`、私人 Excel／JSON／備份檔。
- [ ] 安裝包不含 API key、密碼、OAuth secret、IDE cache、`node_modules` 或 Java `target`。
- [ ] 預設輸出路徑不含原作者姓名或家目錄。
- [ ] 提供版本號、Git commit、SHA-256、更新說明、授權條款、隱私說明與支援方式。

### 14.2 乾淨安裝驗證

- [ ] 從全新空白 PostgreSQL 完成一次安裝，不依賴私人 dump。
- [ ] 將 `ADMIN_EMAIL` 設成非原作者 Google 帳號，首次登入即為 `ADMIN/ACTIVE`。
- [ ] 使用者管理 API 與畫面都以 `protectedAdmin` 標示「主要管理者」，沒有前端 email hard code。
- [ ] 未設定或誤填 `ADMIN_EMAIL` 時，Compose／服務會清楚拒絕啟動。
- [ ] 七個主要服務都能啟動，api-gateway health 為 `healthy`。
- [ ] 能建立或匯入第一份安裝者自己的資產資料。
- [ ] 選用金鑰保持空白時會安全停用，不阻擋啟動。
- [ ] 至少在預計支援的 macOS、Windows WSL 2、Ubuntu 版本各驗證一次。

只有上述檢查完成後，才應將版本標示為可供一般使用者自行安裝。
