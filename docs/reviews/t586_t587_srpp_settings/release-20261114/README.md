# 2026-11-14 發布進度收據

本輪已按使用者核准順序完成「兩專案合併推送 → 登錄新版本」；正式部署與限縮範圍驗收 **PASS**。正式日報 exact-bundle API 仍為409，未宣稱正式日報已採用。[目前狀態](status.json)

| 階段 | 已核對結果 |
|---|---|
| Git | 兩專案 feature commit、main no-ff merge、feature／main push及 exact remote-ref readback完成，當時四個工作區乾淨 |
| Registry | 原合併 SQL insert-only 執行成功，52→53列，新列四個來源欄位完全相同，原52列保持不變 |
| 正式部署 | 四服務重建／recreate完成；已部署 worker、內部業務契約、實際 JAR 投影與 HTTPS 畫面驗收 PASS（範圍限制見下） |
| 登錄後立即 exact-bundle GET | HTTP 409 `NON_TRADING_DAY`，`businessApiAccepted=false` |
| 正式日報採用 | NOT_YET_EXECUTED |

## Git 與來源身分

| 專案／ref | 完整 commit SHA |
|---|---|
| asset-management feature | `9d988e60f183f2bb67139da2412482aecb19d633` |
| asset-management main merge | `b2e057067a19ad551325d6f194a4a0bfb545e22c` |
| SRPP feature | `766e015b1c7d13f4053f07f0640be2e036011b32` |
| SRPP main merge | `b9eccf2e8790c0dd7af9f15ee5f777fce6424ff0` |

既有遠端均為 `twleader` 對應專案。兩份 main merge 均有兩個 parents，並包含相應 feature commit；`ls-remote` 讀回 feature／main 與上述 SHA 完全相同。實際 SRPP 執行工作區安全 fast-forward，原分支及142個未追蹤輸出／報告保留，tracked clean。[精簡 Git 與 remote-ref 證據](git-landing.json)

- Bundle：`fc31e0979d7279cc3f1237e9ea91500d8a48958ed67f9b1d66f9e420ff996bda`
- Model bytes：`6d26483bcb530e26d8686bbaa565e805bfe137efa1d6db7a8671cedb796620aa`
- Swagger bytes：`a270af1021fa58e0c5d91e190a9d86363ceb9cd9f2b08140de96decc5740ec6c`
- Manifest bytes：`16dd92d0f65cc6d97038ad984b7bbe7e53615396dc458840118899f856a8ff22`

合併後各身分與已審凍結來源一致；model原始位元組與正式配置目標未改。

## 精確 registry 登錄

從合併後受追蹤 `backend/src/main/resources/srpp/report/d201-registration.sql` 登錄，SQL SHA-256 為 `5dff438c23c99b5f102a402c3999981277b79b0c1227ec85c707a49e9644f156`。原始 `INSERT … ON CONFLICT DO NOTHING` 未改成 UPDATE／DELETE；此次新增一列，登錄時間為台北2026-11-14 16:22:20。

新列 `policy_bundle_sha256`、`formula_version`、完整 canonical `policy_document` 與 `formula_manifest` 均逐值符合原來源；formula version為 `ASSET_MGMT_SRPP_V2`。登錄前原52列的內容保持不變。[完整新列與比對收據](registry-exact-row.json)

## 登錄後立即 API 診斷

以同一新bundle及Swagger SHA對 `GET /api/public/srpp/report-calculations` 進行唯讀讀取，指定2026-11-14與12:00時段；未retry、未建立報告。回應為409 `NON_TRADING_DAY`（指定日期非交易日），符合週六日曆閘門。[去除無關標頭的 exact-bundle 收據](9090-exact-bundle-after-registry.json)

這個409 **不證明業務 API 已接受新版本，也不證明正式容器已完成部署**。`businessApiAccepted=false`，正式排程採用仍為 `NOT_YET_EXECUTED`；待實際部署、連線及合法日曆／時窗驗收後追加收據，不為補驗收手動重跑日報、寄信或交易。

## 部署後限縮範圍驗收

部署來源為上列 asset main `b2e057067a19ad551325d6f194a4a0bfb545e22c`，四服務重建，另六服務保留；7份business與2份BFF resource bytes、12個class及19份worker檔案核對通過。[部署摘要](deployment-summary-final.json)含實際worker HTTP：100 paths、重跑相同、9種錯誤拒絕、406月audit及原 `e9a969…5f8` 結果hash。內部業務草稿76欄及小數保存回讀、舊草稿不變、正式／草稿短線各24列、舊revision409與detail WAIT通過；實際BFF JAR投影保留原來源，六個未登入／偽造header請求均401。

[部署HTTPS畫面](deployed-browser-proof.json)使用實際業務資料與部署BFF JAR投影攔截回應，身份僅合成rendering，非Google OIDC登入E2E；0 page errors、0 API writes，三組觀察列數為0／0／24。只修測試選取器，未改source。backend LOCAL simulate為 `NOT_EXECUTED_ZERO_VENDOR_GUARD`；V2由已部署worker直接HTTP驗證。服務重建後需重新登入；未以合成身份冒稱正式登入驗收。[最終pins](last-pin-after-browser.json)確認10個容器與驗收時相同、四個tag均指向running image。

[SRPP host連線](9090-connectivity.json)三項HTTP200且非空（日曆365、指數21、報價39筆），來源檢查PASS。部署後[精確bundle讀回](9090-exact-bundle-after-deploy.json)仍409 `NON_TRADING_DAY`，`businessApiAccepted=false`、正式日報採用 `NOT_YET_EXECUTED`。未publish正式設定、手動建立報告、寄信或交易。前置OFFLINE／PREFLIGHT與登錄後立即409收據保留為歷史，不替代上述正式部署證據。

本次收據與任務完成報告收尾只改文件／metadata；相對已部署 `b2e057…` 不改任何runtime、source或authority，故不為文件自身commit SHA重建容器。
