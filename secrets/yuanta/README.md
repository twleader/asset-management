# 元大證券唯讀整合秘密目錄

本目錄只提供部署時的檔名與掛載骨架，秘密檔本身、憑證與 SDK 元件一律不進版本控制，也不可加入 Docker build context（見 `yuanta-broker-service/.dockerignore`）。

## 官方申請前置流程

使用者須為元大證券客戶，並先完成官方流程才能取得 SPARK API 元件與憑證：

1. 簽署風險預告書（元大證券官方文件）。
2. 下載官方測試軟體並完成連線測試。
3. 取得正式或 UAT 環境的 `YuantaSparkAPI.dll`（及其相依原生函式庫）與登入憑證。

本服務只採用元大 **SPARK API**（`pythonnet` 透過 .NET 8 CoreCLR 載入 DLL），不使用僅支援 Windows 的元大 OneAPI／OneCom。詳見 `spec/tasks/t364_yuanta_broker_service.md` 背景章節。

## 部署者須建立的檔案

檔案權限應設為僅擁有者可讀寫（例如 mode `0600`），目錄限制為僅擁有者可進入（例如 mode `0700`）。正式容器以 uid `10002` 執行，因此 host 檔案與目錄仍須讓該 uid 可讀／可進入：

- `sdk/dll/`（目錄）：放入 `YuantaSparkAPI.dll` 與其相依原生函式庫（`.so` 等）。
- `sdk/account`：登入帳號。
- `sdk/password`：登入密碼。
- `sdk/certificate.pfx`：登入憑證（Linux 版仍需搭配憑證，見 SPARK API 官方文件的 `Login(PfxPath, PfxPass, Account, Pass)` 簽章）。
- `sdk/certificate-password`：憑證密碼。
- `sdk/stock-account-selector`（可選）：格式 `分公司代號:帳號`，例如 `001:0001234567`。未設定時，登入後取得的證券帳號候選必須「恰好一個」，否則服務視為 `MISCONFIGURED`。
- `sdk/futures-account-selector`（可選）：格式同上，適用於期貨帳號。
- `internal-service-token`（**flat 檔案，位於本目錄根層，不在 `sdk/` 底下**）：本服務唯一的 internal API 認證密鑰，供呼叫端於 `X-Internal-Service-Token` header 帶入。

## 掛載邊界

- `yuanta-broker-service` 唯讀掛入整個本目錄至 `/run/secrets/yuanta`。
- 本任務**不建立任何 Java 呼叫端**，因此不像 `fubon-broker-service` 拆分 `sdk/`／`shared/` 兩層；`internal-service-token` 直接放在本目錄根層即可。日後若新增 Java 消費端，該任務再決定是否需要拆層供其唯讀掛入子集。
- `YUANTA_ENABLED=false` 是預設且受支援的模式；沒有任何秘密檔時，adapter liveness 仍保持 healthy（`configState=NOT_CONFIGURED`），其餘服務不依賴本服務的 functional readiness，也不會因它沒有 credentials 而 unhealthy 或 restart。

## 唯讀邊界（不可繞過）

本服務只允許查詢類呼叫（登入狀態、庫存、損益、交割款、期貨權益數、報價、五檔、分時、K線、標的資訊、委託成交回報）。任何下單／改單／刪單／複式單／保證金操作一律禁止，對應 `CLAUDE.md`〈券商 API 只能查詢，不得交易〉全專案最高優先鐵則；`yuanta-broker-service/src/yuanta_broker_service/sdk_gateway.py` 的 import 邊界由自動化測試（`tests/test_sdk_gateway_import_boundary.py`）靜態掃描把關。

不要把秘密值貼到 issue、測試 fixture、shell history、容器 log 或完成報告。憑證與密碼的更新應在 host 上原地完成，之後只 recreate 相關容器，不重建含秘密的 image。
