# [t637] SRPP 早報現行來源宣告一致性修復的 authority 配套發布

**對應 Requirements:** Requirements 187／193（不可變計算包與正式規則來源身分）；只修現行來源宣告與驗證守門及其authority metadata，不變更投資政策。
**前置任務:** t633、t636
**Liquibase changeset:** 無

## 背景

2026-11-26 Codex 09:05指定thread `01a21563-462f-79a2-a3b5-381482de5ba4` 在必讀來源階段STOPPED_L0；公開final明載DAILY_TRADING_AUTOMATION的規則包宣告仍D234，CORE_TACTICAL_POLICY仍D234／政策2.76，而SHARED_RULES、model、manifest已D235／2.77。兩次checker卻PASS，不代表來源已語意一致；原run未金融API／未草稿的事實與原STOP收據保留，不重建其manifest來洗成通過。

本輪核准修metadata與checker的現行宣告驗證。現行來源為D-235、policy_version=2.77.0、model_version=3.25.0。正文歷史決策與原投資數值保留。NAMED_DEEP_DRAWDOWN現行header更新會改文件SHA；已核准模型verified_instruments中七個STOCK來源hash的必要連動，不等於投資模型值變更。

## 要做什麼

- [x] 637.1 SRPP的隔離feature由來源實作者修當前required_reads及conditional Markdown文件的明確現行bundle/version宣告。policy_bundle.validate_rules新增第一階段語意守門：依daily_runtime_contract.py REQUIRED_READS與contract required/conditional集合動態取得Markdown，只解析初始文件preamble的『規則包／現行規則包／現行政策／現行執行決策／現行內容版本／決策基準』等明示current行，不把H1歷史D216標題或後續Accepted/history章節當成現行版本。同一Markdown邏輯段落的續行政策／模型欄位與後續明示現行身分句仍屬宣告範圍；完整decision及version token須先辨識宣告再精確驗證，畸形或空值不能因regex未match消失。fenced code須按反引號／波浪號及opening長度配對，範例內H2不得截斷preamble。startup/core/shared必要宣告缺失亦FAIL；不得退成只檢first5或固定少數白名單。具名錯誤CURRENT_AUTHORITY_DECLARATION_MISMATCH／MISSING應帶檔案及可得行號。沿原POLICY_RULE_MISMATCH失敗語意，rules/build/check不能靠重簽manifest或重建runtime祝福錯誤來源。
- [x] 637.2 保持既有pre-calendar startup讀取邊界，不能把完整policy/rules驗證提前到calendar前，也不改required_reads清單、報告時窗、金融風險／數量／費率／稅率、報價／五檔／可賣量／冷卻硬閘、排程、模型選擇、寄信或券商唯讀界線。新增mutation tests至少覆蓋本次D234/D235矛盾、當前policy/model版本漂移、必要宣告缺失、required/conditional非首批文件、歷史章節不誤判、錯來源重簽manifest＋重建runtime仍拒，以及startup不新增其他I/O。歷史D234段若保留須明確歷史標示；不改其金融數值使表面一致。
- [x] 637.3 模型唯一允許的內容leaf差異為execution_policy.usd_asset_fx_preference_d215.verified_instruments內GOOG/GOOGL/MSFT/AVGO/NVDA/AAPL/AMZN這七筆instrument_kind=STOCK、source_document=docs/NAMED_DEEP_DRAWDOWN.md的source_sha256，更新為本輪該文件精確原bytes SHA；其餘leaf、版本2.77.0／3.25.0、D235策略及所有金融值必須完全相同。保存leafdiff與before/after hashes，不用『model bytes不變』冒充metadata-only；不擴到其他instrument或財務規則。
- [x] 637.4 資產端在最新main受控feature納本任務，等SRPP source freeze及rules/runtime/三consumer檢查後精確copy model/manifest原bytes至backend authority。用原canonical projection產formal policy document；正式targets/金融政策投影必須與基準全文相同。原Ruby generator完整stdout產insert-only SQL，stderr分檔；不執行正式SQL，不UPDATE/DELETE舊registry。不改Java金融calculator/BFF production code或任何9090 schema／route。
- [x] 637.5 用既有generate-srpp-formula-v3.py實算來源集與結果。本輪policy_bundle.py／daily Markdown不在其列管計算來源；formulaVersion／formula canonical bytes、catalog generated constant、BFF formula mirror與全部calculator source hashes應完全不變，若不符先停止查證，不自行刷新金融公式求PASS。原golden generator產新bundle metadata；111 report與15 long/short每case金融expected輸出、原calculatorHashes全相同，其他相關golden亦做金融輸出及非metadata差異檢查。只允明確識別的bundle/model/sourcehash metadata差異，不廣泛刪掉輸入欄位掩蓋差異。
- [x] 637.6 Swagger ecc3fa55751c01a1583480f0c5b257ded94444689db2f305a5851b35eb38c6bf與所有mirror、API consumer契約保留原bytes，不跑renderer/sync mutation。完成asset authority、formula/golden checks、Ruby registration generator tests與相關backend authority/calculator測試；驗current BFF validator可接受新policy/package identity，但不新啟正式producer或金融API、不卡入當輪日報驗收。source guard的有界跨repo架構與一般code review用本機Codex唯讀、最多各三輪，每輪問題修完；不自評冒稱review PASS。
- [ ] 637.7 先完成全部來源及generated spec fixture、task完成紀錄，再執行最後獨立spec review/check及spec-review-pass/status，最後gate記錄後不得再編輯spec造成stale。每份原始stdout／錯誤／diff與hash保存本輪release receipts；原09:05 STOP不得覆寫為修後成功，也不建立新報告、草稿、寄信、交易或呼叫金融計算包。
- [ ] 637.8 完整發布授權由主控交operator：兩repo feature commit／main no-ff merge／push及remote ref讀回後才merged SQL insert-only registry，核新row全文與歷史rows未變。BFF production code/resources若經實算完全不變，本輪只需從乾淨main正式設定rebuild/recreate business-services，不為儀式重建BFF；保存設定、其他containers、新image/container/healthy與JAR authority bytes證據。SRPP host 9090三維護GET由主控依release skill另做，不補跑日報或用維護PASS證明自然採用。srpp-simulator既存D234 bundle漂移不在本輪修復，不export或重建sim，也不得宣稱其新bundle已採用。

## 驗證

```sh
bash scripts/spec-check.sh
bash scripts/codex-review.sh spec <完整diff與mechanical材料> 1 <本輪external_receipts>
python3 -B scripts/generate-srpp-formula-v3.py --srpp-root <SRPP_FEATURE> --check
python3 -B scripts/check-srpp-report-authority.py --srpp-root <SRPP_FEATURE> --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root <SRPP_FEATURE> --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root <SRPP_FEATURE> --check
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
SRPP_RELEASE_WORKTREE=<SRPP_FEATURE> python3 -B scripts/tests/check-srpp-report-authority-test.py
mvn -q -f backend/pom.xml -Dtest=DecisionPolicyAuthorityTest,SrppReportModuleCalculatorTest,SrppNamedRadarSellCalculatorTest,SrppSettingsAuthorityValuesTest,SrppPolicyRegistryServiceTest test
mvn -q -f bff/pom.xml -Dtest=SrppReportResponseValidatorTest test
git diff --check
```

源端mutation/rules/build/runtime/Codex・Claude daily/model simulation等檢查用SRPP實際工具，逐命令保存原stdout。最終發布規則身分、實際Git/registry/container/9090分開記錄；正式日報採用NOT_YET_EXECUTED，原STOP仍是STOP。

## 完成報告

來源與資產配套已完成，於本節定稿後只執行最後spec review／gate，不再寫回本檔；637.7的最後gate結果保存在外部收據，因此不提前勾選。637.8的正式Git／registry／部署及9090維護驗證仍由主控operator執行，不先宣稱完成。完整收據目錄為`/Users/steven/.codex/release-receipts/srpp-morning-repair-20261126/`。

- 最終來源固定為bundle `83574d008988843b90a673fc99a0a872e3379ebd102b76a66fd07a80f8aa5304`、model原檔SHA `4cc2874d6b66802b452a447b6eff671c19daffb1e39c0275306c9d12630a038d`、manifest原檔SHA `c3f318c4af677645d228492dca53620a3b8d3a5e82a5d59bb857c94befabaf71`；兩端model／manifest精確bytes相同。source guard SHA `5d2506e6fab320f5976ac52f6b00b44646632a2366e655c0b2bc1afb25f25894`。
- 來源44 tests（15專用mutation methods＋29既有）及11 checks PASS；資產10個原generator、14個authority／golden／formula／Ruby／diff checks PASS，backend相關5類107 tests PASS，BFF validator 39 total／1 skipped、兩個opt-in完整合成包正腿與BLOCKED皆PASS。保留前次既存合成包只重綁新authority metadata，再經BFF與SRPP完整verifier核對；不是新producer報告或當輪金融API取數，所有modules金融輸出精確不變。
- 模型恰七個核准來源hash leaf差異，28個calculator來源hash不變；11份golden的金融expected不變，其中111 report及15 long/short cases全文相同。正式policy projection、公式canonical／catalog／BFF mirror及Swagger原bytes不變；SQL完整stdout與基準僅bundle SHA替換，尚未執行。
- 獨立arch第三輪PASS 0／0／0，取樣source為第三輪修復前的resume4；其後僅同guard/parser及tests有界修補，無Java／BFF／分層改動，未宣稱新source再經第四輪審查。一般code第三輪CHANGES_REQUESTED兩項Major已修：逐一驗證同句明示current標籤、處理欄位分隔符跨行；split與collector共用label集合。依CLAUDE最多三輪規範，**第三輪修復後未再獨立複審，不冒稱code review PASS**。15專測包含80組label／separator／decision reseal三consumer正反；主控另以最終source與bundle做84 cases機械核對PASS，見`srpp/root-final-round-repair-check.json`，這不是第四輪review。
- 原09:05 exact thread的STOPPED_L0與未金融API／未草稿結果保留。新bundle未用於補跑早報，不宣稱12:00自然run已採用。既存simulator D234漂移維持具名未修／未部署；本輪只需backend business-services重建，BFF production code/resources無變動且應保留原container。

最終spec gate、後續main SHA／registry row／image/container與9090結果僅記外部release收據，不為補填正式身分再改tracked spec而造成stale或反覆build。
