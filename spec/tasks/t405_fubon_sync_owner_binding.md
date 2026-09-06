# [t405] 富邦交割／已實現損益同步的固定 owner 與 explicit-account gate

**對應 Requirements:** Requirement 129／130
**前置任務:** 無；本檔是 t394／t395 同一交付切片的共用基礎
**Liquibase changeset:** 無（t395 的 realized-gain 同步去重 migration 另列）

## 範圍

本 task 只提供 t394 與 t395 所需的本地授權及 account-binding 能力；不擴大修改庫存、成交、銀行餘額、行情或來源報表任務。券商端仍只允許既有 read methods。

## 要做什麼

- [ ] **405.1 專用 owner policy。** 新增窄 `FubonSyncOwnerPolicy` 及必要 config/directory port。`fubon.sync-owner-email` 唯一來自 `FUBON_SYNC_OWNER_EMAIL`；trim/lowercase 後必須對應既有 ACTIVE ADMIN，且與 configured admin 的 id 與正規化 email 完全一致。缺少、非法、找不到、停用、非 admin 或不一致均回 sanitized reason，不能 fallback 到 `ADMIN_EMAIL`、第一位 user、login-upsert 或硬編私人 email。範例設定只放中性空值。

- [ ] **405.2 fresh owner lock。** policy 的 preflight 可在 HTTP 前純讀；writer 取得其各自規定的第一把鎖後，必須用 repository `app_user FOR UPDATE` 讀 fresh projection，再重驗 dedicated/configured decision、ACTIVE/ADMIN 與 expected id。snapshot writer 的第一個 DB action 仍是 snapshot lock；realized-gain writer 的第一個 DB action 是 owner lock。owner row lock 持有到 commit，任何等待／變更／停用／降權／email drift 都使本次零 mutation。

- [ ] **405.3 兩條 accounting envelope 的 strict boolean。** Python `SelectedAccount`／`AccountingRead` 記錄 selector 是否明確；只有 branch 和 account selector pair 都由設定提供、selected account 精確相同，且原本每列 identity 驗證成功時，`settlement` 與 `realized-gains` success envelope 才輸出真正 boolean `accountBindingExplicit=true`。未設定 selector 的唯一帳戶可以安全唯讀但必為 false；Java DTO／contract 拒絕 missing/null/string/number，false 不可進個人 writer。raw identity、token 與 selector 值不 log、不回傳、不持久化；HMAC fingerprint 不可作 owner 或 event key。

- [ ] **405.4 設定傳遞與驗證。** `.env.example` 留 `FUBON_SYNC_OWNER_EMAIL=`，Compose 只傳此一非 secret policy key；不得覆寫實際 `.env`、secret mount 或任何 feature flag。tests 覆蓋 missing/invalid/different configured admin、另一 admin、role/status/email race、owner lock timeout、explicit true/false/missing/type-invalid，並證明 disabled gate 在讀 owner 或呼叫 adapter 前結束。所有測試使用 fake SDK／隔離 PostgreSQL，維持 FUBON flags false。

## 完成標準

t394／t395 能共用同一個 strict owner decision 與 fresh-lock 路徑，且任何不確定狀態都拒絕資料庫寫入。此 task 不自行產生在途款或已實現損益資料。
