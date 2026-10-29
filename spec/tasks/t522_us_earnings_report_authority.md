# [t522] 發布美股財報風險報告改善的規則包身分

**對應 Requirements:** Requirement 187（背景不可變報告包與純讀採用）、Requirement 193（現行規則包 authority 可重現發布）
**前置任務:** t517（已落地共用證據檢核 authority）
**Liquibase changeset:** 無

## 背景

使用者在 GOOGL 財報後大跌時要求改善，並明確選定「先改善預警與報告，保留現行賣出條件」。SRPP 只改善事件預警、獲利回吐壓力情境與為何仍續抱的說明；asset-management 必須發布最終 bundle identity，避免新 hash 被舊 authority／registry 拒絕。不得新增財報前保護性減碼、賣出例外、門檻、金融公式、下單、報告寄送或資料刷新。

## 要做什麼

- [x] 522.1 唯一來源固定 `/Users/steven/.codex/worktrees/us-earnings-reporting/SRPP`。等待 root 明確凍結的最終 commit/hash 後，查證來源乾淨、Codex/Claude daily policy 與 runtime contract PASS，記實際 commit、model/manifest raw SHA、POLICY_BUNDLE_SHA256。不得同步中間 manifest，asset 不修改 SRPP。
- [x] 522.2 逐位元同步 `backend/src/main/resources/srpp/decision/model_inputs.json`、`policy_manifest.json`；相同 bytes 不造 diff。保留 strict JSON duplicate-key/raw-byte/JCS、report candidate 精確十欄語意閘門。既有 formal targets `backend/src/main/resources/srpp/report/d201-policy-document.json` 預期不變。由原工具生成 `report-same-input-golden.json` 22 案例及 `d201-registration.sql`，SQL 只能 INSERT ON CONFLICT DO NOTHING；金融 expected、其他 fixtures、calculator source hashes 預期不變，差異先停止回報。
- [x] 522.3 不修改 API/schema/Swagger/producer/排程/資料表；Swagger SHA 預期維持 `40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe`。來源乾淨後 renderer/sync 只讀 `--check --srpp-root`，不做跨 repo mutation。完整 backend、authority release/regression、Ruby registry、主report/state-topup原工具check、spec/diff check及獨立 diff-scoped arch 審查通過。
- [ ] 522.4 依 commit-merge-push 完成 asset feature commit/no-ff main merge/push/remote-ref readback，另等 root 證明 SRPP main 已 push 並遠端一致，才執行受追蹤 registry SQL 的單次 INSERT。完整唯讀讀回新 row，確認舊 rows 不變；不 UPDATE/DELETE、不新增 migration、不 refresh/補抓/寄送。
- [ ] 522.5 僅從乾淨 asset main 經 run-stack rebuild/recreate business-services；main 的 .env/secrets 是部署基準，保留 BFF 及正式資料。重啟前依當下台北日期重新查 analysis self-heal：未到最早啟用寄送時間、無啟用時間、停用或今日已有 OK，必須有實際 durable/config 證據顯示會略過；若時間已過且今日無OK會觸發外抓／寄信，停止部署。另唯讀證十指數（DJI/SPX/IXIC/SOX/SP500TR 與 TPEX/FTSE/DAX/KOSPI/N225）皆最新，US 以實際 mostRecentCompletedUsTradingDay(now)、非US 以 runtime LocalDate.now().minusDays(4) 判準，無列也不通過；不修改旗標或補造資料繞過。跨日重新查。同時查所有日期的 analysis PROCESSING 必為0，且部署與有界觀察期間不得跨過任何啟用寄送分鐘，排除獨立batch poller／scheduled tick寄信。Fubon ETF startup須既有設定原本停用／config非READY，或依實際全owner latest TW holding與TW alerts union、validEtfCode且台股ETF分類得到的radar candidates全有success=true snapshot；0000依原parser排除，不改旗標／補造success列。SnapshotDateRoll startup會重設記憶體guard，須唯讀證明所有owner latest snapshot及到期在途款符合原roll no-op（今日且無到期列、未來日期或無快照）；不由本輪提前roll或結清。其他startup/backfill/export入口逐項查既有gate，配息backfill須原設定未啟用；任何入口不能證明會略過，維持部署pending並具名回報，不得靠事後發現副作用代替事前證明。部署後有界等健康120s與安全GET30s，親核原analysis/index略過 logs 及前後無新增相關寄送／補抓，不拿舊收據冒充本輪。
- [ ] 522.6 分開記 static tests/main landing/registry精確row/JAR embedded model-manifest-Swagger/服務serve。report GET僅目標交易日台北09:05≤now<13:30且新registry/JAR同身份已讀回後，先唯讀`GET /api/public/trading-calendar?year=2026`確認200/availability.tw.status=AVAILABLE/days[].twTrading=true，再以新policyBundleSha256、原swaggerSha256、email=tw.leader@gmail.com、consumer=Codex或Claude、當日tradingDate、合法09:05或12:00 slot唯讀GET report-calculations；同calculationRunId再讀bytes/hash與consumer verifier。PARTIAL/UNAVAILABLE/BLOCKED/NOT_EVALUATED保留；窗口外NOT_YET_EXECUTED。不人工觸發producer/observer/report/寄信/refresh/交易。

## 驗證

```bash
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/us-earnings-reporting/SRPP python3 scripts/tests/check-srpp-report-authority-test.py
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/us-earnings-reporting/SRPP python3 scripts/tests/generate-srpp-state-topup-golden-test.py
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/us-earnings-reporting/SRPP --check
python3 scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/.codex/worktrees/us-earnings-reporting/SRPP --check
python3 scripts/generate-srpp-state-topup-golden.py --srpp-root /Users/steven/.codex/worktrees/us-earnings-reporting/SRPP --check
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root /Users/steven/.codex/worktrees/us-earnings-reporting/SRPP
python3 scripts/sync-srpp-swagger-identity.py --check --srpp-root /Users/steven/.codex/worktrees/us-earnings-reporting/SRPP
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
bash scripts/spec-check.sh
git diff --check
```

主22案例fixture僅 bundle metadata 預期更新；不得手填financial expected。無schema變動，不重產schema.sql。實際registry與run-stack及合法窗口readback收據另記，以上離線指令不取代部署驗收。

## 完成報告

2026-10-30 離線實作與驗收完成；以下 registry、main 部署與合法窗口 API 收據由正式發布步驟另核，不提前宣稱 522.4–522.6 完成。

- 凍結 SRPP feature HEAD `134378194ce61ee38f950cd02e049590d0eb76d3`，工作樹乾淨；SRPP main/origin 已獨立讀回一致為 `8b0e68144205c6850ea328b02e5944eb0c5ee8be`。Codex/Claude daily149來源與runtime45來源check均PASS。
- 新 bundle `da75635ef720e00776690b0c9566538cacc2580f9539865beb55952a39cb37f8`，manifest raw SHA `db9c02336bf6caf0bfdc31ebad915f88f139796d38c784beacb6acb5f1b8062a`；model raw `8ee4eb614a73099455b25d163fce6b675e543e1120963add38d0d7d96f8a8b6d`與Swagger `40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe`不變。model／formal targets／其他fixtures／calculator／賣出規則無diff。
- 僅三個實質artifact差異：來源完整manifest、原Ruby生成SQL的bundle字串、原Python生成主22case golden的bundle欄。22個金融cases及originalCalculatorHashes逐值不變；state-topup22cases只讀check PASS。首個report generator因SQL仍舊而拒絕；先原Ruby重產SQL再原generator成功，無手填expected或繞過檢查。
- 完整backend Maven真實exit0：351 suites／3319 tests／0 failures／0 errors／2 skipped（3317實跑PASS）。skip為非Linux case-variant symlink語意，以及未啟用srpp.report.size-probe的opt-in wire容量測試，未冒稱已實跑。authority checker4 tests、state generator5 tests、Ruby13runs／307assertions、三份Swagger renderer/sync只讀check、spec-check BLOCK0/CHECK0及diff-check全部PASS。
- 兩輪獨立spec review：第一輪major1的startup副作用前置證明已修，第二輪0findings；獨立完整artifact/diff-scoped架構review亦critical0/major0/minor0，確認bytes/JCS、SQL insert-only、financial expected及scope不變。
- 無authority hot-reload／通用startup maintenance旗標。正式部署將依當下durable/config逐項證明既有no-op；不改設定或DB造gate。00:05既有scheduled roll自然完成三owner到今日，非本輪觸發；這一份收據不替代部署當下再查。台北09:05前正式report-calculations runtime維持NOT_YET_EXECUTED，不能用安全GET／健康／靜態hash冒充。
