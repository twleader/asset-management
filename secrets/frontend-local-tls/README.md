# 前端 localhost TLS certificate source

這個目錄是 `https://localhost` 與 `https://127.0.0.1` 的 local TLS source 骨架；certificate/key 一律
不進 Git。正式雙入口部署時，`FRONTEND_LOCAL_TLS_SECRETS_DIR_HOST` 必須指向含有：

- `fullchain.pem`：含 `DNS:localhost` 與 `IP:127.0.0.1` SAN，且由使用者 local login keychain 信任之 CA
  驗證的 certificate chain。
- `privkey.pem`：與上述 certificate 配對的私鑰，權限至少應為 `600`。

這是與 public hostname **不同** 的 certificate pair。將 public chain 放在這裡不會讓 localhost
驗證成功；將 local CA/self-signed chain 放到 public source 也不會讓網際網路瀏覽器信任。

純本機開發可執行：

```bash
scripts/generate-self-signed-frontend-cert.sh localhost
```

它只產生 localhost/loopback self-signed 開發輸入，不能產生 external hostname certificate，也不會寫入
public source。若要讓瀏覽器信任該輸入，仍須依作業系統／瀏覽器程序建立本機 trust；正式雙入口
deployment 不應依賴這個 fallback，而要提供已信任的 local CA-signed pair 並設定
`FRONTEND_PUBLIC_TLS_REQUIRED=true`。
