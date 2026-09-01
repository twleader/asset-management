# [t410] 快照表單存檔改走專屬 BFF

**對應 Requirements:** Requirement 136（新增及更新快照均走 SnapshotFormView 專屬 BFF，保留可讀錯誤）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-09-01 20:08（Asia/Taipei）登入使用者在「新增快照」畫面按下「存檔」時，前端三次送出 `POST /api/snapshots`，Nginx access log 均回 HTTP 403。表單讀取已走 `/api/bff/snapshot-form/**`，但 `assetStore` 的建立／更新仍使用 legacy `snapshotApi` 直打 `/api/snapshots`。此路徑繞過頁面專屬 BFF，與「一個前端頁面一個 BFF」不符，且使已登入瀏覽器無法完成存檔。

正確行為是：SnapshotFormView 的讀取、查找前一版、新增與更新全部走 `/api/bff/snapshot-form/**`；BFF 使用既有的 tenant-aware `businessServicesClient` 呼叫既有 business `/api/snapshots`。本任務不改 AssetService、資料庫資料或 schema，亦不執行任何使用者快照寫入作為驗證資料。

## 要做什麼

- [x] 410.1 在 `SnapshotFormBffController` 新增三條已登入頁面路由：`GET /api/bff/snapshot-form/snapshots` → business `GET /api/snapshots`、`POST /api/bff/snapshot-form` → business `POST /api/snapshots`、`PUT /api/bff/snapshot-form/{id}` → business `PUT /api/snapshots/{id}`。三者必共用既有 `businessServicesClient`，保留 tenant header filter、HTTP method、path、body 與成功 JSON；寫入端不得使用 `onErrorReturn` 或 catch 後改回 200。
- [x] 410.2 在 `frontend/src/api/index.js` 的 `bffApi.snapshotForm` 新增 `listSnapshots`、`create`、`update` wrapper，並移除舊 `snapshotApi` 直打 `/api/snapshots` 的 wrapper。快照清單／歷史的共用 store 讀取改分別使用既有 `bffApi.snapshotList.getAll()` 與 `bffApi.assetHistory.getHistory()`；不得讓 SnapshotDetailView 改用 SnapshotForm BFF，它要維持自己的 `snapshot-detail` BFF。
- [x] 410.3 `SnapshotFormView` 的「複製前一版」使用 `bffApi.snapshotForm.listSnapshots()`，存檔直接使用 `bffApi.snapshotForm.create(payload)` 或 `update(id, payload)`，成功後只刷新必要的 Pinia state；不得透過 legacy `/api/snapshots` 重新取得資料。既有日期重複時 `400 ProblemDetail.detail` 的中文提示、成功後導向新快照編輯頁的行為都必保持。
- [x] 410.4 新增 BFF 單元測試，以可控 `WebClient` exchange function 驗證三條 SnapshotForm BFF 路由的下游 HTTP method、URI、payload 及成功 JSON 轉送；至少明確覆蓋 POST 與 PUT。測試必以既有 `WebClientConfig.tenantHeaderFilter()` 組裝 client，並在帶有 `TenantIdentity` 的 Reactor context 下呼叫 POST／PUT，斷言捕捉到的下游請求具有正確的 `X-User-Id`、`X-User-Role` 與 `X-User-Status`。測試不得啟動真券商或修改實際 PostgreSQL 資料。
- [x] 410.4a 在同一 controller 測試中，以 `400 application/problem+json` 的下游 fixture 分別覆蓋 POST 與 PUT，並將 `SnapshotFormBffController` 與既有 `BusinessErrorAdvice` 一起綁定至 `WebTestClient`；斷言回應仍為 HTTP 400、Content-Type 為 `application/problem+json`、保留原始 `ProblemDetail.detail`，絕不可變成 HTTP 200 或空成功回應。
- [ ] 410.5 驗收需使用 Java 21 跑 BFF 測試、跑 frontend production build，並依 run-stack 僅 rebuild/recreate `bff` 與 `frontend`。執行中容器必健康；需確認實際 frontend bundle 含 `/bff/snapshot-form` 寫入路徑且不再含 `snapshotApi` 直打 `/snapshots` 的 API wrapper。登入後的實際表單可由使用者按存檔驗證；代理不得為測試建立、更新或刪除使用者快照。

## 驗證

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
bash scripts/spec-check.sh
docker compose -p asset-management build bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate bff frontend
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
curl -sI http://localhost/ | head -1
```

## 完成報告

- 實作：`SnapshotFormBffController` 新增清單、新增與更新 passthrough；前端表單的複製／儲存全走 `snapshot-form` BFF，移除 legacy `snapshotApi`，共用 store 的清單與歷史改走既有 page BFF。
- 測試：Java 21 focused 與完整 `bff` Maven tests 均通過；frontend production build 通過。新增 controller test 使用 in-memory WebClient exchange function、Reactor tenant context 和 `BusinessErrorAdvice`，沒有啟動 broker 或連線／寫入實際 PostgreSQL 快照。
- Docker runtime：依本次明確指示，未執行 410.5 的 Docker build/recreate、health check 或登入表單驗證，故 410.5 保持未勾選。
