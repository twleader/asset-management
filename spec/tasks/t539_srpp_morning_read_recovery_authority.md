# [t539] 發布台股早盤讀取恢復修正的精確規則包身分

**對應 Requirements:** Requirements 187／193（SRPP 唯讀計算報告與現行規則包 authority 發布鏈）
**前置任務:** SRPP 早盤讀取恢復與守門員修正完成驗證並凍結來源
**Liquibase changeset:** 無

## 背景

2026-11-03 Codex 早盤在初次條文輸出截斷後，對已恢復完整的批次仍記為未確認，提前停止且未建立報告。SRPP 負責修正單批輸出、失敗恢復／停止判定和守門員查核；這些來源程式／規格變更必須重產 bundle。資產端只發布同一已驗證新身分，避免新規則包被 9090 authority 拒絕。

## 要做什麼

- [x] 539.1 唯一 source root 為 `/private/tmp/srpp-morning-report-fix-20261103`，先等待來源負責者確認最終凍結與測試通過，再以 `python3 -B scripts/policy_bundle.py check --purpose daily --consumer Codex` 和 `python3 -B scripts/daily_runtime_contract.py check` 驗源；記錄實際 source commit、bundle、manifest/model/Swagger hashes，不預填未產生 hash、不同步暫存版本。model 金融 bytes、公式、targets、Swagger、API/schema、producer、寄信／券商權限均維持。
- [x] 539.2 完整複製同一 source 的 `assumptions/policy_manifest.json` 到 `backend/src/main/resources/srpp/decision/policy_manifest.json`。model 與正式 targets document 必逐位元維持。使用既有 Ruby generator 重產 `backend/src/main/resources/srpp/report/d201-registration.sql`，只能 INSERT 新 hash 並 `ON CONFLICT DO NOTHING`；不得 UPDATE／DELETE 歷史 rows。以原 `generate-srpp-report-golden.py --srpp-root <absolute source>` 重產主22案例，並用既有 `generate-srpp-long-short-exit-golden.py --srpp-root <absolute source>` 重產15案例 golden；兩份僅允許 bundle metadata 改變，所有 cases、calculator hashes 與其餘 fixtures 不變。Python 一律 `-B` 或 `PYTHONDONTWRITEBYTECODE=1`，不得在 SRPP 寫 bytecode。
- [x] 539.3 保留 Java authority strict duplicate/raw-byte/JCS/hash 驗證及 report candidate 十欄精確語意 gate；不改 Java 計算、API、schema、Swagger 或 gate。使用同一 source 執行唯讀 authority release checker、Ruby registry 測試、release gate regression 和聚焦 Java authority／calculator 測試。source 乾淨提交後，執行 renderer／identity 的 `--check --srpp-root` 零寫入驗三份 Swagger 同身分；本次不需要任何 Swagger mutation。
- [x] 539.4 兩 repo 的 feature commit、no-ff merge、push 和 remote-ref readback 都完成後才單次登錄 insert-only SQL並精確回讀新row，驗歷史row不變。只從乾淨 asset main 使用原部署設定重建／recreate business-services；保留 BFF 和其他容器，驗新 JAR embedded manifest/model bytes與 registry row 同身分及健康／safe GET。台股金融 runtime 只在官方交易日09:05至13:30前唯讀驗收；本次晚間記 NOT_YET_EXECUTED，不手動重跑報告、寄信、POST觀測或交易。

## 驗證

```bash
python3 -B /private/tmp/srpp-morning-report-fix-20261103/scripts/policy_bundle.py check --purpose daily --consumer Codex
python3 -B /private/tmp/srpp-morning-report-fix-20261103/scripts/daily_runtime_contract.py check
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-morning-report-fix-20261103 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-morning-report-fix-20261103 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-morning-report-fix-20261103 python3 -B scripts/tests/check-srpp-report-authority-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest test
bash scripts/spec-check.sh
git diff --check
```

聚焦測試若類名與現行來源不符，須先核對實際測試類名後更正，不以不存在類名跳過測試。驗收必須包含只重建/recreate business-services、讀回 JAR authority、registry 精確 row、safe GET與其他容器未變的收據；不以健康代替正式金融採用。

## 完成報告

- 凍結來源 feature `c68fdd41a48fe1e451a12ab2f9737e272bc0d331`；SRPP main／remote `9a9a19705a447c7e42429d636479f39fecc966df`。bundle `d4d93e3e44db07a154872a7fa22b98e2962cd745bd986bb16372af15c1c62021`；model、公式、targets及Swagger原始 bytes 維持。
- 僅同步 manifest／insert-only SQL，以及主22案例與long-short-exit15案例golden的bundle metadata；全部 cases／calculator hashes逐值不變。42項Java、13項Ruby／307 assertions、4項release-gate regression、authority checker與renderer／identity checks通過。spec-check BLOCK0／CHECK0，獨立spec與架構審查無未解 findings。
- 使用者明確同意後，asset feature `332b0242bfc7b8b740bfbd3279081557c6276c4c` 已no-ff合併並push至main／remote `6ce1350bd0742a562163989da8ecf95a3c183167`；兩repo落地後於21:50:39執行新hash單次INSERT，registry25→26，舊25row逐值不變，新row全文hash精確符合原generator。
- 從乾淨asset main僅重建／recreate business-services；21:51:22驗收PASS，image `sha256:183dde043c49a557c33ca21cc0160732ab7fba6a3b5a88ff3c1b6d9cd943168f`，healthy、gateway安全GET首試真JSON、BFF UP、官方台股日曆AVAILABLE。執行JAR manifest/model/Swagger/SQL四資源逐位元同源；46項effective env、main.env與其他8容器ID/image保持。另作獨立最終精確回讀PASS。
- 正式金融runtime維持 `NOT_YET_EXECUTED`；本輪未呼叫report-calculations、重跑報告、寄信、POST觀測或交易。完整本機驗收收據位於 `/private/tmp/asset-morning-release/`；受版控發布摘要位於 `reports/srpp-morning-authority-20261103/RELEASE.md`。本次收尾僅回填文件，不變更已驗收執行資源。
