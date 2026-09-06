# 前端 public TLS certificate source

這個目錄是 `asset-management.asuscomm.com` 的 **public-trusted** certificate source 骨架；
`fullchain.pem` 與 `privkey.pem` 一律不進 Git。它不是 localhost self-signed certificate 的輸出位置。

部署時由 `FRONTEND_TLS_SECRETS_DIR_HOST` 指向含有下列檔案的**絕對** host path：

- `fullchain.pem`：含 `DNS:asset-management.asuscomm.com` SAN、可由一般瀏覽器公開 trust 驗證的完整鏈。
- `privkey.pem`：與上述 certificate 配對的私鑰，權限至少應為 `600`。

正式雙入口部署必須同時設定：

```dotenv
FRONTEND_TLS_SECRETS_DIR_HOST=/absolute/path/to/public-trusted-pair
FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST=/absolute/path/to/local-ca-signed-pair
FRONTEND_PUBLIC_TLS_REQUIRED=true
```

Compose 會將兩個 source 以唯讀方式掛載；frontend entrypoint 只複製它們到容器 tmpfs，絕不回寫
host source。`FRONTEND_PUBLIC_TLS_REQUIRED=true` 時，public/local 任一 pair 遺失或任一路徑不是絕對
path，frontend 都會 fail closed，不能用 localhost fallback 代替 public certificate。

不要把 feature worktree 的 `./secrets/...` 當成 public source，也不要以瀏覽器「繼續前往」或關閉
驗證來處理 public hostname 的 certificate error。若外網出現憑證錯誤，先依
[`docs/operations/frontend-dual-sni-tls.md`](../../docs/operations/frontend-dual-sni-tls.md) 檢查 source、
mount 與 SNI，再 targeted recreate frontend。
