# 成交回報受控匯入部署

此功能只新增交易帳本，沒有券商下單、庫存、現金或 Gmail 同步。僅登入的 ACTIVE owner 可使用，不接受代看。原始 `.eml` 與來源、帳戶歸屬為匯入者的明確聲明，不代表系統驗證 DKIM 或 S/MIME。未知費稅存 NULL。preview 不寫入；apply 重算並鎖 owner，衝突整批 409，不自動更正。人工編輯或刪除後重寄會報衝突，原始 provenance 保留。

## BFF-only origin 設定

新端點嚴格比對 `APP_PUBLIC_BASE_URL`；空值會 fail closed。若既有 business-services 的值為空，**不要為此改 main.env、business-services、CORS、登入設定或任何 secrets**。使用部署機本機、repo 外的 Compose override，僅覆寫 BFF：

```yaml
services:
  bff:
    environment:
      APP_PUBLIC_BASE_URL: https://asset-management.asuscomm.com
```

以下是管理者經核准部署時的指令範例，不是匯入或交易指令。先確認現行 labels；本環境既有 project 為 `asset-management`，main 目錄為 `/Users/steven/Project/asset-management-main`。若 labels 不符，停止並先核對，不能另開 project 或改 volumes。

```sh
docker inspect asset-bff --format '{{index .Config.Labels "com.docker.compose.project"}} {{index .Config.Labels "com.docker.compose.project.working_dir"}}'
cd /Users/steven/Project/asset-management-main
# 使用部署者已建立的 repo 外 override 絕對路徑
OVERRIDE=/absolute/local/path/bff-trade-report-origin.override.yml
# 保持既有 env 載入方式與相同 Compose project；不可修改 volumes / secrets。
docker compose -p asset-management -f docker-compose.yml -f "$OVERRIDE" build bff
docker compose -p asset-management -f docker-compose.yml -f "$OVERRIDE" up -d --no-deps --force-recreate bff
# 僅讀回非 secret 指定值，不輸出完整環境。
docker inspect asset-bff --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^APP_PUBLIC_BASE_URL='
docker inspect asset-business-services --format '{{range .Config.Env}}{{println .}}{{end}}' | grep '^APP_PUBLIC_BASE_URL='
```

確認 BFF 為核准的公開 origin，business-services 與部署前完全相同。後續從 main rebuild / recreate BFF **每次都必須帶同一 override**，否則會回到空值並拒絕匯入；這不是可藉放寬 origin 檢查修復的問題。不可用完整 `docker compose config` 輸出作驗證，以免帶出 secrets。遷移依既有 Liquibase 流程由 business-services 部署套用，schema.sql 依既有規範從實際 runtime 重產，不可對 production 手動填入交易或 fake schema。

驗收使用獨立 task tab，避免覆蓋使用者未儲存表單。選取已授權可信原始回報，確認兩項聲明，preview 核對後才 apply。不要把真實 MIME、帳號、token 放進 repo、測試 fixture 或 log；不要擷取 session token 呼叫 curl。
