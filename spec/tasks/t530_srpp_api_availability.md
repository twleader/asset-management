# [t530] 修正 SRPP 雷達版本拒絕與投影驗證身分漂移

**對應 Requirements:** Requirement 187（不可變日報包）、191（唯讀投影）、193（正式規則包發布）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-02 9090 基礎查詢及雙軌 report-calculations 皆 HTTP 200，十二模組 7 PARTIAL、5 UNAVAILABLE。已凍結 0056／00713／00878／00919 雷達是 EVIDENCE_GATE_V2 且 dataComplete=true，但 Java 三個 consumer 與 Python 短線分析只接受 V1，導致有效資料被拒。SRPP A1/A2 verifier 另硬編舊 Swagger hash，與已發布文件不符。真正缺官方薪資、券商子帳戶、委託與策略狀態的項目不能憑空修成可用。

## 要做什麼

- [x] 530.1 在 backend SRPP report 套件提供純函數的封閉版本判斷，只接受精確 EVIDENCE_GATE_V1／EVIDENCE_GATE_V2；來源擷取、精確雷達及短線分析共用。未知／缺少／空白均拒絕，不接受字首匹配。V2 用於交易判斷時另要求 officialSma20Verification 存在、status 為 CONFIRMED／UNAVAILABLE／NOT_COMPARABLE 且 gateApplied 為 boolean false；CONFLICT／缺值／未知／gateApplied=true 均拒絕。來源品質可驗證並保存衝突事實，但不能作為交易閘通過依據。保留 owner、market、symbol、日期、價格、時效、dataComplete 及所有既有方向／風險／資金／五檔／下單阻擋；不更動投資公式或閾值。
- [x] 530.2 SRPP Python 短線分析採相同版本集合，保留原風險條件；投影驗證每次讀專案 root 的已發布 Swagger bytes 計算 SHA-256，caller hash 不符即拒絕，通過仍須原 immutable package／schema／L0／owner／policy 驗證。
- [x] 530.3 依已發布投影契約修正 SRPP consumer：預設 universe 只取原 module.index 的 BUY/SELL 腿，排除 NONE，保留被阻擋 BUY/SELL 腿；合法空 universe 回應可通過。未知 fact 必須 unit=NONE/value=null/asOf=null/sourceRevision=null/receiptRefs=[]且有原因，AVAILABLE 才使用具名指標單位。明確查詢包外合法代碼時只能接受 row 與全部 facts 皆 UNAVAILABLE/null，禁止接受包外 AVAILABLE 或偽造0；未明確查詢仍要求精確凍結 universe。所有內容雜湊／身分／coverage／固定run ID／來源驗證不變。新增空結果、NONE排除、blockedBUY保留、未知unit及包外具名拒絕資料回歸，保留既有偽造數值負例。修正既有富邦交割整合測試過期的目標帳戶fixture為現行股票款對應證券戶，金融數字／冪等／rollback斷言不變，不改產品交割邏輯。
- [x] 530.4 新增 V2 正例與 V1 歷史相容、V3／缺少版本負例；保留錯日期／市場、actionGateReasons、BLOCKED 與 null 邊界。Java 三個 consumer 及 Python 兩個修復面向皆需有對應回歸測試。僅使用合成 fixture 驗邊界，不能宣稱等於真資料成功。
- [x] 530.5 完成 SRPP runtime contract、policy manifest 重產；只同步本輪實際變更來源 hash，不修改模型／已接受投資規則／公開 Swagger bytes。同步 backend 原位元組 authority、golden bundle metadata、正式 target document 與 insert-only registration SQL。舊 registry rows 及已凍結 package 不修改。
- [ ] 530.6 先完成離線測試、authority 檢查與建置驗收；兩專案各自 feature commit、no-ff merge、push 並讀回 remote main 後，由本輪使用者已授權的 release operator 執行 insert-only registry SQL，唯讀回讀新 hash 的精確 registry row。重啟前依當下台北日期唯讀逐項證明啟動及有界觀察期間的外抓／寄信／資產修改入口皆 no-op：analysis self-heal（未到最早啟用寄送時間、無啟用時間、停用或今日已有 OK；時間已過且今日無 OK 則停止部署）、所有日期 analysis PROCESSING=0 且期間不跨任何啟用寄送分鐘、十個 index 皆最新、Fubon ETF startup 原設定停用或 radar candidates 全有 success snapshot、SnapshotDateRoll 對所有 owner 最新快照與到期在途款符合原 roll no-op、配息 backfill 原設定未啟用、其他 startup／backfill／export 入口逐項查既有 gate。任一入口無法證明會略過則停止部署並具名回報，不修改旗標或補造資料繞過；跨日重新查。再從乾淨 main rebuild／recreate business-services，保留 main 正式設定並讀回 jar embedded authority／新 image 身分；部署後有界等健康 120 秒與安全 GET 30 秒，親核 analysis／index 略過 logs 及前後無新增寄送／補抓。先讀交易日曆，僅在當日台股交易日且台北 09:05 <= now < 13:30 以新 policy hash 唯讀 GET、原 verifier 及固定 calculationRunId 讀回確認來源狀態與缺口。此順序沿用既有規則包發布鏈，不以未註冊的新 hash 中斷正式 producer。沒有正式來源的模組保持不可用，不新增刷新、寄信、券商操作或 observer POST。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='SrppReportExtraCaptureAdapterTest,SrppReportModuleCalculatorTest,SrppReportShortUsCalculatorTest,SrppReportStateTopupCalculatorTest' test
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/srpp-api-availability-20261102/SRPP --check
```

SRPP worktree 執行 `python3 -m unittest discover -s scripts -p test_verify_srpp_projection_api.py` 與 `python3 -m unittest discover -s scripts -p test_short_term_opportunities.py`；依現行 run-stack 流程由正確 worktree rebuild／recreate business-services，確認 9090 sourceVector 不再僅因 V2 降級，並以原 verifier 驗雙軌計算回應。正式來源不足不得以人工內容解封。

## 完成報告

- 修正兩類程式根因：四處 V1-only 雷達拒收與投影 consumer 漂移（舊 Swagger hash、NONE 腿 universe、未知 unit 及包外 UNAVAILABLE 回應）。Java 共用純判斷，Python 使用既有 policy 相容驗證，V2 官方均線衝突仍否決；coreTiming 直接入口也保留否決。
- 完整後端測試 3,343 項，0 failures／0 errors／2 既有 skipped；另聚焦五類 36 項。完整 suite 發現未修改 main 也會失敗的既有交割 fixture，依已發布證券戶規格修正測試資料後，該類 23 項與完整 suite 均通過，金融數字／冪等／rollback 斷言不變。
- SRPP projection 13、短線 26、D-174 24 項通過；registry Ruby 13 案／307 assertions、authority regression 4、Swagger identity regression 6 皆通過。spec 機械 BLOCK 0／CHECK 0；spec 獨立審查三輪，最終 0 critical／major／minor；獨立架構初查及追加 diff 查核皆 0 findings。
- project-review-loop 的全範圍審查共 1 輪、最後確認 2 個程式根因，未進第 5 輪自查；停止迴圈後的修正已做上述測試與 diff 架構查核，不宣稱再做另一輪全專案審查。
- 正式模型、投資規則及 Swagger 原始 bytes 不變。本輪原離線驗證包為 `4dc22408b91c927c08578f7ab6308efd1b86d706c7eb3076b5cf2e7288b430ca`；Swagger 為 `40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe`。兩 repo authority bytes 與 insert-only SQL／golden metadata 同步；22 主案例、13 short/US 案例的完整 cases／expected 均未改。
- 薪資與扣繳、富邦 owner 綁定、國泰子帳戶、正式委託、consumer/core/pace/regime/short-sleeve 歷史來源缺口保留具名不可用，不以零或另一來源猜測。
- 本次提交時，兩 repo 合併／推送後的 registry、乾淨 main 部署與合法時窗 GET 尚待執行，將於實際驗收後補記；12:41 的 no-op 預檢僅證明當時條件，正式 recreate 前必須重新查核。

- 並行整合：SRPP main 在本輪 Git landing 前已發布 `3bd5acf` 的 009826 本機建倉工具。本輪 feature 以該 main 為正式來源整合，完整保留 incoming 模型與權威條文，僅重產自己的 runtime／manifest；combined bundle 為 `a51f8278f558a48449aee5e2ced0a36e04408e033a092b7b5abd10d26475aa9b`，feature integration commit `098bf732ce9b1fb1d0268fccd7e51632e917460a`。與最新 main 的來源差異僅本輪四支修復／測試 scripts 加 runtime contract。兩軌 policy/runtime 與 Python 79 項（含 incoming 009826 16 項）通過。Task531 已合併為 asset main `a1f04436`，本輪在其共享檔案落地後保留全部內容並同步 combined backend authority；source checker、22 主案例／22 state component parity、renderer／sync checks 與整合後聚焦36項皆通過。combined runtime 仍待兩 repo Git landing 後實測。
