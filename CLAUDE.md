# 開發規範 — 資產管理系統

## 所有回覆，盡量用台灣繁體中文，非不得已才用英文，不得使用其它文字。

## 核心原則：嚴格遵守 AWS KIRO SDD 方法

本專案採用 **AWS KIRO SDD（Spec-Driven Development）** 方法開發。
**每一次功能新增或變更，必須依以下順序執行，不得跳過：**

```
1. spec/requirements.md     → 先確認或新增 User Story + Acceptance Criteria
2. spec/design.md           → 確認架構、資料模型、API 設計已反映變更
3. spec/tasks/tNNN_*.md     → 建立自足任務檔（規範見 spec/tasks/README.md）
4. spec 對抗式審查          → /spec-review，quality_score < 8 不得進入實作
5. 實作程式碼
```

> **第 3 步的任務檔是新制。** Task 201 之後的新任務一律建立獨立的自足任務檔
> `spec/tasks/tNNN_<slug>.md`，不再追加進 `spec/tasks.md`；後者已降為索引，
> 歷史 Task 1–200 凍結在 `spec/tasks/archive/`。詳見 [spec/tasks/README.md](spec/tasks/README.md)。

> **第 4 步的審查必須由另一支 subagent 執行**，寫 spec 的人自己審等於沒審。
> 審查前會先跑 `scripts/spec-check.sh` 取機械證據（編號撞號／重號、changeset 版號碰撞與
> 冪等性、宣稱的測試類是否存在、文件計數漂移）——這些是本專案實際反覆犯的錯，
> 一年內光編號避讓就有 12 次 commit。

> **凡涉及商業邏輯變更（新增、刪除、修改、bug fix），都必須同步更新 spec/**。
> 包含：新 Entity、新 API endpoint、新頁面、新業務邏輯，以及修正既有商業邏輯的 bug fix。
>
> 唯一例外（可不更新 spec）：純樣式 / CSS 微調、純 typo、純 import 整理等不影響功能契約的變更。

### Commit-msg Hook（強制 SDD 同步）

本專案內建 commit-msg hook（`scripts/git-hooks/commit-msg`），staged 變更若觸及
controller / model / dto / views / router / db changelog / bff 等「會改變功能或契約」
的檔案，**強制要求同 commit 也包含 `spec/` 變更**，否則阻擋提交。

**首次安裝（每個 clone / worktree 各執行一次）：**
```bash
git config core.hooksPath scripts/git-hooks
```

**例外：**
- 純樣式 / 無商業邏輯影響的調整：commit 訊息加 `[skip-spec]`
- 緊急情況：`git commit --no-verify`（請審慎）

> **hook 只是最低限度的閘門，不要把它當成 spec 品質保證。** 它只檢查「`spec/` 有沒有被碰」——
> 改一個錯字就能過關；而且觸發清單漏了 `service/`、`repository/`、`external-materials-service/**`，
> Task 195 那兩處排程漂移（改的是 service 層的 `@Scheduled`）根本不會觸發它。
> 內容正確性由第 4 步的 `/spec-review` 負責。

---

## 技術棧

### Backend
- Java 21 + Spring Boot 3.4.4（CLAUDE.md 原標「Java 25」為未來目標；pom.xml 與 Dockerfile 目前實際為 21）
- Spring Data JPA（H2 開發 / PostgreSQL 生產）
- Apache POI（Excel 匯入）
- Lombok
- `ddl-auto: none` + Liquibase（資料庫 Schema 版本管理）

### Frontend
- Vue 3 + Vite 5
- Element Plus（UI 元件）
- ECharts（圖表）
- Pinia（狀態管理）
- Axios（API 呼叫）
- unplugin-auto-import（自動匯入 ref/computed/watch 等）

---

## 架構規範

### 資料庫完整正規化
相同的資料只能存一份。禁止：
- 同一欄位同時以 FK 和字串冗餘儲存（如 `bank_id` + `bank_name`）
- 存入可從其他欄位計算得出的衍生值（如 `profit = currentValue - investmentCost`）
- 跨資料表重複儲存同一事實

例外（刻意的 denormalization，需加註說明）：
- 歷史快照的匯總欄位（`asset_snapshot` 的 `total_*`，供歷史回溯）
- 歷史交易記錄中的名稱字串（如 `realized_gain.broker`，記錄成交當下的券商名稱）

### 禁止 Enum 寫死
所有業務分類（銀行、券商、存款類型、市場類型）**必須存入資料庫**，由 `DataInitializer` 提供 Seed Data，並提供 `/api/settings/*` 管理端點與前端設定頁面。

### 零遷移策略
欄位從 Enum 改為 String 時，因 `@Enumerated(EnumType.STRING)` 原本就以 VARCHAR 儲存，無需 DB Migration。`DepositTypeEntity.code` 與 `MarketType.code` 即為寫入欄位的字串值。

### API 命名規則
- 資源操作：`GET/POST/PUT/DELETE /api/{resource}`
- 軟停用：`PATCH /api/{resource}/{id}/active`
- 設定管理：`/api/settings/{resource}`
- 市場資料：`/api/market-data/{action}`

### BFF 與資料來源規範

**1. 一個前端頁面一個 BFF**

每個前端頁面對應一支獨立的 BFF controller（或 route 設定），路徑前綴 `/api/bff/{page-name}/...`。
- 前端 view 一律走自己頁面對應的 BFF endpoint，不直接呼叫 business service `/api/{resource}`
- BFF 負責跨服務 aggregation、預先計算 / 排序 / 過濾，前端只負責 render
- 範例：`DashboardBffController`、`SnapshotFormBffController`、`AssetHistoryBffController`、`BankSettingsBffRoutes`（純 passthrough 也要有自己的 route）

**2. 同義欄位、同一 business service API**

不同頁面顯示「同樣意義的值」時，BFF 必須呼叫**同一支 business service API**取得，避免值在不同頁面不一致。
- 例：股票即時 K/D/季線 → 兩個頁面都透過 `TechnicalIndicatorService.compute()`
- 例：snapshot 預估配息 → 各頁面都讀 `asset_snapshot.estimated_annual_dividend`（不要前端各自重算）
- 例：股價收盤值 → 一律從 `stock_price_history` 抓
- 共用邏輯抽到 `bff/common/`（如 `SnapshotEnricher`），各 BFF controller 注入使用

### 服務啟動
```bash
# 後端（port 8080）
cd backend
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn spring-boot:run

# 前端（port 5173）
cd frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run dev
# 或直接執行 vite：
/Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite
```

---

## Spec 文件位置

| 文件 | 說明 |
|------|------|
| `spec/requirements.md` | User Stories + Acceptance Criteria（45 個 Requirements） |
| `spec/design.md` | 架構圖、ERD、API 端點、關鍵業務邏輯 |
| `spec/tasks.md` | 任務索引（Task 1–220）＋ 尚未歸檔的 Task 201 起區段 |
| `spec/tasks/README.md` | 自足任務檔規範（新任務寫這裡，不再追加 `tasks.md`） |
| `spec/tasks/tNNN_*.md` | 自足任務檔（Task 201 之後的新任務） |
| `spec/tasks/archive/` | Task 1–200 歷史，已凍結不再修改 |
| `spec/steering/` | 長期 context：`product.md` / `tech.md` / `structure.md` |

| 工具 | 用途 |
|------|------|
| `scripts/spec-check.sh` | spec 變更的機械前置檢查（撞號／重號／changeset／計數漂移） |
| `/spec-review` | 實作前的獨立對抗式審查閘門，門檻 8/10 |
