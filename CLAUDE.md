# 開發規範 — 資產管理系統

## 所有回覆，盡量用台灣繁體中文，非不得已才用英文，不得使用其它文字。

## 核心原則：嚴格遵守 AWS KIRO SDD 方法

本專案採用 **AWS KIRO SDD（Spec-Driven Development）** 方法開發。
**每一次功能新增或變更，必須依以下順序執行，不得跳過：**

```
1. spec/requirements.md  → 先確認或新增 User Story + Acceptance Criteria
2. spec/design.md        → 確認架構、資料模型、API 設計已反映變更
3. spec/tasks.md         → 確認對應 Task 已建立並標記完成狀態
4. 實作程式碼
```

> **凡涉及商業邏輯變更（新增、刪除、修改、bug fix），都必須同步更新 spec/**。
> 包含：新 Entity、新 API endpoint、新頁面、新業務邏輯，以及修正既有商業邏輯的 bug fix。
>
> 唯一例外（可不更新 spec）：純樣式 / CSS 微調、純 typo、純 import 整理等不影響功能契約的變更。

### Pre-commit Hook（強制 SDD 同步）

本專案內建 pre-commit hook（`scripts/git-hooks/pre-commit`），staged 變更若觸及
controller / model / dto / views / router / db changelog / bff 等「會改變功能或契約」
的檔案，**強制要求同 commit 也包含 `spec/` 變更**，否則阻擋提交。

**首次安裝（每個 clone / worktree 各執行一次）：**
```bash
git config core.hooksPath scripts/git-hooks
```

**例外：**
- 純樣式 / 無商業邏輯影響的調整：commit 訊息加 `[skip-spec]`
- 緊急情況：`git commit --no-verify`（請審慎）

---

## 技術棧

### Backend
- Java 25 + Spring Boot 3.4.4
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
| `spec/requirements.md` | User Stories + Acceptance Criteria（27 個 Requirements） |
| `spec/design.md` | 架構圖、ERD、API 端點、關鍵業務邏輯 |
| `spec/tasks.md` | 實作任務清單（Task 1–122，含完成狀態） |
