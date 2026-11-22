VERDICT: CHANGES_REQUESTED

## 判定
critical: 0   major: 1   minor: 2

## Findings
### [Major] 627.3 把「匯出排程分鐘」寫死成 21:25–21:43，與運行中 DB 的實際排程已漂移，照字面避開反而會撞上 22:25–22:32 的匯出
位置：spec/tasks/t627_srpp_d234_release.md:19（627.3 末句「避開 21:25–21:43 匯出排程分鐘」）
證據：
- 匯出排程的時點是 DB 可設定欄位：`db/schema.sql:649–666`（`asset_transaction_export_schedule.run_hour／run_minute`，另有 commodity／exchange_rate／realized_gain／trading_calendar／index 五張 `*_export_schedule`），`backend/src/main/java/com/steven/assets/dto/ExportScheduleDto.java` 的 `ScheduleRequest(runHour, runMinute…)` 讓使用者改時間。
- 實查（唯讀 `docker exec asset-postgres psql -U assets -d assets -At -c "select enabled,run_hour,run_minute from <t>_export_schedule where enabled"`，2026-11-22 約 20:10）：asset_transaction 07:20／11:46／22:25；commodity 09:02／11:55／22:32；exchange_rate 09:02／11:53／22:31；realized_gain 08:00／11:52／22:25；trading_calendar 08:30；index 08:45／09:03／11:52／21:35。晚間實際分鐘是 21:35、22:25、22:25、22:31、22:32；21:25–21:43 只剩 index 的 21:35 一個落在窗內，22:25–22:32 的四個匯出完全不在窗內。
- 漂移有前例可對照：`spec/tasks/t545_claude_compact_transport_authority.md:54` 當時是「下一個排程 21:25」；`spec/tasks/t617_srpp_d229_rule_text_alignment_release.md:23`（617.5）寫的是「依本輪有效排程與時區確認…各匯出排程分鐘（約21:25–21:43）；證據不足或時間不夠安全時停止並保留已落地的main與registry row」。t627 把「約」「依本輪有效排程確認」「證據不足即停止」三個限定全拿掉，只剩一個字面窗。
- 本輪 627.3 要 `docker compose build` 兩個映像（business-services、bff）再 force-recreate，耗時遠大於 t617／t620 的單一服務；若執行者信字面窗，晚上 21:43 後任何時間都被視為「安全」，會直接撞 22:25–22:32。
被違反的出處：審查判準「資料來源正確性」——已改為 DB 可設定的排程時點不得在 spec 裡寫死成字面時間（Task 195：「寫死正是本次漂移成因」）；t617 的 617.5 是同一件事的既有正確寫法。
修法：把 627.3 末句改為依重建當下的 DB 取值，不寫字面分鐘，例如：「重啟前以唯讀 SQL 讀出六張 `*_export_schedule`（asset_transaction／commodity／exchange_rate／realized_gain／trading_calendar／index）所有 enabled 排程的 run_hour:run_minute 與今日 last_run_status，整段 build→recreate→JAR bytes 讀回→日報工作區 `--ff-only` 快轉不得跨入任一排程分鐘（前後各留 build 預估時間），亦不得跨入台股報告與守門員窗 09:05–13:10；證據不足或時間不夠安全時停止並保留已落地的 main 與 registry row，不強行切換。」

### [Minor] 驗證段缺 627.1 要求通過的兩支 `--check`，也沒列因 OpenAPI 1.29.0 受影響的測試；其中 unified-9090-openapi-renderer-test.rb 實測失敗且寫死 1.28.3
位置：spec/tasks/t627_srpp_d234_release.md:17（627.1 要求 renderer 與 identity sync 兩者 `--check` 通過）、:23–38（驗證段）；scripts/tests/unified-9090-openapi-renderer-test.rb:20
證據：
- 驗證段 :24–37 沒有 `ruby scripts/render-9090-openapi-docs.rb --srpp-root <SRPP root> --check`、`python3 -B scripts/sync-srpp-swagger-identity.py --srpp-root <SRPP root> --check`；t623／t624／t625 的驗證段都有列（含 `ruby scripts/tests/docker-external-api-openapi-test.rb`、`python3 scripts/tests/srpp-formula-v3-test.py`）。我實跑兩支 `--check` 均通過（`PASS: 9090 OpenAPI Markdown mirrors are byte-identical and current`；`{"status": "CURRENT", … "policyBundleSha256": "a64154457d4f…"}`），所以是漏列，不是壞掉。
- `ruby scripts/tests/unified-9090-openapi-renderer-test.rb` 實測 `5 runs, 43 assertions, 1 failures`：第 20 行斷言 `'| 契約版本 | `1.28.3` |'`。`git show HEAD:docs/openapi/docker-external-api.yaml` 第 4 行已是 `version: 1.28.4`、工作樹是 `1.29.0`；`git diff HEAD --stat -- scripts/tests/unified-9090-openapi-renderer-test.rb` 為空，故此失敗在 HEAD 就存在（自 1.28.4 起），t626 的 1.29.0 bump 沒有順手修，t627 也沒列。
- 我另跑 `ruby scripts/tests/docker-external-api-openapi-test.rb`、`python3 -B scripts/tests/srpp-formula-v3-test.py`、`python3 -B scripts/tests/test_sync_srpp_swagger_identity.py`、`ruby scripts/tests/srpp-policy-register-test.rb`、`SRPP_RELEASE_WORKTREE=<D-234 root> python3 -B scripts/tests/check-srpp-report-authority-test.py` 全數通過，只有 renderer test 這一個失敗。
被違反的出處：t627 自身 627.1 與驗證段不一致；`spec/tasks/README.md`「驗證段的指令，是不是貼上去就能跑／至少涵蓋建置、測試」。
修法：驗證段補上兩支 `--check` 與 `ruby scripts/tests/docker-external-api-openapi-test.rb`、`python3 -B scripts/tests/srpp-formula-v3-test.py`；把 `unified-9090-openapi-renderer-test.rb:20` 改為從 `@financial.dig('info','version')` 取版本（不再寫死）並列入驗證段，或在 627.2 明載「此測試自 1.28.4 起即失敗、屬基準失敗」。

### [Minor] 627.2 的「乾淨 main 基準」沒有寫出基準數量與失敗清單，而驗證段的 `mvn test` 在基準下本來就會非 0
位置：spec/tasks/t627_srpp_d234_release.md:18、:35–36
證據：
- 627.2 只寫「與乾淨 main 基準比對無新增失敗」。在 `spec/tasks/t6[0-2]*.md` 全文搜尋「基準／既有失敗／pre-existing」，除 627.2 外沒有任何任務檔記錄 backend 基準失敗。
- 第 1 輪處理說明提到「後端失敗集合回到基準 28 個」，但該數字只在審查往返文字，不在任務檔。我核對 `backend/target/surefire-reports`（20:03 這輪）：`<failure|error>` 共 28 個，分布在 11 個類別：ApiErrorLogServiceTest、HistoryDtoJsonCompatibilityTest、BacktestWeeklyWiringTest、CanonicalDividendDisplayReadServiceTest、MarketDataPortDelegationTest、MarketDataPortSpringWiringTest、TradingRadarServiceOwnerScopeTest、TransactionAssetClassPostgresTest、SrppRadarPolicyCompatibilityTest、UsReportCalendarTest、UsReportRepositoryPostgresTest；BFF（`bff/target/surefire-reports`）0 失敗。
- 其中 SrppRadarPolicyCompatibilityTest（`topupDecisionAcceptsV2WithEachConservativeOfficialSmaStatus`，差異在 `d218ObservedNoNewLowSessions` 變 `UNAVAILABLE/FIELD_SOURCE_UNVERIFIED`）與 UsReportCalendarTest（`UsCoreSellCalculator$Gap: WATCH_STATE_UNVERIFIED`）名稱帶 SRPP，執行者若沒有清單，無法機械分辨是基準還是 D-234 回歸。
被違反的出處：`spec/tasks/README.md`「約束寫在『要做什麼』裡，不能只寫在『驗證』裡」與「驗證段貼上去就能跑」；審查判準「可查證性」。
修法：627.2 寫入基準失敗數（backend 28、bff 0）與上列 11 個類別，並規定以「失敗方法名集合與乾淨 main 逐一相等」判定；驗證段 `mvn -q -f backend/pom.xml test` 旁註明「基準下 exit code 非 0，以集合比對為準」。

## 已查無發現
- 可查證性（第 1 輪處理逐項實證）：
  - 第 1 輪 Major（order-plan v2 golden 舊 hash）：`python3 -B scripts/generate-srpp-order-plan-v2-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check` → `ORDER_PLAN_V2_GOLDEN_PASS:22`；`backend/src/test/resources/srpp/order-plan-v2-golden.json` 的 `srppSources` 為 `execution_policy.py 16fb30de…`（等於 SRPP root 實算 sha256）與 `trade_blocker_policy.py c4c51f95…`（等於 SRPP root 實算）。已解決。
  - 第 1 輪 Minor（缺 manifest 檔案 sha256）：`shasum -a 256 assumptions/policy_manifest.json` = `c1521fdf0cec96736d61ec584d54365236e234ec80639309f5802043cdc000fe`，與任務檔 :13 一致，asset 端 `backend/src/main/resources/srpp/decision/policy_manifest.json` 同值。已解決。
  - 四組 D-234 golden 與其餘 `--check` 全跑通過：authority `SRPP_REPORT_AUTHORITY_CHECK_PASS:a64154457d4f…`；report-golden 111 案；long-short-exit 15 案；state-topup 22 案（帶 `SRPP_RELEASE_WORKTREE`）；formula V3 `SRPP_FORMULA_V3_CHECK_PASS:9962ff17…`；order-plan-v2 22 案；satellite-build 70 案；pooled-headroom 16 案；d215-fx 17 案。`bash scripts/spec-check.sh`：BLOCK 0／CHECK 0（含 B10 schema 逐位元一致、129 張表）；`git diff --check` exit 0；`ruby scripts/tests/srpp-report-policy-register-test.rb` 6 runs／264 assertions／0 failures。
- 可查證性（任務檔雜湊與身分）：Swagger `8350049985468307…`、model_inputs `e9e561a9fe5d8febffc480a20b2b2c013bf9c0d1469e9f37a000978e60c7f46d` 在 SRPP root 與 asset 端 resource 逐字相同；`POLICY_BUNDLE_SHA256` 在 SRPP root manifest 為 `a64154457d4f…`，`git show 204d4ae:assumptions/policy_manifest.json` 為 `86a0df0e0482…`，asset `HEAD` manifest 為 `bef329db726e…`，三者與任務檔 :13 一致；policy document 雜湊 `c6d1d10aa5c6…` 與 formula 雜湊 `9962ff17192a…` 皆等於從 `d201-registration.sql` 抽出的對應欄位 sha256；`ruby scripts/srpp-report-policy-register.rb` 重產結果與 `backend/src/main/resources/srpp/report/d201-registration.sql` 位元組相同（7747 bytes）；`spec/fixtures/srpp_formula_manifest_v3.json` 與 `bff/src/main/resources/srpp/formula_manifest_v3.json` 位元組相同。`ruby scripts/render-9090-openapi-docs.rb --srpp-root … --check` 與 `sync-srpp-swagger-identity.py --srpp-root … --check` 均 CURRENT。
- 可查證性（D-234 數值）：SRPP `model_inputs.json` 的 `global_equity_build_cadence_d141.VT` 為 window `2026-11-23 to 2027-06-30`、`max_shares_per_day 5`、`max_shares_per_week 15`、`min_shares_per_order 5`、`monthly_baseline_twd 53333`、`monthly_cap_twd 80000`、`stop_at_weight 0.025`、funding「Cathay TWD sub-brokerage only」；`global_equity_usd_dca_d182.funding` 含「USD deposits cannot cover → Cathay debits in TWD … must keep the D-152 TWD post-buy floors」；SRPP `docs/DECISION_LOG.md:1` 為 D-234（2026-11-22 Accepted），與任務檔背景逐項吻合。OpenAPI 1.28.4（`git show HEAD:docs/openapi/docker-external-api.yaml:4`）→1.29.0（工作樹 :4、`9090-api-swagger.md:10`）與 626.18 相符。
- 可查證性（引用與前置）：`spec/requirements.md:7285／7299／7309／7321` 為 Requirement 220–223，標題分別對應 Task 623–626；187／193／199 位於 :6076／:6232／:6501；`docs/reviews/t623_t626_d234_spec_review/` 的 README 與 round1–3／arch-review 的 VERDICT 行（CHANGES_REQUESTED、CHANGES_REQUESTED、PASS、PASS）與任務檔 :9 的「三輪＋實作後架構審查 PASS」相符；分支名 `claude/srpp-d234-calc-migration` 為目前分支；t627 編號在 main 與其他分支無撞號；`git status` 顯示可部署模組只有 backend 與 bff（BFF 變更為 `PublicSrppProjectionService`、`SrppProjectionQuery`、`SrppReportResponseValidator`），與 627.3「business-services 與 bff」一致，沒有漏掉的 frontend／nginx／gateway 變更。
- 可查證性（golden 差異範圍）：`git diff HEAD -U0` 顯示 report-same-input 與 long-short-exit 只變 `execution_policy.py` hash 與 `policyBundleSha256` 兩行、state-topup 只變 `execution_policy.py` hash 一行，與 627.1「金融輸出不變，只變 bundle 與 calculator hash 行」相符。
- 內部一致性：背景（D-234 參數）、SRPP 凍結來源（bundle／hash）、627.1–627.3、驗證段、完成報告占位彼此無矛盾；627.1 的 [x] 狀態與我實跑結果一致，627.2／627.3 為 [ ] 與「發布完成後回填」一致；「最終 bundle 對應的 SRPP commit 於 627.3 回填」已明寫為待辦，不是錯誤斷言（SRPP root 目前 HEAD `204d4ae`、4 個檔未提交，與任務檔 :13 描述一致）。
- 架構鐵則：t627 是規則包身分發布任務，不新增 controller／DTO／頁面／endpoint／業務分類，檢查表九題皆不適用；任務檔沒有對 DB schema 形貌做斷言（`Liquibase changeset: 無`），`db/schema.sql` 與運行中 DB 經 spec-check B10 一致。
- 資料來源正確性：除上列 Major 的匯出排程字面時間外，「2026-11-23 09:05」是 SRPP 日報固定時槽（`srpp-report-policy-register.rb` 的 `report_slots %w[09:05 12:00]`），不是 DB 可設定排程，不構成寫死問題。

## 自我挑戰
- 撤下或不報的項目：
  - 驗證段使用 `<SRPP root>` 佔位而非絕對路徑：t622 同形式，且同一檔 :13 已給出字面路徑，不報。
  - 驗證段沒有 docker rebuild／recreate 指令（README 範本要求）：627.3 以文字完整描述且 t622／t620 同形式，不報。
  - 標頭「Requirements 220／221／222／223（t623–t626）」沒有一句話摘要：屬措辭，不報。
  - 公式 V3 manifest 的 `sourceSha256` 未涵蓋 `SrppPooledFunding`／`SrppReportD215FxPreference`／`SrppOrderPlanV2`：這是 t623.18／t625.20 的設計決定且前三輪 spec 審查已處理，不屬 t627，不報。
  - `check-srpp-report-authority-test.py` 不帶 `SRPP_RELEASE_WORKTREE` 會失敗（預設指向 `SRPP-main`）、`srpp-core-scope-consumer-test.py` 需另一個 fixture 環境變數：皆是測試自身的環境約定，不是 t627 缺陷，不報。
  - 627.3 缺少 617.5 的「不跨報告窗／證據不足即停止」：與 Major 同源，已併入該條修法，不另立。
- 保留 Major 的理由：t620 的 620.2 也有相同字面窗，但運行中 DB 已證明該窗與實際排程不符（晚間是 21:35 與 22:25–22:32），且 t627 要 build 兩個映像、耗時更長，照字面執行有撞上匯出的實際風險；不報會讓同一個 Task 195 漂移模式再次複製。我查詢的是唯讀 SELECT，結果會隨使用者調整排程而變，但這正是不該寫死的理由。
- 保留兩條 Minor 的理由：renderer test 失敗是我實跑得到的機械證據；baseline 失敗清單是用 surefire 報告實數核對（28／11 類別），不是推測。兩條皆不影響 VERDICT，僅 Major 觸發 CHANGES_REQUESTED。
- 操作紀錄（誠實揭露）：為確認 `generate-srpp-short-us-golden.py` 與 `generate-srpp-income-payroll-golden.py` 是否受 bundle 影響，我曾不帶 `--check` 執行它們（這兩支腳本沒有 `--check`，會直接寫檔）；它們重寫了 `backend/src/test/resources/srpp/report-short-us-full-golden.json` 與 `report-income-payroll-golden.json`，但 `git status`／`git diff HEAD` 顯示內容與 HEAD 位元組相同（僅 mtime 改變），未造成內容變更；另外在審查暫存區產生並已刪除一個 `regen.sql`。其餘查證皆為唯讀（含對 asset-postgres 的 SELECT）。

## 無法查證
- 乾淨 main 上的 backend 全套測試基準：需要在乾淨 main 執行 `mvn test`（會寫入 `target/`），唯讀模式下無法重跑；本報告只能引用工作樹最近一輪 surefire 報告的 28 個失敗作為「與第 1 輪處理說明一致」的證據，無法獨立證明它們等於乾淨 main 的基準。
- 最終 bundle 對應的 SRPP commit：尚未提交，無從核對。
