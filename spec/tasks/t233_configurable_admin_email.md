# [t233] 主要管理者改由環境變數設定並補齊一般使用者安裝手冊

**對應 Requirements:** Requirement 28（Gmail OAuth2 登入、多租戶隔離與可部署的主要管理者設定）
**前置任務:** 無
**Liquibase changeset:** v1.71.0-configurable-admin-email.sql

## 背景

舊版把原作者 Gmail 寫死為主要管理者，導致同一份安裝包交給其他使用者後，主要管理者身分仍指向原作者。系統必須改由部署者在 `.env` 明確提供 `ADMIN_EMAIL`，並由 business-services 作唯一權威判定；BFF 與前端不得再各自保存 email。歷史 migration 已在既有資料庫執行，不可修改 checksum，因此以新 changeset 只清理完全沒有資料參照的舊固定 seed。安裝手冊必須讓沒有資訊背景的使用者能從 Docker、Google OAuth、環境設定到首次登入獨立完成安裝。

## 要做什麼

- [x] 233.1 `application.yml` 綁定必填 `ADMIN_EMAIL`；啟動時 trim、轉小寫、驗證 email，缺值或格式錯誤 fail-fast。
- [x] 233.2 `UserAdminService` 集中判定主要管理者；登入時強制 `ADMIN/ACTIVE`，停用與降級操作一律拒絕。
- [x] 233.3 `UserResponse` 增加 `protectedAdmin:boolean`；BFF 與前端只依此欄位標示及鎖定主要管理者，不得 hard code email。
- [x] 233.4 新增 `v1.71.0-configurable-admin-email.sql`；掃描所有 `owner_user_id` 欄位並以外鍵例外兜底，只刪除完全無參照的舊固定管理者 seed，不轉移既有資料所有權。
- [x] 233.5 `.env.example` 與 Compose 加入必填 `ADMIN_EMAIL`，只注入 business-services；輸出根目錄範例改為可攜的使用者家目錄。
- [x] 233.6 重寫安裝手冊，分別涵蓋 macOS、Windows/WSL 與 Ubuntu 的 Docker 安裝、Google OAuth、`.env`、啟動、首次登入、更新、備份與故障排除。
- [x] 233.7 補上管理者保護與任意指定主要管理者的測試。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml package -DskipTests
npm --prefix frontend run build
bash scripts/spec-check.sh
```

部署時以現有資料庫唯一一位 `ACTIVE ADMIN` 作為 `ADMIN_EMAIL`，重建 business-services、BFF 與 frontend；確認 business-services／BFF healthy、Liquibase `v1.71.0-remove-unreferenced-legacy-admin` 已執行，且 BFF 近期無 `Connection refused` 或 500。

## 完成報告

已完成主要管理者單一來源、DTO／前端保護、Liquibase 相容清理、發行環境契約與一般使用者安裝手冊。Java、BFF、frontend production build 與 spec-check 成功；三個服務已重建置並通過健康檢查。
