# 富邦唯讀整合秘密目錄

本目錄只提供部署時的檔名與掛載骨架，秘密檔本身一律不進版本控制，也不可加入 Docker build context。

首次使用 API key 前，使用者必須先依富邦官方流程，在富邦官方工具中以一般帳密與憑證完成一次連線測試，確認 API key 已啟用且具備所需唯讀權限。一般帳密只用於該官方前置流程；本服務永不要求、讀取、保存或使用一般下單帳戶登入密碼，也沒有對應的設定或檔名。前置流程未完成或權限不足時，服務會 fail closed 並停止功能性呼叫。

部署者建立下列檔案，檔案權限應設為僅擁有者可讀寫（例如 mode `0600`），兩個目錄則限制為僅擁有者可進入（例如 mode `0700`）。正式容器以 uid `10001` 執行，因此 host 檔案與目錄仍須讓該 uid 可讀／可進入；可依部署環境用 owner 或唯讀 ACL 達成，勿為方便而開放成全域可讀：

- `sdk/personal-id`
- `sdk/api-key`
- `sdk/certificate.pfx`
- `sdk/certificate-password`
- `sdk/account-branch-no`（可選；必須與下一檔成對提供）
- `sdk/account-number`（可選；必須與上一檔成對提供）
- `shared/internal-service-token`

掛載邊界如下：

- Python `fubon-broker-service` 唯讀掛入整個本目錄至 `/run/secrets/fubon`，供官方 SDK 登入與 internal token 驗證。
- `business-services` 與 `external-materials-service` 最多只唯讀掛入 `shared/` 至 `/run/secrets/fubon/shared`，不得看見 `sdk/`。
- `FUBON_ENABLED=false` 是預設且受支援的模式；沒有任何秘密檔時，adapter liveness 仍保持 healthy，其餘服務不依賴 Fubon functional readiness。

不要把秘密值貼到 issue、測試 fixture、shell history、容器 log 或完成報告。憑證與 API key 的更新應在 host 上原地完成，之後只 recreate 相關容器，不重建含秘密的 image。
