# [t566] 現金先買與一般股票高檔減碼規則發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t550／t542；本輪 SRPP D211來源凍結
**Liquibase changeset:** 無

## 背景

使用者現金充足，明確要求低點先以可動用現金買進不足標的，待高點才減碼或清空舊股票，不為換股、配置超標或期限而低賣。先前D208／D209高檔與低檔保護僅適用00919，其餘台美股票有機會在歷史高點觀察後一路回落至低檔才釋放，需補上共同必要閘門。高點定義沿用既有接受的完成日與即時日D≥80，四個KD>50與其他超賣反證同時檢查，仍需D174轉弱及1.5%回落，不宣稱知道市場最高點。

## 要做什麼

- [x] 566.1 使用者已明確回覆「允許，不用等，直接改」，授權本輪略過Task565共享檔案等待限制；先核main／workingtree，再以獨立append保留其他session工作，依序補齊既有Requirements 187／193／199的驗收條款、對應設計與本自足任務，不能以單一task替代前兩步。共享檔及任務完整後執行spec-check，由作者以外唯讀spec審查，無critical／major後記錄spec-review-pass，才寫backend。
- [x] 566.2 來源只用 `/private/tmp/srpp-cash-first-high-sell-20261107` root凍結的D211／policy2.60.0／model3.22.0與原model schema。新增core_sell_guard_d174.ordinary_equity_high_sell_d211：decision=D-211、enabled=true、markets=[台股,美股]、excluded_bond_symbols=[00679B,00697B,00719B,00751B,00865B,SGOV]、minimum_kd_exclusive=50、minimum_d_inclusive=80、reuse_low_point_policy=low_point_sell_protection_d208、reuse_high_zone_policy=high_zone_reversal_d209。原00919 D208/D209設定及原因維持；新條件只套其他台美普通股票core候選，不改債券、既有具名緊急風險退出、獨立已確認戰術sleeve退出。
- [x] 566.3 Java台股SrppReportModuleCalculator.coreTiming與美股UsCoreSellCalculator.evaluate對D211股票套相同閘門：最近完成日K/D及同輪精確雷達K/D四值嚴格>50；RSI5≤30、反向W%R9≥80、BIAS10≤−8、52週位置≤0.15、雷達BUY/ADD與低檔／超賣／反彈觀察等原D208反證任一成立即禁止；全部通過後最近完成日D及即時D皆≥80，K可小於80。原D174完成K連降、K<D、D下降、下降日在高點後、即時K<D、同價基回落、來源／時效／方向等全保留。D211_LOW_POINT_SELL_BLOCKED與D211_HIGH_ZONE_SELL_BLOCKED是有效false；缺設定／數值／來源是局部未驗證，不能填0或放行；設定門檻須與重用原D208/D209一致。先保留／更新已驗高水位，不清帳；四欄穩定觀察hash保持，歷史完整政策hash只接受已核對精確前版，不能任意豁免。
- [x] 566.4 US內部執行D211低檔／高檔閘門，但公開SrppUsCoreResult保留原欄位集合，以既有status／timing_pass／reason表達D211結果，不新增low_point_sell_protection或high_zone_reversal欄位。原Python的US公開投影／獨立verifier須從凍結原始input與DB state重算D211全部條件並逐值比較原結果，不能只移除證據後放行；US同輸入golden逐欄parity亦依此公開結果。TW保留原兩項明細receipt，US projection不改TW證據。故不改現有公開API/schema/module scope、observer、排程、DB或交易權限。TW公開module保留canonical D174 scope與既有receipt形狀，以D211原因綁定新語意。Java新增50／80上下邊界、四KD、每項超賣反證／缺值、高檔轉弱正例、觀察保存及債券不套用反例；US同輸入golden必須由原Python計算，不能手改expected。原00919理由維持。
- [x] 566.5 現金先買屬獨立資金路徑：只計已交割可動用現金，先扣保護現金、專用保留款、待交割責任、已確認未成交買進及費用；未成交賣單／未來價差不當資金或配置空間。買腿不依賴賣腿同日READY，不要求先賣；仍須不足目標、原低接／買點、個別／群組／全股票容量、月週日額度、熊市與全部買進硬閘門。原已交割換股準備金FIFO／第21交易日規則只管該專用款，不能限制其他合規現金或重複占用資金。資產端目前ROTATION_AND_REGIME_CAPACITY只計容量與準備金，不新增可交易買腿或資金重分配器；以來源設定／報告規範表達分離，不聲稱現有無來源capacity可用。無資料時WAIT，無賣方READY也不能阻止已另驗合格現金買腿。新增實際calculateShared的FUNDING_CAPACITY回歸：core狀態不可用／無賣方READY時，已交割資金扣已驗保留買款、待付與通膨保護底線仍能產生正的deployableCapacity；未成交SELL與待收款不能增加額度，定存低於保護底線則為0。此測試僅證明獨立資金容量，不把未通過全股票上限的合成組合稱為可執行買單。
- [x] 566.6 root確認最終bundle後逐位元同步model／manifest，原targets投影formal document、原Ruby完整stdout產insert-only SQL，原Python重產same-input／long-short／US goldens及必要generator案例。金融expected允許只因新增股票門檻轉為BLOCKED，逐案紀錄差異；其他配置／公式／買量／債券／戰術輸出保持。authority與generator checks、focused Java／Ruby／負向回歸、backend package及獨立diff架構審查通過。
- [ ] 566.7 正式順序：離線驗證build→兩repo各feature commit／main no-ff merge／push及remote-ref讀回→已合併SQL insert-only registry四欄全文及舊rows不變→乾淨asset main原正式設定僅business-services build／force-recreate。保留其他容器與BFF，核JAR model／manifest／Swagger／SQL／formal全文與合併bytes一致、新image與healthy、effective config／mount一致。從SRPP host以新完整hash唯讀GET業務API保存原HTTP與回應；週末／時窗外只診斷不稱正式採用。不得人工producer、刷新、報告、草稿、寄信或交易。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-cash-first-high-sell-20261107 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /private/tmp/srpp-cash-first-high-sell-20261107 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-cash-first-high-sell-20261107 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-cash-first-high-sell-20261107 python3 -B scripts/tests/check-srpp-report-authority-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest,UsCoreSellCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
git diff --check
```

正式serve只在雙repo落地及registry後，從確認為乾淨main且原正式設定的目錄執行 `docker compose -p asset-management build business-services` 與 `docker compose -p asset-management up -d --no-deps --force-recreate business-services`。DB schema無變更，不重產schema.sql；新US golden以 `python3 -B scripts/generate-us-srpp-calculator-golden.py --srpp-root /private/tmp/srpp-cash-first-high-sell-20261107` 產生並由Java逐值驗證。

## 完成報告

離線實作與驗收、雙專案commit／no-ff merge／push、正式insert-only登錄與business-services部署已完成。主機連線驗收為PARTIAL：日曆與指數通過，台股報價HTTP200但空陣列，未通過非空條件；正式日報採用仍為NOT_YET_EXECUTED。使用者已明確授權略過Task565共享檔案等待，本輪append-only保留原工作；完整spec經作者以外審查及機械檢查BLOCK0／CHECK0，實作另經獨立arch／正確性審查，最終critical0／major0／minor0。審查找到的台股市場身分缺漏可繞過問題已修正：coreTiming要求body／quote／radar三份market皆精確台股，再以權威台股呼叫共同閘門；缺／錯市場回歸通過。未更動公開API／Swagger／schema、原公式家族、債券與戰術規則或交易權限。

凍結來源D211／policy2.60.0／model3.22.0，bundle `bedb78fffc74ea38b04f848676d53448e27cc30e0c0fb7b459b3aeb8ec438254`，model bytes SHA `64e3f9fc86208191cbf4492b43da4eb9a2ecfc426bffd7e886b81a4fe54a0c06`，manifest bytes SHA `8ff60d6bdf4e35309dfb3c540de529e60efe5ea191a965fba8d5c9d4020fcdcc`；五份離線JAR authority逐位元相符。新增共同Java OrdinaryEquitySellGate與TW／US原計算器銜接，高水位及原確認保留；US結果維持closed shape，以status／reason表達阻擋，原Python verifier從實際Java publisher合成包重算及精確比較PASS，僅DIAGNOSTIC／formal_report_adoption=false。

75項Java（含隔離Testcontainers publisher 11項）、13項Ruby（307 assertions）、4項authority負向與5項exit-generator回歸、authority／golden檢查及backend package通過。111項TW same-input、51項US core＋9項US tactical及15項long-short由原Python產生；原73TW市場body與18US input逐值保留，其中2項TW、9項US原擇時結果依新D211閘門合法改為阻擋；US tactical全文不變，不宣稱所有金融結果或計算器hash維持。首次funding測試失敗為Frozen defensive-copy導致測試新增待收未進入新input，已重建Frozen修正測試並保留失敗log，沒有放寬任何資金閘門。實際資金回歸證明無賣方READY仍有正的獨立已交割容量；未成交SELL、待收不增額度，定存低於底線為0。這是容量測試，不聲稱整個合成組合具可執行股票單。

本輪收據在 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-07-cash-first-high-sell-release/`，核心為 `asset-offline-validation.json`、`asset-golden-comparison.json`、`asset-us-synthetic-independent-verifier.json`與完整測試logs。566.7已完成Git／registry／部署及精確hash業務API診斷；報價非空驗收未通過、正式日報採用未執行，故566.7保留未完成勾選並明列剩餘驗收，不把PARTIAL寫成PASS。本段不宣稱正式報告採用或交易。此完成報告／566.1–566.6勾選只回填既有實作證據，未改功能契約，依CLAUDE明文「只是回填任務完成報告」例外處理。


### 566.7 發布與主機診斷紀錄（2026-11-08 台北）

- SRPP feature `e86182e3a21f4d07dd274fe0b1630cb6789d5d4a`、main no-ff `25e10b769056649952e3c4f632dbc2bbadf060d2`；asset feature `9a342c1bec74b4eb163fed599ab7b6bfe65d52e4`、程式發布main no-ff `983d01ddb740b62c83e76d0f17491987366be82c`。兩者均push並精確核對remote refs。實際SRPP日報checkout已安全fast-forward，原未追蹤檔保留。
- 從已合併受追蹤原generator SQL insert-only登錄新bundle，registry由36增至37筆；policy_bundle_sha256、formula_version、policy_document與formula_manifest全文逐值相符，全部原36筆（含registered_at）未變。formula_version為`ASSET_MGMT_SRPP_V2`。
- 乾淨asset main與原正式設定僅build／force-recreate business-services。container `5b5ea8e1f8ec5d82fcee1f6bafb9c492a5e2f5854be36cf21aa40f3f0d036cc1`，image `sha256:d27278200a3dcd959a6606ed758c1fdb5f1e595a496006c87c0c5d47cdbcc6e6`，healthy。運行JAR SHA `c33a6cccaf40f62e6c6dac004f0d4068a03c6295287aaaf6b26817c464ee8b45`；model／manifest／Swagger／SQL／formal五份authority原bytes精確相符，D211三個類別存在。47個environment鍵、3個mount與正式設定一致，其他8個container ID／image均保留。BFF無重啟即可從9090讀TWSE/1m HTTP200、21筆收盤。
- 主機`verify_9090`：日曆HTTP200、TW AVAILABLE、365日；TWSE日線HTTP200、21筆；台股quotes HTTP200但`[]`，非空檢核FAIL，整體連線驗收PARTIAL，未放寬檢核。唯讀Redis檢查台股price:index不存在（SCARD0、EXISTS0、TTL−2），0050 price key亦不存在；同輪美股index15筆、英股4筆仍存在。PriceCacheReader只讀Redis，miss不補DB；writer原設定24小時TTL，index亦依Lua EXPIRE相同TTL，週日缺台股cache與週末過期相符，但無歷史TTL前態收據，不能稱為已證明歷史過期。行情reader／writer與BFF quote程式本輪零變更，external-materials容器未替換。
- 精確新bundle＋既有Swagger的業務GET：台股`tradingDate=2026-11-08, reportSlot=09:05`於台北00:04回HTTP400 `INVALID_REQUEST`，既有BFF時鐘閘門在slot之前即拒絕，並未取得業務計算包；美股`sessionDate=2026-11-07, US_OPEN_0945`回HTTP409 `NON_TRADING_DAY`。兩者都是實際已保存診斷回應，不能稱為成功API金融採納或正式日報實跑。正式採用維持`NOT_YET_EXECUTED`，待合法交易日／時窗與完整required_reads、L0及獨立verifier。
- 主要收據：`operator-git.json`、`operator-merged-authority.json`、`operator-registry.json`、`operator-registry-exact-row.json`、`operator-deployment.json`、`operator-jar-authority.json`、`9090-connectivity.json`、`business-api-exact-hash-readback.json`、`operator-readonly-diagnosis.json`。前置Ruby loader錯誤在DB寫入前發生並留收據，以明確絕對require有界修正後一次登錄成功。未刷新行情／Redis、人工啟動producer、重跑報告、建立草稿、寄信或交易。

本次僅回填此任務文件的真實發布證據與剩餘驗收；不改程式／authority／SQL／金融規則。文件收尾另走feature commit與main no-ff merge／push；因運行資源bytes不變，保留已驗證image，不為文件收尾重複build／recreate。
