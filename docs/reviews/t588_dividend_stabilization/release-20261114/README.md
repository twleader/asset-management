# D218 高股息兩日止穩正式發布收據

0056、00713、00878 的自主核心補足已發布為 D-218／v2.66.0；兩專案已合併推送、新規則已登錄，business-services、BFF 與情境 worker 均已重建並重新建立容器。正式交易日報採用仍為 **NOT_YET_EXECUTED**；當日非交易日的業務 API 負向測試沒有被當成採用成功。[完整狀態](status.json)

## 正式行為

- 初步止穩採創低次日起連續兩個已完成台股交易日未創新低；平低可、再次創低重新起算，盤中資料只能阻擋破底，不計入完成日數。首批與回測批不要求五日線、月線或 KD 轉強。
- 首批最多固定補足預算 10%；真首批成交後，具完成日先後證據的回測守穩可再用 20%，累計至 30%。部分成交即占用該批；待成交委託先阻擋新增。同波訊號不因跨週、換 consumer 或規則 hash 改變而重新產生首批。
- 首批尚未真成交或掛單前，新低可更新同一 wave 的觀察低點並重算兩日。首批真成交／掛單後沿原始來源固定保護低點，後續破底凍結後批；新 wave 須有核准與前波結束證據，不能由滾動窗口自動重生。
- 後續剩餘預算須真回測批完成、兩日止穩仍成立，並由精確雷達確認月線 ABOVE 與 K>D；沿原每日 3 張、每週 5 張總額度逐批。D198 配息調整價位、D189 不追高、個別目標、12% 高股息群組、資金、風險、來源與去重檢查保留。00919、既有定期定額與短線部位各沿原規則。

## 測試與審查

SRPP 307 項回歸通過；該輪在 Swagger metadata 最後同步前執行，之後只變更已驗證的 API 契約／規則包身分，金融程式與參數不變。合併後另從實際 SRPP 工作區重驗 rules、runtime 與 Claude／Codex／model 三個 consumer，全部精確綁定本次最終 bundle。[SRPP 回歸與來源說明](validation/validation-notes.md)、[最終 Codex 身分](validation/merged-consumer-codex-final.log)

資產端最終 backend 74 項、BFF 51 項，共 125 項通過；含 26 個跨語言 synthetic fixtures。兩個 host Maven package 實際 exit 0；這些前置檢查不替代部署。[逐類測試收據](java-test-receipts.json)、[host package 證據](host-package-preflight.json)

獨立 Codex CLI 規格第 3 輪 PASS，唯一 Minor 的調用順序文字已修；架構第 2 輪 PASS（0 Critical／Major／Minor）。審查者對完整 authority checker 的唯讀環境限制有明示，未把主 agent 後續 host 實測冒稱為審查者執行；host authority 原工具檢查另有 PASS 收據。[規格審查](reviews/codex-spec-review-round3.md)、[Minor 處理](reviews/d218-spec-round3-resolution.md)、[架構審查](reviews/codex-arch-review-round2.md)、[權威準備逐步結果](authority-preparation.json)

## Git 與正式登錄

| 專案 | feature commit | 程式 main merge |
|---|---|---|
| SRPP | `24ad536eb9ecda15f136a2f476d282cc8ee7d9d5` | `ab1c27adc4e1e0db2eafe798aed2749dcba595b2` |
| asset-management | `70b368b2f6664f87a33fd218b8f4058da03ef3ec` | `3ce6744f98e2dffae2a61f115f868d9f81e9aa5f` |

兩專案都先 feature commit／push，再 main `--no-ff` merge／push，並精確讀回 feature／main 遠端 refs。實際日報工作區 `/Users/steven/Project/SRPP` 安全 fast-forward，原分支及 142 個未追蹤檔案保留。[Git 收據](git-landing.json)

最終 bundle：`62a95aad53248a93e8fa3768347d2abd7b2c81babbfc97789f2d8a6233e6fc56`。Swagger：`6e2ada03c01a5e3bcbde24be7c6fc7df04e30bf76dd76766d5215b1839c820c0`（1.28.2）。V3 formula：`452d42608d6357c8e7ddd82ff03e91b3fb82d1c56978e029fd4717cfc60f90fb`。V1／V2 歷史 manifest bytes 與 digest 保留。

兩專案落地後，僅執行合併 main 受追蹤的原 generator `INSERT ... ON CONFLICT DO NOTHING` SQL，transaction 成功，registry 53→54。新列四個來源欄位全文相同，原 53 列完整不變；完整舊列僅留本機私有收據，不納入 Git。[新列全文與比對結果](registry-exact-row.json)

## 正式部署與實機驗證

從乾淨 asset main 與其原 `.env`／Compose project `asset-management` 建置並 force-recreate 三個必要服務。環境與掛載只比較相等，不輸出秘密值；三服務 healthy，七個無關容器 ID／image 保留。BFF 在上游健康後具體 market-index GET 已恢復，再部署本輪有來源變更的 BFF。[容器、設定及 pins](deployment-summary.json)

運行中 business JAR 的 model／manifest／Swagger／SQL 四份資源與合併來源逐位元相同；BFF JAR trusted V3 mirror 亦相同。從實際運行 JAR 抽取類別並反射 `SrppFormulaCatalog`，V3 manifest 原文等於 registry、digest 等於來源；其 D218 pure evaluator 實跑 26 個 synthetic cases 全通過。這是部署程式驗收，不是真實市場交易或正式報告採用。worker 實際 HTTP health／catalog 的 bundle 相同，20 個 runtime 檔案與匯出來源逐位元相同。[運行產物與行為驗證](runtime-artifact-proof.json)

從實際 SRPP Python 環境依最終 Swagger 完整目錄與四個路由讀取後，skill 連線工具三個 GET 均 HTTP 200 且非空：日曆 365 日、TWSE 日線 21 筆、台股報價 39 筆。行情新鮮度與買進資格不在該收據範圍。[連線證據](9090-connectivity.json)、[Swagger 讀取範圍](swagger-maintenance-reads.json)

2026-11-14 為非交易日。以當日／11:40／精確新 bundle 對 calculation-context 僅 GET 一次，實際 HTTP 400 `INVALID_REQUEST`；明示負向日曆邊界測試，`businessApiAccepted=false`。沒有送已知非當日的 request 製造 503，也沒有呼叫時窗外 report-calculations、重試、手動 producer、寄信或券商交易。[精確 bundle 業務 API 收據](9090-exact-bundle-current-date.json)

## 仍需真實來源的部分

本輪接入正式 DIVIDEND_TOPUP 計算與報告檢核，但不補造既有 canonical wave／國泰帳戶／pending／資金與風險來源。缺必要來源仍為具名 BLOCKED；市場事實可先揭露，不代表已有可執行買量。雷達 WAIT 尚無 typed cause 可證明只在等待技術面時，仍列 `RADAR_WAIT_CAUSE_NOT_VERIFIABLE`；本輪沒有假稱完成技術 WAIT 豁免。下一次合法交易日時窗的完整正式日報採用尚未執行。

本目錄為部署後文件證據收尾；相對上述程式 main merge 不更動 runtime／authority，後續文件 commit 不需要重新建置同一映像。
