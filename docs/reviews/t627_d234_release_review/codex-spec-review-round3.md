VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 3

## Findings
### [Minor] 627.2 寫「11 個類別」，基準與本分支實際失敗的類別是 12 個（BacktestWeeklyWiringTest 與 DualFormatSingleTableExportTest 是兩個獨立類別）
位置：spec/tasks/t627_srpp_d234_release.md:18（627.2）
證據：解析 `/private/tmp/asset-baseline/backend/target/surefire-reports/TEST-*.xml` 與 `/private/tmp/asset-d234-calc-migration/backend/target/surefire-reports/TEST-*.xml`，兩邊都是 failures 9＋errors 19＝28，失敗的測試類別各 12 個且逐一相同：ApiErrorLogServiceTest(2)、HistoryDtoJsonCompatibilityTest(2)、BacktestWeeklyWiringTest(1)、CanonicalDividendDisplayReadServiceTest(1)、MarketDataPortDelegationTest(1)、MarketDataPortSpringWiringTest(1)、TradingRadarServiceOwnerScopeTest(1)、TransactionAssetClassPostgresTest(4)、DualFormatSingleTableExportTest(3)、SrppRadarPolicyCompatibilityTest(1)、UsReportCalendarTest(2)、UsReportRepositoryPostgresTest(9)；加總 28。任務檔文字列出 10 個獨立名稱，再加「BacktestWeeklyWiringTest／DualFormatSingleTableExportTest 等」，實際是 12 個類別，卻寫「11 個類別」。28 個失敗、方法名集合逐筆相同、4135（4137 個 testcase 扣掉 ScratchV2ProbeTest、TmpBookDebugTest 兩份舊報告）、BFF 1039 個 0 失敗都對得上，只有類別數漂移。
被違反的出處：審查判準「可查證性」的計數漂移；`spec/tasks/README.md`「驗證段貼上去就能跑」（比對基準要寫得可核對）。
修法：把「11 個類別」改為「12 個類別」，並把「BacktestWeeklyWiringTest／DualFormatSingleTableExportTest 等」改為「BacktestWeeklyWiringTest、DualFormatSingleTableExportTest」，拿掉「等」。

### [Minor] 627.3 的「重啟前 no-op」判準沒有限定 enabled，字面上目前永遠過不了；「啟動入口皆為 no-op」也比後面寫的證明方式寬
位置：spec/tasks/t627_srpp_d234_release.md:19（627.3 末段）
證據：
- 判準寫「當日已過時點皆 last_run_date=今日」，沒有限定 `enabled=true`。唯讀查詢（`docker exec asset-postgres psql … default_transaction_read_only=on`，2026-11-22 約 20:15）顯示有停用排程：`exchange_rate_export_schedule` id 2（enabled=f、8:00、last_run_date NULL）、`realized_gain_export_schedule` id 4（f、8:00、NULL）、`index_export_schedule` id 2（f、8:00、NULL）、`export_schedule_setting` id 2（f、8:00、NULL）。8:00 已過，last_run_date 卻不是今日，照字面執行者永遠過不了判準，只能自行加過濾。
- 句首寫「證明啟動入口皆為 no-op」，後文只給匯出排程的證明法。`backend/src/main/java` 另有 ApplicationReadyEvent 啟動入口不是匯出排程：`FubonEtfHoldingsSyncScheduler.java:47`（Intent.STARTUP 同步）、`MarketAnalysisScheduler.java:81`（selfHealOnStartup）、`IndexDailyRefreshScheduler.java:134`（selfHealStaleOnStartup）、`SnapshotDateRollScheduler.java:55`（selfHealOnStartup）。「皆」字涵蓋它們，卻沒說怎麼證明。t617 的 617.5 也是這個寫法，所以這條只算措辭過寬，不是方向性錯誤。
被違反的出處：審查判準「內部一致性」（結論句比證明方式寬）與「可查證性」（判準須可機械執行）。
修法：把判準改為「所有 `enabled=true` 的排程，當日已過時點者 last_run_date=今日」。「啟動入口皆為 no-op」二選一：改為「匯出排程的啟動補跑為 no-op」，或補一句說明其餘 ApplicationReadyEvent 入口（上列四個）為 30 秒延遲後自行判斷、非匯出，不在此證明範圍。

### [Minor] 627.3 只剩「避開匯出排程分鐘」，第 2 輪建議修法裡的「不跨入報告窗／證據不足即停止並保留已落地 main 與 registry row」沒有保留
位置：spec/tasks/t627_srpp_d234_release.md:19；對照 spec/tasks/t617_srpp_d229_rule_text_alignment_release.md:23（617.5）、docs/reviews/t627_d234_release_review/codex-spec-review-round2.md（Major 的「修法」與自我挑戰末條）
證據：617.5 明寫整段「recreate→JAR bytes 讀回→日報工作區 --ff-only 快轉」不得跨入台股報告與守門員窗（09:05–13:10）、美股報告窗（22:35–23:45）及匯出排程分鐘，且「證據不足或時間不夠安全時停止並保留已落地的 main 與 registry row」。t627 本輪要 build 並重建 business-services 與 bff 兩個服務，627.3 現在只剩匯出排程判準。`.claude/skills/run-stack/SKILL.md` 沒有任何報告窗或匯出窗的條文（grep 09:05／13:10／22:35／匯出皆無），不能靠 skill 補上。任務檔其他處暗示切換要在 2026-11-23 09:05 前完成，但沒有把「不在報告窗內重啟」寫成約束。t620～t622 同樣沒寫，所以只列 Minor，不改變 VERDICT。
被違反的出處：`spec/tasks/README.md`「約束寫在『要做什麼』裡」；審查判準「內部一致性」（與同類前例 617.5 對照）。
修法：在 627.3 末句補「整段重建與快轉不得跨入台股報告與守門員窗 09:05–13:10、美股報告窗；證據不足或時間不夠安全時停止，保留已落地的 main 與 registry row，不強行切換」。

## 已查無發現
- 可查證性（身分與雜湊，逐一實算）：
  - `sha256sum` 實算 asset 端 `backend/src/main/resources/srpp/decision/policy_manifest.json`＝`c1521fdf…`、`model_inputs.json`＝`e9e561a9…`、`9090-api-swagger.md`＝`83500499…`（與 `docs/openapi/9090-api-swagger.md` 位元組相同），三者都與 SRPP root 的對應檔逐字相同，也都與任務檔 :13 一致。
  - `POLICY_BUNDLE_SHA256` 在 asset 端與 SRPP root 的 manifest 都是 `a6415445…`；`git show 204d4ae:assumptions/policy_manifest.json` 為 `86a0df0e…`；asset `HEAD` 的 manifest 為 `bef329db…`，與任務檔「第一個凍結 commit」「前一版正式 bundle」相符。
  - 把 `d201-registration.sql` 的 policy_document 欄抽出實算，sha256＝`c6d1d10a…`，formula_manifest 欄與 `spec/fixtures/srpp_formula_manifest_v3.json` 的 canonical JSON 也都＝`9962ff17…`，兩者與任務檔一致。`spec/fixtures/srpp_formula_manifest_v3.json` 與 `bff/src/main/resources/srpp/formula_manifest_v3.json` 位元組相同（`5c555c7e…`）。
  - SRPP root 目前 HEAD 為 `8d6a500`（2026-11-22 20:14:45「D-234 9090 Swagger 1.29.0 身分同步（bundle a6415445）」），工作樹乾淨，`8d6a500:assumptions/policy_manifest.json` 的 sha256 為 `c1521fdf…`。任務檔「最終 bundle 對應的 SRPP commit 於 627.3 回填」仍是正當的待辦，不是錯誤斷言；提醒：此 commit 已存在，回填時可直接記 `8d6a500`。
- 可查證性（第 2 輪處理逐項實證）：
  - 第 2 輪 Major（匯出時窗寫死）：現在的 627.3 不再有字面 21:25–21:43，改為重啟前以唯讀 SQL 讀各排程。我實測唯讀 SELECT 驗證任務檔記錄的 2026-11-22 讀回值，全部吻合：index 8:45／9:03／11:52／**21:35**；asset_transaction 7:20／11:46／**22:25**；realized_gain 8:00／11:52／**22:25**；`export_schedule_setting`（`ExportScheduleService.java:155` 檔名前綴為「資產總覽」）8:33／11:52／**22:28**；exchange_rate 9:02／11:53／**22:31**；commodity 9:02／11:55／**22:32**；trading_calendar 8:30。晚間確為 21:35 一個與 22:25–22:32 一組，其餘 `last_run_date=2026-11-22`。此項已解決，僅殘留上面兩條 Minor 的精確度問題。
  - 第 2 輪 Minor（驗證段）：任務檔 :27–:32 已列 `render-9090-openapi-docs.rb --check`、`sync-srpp-swagger-identity.py --check`、`docker-external-api-openapi-test.rb`、`unified-9090-openapi-renderer-test.rb`（並註明基準即 1 failure）。我在 `/private/tmp/asset-baseline`（main `dc7720a6`，git status 乾淨）實跑該 ruby 測試，確實 `5 runs … 1 failures`，失敗在 `scripts/tests/unified-9090-openapi-renderer-test.rb:20` 的寫死 `1.28.3`（基準 yaml 版本為 1.28.4）；`git diff HEAD --stat` 顯示該測試檔本分支未動。已解決。
  - 第 2 輪 Minor（627.2 基準）：28 個失敗（9 failures＋19 errors）、失敗方法名集合在基準與本分支逐筆相同（集合差為空）、BFF 1039 個 testcase 0 失敗，皆與任務檔一致。未解決的只有類別數（見第一條 Minor）。
- 可查證性（驗證段指令全數實跑，皆為 `--check` 或唯讀測試）：`bash scripts/spec-check.sh` BLOCK 0／CHECK 0；`render-9090-openapi-docs.rb --check` PASS；`sync-srpp-swagger-identity.py --check` CURRENT；`check-srpp-report-authority.py --check` PASS（bundle `a6415445…`）；report-golden 111、long-short-exit 15、state-topup 22 個 parity case 通過；formula V3 `--check` PASS（`9962ff17…`）；order-plan-v2 22、satellite-build 70、pooled-headroom 16、d215-fx 17；`ruby scripts/tests/docker-external-api-openapi-test.rb` PASS；`ruby scripts/tests/srpp-report-policy-register-test.rb` 6 runs／264 assertions／0 failures；`git diff --check` 0。`mvn` 路徑 `/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn` 存在。
- 可查證性（golden 差異範圍）：`git diff HEAD -U0` 三份 D-234 報告 golden 只變 `execution_policy.py` hash 與 `policyBundleSha256`，與 627.1「金融輸出不變，只變 bundle 與 calculator hash 行」相符。
- 可查證性（D-234 數值與引用）：SRPP `model_inputs.json` 的 `global_equity_build_cadence_d141.VT` 為 window 2026-11-23 到 2027-06-30、`max_shares_per_day 5`、`max_shares_per_week 15`、`min_shares_per_order 5`、`monthly_baseline_twd 53333`、`monthly_cap_twd 80000`、`stop_at_weight 0.025`；`global_equity_usd_dca_d182.scheduled_instructions` 恰三筆（5 日 VT、15 與 25 日 VOO，各 US$350），funding 文字含美元不足改國泰台幣扣款。`spec/requirements.md:7285／7299／7309／7321` 為 Requirement 220–223；187／193／199 位於 :6076／:6232／:6501；`docs/reviews/t623_t626_d234_spec_review/` 的 VERDICT 為 round1 CHANGES_REQUESTED、round2 CHANGES_REQUESTED、round3 PASS、arch-review PASS，與任務檔「三輪＋架構審查 PASS」相符；分支名與 main 基準 `dc7720a6` 相符（`git merge-base HEAD main` 為 `dc7720a6`）。
- 內部一致性：背景、凍結來源、627.1–627.3、驗證段與完成報告占位彼此沒有矛盾；627.1／627.2 為 [x]、627.3 為 [ ] 與「發布完成後回填」相符。627.3 提到的「business-services 與 bff」對應 `git status` 的可部署模組變更（backend 與 bff 的 `PublicSrppProjectionService`、`SrppProjectionQuery`、`SrppReportResponseValidator`），沒有漏掉 frontend／nginx／gateway。
- 架構鐵則：t627 是規則包身分發布任務，不新增 controller／DTO／頁面／endpoint／業務分類，檢查表九題皆不適用。
- 資料來源正確性：任務檔不對 DB schema 形貌做斷言（`Liquibase changeset: 無`）；匯出排程時點已改為重啟前讀 DB，僅「2026-11-22 讀回」留作帶日期的觀測值，不是寫死的規則；`bash scripts/spec-check.sh` 的 B10（schema 逐位元）通過。

## 自我挑戰
- 撤下或不報的項目：
  - 「最終 SRPP commit 尚未回填」：此 commit 現已存在（`8d6a500`），但任務檔寫的是 627.3 時回填的待辦，不是對現況的錯誤斷言，不報；只在「已查無發現」提醒。
  - 驗證段缺 `python3 -B scripts/tests/srpp-formula-v3-test.py` 等第 2 輪建議但非必要的測試：第 2 輪該 Minor 的核心（兩支 `--check`、OpenAPI 測試、renderer 基準失敗註記）已落實，不再追加。
  - 627.3 句首與冒號後各出現一次「重啟前以唯讀 SQL …」的重複措辭：屬可讀性，不報。
  - 驗證段使用 `<SRPP root>` 佔位、沒有 docker rebuild 指令：與 t620～t622 同形式，627.3 已以文字描述，不報。
  - 驗證段沒有 `generate-srpp-short-us-golden.py`／`generate-srpp-income-payroll-golden.py`：兩支沒有 `--check`（執行會寫檔），我沒有執行；t620～t622 的驗證段同樣沒列，不報。
- 保留 Minor 的理由：類別數 12 與停用排程 NULL last_run_date 是我實測解析 surefire XML 與唯讀 SELECT 得到的機械證據；報告窗那條有 617.5 原文與第 2 輪「修法」可對照，但因 t620～t622 同樣沒寫，我只列 Minor，不升級為 Major，避免對慣例判重。三條都不影響 VERDICT。
- 操作紀錄（誠實揭露）：全程只執行 `--check`、唯讀 SELECT（`default_transaction_read_only=on`）與測試。為證實「基準即 1 failure」，我在乾淨基準目錄 `/private/tmp/asset-baseline` 跑了 `ruby scripts/tests/unified-9090-openapi-renderer-test.rb`，該測試只在記憶體產生文件。審查後本分支 `git diff --stat` 仍為 46 files／+3442／-276，基準 `git status` 仍為空；未執行 `generate-srpp-short-us-golden.py`／`generate-srpp-income-payroll-golden.py`。

## 無法查證
- 乾淨 main 上全套 `mvn test` 的重跑：唯讀模式下不能重建；基準與本分支的失敗集合比對，用的是兩邊既有 surefire 報告（基準 4058 個 testcase、本分支 4137 個，含兩份舊的 Scratch／TmpBook 報告）。
- 實際重建、registry 寫入、JAR 讀回與 9090 實測：627.3 仍是 [ ]，尚未發生。
