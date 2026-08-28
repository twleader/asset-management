# 前端 HTTPS 自簽憑證目錄

本目錄只提供部署時的檔名與掛載骨架，憑證與私鑰本身一律不進版本控制。

`frontend` 容器啟動時，若本目錄缺少 `fullchain.pem` / `privkey.pem`，會自動產生一組
`localhost` / `127.0.0.1` 專用的預設自簽憑證寫回本目錄，確保 `docker compose up` 在
未手動設定憑證的情況下也能正常啟動 HTTPS（僅供本機測試，瀏覽器會顯示不受信任警告）。

若要讓其他裝置或網際網路能以固定 IP（或網域）連線並看到對應的憑證，請在 host 執行：

```bash
scripts/generate-self-signed-frontend-cert.sh <公網 IP 或網域>
docker compose up -d --force-recreate frontend
```

該腳本會覆蓋本目錄下的 `fullchain.pem` / `privkey.pem`（需已存在時加 `--force`）。

- `fullchain.pem`：自簽憑證（公開，可讀）
- `privkey.pem`：私鑰，權限應設為僅擁有者可讀寫（`chmod 600`）

自簽憑證不是任何瀏覽器信任的憑證機構簽發，連線時會出現「不受信任」警告，需手動選擇
「進階 → 繼續前往」，這是預期行為，不是設定錯誤。是否要把本機／裝置對外開放（路由器
port forwarding、防火牆規則）屬於本系統之外的網路環境設定，需自行評估風險並操作；
本專案不會、也不能代為變更路由器或防火牆設定。
