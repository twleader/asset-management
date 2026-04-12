# 開發規範 — 資產管理系統

## 核心原則：嚴格遵守 AWS KIRO SDD 方法

本專案採用 **AWS KIRO SDD（Spec-Driven Development）** 方法開發。
**每一次功能新增或變更，必須依以下順序執行，不得跳過：**

```
1. spec/requirements.md  → 先確認或新增 User Story + Acceptance Criteria
2. spec/design.md        → 確認架構、資料模型、API 設計已反映變更
3. spec/tasks.md         → 確認對應 Task 已建立並標記完成狀態
4. 實作程式碼
```

> 如果是 bug fix 或細部 UI 調整（不涉及新功能或架構變動），可不更新 spec，
> 但凡涉及新 Entity、新 API endpoint、新頁面、新業務邏輯，**一定要先更新 spec**。

---

## 技術棧

### Backend
- Java 25 + Spring Boot 3.4.4
- Spring Data JPA（H2 開發 / PostgreSQL 生產）
- Apache POI（Excel 匯入）
- Lombok
- `ddl-auto: update`（開發環境自動建表）

### Frontend
- Vue 3 + Vite 5
- Element Plus（UI 元件）
- ECharts（圖表）
- Pinia（狀態管理）
- Axios（API 呼叫）
- unplugin-auto-import（自動匯入 ref/computed/watch 等）

---

## 架構規範

### 禁止 Enum 寫死
所有業務分類（銀行、券商、存款類型、市場類型）**必須存入資料庫**，由 `DataInitializer` 提供 Seed Data，並提供 `/api/settings/*` 管理端點與前端設定頁面。

### 零遷移策略
欄位從 Enum 改為 String 時，因 `@Enumerated(EnumType.STRING)` 原本就以 VARCHAR 儲存，無需 DB Migration。`DepositTypeEntity.code` 與 `MarketType.code` 即為寫入欄位的字串值。

### API 命名規則
- 資源操作：`GET/POST/PUT/DELETE /api/{resource}`
- 軟停用：`PATCH /api/{resource}/{id}/active`
- 設定管理：`/api/settings/{resource}`
- 市場資料：`/api/market-data/{action}`

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
| `spec/requirements.md` | User Stories + Acceptance Criteria（12 個 Requirements） |
| `spec/design.md` | 架構圖、ERD、API 端點、關鍵業務邏輯 |
| `spec/tasks.md` | 實作任務清單（13 個 Tasks，含完成狀態） |
