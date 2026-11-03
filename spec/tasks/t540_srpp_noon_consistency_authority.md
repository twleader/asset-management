# [t540] 發布午盤建倉阻擋與時間解析修正的規則包

**對應 Requirements:** Requirements 187／193（SRPP 唯讀報告計算與規則包 authority 發布）
**前置任務:** SRPP 午盤一致性修復完成測試並凍結來源
**Liquibase changeset:** 無

## 背景

2026-11-03 午盤兩軌的 009826 計算器都產出一張容量及非高檔資格，Codex 卻把通用資金 API 的未成交委託／reservedFunds／reservedFees 缺口另作等待條件，未使用 D-143／D-152 的既有用途規則。Claude 的 Python 執行環境另無法解析來源九位秒小數，D-174 全列成未驗證，並把既存 00919 觀察誤述為未武裝。兩軌各自觀察歷史不同；不能複製對方帳本來取得一致結果。

SRPP 本次修正本機時間正規化、報告一致性機械檢核與條文，不改金融數值。發布前另一流程已將 D-206／v2.55.0 合併並推送至 SRPP main `887f2bc70e60a3835461956b56e6fa2d1a78a5a2`，本輪以該已合併內容為基準接回修復，保留已合併研究契約，不取用 D-206 資產端未合併工作。資產端發布同一來源的新 bundle，維持未知掛單為未知、既有有效 BLOCKED 與所有 API／金融公式不變；早先 D-205／5b756984 的驗收只作歷史製備證據，不能冒稱新基準已驗收。

## 要做什麼

- [ ] 540.1 唯一來源為 `/private/tmp/srpp-noon-consistency-20261103`。等待來源凍結，先以原 `policy_bundle.py` 分別驗 daily Codex／Claude 與 simulation model，再驗 runtime contract；記錄 bundle、manifest/model/Swagger 原始 SHA-256。來源若變，舊驗收全部失效。不得混入其他 worktree 未合併變更。
- [ ] 540.2 完整同步已合併 D-206 基準加本輪修復的 model／manifest 原始 bytes 到 classpath decision authority。model 相對指定 SRPP 已合併基準不再改動；相對尚未同步 D-206 的 asset main 可以承接 D-206 身分／研究契約，不聲稱舊 model bytes 完全不變。正式 targets 維持。用原 Ruby generator 重產 insert-only registration SQL，既有 Python generators 重產主 report 與 long-short-exit golden。全部既有 cases 的輸入／金融 expected 逐值不變；僅 bundle 與實際變更計算器的原始來源 hashes 可變。Python 全部禁止 bytecode 寫入，fixture 的合成日曆只在測試暫存區，不能替代正式日曆。
- [ ] 540.3 保留 `DecisionPolicyAuthority` 原始 bytes／strict JSON／JCS 驗證及 report authority 的語意 gate。通過 authority checker、registry generator 測試、authority 負例與聚焦 Java authority／calculator 測試。無 Java 業務規則、API／schema、Swagger、producer、排程、權限、DB schema 或券商整合變更。既有 `DecisionPolicyAuthorityTest` 的當前來源身分斷言明確更新為 D-206／2.55.0／3.22.0，只核已合併新基準，不複製另一 worktree 的未合併測試，也不修改金融數值、放寬驗證或刪除斷言。`FUNDING_CAPACITY` 未支援欄位維持 UNAVAILABLE，不偽造零掛單或解除有效 BLOCKED；009826 專屬回退由 SRPP 報告層核對。
- [ ] 540.4 SRPP 觀察查核只以每軌自己的原始 quote＋radar anchor 及結果驗指紋。Codex 0050／006208／00881／009804 完整即保留；Claude 00919 只有在原 anchor 精確符合現帳後，才用既有 maintenance replay 接續，保留武裝日、高水位與完整稽核，固定無交易授權。歷史核心、appendix、原始 API 證據不改寫；不跨軌補造觀察。
- [ ] 540.5 先完成本任務可審閱產物、離線測試及必要 build。使用者已在本聊天明確指定雙 repo Git → registry → 乾淨 main 部署 → 精確新 hash GET，並同意不等待 D-206、優先本輪修復及推送既有私人 `twleader/SRPP`／公開 `twleader/asset-management`；不重問相同順序或目的地。已合併 D-206 是新基準，不回退；未合併 D-206 工作保留、不取用。兩 repo feature commit／main no-ff merge／既有 remote push 與 exact refs 核對後，才執行已合併 SQL 的單次冪等 INSERT transaction；新 row 的 `policy_bundle_sha256`、`formula_version`、`policy_document`、`formula_manifest` 必須按原 generator canonical 全文逐值精確相等，歷史 rows 全文不變。從乾淨 asset main／原正式設定只 rebuild＋recreate business-services，驗新 image／container、health、JAR authority 全文、effective config 與其他容器未變；不關閉或變更既有排程／flags。
- [ ] 540.6 從日報實際 SRPP 主機環境唯讀驗 9090 日曆、TWSE 與台股報價；並以最終新 bundle hash 分別為 `consumer=Codex`／`consumer=Claude` 直接 GET `report-calculations`，保存完整 HTTP status/body 並驗 consumer／owner／日期／slot／hash／不可變內容契約。合法盤中才可宣稱正式日報採用；晚間僅是 Task537 診斷包相容讀回。503 CONTEXT_NOT_READY 表示註冊已完成但業務 API 接受尚未通過，不得以 health／registry／JAR 代稱通過。不得手動觸發 producer、刷新來源、POST observer、重跑報告、建立或寄送郵件、交易。

## 驗證

```bash
python3 -B /private/tmp/srpp-noon-consistency-20261103/scripts/policy_bundle.py check --purpose daily --consumer Codex
python3 -B /private/tmp/srpp-noon-consistency-20261103/scripts/policy_bundle.py check --purpose daily --consumer Claude
python3 -B /private/tmp/srpp-noon-consistency-20261103/scripts/policy_bundle.py check --purpose simulation --consumer model
python3 -B /private/tmp/srpp-noon-consistency-20261103/scripts/daily_runtime_contract.py check
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-noon-consistency-20261103 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /private/tmp/srpp-noon-consistency-20261103 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-noon-consistency-20261103 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-noon-consistency-20261103 python3 -B scripts/tests/check-srpp-report-authority-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest test
bash scripts/spec-check.sh
git diff --check
```

另須保留兩 repo remote refs、registry exact row／歷史內容、main build／recreate、JAR bytes、9090 connectivity 與新 hash report HTTP 回應收據。正式日報盤中採用與晚間診斷包讀回分開記錄。

## 完成報告

待實作與驗收回填。
