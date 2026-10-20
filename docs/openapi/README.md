# 9090 API 契約

| 契約 | 標準來源 | 閱讀文件 | 用途 |
| --- | --- | --- | --- |
| 金融與市場 API | [docker-external-api.yaml](docker-external-api.yaml) | [9090-api-swagger.md](9090-api-swagger.md) | 金融26個method/path pairs（22 GET、4 POST）；閱讀文件與 PublishedSwaggerIdentity 均以統一文件 SHA-256 為準 |
| AI 判斷流程觀測 API | [ai-flow-observation-api.yaml](ai-flow-observation-api.yaml) | [9090-ai-flow-observation.md](9090-ai-flow-observation.md) | 新1個exact POST，只記錄公開流程；使用獨立版本及專用憑證，同樣納入統一Swagger發布身份 |

兩份契約各自解析 local refs，路徑不重疊；金融 26 加觀測 1，union 為 27 個 method/path pairs（22 GET、5 POST）。Tailscale 使用 26 個 path handlers，寄信 POST 與其 GET child 沿用同一 handler。

登入後在「系統資訊 → 開放API」切換閱讀金融／AI觀測文件；該頁只呈現契約，不執行API。「系統資訊 → AI判斷流程」只有ADMIN可查詢，按日期與Codex／Claude分開顯示早午盤紀錄，保留90個台北日期。

此觀測功能不修改SRPP規則或既有LLM金融排程。詳細收集方式與Claude遠端trace缺口見 [AI判斷流程操作說明](../ai-flow-observation.md)。只更新Tailscale觀測路由時，使用 `scripts/configure-tailscale-api-gateway.sh --observation-only`；此模式只追加新route，保留既有設定、不跑金融探測、不reset。

## 統一文件與 SRPP 身分維護

未明示 `--srpp-root` 的 renderer（預設或 `--asset-only`）只讀寫 asset-management 的兩份 mirror，絕不讀寫 SRPP。sync 未帶 `--srpp-root` 不會進行 SRPP 同步；`--asset-only` 只回報 `ASSET_ONLY_NO_SRPP`，同樣不接觸 SRPP。

```bash
ruby scripts/render-9090-openapi-docs.rb --asset-only
ruby scripts/render-9090-openapi-docs.rb --check --asset-only
python3 scripts/sync-srpp-swagger-identity.py --asset-only
```

跨 repo 發布必須明示一個**絕對、非 symlink、非 main、乾淨的 Git managed SRPP feature worktree**，依下列順序執行；不要把 SRPP primary checkout 當作目標：

```bash
ruby scripts/render-9090-openapi-docs.rb --srpp-root /absolute/managed-srpp-feature-worktree
python3 scripts/sync-srpp-swagger-identity.py --srpp-root /absolute/managed-srpp-feature-worktree
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root /absolute/managed-srpp-feature-worktree
python3 scripts/sync-srpp-swagger-identity.py --check --srpp-root /absolute/managed-srpp-feature-worktree
```

第一步只可留下 `docs/9090 Port API Swagger.md` 的 renderer mirror 差異，sync 才可接續，且只可寫 consumer schema、runtime contract、policy manifest 與本 repo 的 embedded authority artifact。兩個 `--check` 可在這四個未 staged 固定產物仍存在時驗證；rename、staged、untracked 或任何其他 dirty path 一律 fail closed。metadata 同步會先驗原政策全部 source hashes，拒絕其他漂移；模型／schema 驗證條件／公式不變。

凡規則包、Swagger identity、consumer metadata 或 authority 衍生檔變更，都必須重算並記錄新的 policy bundle SHA-256；不得沿用舊 hash。先以唯讀 gate 驗證，再產生受版控、insert-only 的 registry SQL：

```bash
python3 scripts/check-srpp-report-authority.py --srpp-root /absolute/managed-srpp-feature-worktree --check
ruby scripts/srpp-report-policy-register.rb
```

SQL 必須由授權的 release operator 審閱並執行，之後唯讀回讀精確 registry row 與 jar embedded authority。只有在 asset-management 已由乾淨 main 重建/recreate、`GET /api/public/trading-calendar?year=<YYYY>` 回 200 且 `availability.tw.status="AVAILABLE"`、目標日從 `days[].twTrading==true` 選出後，才可做 9090 runtime readback。目標日必須在 Asia/Taipei `09:05 <= now < 13:30`；時段前或外、未到交易日均是 `NOT_YET_EXECUTED`，不得用靜態 hash、health 或日曆代替成功。這個 readback 是純 GET，不能手動 POST、重跑報告、寄信或交易。
