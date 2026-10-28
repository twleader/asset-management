# [t517] 發布兩軌共用證據檢核的規則包身分

**對應 Requirements:** Requirement 187（背景不可變報告包與新規則包的純讀採用）
**前置任務:** t516（已修可選雷達交易隔離並落地 main）
**Liquibase changeset:** 無

## 背景

2026-10-28 午盤 Claude 未嘗試報告計算 GET 即用本機、Codex 通膨1分只有單邊美國3.7%來源，另把早盤草稿視作額度用滿。SRPP 端補共用機械檢核與 fingerprint 納入風險／額度證據；不得改投資政策、重寫已凍結核心／結果或把結構 VERIFIED 冒充來源驗真。規則來源改動產生新 bundle，asset 必須同步 authority 及 insert-only registry，否則新 hash 回 POLICY_UNSUPPORTED。

## 要做什麼

- [x] 517.1 以唯一來源 `/Users/steven/.codex/worktrees/daily-evidence-parity/SRPP` 最終獨立審查後的乾淨 managed feature commit 鎖定發佈收據；root 明確提供最終 commit/hash 後才複製，不沿用有待修正的中間 hash。先 Codex/Claude daily policy check 及 runtime contract check PASS，記實際來源 commit、model/manifest raw SHA、POLICY_BUNDLE_SHA256。檢查 guard/docs 不改投資門檻、不新增L0、缺口報告可交付、VERIFIED結構不替代auth；發現明確缺陷先回報，不擅改SRPP。
- [x] 517.2 由實作subagent逐位元同步 backend/src/main/resources/srpp/decision/model_inputs.json 與 policy_manifest.json；model bytes 預期不變，不能製造diff。formal targets由同model導出，原bytes不變不改。保留strict JSON duplicate/raw-byte/JCS、report candidate十欄及既有scope驗證。原金融Swagger SHA256 40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe 不變，無API/schema/route/producer/公式變更。
- [x] 517.3 顯式使用同SRPP root重產原report 22case golden，check state-topup22cases；不得為green任意改 expected，除新bundle metadata外的case/sourcehash差異須有已驗來源因果；本輪已確證 state-topup 既有 source-hash metadata 漂移：asset fixture 最後更新1100ff1f（10/26 18:03）早於 SRPP execution_policy.py 的690c6f1bc6cd95a12867fcb30048f03ba36751b8（22:44，SHA由6787b2ec…到af21a834…）；主reportfixture已採af21a834。原工具在隔離tmp tree生成preview，全部22cases與其他兩個sourcehash逐值相同，唯一diff是originalCalculatorHashes.execution_policy.py。納入第四個artifact report-state-topup-golden.json，只准原generator重產這個metadata，不手改hash或case/expected；保留preview diff/Git證據並重驗--check和Java parity。以既有 Ruby generator重產追蹤 d201-registration.sql，INSERT ON CONFLICT DO NOTHING，不UPDATE/DELETE舊row；authority --check需PASS。
- [ ] 517.4 完整backend suite、兩組22case Java/Python parity、Ruby registry、authority release gate及其tests、Swagger renderer/sync --check、spec-check/diff-check通過，獨立審查完整diff。使用者指定正式部署只從乾淨main，本輪feature只做離線驗收，不recreate正式服務。asset完成feature commit/no-ff mainmerge/push/remote讀回後，還需root明確通知SRPPmain已push及remoteVerified，才能執行受追蹤SQL單次insert。讀回精確row全部欄位，確認舊rows不變。
- [ ] 517.5 僅從乾淨asset main經run-stack build/recreate business-services，保留main .env/secrets掛載與BFF；不要例行重啟BFF，依有界health120s/safe GET30s判復原。每次recreate前唯讀確認今日daily_market_analysis.status=OK且email_sent_at存在、US五碼latest>=2026-10-27及非US五碼latest>=2026-10-24、共十碼齊；任何不符先停部署回報，不改DB/政策來騙gate。部署後等原startup30秒自然check，確認analysis hasOk=true略過、index皆最新略過及無新增相關寄送/補抓；先前feature重啟曾自動寄兩封非SRPP分析通知及補TPEX2453筆的事實保留，不能聲稱歷史零寄信/零刷新。
- [ ] 517.6 新registry row及運行jar完整model/manifest/新bundle/Swagger身分讀回後，今日台北09:05≤now<13:30才能先GET trading-calendar?year=2026確認200/availability.tw.status=AVAILABLE/今日twTrading=true，再用email=tw.leader@gmail.com、consumer=Codex或Claude、tradingDate=2026-10-28、reportSlot=12:00、真正新policyBundleSha256及原swaggerSha256唯讀GET report-calculations，固定同calculationRunId再讀位元/hash一致且consumer verifier PASS。正常背景tick自行發包，不人工觸發producer/observer/report、refresh市場、寄信或下單。保留partial/unavailable/blocked，舊hash200不替代新hash驗收；>=13:30記NOT_YET_EXECUTED。

## 驗證

```bash
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/daily-evidence-parity/SRPP python3 scripts/tests/check-srpp-report-authority-test.py
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/daily-evidence-parity/SRPP python3 scripts/tests/generate-srpp-state-topup-golden-test.py
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/daily-evidence-parity/SRPP --check
python3 scripts/generate-srpp-state-topup-golden.py --srpp-root /Users/steven/.codex/worktrees/daily-evidence-parity/SRPP --check
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root /Users/steven/.codex/worktrees/daily-evidence-parity/SRPP
python3 scripts/sync-srpp-swagger-identity.py --check --srpp-root /Users/steven/.codex/worktrees/daily-evidence-parity/SRPP
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
bash scripts/spec-check.sh
git diff --check
```

本輪無schema變動，不重產schema.sql。registry SQL僅兩repo main遠端驗證後由授權release operator執行。Docker與合法交易日GET之實際證據需獨立記錄，不能拿以上靜態測試取代。

## 完成報告

2026-10-28 完成來源凍結、離線發佈驗收與獨立 spec／arch 審查。registry、main 部署及合法窗口內新 hash API 成功證據需由後續正式收據另核；本段不提前宣稱517.4–517.6完成。

- 凍結 managed SRPP source HEAD=b8e4c782069968ca850172af9a40b21afc2a7e5c，branch=codex/daily-evidence-parity-release-20261028，乾淨。SRPP main 已 no-ff merge/push/remote readback 為 e1f8a1005bf1a1998b2bcfa577a802ca3b4bdaf0。Codex/Claude daily policy146 sources及runtime42 sources檢查通過。
- 新 POLICY_BUNDLE_SHA256=72ab934b84e44b2070ea5de13f8d79501153d6e4268834d868b8547415b3a055；manifest raw SHA=f26b6b2a2a6b446432080080231a8ae6a546b3daa99274af5cd829fe587daafd；model維持8ee4eb614a73099455b25d163fce6b675e543e1120963add38d0d7d96f8a8b6d；Swagger維持40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe。早期f43/bbe候選從未採用發佈。
- 實質改動僅四個衍生artifacts：原manifest完整bytes、generator產生insert-only SQL、主report golden的bundle欄，以及state-topup golden的一欄舊sourcehash metadata。model、formal targets、Java/計算公式、Swagger/路由、BFF、schema均未變。主report22cases與state/topup22cases的金融expected逐值完全相同。
- 原state-topup --check曾FAIL，已保存 PREEXISTING_SOURCE_HASH_METADATA_DRIFT、隔離tmp原generator preview與Git證據；兩代 execution_policy.py 原始SHA由690c6f1前後可確證6787→af21，只新增市場日曆／完成價跨市場支援，並非本輪guard修改。獨立spec增補與審查後，才授權原工具重產唯一metadata，再以原--check取得PASS22cases；未覆蓋早先失敗、未手填hash或改cases。
- 原golden工具一開始因隔離source缺既有2026日曆，曾TRADING_CALENDAR_UNVERIFIED。root先用既有adapter驗primarycanonical日曆後只複製原bytes到source，SHA48d1521a93f0fe7121be7602fc520793babbe6f00792254edc33d8cf93cb3f80，沒有刷新／植入假日曆；source仍乾淨。其後原report generator/check均PASS22cases。
- 完整backend Maven真實exit0：350 suites／3318 tests／0 failures／0 errors／2 skipped（3316實跑PASS）。新資源後另跑 StateTopupCalculator／ModuleCalculator／DecisionPolicyAuthority 三類 focused 共23tests，真實exit0且零fail/error/skip。兩個既有skip分別為非Linux環境的case-variant symlink語意測試，以及未設srpp.report.size-probe的opt-in實際wire容量測試，未當成已實跑。Ruby registry兩組共13runs／307assertions全通過；authority gate與其4項Python測試、state generator5項負向測試、report22case check、state-topup22case check、renderer/sync只讀check均PASS。spec-check BLOCK0/CHECK0、git diff --check及獨立完整artifact審查均無critical/major/minor findings。
- 來源SRPP新guard聚焦測試通過；root另有較廣rule_cleanup/data_tools測試因隔離來源缺current_cash_income、latest_assets_snapshot、ten_year_performance等本機產物未通過，未刷新／補造金融輸入，也不將那些較廣測試稱為PASS。本輪asset完整backend與發佈檢查結果分開記錄。
- 先前Task516 feature重啟曾由既有startup自癒寄兩封非SRPP通知及補TPEX2453筆的事實保留；本輪每次正式recreate前仍須重核今日analysis/十index durable gate並在啟動後實證skip，不能以既有收據當成本輪零副作用證明。
