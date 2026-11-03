# SRPP 午盤一致性修復發布紀錄（2026-11-03）

狀態：程式、雙專案 Git、規則包登錄、正式部署及新 hash 夜間業務 API 讀回驗收完成。正式盤中日報採用仍為 `PENDING_LIVE_OBSERVATION`／`NOT_YET_EXECUTED`。

## 修正範圍

- SRPP 共用報告一致性檢核辨識 009826 的 D-143 建倉用途，避免把通用資金 API 未支援欄位單獨升格為額外阻擋；原始掛單未知仍保持未知，既有必要條件及有效 BLOCKED 不解除。
- 來源時間先作嚴格正規化，使九位秒小數能在 Python 3.10 解析；保留原時區、日期及 D-175 時效規則。解析失敗不能當作確定未武裝或賣出條件未成立。
- 觀察紀錄按各 consumer 自有原始證據查核，保留隔離、歷史武裝與高水位，不跨軌複製、不回寫歷史日報。維護重播不具交易授權。
- SRPP main 在本輪發布前已納入 D-206；本輪修復保留其已合併來源，model 完整 bytes 與該基準相同。資產端同步最終 model／manifest／SQL／golden，production Java、公式、正式 targets、API／schema、Swagger、排程與權限不變。Java 測試只同步既有 current-authority 身分斷言及測試名稱。

## 最終來源與 Git

| 項目 | 精確身分 |
|---|---|
| Bundle | `3713fffaf08ca7f365a9c942c9f5e8c298bed99816f6ac1f29d304506c6c1521` |
| Model bytes SHA-256 | `7a7ad412bb829db6f1451e7b1d7c46482137a0cec810ddea1ae22b34e5702f7e` |
| Manifest bytes SHA-256 | `c066521b622bed5b0a1365add60424b75536c5d91247edcca96057be5def598e` |
| Swagger SHA-256 | `d8211f7c50da18c0eba69bb2dfdfcc4f4b2a6cc5ff6341ec28d6d63dd00116f3` |
| SRPP feature | `03dc38d9e705839c207b8331fa88030c030f040a` |
| SRPP main／remote | `6ff2f886e6684655e6b677cb33567bcc65ae412b` |
| Asset feature | `219db69843db6dd144e91a42a7dd23e091ba4aec` |
| Asset main／remote（執行資源發布） | `171c593ccc3a750b25bfa673d29312ceb6069fa8` |

兩專案均為 feature commit、main `--no-ff` merge、feature／main 推送既有 origin 與精確 remote-ref 讀回。SRPP 實際日報工作區已安全 fast-forward，原有未追蹤檔案逐位元保留。早期 `5b756984…` 產物因基準推進已失效，僅保留歷史收據，沒有登錄或部署。

## 測試與產物

- 原工具 source／runtime、Codex／Claude／model 檢查通過；SRPP 聚焦與 D-206 回歸為 129＋52 項通過。
- 主 report 22 案例及 long-short-exit 15 案例的全部輸入與金融 expected 逐值不變；兩份 golden 僅更新 bundle 與實際變更的 calculator source hash。
- Authority checker、4 項 authority 負例、兩份 golden `--check`、13 項 Ruby／307 assertions、42 項 Java 測試全通過，零失敗、零跳過。
- Full renderer／identity check 與三份 Swagger 原文逐位元比對通過；spec-check `BLOCK 0／CHECK 0`，DB schema baseline 完整一致，`git diff --check` 通過。

## Registry 與正式部署

23:48:48+08:00 從已合併 SQL 執行單次 insert-only transaction，registry 26→27 rows；既有 26 rows 全文不變。新 row 的 bundle、formula version、policy document、formula manifest 四項全文精確符合官方 generator；formula 為 `ASSET_MGMT_SRPP_V2`，未 UPDATE／DELETE 歷史 row。

從乾淨 asset main、原正式 `.env` 只重建並 recreate `business-services`，23:50:30+08:00 完成：

- Image：`sha256:8ba68f3fb41c30aaf8c38e35261e79fbe3058552c556281cab68f4c3a670bf9f`
- Container：`d3ba04cf5914b3c837a0a483f1ded3dab06775b684d8cf36b18b3dc11db13dc0`，healthy。
- JAR 內 model／manifest／Swagger／registration SQL 四份完整 bytes 與已合併來源一致。
- 46 項 effective environment 與 main 設定一致，`.env` 原文不變，其他 8 容器 ID／image 均未變；結束時再次固定 image。
- BFF UP、gateway 安全 GET、官方日曆通過。SRPP host connectivity 取得非空日曆、TWSE 與台股報價。
- 本輪未手動觸發 producer、來源刷新、郵件或券商交易；未重送 registry SQL。

## 業務 API 驗收

登錄後、部署前 exact hash 雙 consumer GET 為 `409 POLICY_UNSUPPORTED`；23:51:10 部署後首次雙 consumer GET 為 `503 CONTEXT_NOT_READY`。過渡失敗回應已完整保留，不能以 registry、JAR、health 或普通 connectivity 代替業務 API 接受。

既有背景初始化自然完成後，23:53:53+08:00 從 SRPP 實際主機以同一最終 bundle 再次讀回：Codex／Claude 均為 HTTP 200，直接 L0 與獨立 verifier 均 exit 0／PASS，hash、consumer、日期、slot、不可變內容與來源契約核對通過。兩份 coverage 均為 0 COMPLETE／7 PARTIAL／5 UNAVAILABLE，`tradeAuthorization=false`；不將缺失模組宣稱完整或可交易。

本次通過的是晚間非可執行診斷包相容性讀回。正式盤中金融採用仍為 `PENDING_LIVE_OBSERVATION`／`NOT_YET_EXECUTED`，另受原時窗與日報必要條件約束。409、503 與後續 200 的原始回應及驗證收據均保留，沒有以 health 或 registry 取代業務 API 驗收。

本輪安全發布收據位於本機 `/private/tmp/asset-noon-release/final-3713/`。完整 API body、直接資產與觀察帳本證據僅留在 SRPP 私有 ignored output，不納入此公開專案。此文件後續收尾只更新文件，不改執行資源或重建服務。
