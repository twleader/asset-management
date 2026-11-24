# [t631] SRPP 台美事件反彈退潮規則包發布

**對應 Requirements:** Requirements 187／199（不可變台美日報計算與正式 authority）
**前置任務:** t629
**Liquibase changeset:** 無

## 背景

使用者授權將候選事件反彈風險提示發布至 SRPP 與 asset-management。舊 bundle 為 f1af47959e490b84ba47e722071ab66e183a18f018fd37f9c38a193bfbe7dbc9；候選來源位於 /Users/steven/.codex/worktrees/event-rebound-release/SRPP，候選 bundle 為 da1a1dadcecea13671f687d90e92232bb7e3c2547eeadb34e0690c312f4965b6，以最終獨立審查後凍結來源為準，來源異動需重新產生及驗收全部相依產物。

## 要做什麼

SRPP 台股與美股事件反彈退潮提示只供日報風險說明，原交易雷達 action、交易閘門、資金及限價規則維持；資產端只發布對應 authority 身分，不新增 API、計算公式、producer、DB schema、排程或交易權限。model_inputs、Swagger、formula manifest 與 calculator hashes 應保持原 bytes。以凍結 SRPP manifest 原 bytes 同步 backend，由既有 generator 重產 SQL 與 golden，golden 金融輸出保持不變。兩 repo feature commit、main no-ff merge、push 並精確 remote readback 後才執行 insert-only registry；舊 rows 不更新或刪除。之後由乾淨 main rebuild/recreate business-services、驗 healthy、新 image 與 JAR authority bytes，再從 SRPP host 對 9090 作唯讀 GET 實測。夜間不補造正式日報採用，report-calculations 合法時窗驗收保留 NOT_YET_EXECUTED。

- [ ] 631.1 spec 獨立 Codex CLI 審查通過後，複製凍結 manifest/model bytes、原 generator 產 SQL、same-input/long-short-exit golden，authority gate 與 registry 測試通過。
- [ ] 631.2 部署與 JAR bytes 驗證完成後、最終 9090 驗證前，以 `git -C /Users/steven/Project/SRPP merge --ff-only main` 安全同步日報實際工作區至已核對 remote 的 main，保留所有未提交工作；遇分歧或路徑衝突即停止，不得 reset 或覆寫。讀回 commit、manifest bundle 並執行 policy daily Codex/Claude、runtime check，確認最終 hash 一致。依授權順序兩 repo 落地、registry 精確讀回、只部署 business-services，保留無關服務、正式設定及全部未提交工作。保存所有命令、退出碼、hash 與收據。

## 驗證

```sh
bash scripts/spec-check.sh
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/event-rebound-release/SRPP --check
python3 scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/.codex/worktrees/event-rebound-release/SRPP --check
python3 scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/.codex/worktrees/event-rebound-release/SRPP --check
ruby scripts/tests/srpp-report-policy-register-test.rb
mvn -q -f backend/pom.xml -Dtest='SrppReport*Test,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest' test
git diff --check
```

正式兩 repo 落地與 registry 後，從 /Users/steven/Project/asset-management-main 執行：

```sh
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker inspect asset-business-services --format '{{.State.Health.Status}}'
git -C /Users/steven/Project/SRPP merge --ff-only main
python3 /Users/steven/Project/SRPP/scripts/policy_bundle.py check --purpose daily --consumer Codex
python3 /Users/steven/Project/SRPP/scripts/daily_runtime_contract.py check
python3 /Users/steven/.codex/skills/srpp-policy-release/scripts/verify_9090.py --srpp-root /Users/steven/Project/SRPP --expected-bundle <最終bundle> --output /tmp/event-rebound-release-receipts/9090-connectivity.json
```

## 完成報告

待驗收；正式發布由本輪 release operator 執行。本任務不得以部署健康或一般 HTTP 成功宣稱正式日報已採用。
