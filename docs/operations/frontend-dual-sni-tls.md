# 前端雙 SNI TLS：事件結論與操作手冊

## 已確認的結論

2026-09-06 發生的 `asset-management.asuscomm.com` 登入失敗，根因不是 Google OAuth、session cookie、
BFF 或瀏覽器沒有信任本機 CA。frontend 從 feature worktree recreate 時，compose 的舊設定以相對
`./secrets/frontend-tls` 作為唯一 TLS mount；該 worktree 沒有 public pair，entrypoint 便產生
`localhost`／`127.0.0.1` self-signed fallback。因為 Nginx 當時只有一個 443 server，外網 hostname
也拿到了這張 fallback，Chrome 對 HSTS 網域正確顯示 `ERR_CERT_AUTHORITY_INVALID`。

當時 `https://localhost/login` 可用而 public hostname 仍失敗，正好證明 local CA trust 本身正常；
外網需要的是原本的 public-trusted chain，不是再把 local CA 安裝到系統或略過瀏覽器警告。

目前的正確架構是：

| TLS SNI / 連線 | certificate source | 必要驗證 |
|---|---|---|
| `asset-management.asuscomm.com` | `FRONTEND_TLS_SECRETS_DIR_HOST` | 普通瀏覽器 public trust；SAN 含 public hostname |
| `localhost`、`127.0.0.1`、無 public SNI 的 loopback | `FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST` | 使用者 local CA trust；SAN 同時含 localhost 與 loopback IP |

Nginx 的 local TLS server 是 443 default；public server 只在 public SNI 是
`asset-management.asuscomm.com` 時選取。三個 server（80、local 443、public 443）共用同一份 route
include，所以 OAuth proxy、BFF proxy、9090 deny、長時限 route 與 static cache 不會因憑證分流而漂移。

## 不可省略的正式部署設定

在部署使用的 `.env` 或啟動環境中，必須使用兩個絕對 host path，並啟用 required gate：

```dotenv
FRONTEND_TLS_SECRETS_DIR_HOST=/absolute/path/to/public-trusted-pair
FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST=/absolute/path/to/local-ca-signed-pair
FRONTEND_PUBLIC_TLS_REQUIRED=true
```

兩個 source 都必須有 `fullchain.pem` 和 `privkey.pem`。Compose 會以唯讀方式掛載 source；entrypoint
只把 pair 複製到 container tmpfs `/run/frontend-tls`，不會回寫 source。當
`FRONTEND_PUBLIC_TLS_REQUIRED=true` 時，發生下列任一情況都必須讓 frontend non-zero 結束：

- public 或 local pair 缺檔；
- public 或 local host path 是空白或相對路徑；
- `FRONTEND_PUBLIC_TLS_REQUIRED` 不是 `true` 或 `false`。

這是刻意的 fail-closed 防線：不能再以不同 worktree 的相對目錄、或 localhost fallback 靜默代替
外網 certificate。純本機開發可維持 `false`；這時 fallback 只在 container tmpfs 生成，僅有
`localhost`／`127.0.0.1` SAN，絕不能拿來做 public deployment。

## 安全重建與驗證

只改前端 TLS 時，只重建／recreate frontend，不要使用 `docker compose down` 或重啟其他服務：

```bash
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
```

驗收時不能使用 `curl -k`、Chrome「繼續前往」或停用 certificate verification。至少驗證：

```bash
# public：normal system trust、正確 SNI 與真正 GET
curl --fail --silent --show-error --output /dev/null https://asset-management.asuscomm.com/login

# local：使用已信任的 local root CA；同時驗 localhost 與 IP
curl --cacert "$LOCAL_CA_ROOT" --fail --silent --show-error --output /dev/null https://localhost/login
curl --cacert "$LOCAL_CA_ROOT" --fail --silent --show-error --output /dev/null https://127.0.0.1/login
```

另執行 `bash scripts/tests/frontend-dual-tls-config-test.sh`。它會驗證 source/read-only mount、dual SNI
設定、相對路徑／缺 public pair／缺 local pair 的 fail-closed 行為，以及 localhost generator 拒絕
external hostname。它不會啟動 Docker、登入 Google 或呼叫券商。

## 2026-09-06 實機驗收紀錄

本修復以 public-trusted source 與獨立 local CA source targeted recreate frontend 後，驗證結果如下：

- `nginx -t` 成功，且 frontend 新鮮 log 沒有 TLS/Nginx 設定錯誤；
- 兩個 source 均為唯讀 mount，runtime copy 位於 tmpfs；
- `asset-management.asuscomm.com` 的 normal system trust、SNI hostname verification 與 `GET /login`
  均成功（當時 issuer 為 Let's Encrypt YE2）；
- `localhost`、`127.0.0.1` 以 local root CA 驗證 SAN 與 `GET /login` 均成功；
- 沒有重啟其他服務、沒有執行 OAuth 登入、沒有呼叫 Fubon/broker 或任何寫入 API。

若日後 external hostname 再出現 certificate error，先比對 public SNI certificate、兩個 absolute source
path、container mount 和 `FRONTEND_PUBLIC_TLS_REQUIRED=true`，不要先改 OAuth 或要求使用者略過警告。
