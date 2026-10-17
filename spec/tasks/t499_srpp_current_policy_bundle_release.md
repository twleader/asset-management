# [t499] 發布現行 SRPP 規則包 authority 與精確 hash

**對應 Requirements:** Requirement 193（SRPP 現行規則包 authority 發佈鏈）
**前置任務:** t489
**Liquibase changeset:** 無

## 背景

asset-management 的 report-calculations authority 曾固定接受 D-201 / policy 2.49.0；目前已驗證的 SRPP source 是 D-204 / policy 2.53.0 / model 3.22.0，bundle SHA-256 為 `ef4bd047956514548f2c5b3c0fafb56d250f46198efcc2e73bdce17e1eed3839`，raw model SHA-256 為 `b615369d78ffc2f1c89c4c9485c818db73a5796a30c254ed3760dd5eda793cdc`。因此只更新 source artifact 仍會被舊 report authority 或 SQL generator 的 D-201 hard-lock 拒絕，造成新 hash 無法完成 registry 到 9090 的發佈鏈。

本任務只處理 report-calculations authority 發佈鏈。不得新增 capacity reflow、client risk assertion、quote revision/freshness design 或任何新的 projection 契約；不得新增 API、資料表、Liquibase migration、producer、broker/行情存取、計算包、郵件或交易。

## 要做什麼

- [ ] 499.1 對 `/Users/steven/Project/SRPP` 執行官方 `policy_bundle.py check --purpose daily --consumer Codex`。以該次通過的 model/manifest 原始 bytes 同步 backend decision artifacts；不接受手改 JSON、手填 hash 或部分同步。此任務的來源預期是 D-204 / `2.53.0` / `3.22.0`、bundle SHA `ef4bd047956514548f2c5b3c0fafb56d250f46198efcc2e73bdce17e1eed3839`、model SHA `b615369d78ffc2f1c89c4c9485c818db73a5796a30c254ed3760dd5eda793cdc`；若來源已演進，必先重新驗所有下游產物，不能混用舊值。
- [ ] 499.2 保留 `DecisionPolicyAuthority` 的 raw model/manifest/JCS/hash 驗證，且 model/manifest 都 strict parse、拒絕 duplicate JSON key。將 `SrppReportAuthority` 的 D-201/2.49.0 hard-lock 替換為封閉 semantic compatibility gate：`execution_policy.llm_orchestrated_api_d197.read_only_routes` 必含精確 `/api/public/srpp/report-calculations`，但不得鎖死整個 routes array（D-204 合法另有兩條 projection route）。其 `published_read_candidates` 的 report entry 必只有這十個欄位且以順序敏感 JSON array 精確匹配：`publication_status="PUBLISHED_SWAGGER_1_26_0"`、`daily_adoption_status="ENABLED_TRIAL_RUNTIME_PENDING"`、`enabled_for_daily=true`、`report_slots=["09:05","12:00"]`、`uses_legacy_api_slot_mapping=false`、`calculator_version="SRPP_REPORT_CALCULATIONS_V1"`、`consumer_verifier="scripts/verify_srpp_report_calculations.py"`、`prerequisites=["CURRENT_POLICY_REGISTRATION","INDEPENDENT_CONSUMER_VERIFIER"]`、`observations_pending=["SCOPED_SAME_INPUT_GOLDEN_PARITY","TRADING_DAY_RUNTIME_ACCEPTANCE"]`、`fallback_reason="REPORT_CALCULATION_SCOPE_UNAVAILABLE"`。所有缺欄、錯型、不同/額外 array value、錯 calculator/status/verifier/fallback 或 extra field 都必 fail closed。
- [ ] 499.3 對 `scripts/srpp-report-policy-register.rb` 實作相同的 semantic gate，移除硬編碼 D-201 成功條件。保留既有 `d201-policy-document.json` 與 `d201-registration.sql` 路徑以維持包裝相容性，但由 current authority 重新產生其內容和訊息；registration SQL 必是可重複執行的 insert-only 新 hash 登錄，不能 UPDATE/DELETE 歷史 registry row。
- [ ] 499.4 從 current embedded authority 重產並納入版本控制的 report same-input golden、formal targets policy document 與 registration SQL。golden generator 必接受與 release gate 相同的 `--srpp-root <absolute-path>`（預設可維持現有本機 path），仍覆蓋既有 22 個 deterministic cases，並同時驗 source bytes、policy bundle SHA、formal targets 和 generated SQL 的精確一致性。
- [ ] 499.5 新增一個只讀離線 release gate（建議 `scripts/check-srpp-report-authority.py --srpp-root <absolute-path> --check`）：它驗官方 source bundle、backend model/manifest bytes、golden、formal policy document、Ruby generator stdout 和 tracked registration SQL。它不可改 source/backend artifacts、不可連 DB/HTTP、不可呼叫 broker/行情/郵件/交易；golden 寫入只保留給顯式 generator。
- [ ] 499.6 新增或更新自動化測試，至少覆蓋 D-204 happy path、舊 D-201/historical artifact 不能偷渡、每個 required semantic candidate property 缺失/錯型/不同值/多餘值皆被拒絕、release gate 對 stale embedded artifact/golden/SQL 發現漂移。每個 candidate metadata mutant 必先重建對應 model hash、manifest `files` hash 與 manifest bundle JCS，並先證 `DecisionPolicyAuthority` 成功，再分別斷言 Java authority 與 Ruby generator 的 semantic gate 拒絕；不得把外層 bytes mismatch 當作覆蓋。更新不再真實的 D-201-only test name/diagnostic，更新 `DailyDecisionCalculatorTest` 的 current bundle assertion，且完整 backend suite 必須通過；不降低既有 strict duplicate/JCS/hash 驗證。
- [ ] 499.7 只在程式與測試都通過、變更已合併 main 後，從乾淨 main 由 release operator 執行：重建/recreate `business-services`（不重啟 BFF/gateway）→ 執行本任務產生的 insert-only SQL → 唯讀回讀精確 registry row、jar embedded artifact 和 `localhost:9090` 的 report-calculations request。request 必攜帶精確新 policy bundle SHA；回報精確 200，或如實保留 404/409/503 fail-closed 結果。禁止以舊 hash、fixture、10/7 output 或其他 calculation package 作替代證據。

## 驗證

```bash
python3 /Users/steven/Project/SRPP/scripts/policy_bundle.py check --purpose daily --consumer Codex
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP --check
python3 scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP --check
ruby scripts/srpp-report-policy-register.rb > /tmp/srpp-current-policy-registration.sql
cmp /tmp/srpp-current-policy-registration.sql backend/src/main/resources/srpp/report/d201-registration.sql
ruby scripts/tests/srpp-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
bash scripts/spec-check.sh
git diff --check
```

Deployment is a separate release action after merge: build/recreate only `business-services` from clean main, run the generated insert-only SQL once, then perform DB/jar/9090 exact-hash readback. It must not generate a report package or conduct financial I/O.

## 完成報告

（實作者做完後回填：實際同步的 source hash、變更檔案、離線檢查與測試輸出、以及 main deployment/DB/jar/9090 exact-hash readback 的結果；若沒有 current FINAL package，明列其 fail-closed status，不得宣稱 live business response 成功。）
