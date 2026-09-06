# [t419] 前端雙 SNI TLS，讓 localhost 與公網登入共存

**對應 Requirements:** Requirement 142（前端雙 SNI TLS 讓 localhost 與公網登入同時可用）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

`asset-management.asuscomm.com` 曾以受一般瀏覽器信任的 public certificate 正常提供 HTTPS；但 frontend 從另一個 worktree recreate 時，compose 的 `./secrets/frontend-tls` mount 相對於該 worktree，找不到 public pair。舊 entrypoint 因而在該目錄產生 `localhost` self-signed pair，唯一 443 Nginx server 將它回覆給公網 hostname，讓 HSTS 的 Chrome 顯示 `ERR_CERT_AUTHORITY_INVALID`。同時，本機使用者需要自己信任的 CA-signed certificate，讓 `https://localhost/login` 與 `https://127.0.0.1/login` 可登入。

正確結果不是讓瀏覽器忽略 public certificate error，也不是把 localhost self-signed leaf 當 public certificate；而是由 SNI 將 public hostname 與 localhost/loopback 導向兩組獨立 pair。此任務不得改 Google OAuth、BFF、session cookie、9090/Tailscale、資料庫、Fubon 開關、SDK 或任何可讀／可寫券商動作。

## 要做什麼

- [ ] 419.1 將原本單一 frontend Nginx server 拆為三個 server block：80 default、443 local default、443 `server_name asset-management.asuscomm.com`。443 local default 必包含 `localhost` 與 `127.0.0.1`，讀 `/run/frontend-tls/local/fullchain.pem`／`privkey.pem`；其 supplied source certificate 必含 `DNS:localhost,IP:127.0.0.1` SAN 並由 local CA 驗證。443 public server 只讀 `/run/frontend-tls/public/fullchain.pem`／`privkey.pem`；其 supplied source certificate 必含 `DNS:asset-management.asuscomm.com` SAN 並由 public trust 驗證。兩個 TLS server 都限制 TLS 1.2/1.3。不得以同一憑證或同一 runtime directory 服務兩種 identity。
- [ ] 419.2 從原本 Nginx server 抽出一份共用 application include，讓三個 server 共用 root、resolver、gzip、所有 static/BFF/OAuth location、長 timeout route、9090 public route deny 與 cache。Dockerfile 必將該檔放在 `/etc/nginx/includes/frontend-app.conf`，不可放進 nginx 自動 glob 的 `conf.d/*.conf`，否則 location 會在 http scope 被錯誤解析。`/oauth2/`、`/login/oauth2/`、`/logout` 必保留 `Host`、`X-Forwarded-Proto=$scheme`、`X-Forwarded-Host=$host`；不得新增、刪除或改寫 proxy 路由語意。
- [ ] 419.3 Compose 新增 `FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST`→`/run/secrets/frontend-local-tls-source:ro` mount，public mount 同樣以 `/run/secrets/frontend-tls-source:ro` 掛載，另提供只供 `/run/frontend-tls` 使用的 ephemeral tmpfs。新增 `FRONTEND_PUBLIC_TLS_REQUIRED`，並以 `FRONTEND_PUBLIC_TLS_SOURCE_HOST_PATH`／`FRONTEND_LOCAL_TLS_SOURCE_HOST_PATH` 傳入同一組 non-secret host source strings 供 entrypoint preflight。public-required=true 時兩個值任一空白或不是 `/` 開頭即必拒絕。保留 `FRONTEND_TLS_SECRETS_DIR_HOST` 作 public pair 來源以相容既有部署；`.env.example` 必明確要求 public deployment 用兩個絕對 host path 並設 `FRONTEND_PUBLIC_TLS_REQUIRED=true`，不得把相對 worktree path 說成正式部署方式。
- [ ] 419.4 重寫 entrypoint 的 pair 檢查：public-required=true 是雙入口 production mode，public 或 local source 任一 pair 缺任一檔、或任一路徑非絕對，均 non-zero 終止，錯誤不得印 private key；有 source pair 時只複製至對應 `/run/frontend-tls/{public,local}` runtime directory，host source 不得寫入或覆寫。未啟用 public-required 的純本機開發模式才可在各自缺少 source pair 的 runtime directory 產生 localhost/IP-only fallback，讓兩個 Nginx server 都能載入；**僅 generated fallback pair** 的 SAN 固定 `DNS:localhost,IP:127.0.0.1`，文件必明示這不是 public certificate，也不得把該模式用於 public hostname。
- [ ] 419.5 修改 `scripts/generate-self-signed-frontend-cert.sh`，預設輸出改為 `${FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST:-./secrets/frontend-local-tls}`，不再預設接觸 public mount。它只接受無 host 參數、`localhost` 或 `127.0.0.1`，subject/SAN 固定 localhost／loopback，external hostname input 必須拒絕。使用說明必清楚指出它只做 localhost／loopback self-signed 開發輸入，不會讓 external hostname 被一般瀏覽器信任；不得自動申請、續期、複製或取代 public chain。
- [ ] 419.6 建立 `secrets/frontend-local-tls/.gitignore` 與 `.gitkeep`；忽略 `*.pem`，絕不把 certificate 或 key commit。新增可自動執行的 static/config test，至少驗證 public/local two-mount、SNI server identity、shared include、public-required true 下 relative path／缺 public source／缺 local source 的實際 fail-closed，以及 script 的 local default 和 external hostname 拒絕。
- [ ] 419.7 runtime deployment 一律顯式提供下列不含私鑰內容的設定：`FRONTEND_TLS_SECRETS_DIR_HOST=<public-trusted-pair 的絕對路徑>`、`FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST=<local-CA-signed-pair 的絕對路徑>`、`FRONTEND_PUBLIC_TLS_REQUIRED=true`。只 rebuild/recreate frontend，不對其他服務做 down 或不相關重啟；不得用相對路徑或 feature worktree private certificate directory 當 public pair。

## 驗證

```bash
bash scripts/tests/frontend-dual-tls-config-test.sh
(cd frontend && npm run build)

PUBLIC_TLS_DIR=/absolute/path/to/public-trusted-pair
LOCAL_TLS_DIR=/absolute/path/to/local-ca-signed-pair
LOCAL_CA_ROOT=/absolute/path/to/local-ca-root.pem
test "${PUBLIC_TLS_DIR#/}" != "$PUBLIC_TLS_DIR"
test "${LOCAL_TLS_DIR#/}" != "$LOCAL_TLS_DIR"
test -r "$LOCAL_CA_ROOT"

FRONTEND_TLS_SECRETS_DIR_HOST="$PUBLIC_TLS_DIR" \
FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST="$LOCAL_TLS_DIR" \
FRONTEND_PUBLIC_TLS_REQUIRED=true \
docker compose -p asset-management build --no-cache frontend
FRONTEND_TLS_SECRETS_DIR_HOST="$PUBLIC_TLS_DIR" \
FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST="$LOCAL_TLS_DIR" \
FRONTEND_PUBLIC_TLS_REQUIRED=true \
docker compose -p asset-management up -d --no-deps --force-recreate frontend
docker exec asset-frontend nginx -t
docker inspect asset-frontend --format '{{range .Mounts}}{{println .Source "->" .Destination "rw=" .RW}}{{end}}'

# public：不加 -k；SNI、hostname verification、public trust、subject/SAN/issuer 與 GET 都要成功。
printf '' | openssl s_client -connect asset-management.asuscomm.com:443 \
  -servername asset-management.asuscomm.com -verify_return_error \
  -verify_hostname asset-management.asuscomm.com 2>&1 | grep 'Verify return code: 0 (ok)'
printf '' | openssl s_client -connect asset-management.asuscomm.com:443 \
  -servername asset-management.asuscomm.com -showcerts 2>/dev/null \
  | openssl x509 -noout -subject -issuer -ext subjectAltName \
  | grep -F 'DNS:asset-management.asuscomm.com'
printf '' | openssl s_client -connect asset-management.asuscomm.com:443 \
  -servername asset-management.asuscomm.com -showcerts 2>/dev/null \
  | openssl x509 -noout -issuer
curl --fail --silent --show-error --output /dev/null https://asset-management.asuscomm.com/login

# local：不加 -k；local CA、localhost/IP SAN、default local SNI 與 GET 都要成功。
printf '' | openssl s_client -connect 127.0.0.1:443 -servername localhost \
  -CAfile "$LOCAL_CA_ROOT" -verify_return_error -verify_hostname localhost 2>&1 \
  | grep 'Verify return code: 0 (ok)'
printf '' | openssl s_client -connect 127.0.0.1:443 -CAfile "$LOCAL_CA_ROOT" \
  -verify_return_error -verify_ip 127.0.0.1 2>&1 | grep 'Verify return code: 0 (ok)'
printf '' | openssl s_client -connect 127.0.0.1:443 -servername localhost -showcerts 2>/dev/null \
  | openssl x509 -noout -subject -issuer -ext subjectAltName \
  | grep -F 'DNS:localhost'
printf '' | openssl s_client -connect 127.0.0.1:443 -servername localhost -showcerts 2>/dev/null \
  | openssl x509 -noout -subject -issuer -ext subjectAltName \
  | grep -F 'IP Address:127.0.0.1'
curl --cacert "$LOCAL_CA_ROOT" --fail --silent --show-error --output /dev/null https://localhost/login
curl --cacert "$LOCAL_CA_ROOT" --fail --silent --show-error --output /dev/null https://127.0.0.1/login
```

驗證還必須包含 frontend container 的 mount source inspection，確認兩個 TLS source 都是設定的絕對 host path。只檢查登入頁 HTTP/TLS；不得實際完成 Google OAuth、不得呼叫 Fubon SDK、不得進行任何 broker/account/order/sync/write API。

所有 static、image、Nginx、public TLS、local TLS 與登入頁驗收都成功後，才更新操作文件，寫下根因（worktree-relative public mount + single TLS server）、dual-SNI source of truth、public-required fail-closed、絕對 path 佈署設定與後續 rebuild 驗證步驟。

## 完成報告

（實作者做完後回填：實際改了哪些檔、public/local TLS chain 與 `/login` 驗證輸出、mount inspection、文件更新，以及與原計畫的偏差。）
