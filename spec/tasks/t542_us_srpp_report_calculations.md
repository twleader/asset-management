# [t542] 美股 SRPP 計算 API、觀察移轉與雙專案發布

**對應 Requirements:** Requirement 199（以美東session身分讀取唯讀不可變美股擇時／戰術計算，資產端唯一保存US觀察）
**前置任務:** 無
**Liquibase changeset:** 新增獨立US tables；實作前再次查核未占用版號，不修改歷史changeset。

## 背景

現有report-calculations只支援台股09:05／12:00，台股HTTP200不能證明美股能力。基準兩專案遠端main為asset a5176ab2、SRPP bd8ec40，D-206／v2.55.0；所有發布身分仍須重新由工具產生。既有Codex-US帳為可核對歷史結果的空帳，Claude-US檔缺失，不能清帳／補空。Python core guard原有台北asof日期及固定16:00問題需修正為NY與官方半日市；投資條件不變。

## 要做什麼

- [ ] 542.1 完成SDD機械檢查與另一唯讀subagent獨立spec審查；保留其他worktree同檔工作。實作由subagent負責；完成後另作diff-scoped arch審查。
- [ ] 542.2 新exact GET `/api/public/srpp/us-report-calculations`，query只接受單值email?、consumer=Codex|Claude、market=US、sessionDate=ISO NY日期、reportSlot=US_OPEN_0945、兩個小寫64碼hash、calculationRunId? UUID。unknown／duplicate／blank／body／錯市場回400。RFC9457固定七欄，instance是新路由；400 INVALID_REQUEST，404 CALCULATION_NOT_FOUND，409 NON_TRADING_DAY／OUTSIDE_REPORT_WINDOW／POLICY_UNSUPPORTED／SWAGGER_MISMATCH／CONTEXT_IDENTITY_MISMATCH，503 OWNER_UNAVAILABLE／CALENDAR_UNAVAILABLE／CONTEXT_NOT_READY，502 UPSTREAM_INVALID，500 INTERNAL_ERROR。所有結果private,no-store。
- [ ] 542.3 包schemaVersion1／apiScope US_REPORT_CALCULATIONS／status FINAL，包含identity(ownerUserId,consumer,US,sessionDate,US_OPEN_0945,policy,Swagger)、run UUID、calculatorVersion、capturedAt/publishedAt、displayReportTime、NY session open/close、frozen inputBundle/sourceVector及input/content JCS hash、固定CORE_SELL_GUARD與US_TACTICAL兩模組、tradeAuthorization=false。模組coverage COMPLETE/PARTIAL/UNAVAILABLE與逐檔有效等待／阻擋／NOT_ARMED／NO_CANDIDATES分離。缺數字null＋具名原因；完整OpenAPI閉合所有nested schema，BFF與Python獨立驗證來源、內容與identity。固定id讀取不得跨scope，GET沒有writer／capture／producer依賴，不因GET計算或外呼。
- [ ] 542.4 America/New_York完整交易日、DST、半日市及跨台北日；已驗證官方年份之外fail closed。背景每分鐘tick只在NY09:40至10:45且regular session內發布，09:45前不供報告採用；NY09:45對應原台北21:45／22:45排程，沿用入口±300秒與60分鐘停止條件、不改automation。同步SchedulePublicBffController.JOBS排程登錄。官方early close來源：https://www.nyse.com/markets/hours-calendars 。不得手動觸發producer或偽造時鐘驗收。
- [ ] 542.5 背景capture只讀既有資產／Redis／stock_price_history／配息／完整雷達；每標的一次detail、宇宙=正持有US∪全部US雷達∪已驗證明確觀察。各市場估值日按自己calendar；保留全量與coverage。USD、同regular session、五分鐘價格，獨立feed／時間／實質價差逐項保留證據或缺口。D108不要求／不讀五檔；business不外呼供應商。
- [ ] 542.6 DB按owner/consumer/US/symbol保存current state，import marker確認帳真實存在，缺marker不可視為新空帳。離線匯入工具讀原ledger與原同軌anchor input/result核對input hash及重播結果；保留原始SHA、Python穩定觀察指紋、觀察起日／高水位／高點日／除息價基／證據。未知指紋拒絕；僅原legacy allowlist可銜接。完整政策hash或JCS不得代替Python stable watch fingerprint。Codex可驗真空帳，Claude缺失保持SOURCE_UNAVAILABLE。
- [ ] 542.7 核心純計算與同版scripts/core_sell_guard.py同輸入等價：首次武裝且當日不能READY；創高持續更新且不重設起日；完整完成日高價、現金除息、最近三完成日KD兩日下降且在高點後、本輪KD恢復、1.5%邊界、雷達版本否決與missing。先更新有效價格觀察，雷達／KD不過仍保留創高。每輪stateBefore/stateAfter/source fingerprint/version append-only；鎖與版本原子更新，重複來源冪等，publication與state同交易，GET不更新舊包。
- [ ] 542.8 戰術共用既有D186純計算元件與同版us_tactical_order_check.py parity，主RADAR路徑不套全球前10；FALLBACK維持原三情境2:1，兩路同向detail、牛市急跌止穩、RISK_OFF限制、原15%成本後空間、即時限價±0.5%與tick、0.25%／0.75%／2%上限、資金實質底線、國泰TWD桶、費稅、成交去重與原出場期限。目標／成本／帳本／資金缺正式來源逐項缺口，不補0或用verified旗標製造COMPLETE；GET不得傳此資料。D206研究論點及採納／等待／否決仍LLM，不固定排名或加分數。
- [ ] 542.9 同步exact gateway/BFF/Security/Tailscale/前端拒絕與matrix變體、OpenAPI與三份Swagger mirrors、SRPP consumer schema/verifier與美股入口文件。SRPP逐scope優先採用已驗證API；有效BLOCKED不能後備解除。切換後US本機ledger禁止寫入，後備只使用已驗證DB包stateBefore與同證據唯讀，缺狀態不可用；台股ledger不動。禁止公網入口／廣泛路由／交易／寄信。
- [ ] 542.10 全部適用測試與離線建置通過後，依renderer→sync→兩check限定dirty鏈與原policy/runtime工具產生最終bundle／authority／golden／insert-only SQL。兩專案feature commit、no-ff merge、push並核對remote後才執行registry；再從乾淨main及正式設定build/recreate必要服務、核對registry與JAR原bytes。schema新增後重產db/schema.sql並驗漂移；前置可用隔離測試DB驗schema，禁止先動正式部署。
- [ ] 542.11 從SRPP實際Python／主機以精確最終hash呼叫新US GET，保存HTTP原文及獨立verifier私人收據，公開repo僅摘要。另執行發布技能要求的唯讀連線檢查。時間不合法只能診斷，不當正式排程PASS；不重跑報告／不POST producer／不寄信／不交易。回報全部不可用欄位、未執行驗收及正式排程是否實跑。
- [ ] 542.12 移轉owner須同輪report-facts+input receipt綁定原assets及core states/results、工具重算事實指紋並對原assets做L0，正式DB snapshotId→ownerUserId收據吻合；SQL再次WHERE asset_snapshot(id,owner_user_id)核對，不能只憑operator owner參數。缺鏈維持不可用，私人來源與SQL不提交公開repo。
- [ ] 542.13 latest GET只在當前NY日期09:45至10:45且regular；同NY日期fixed runId可時窗外診斷，原包不變、不表示可採用。consumer本輪採用須入口time gate及60分鐘上限、capture和全部必要價格來源距本輪asof各不超五分鐘且無未來時間，舊包過期僅回退受影響scope。非當前NY日期400、休市409、未知日曆503。測試09:40包不得於10:44當fresh。
- [ ] 542.14 用App automation工具同步實際22-00 prompt的美股證據片段並readback exact bytes/hash；將原US本機寫帳描述改API優先／同DB凍結狀態唯讀後備。保留原rrule/status/model、季節±300秒time gate、日曆no-op、原停止、Gmail及交易權限原文；不能只更新docs宣稱已接入。

## 驗證

以下測試項為實作計畫，類名尚待建立：美股日曆夏冬令／半日市／休市／NY跨台北日；完整identity隔離、query拒絕、來源與內容hash竄改；狀態移轉有效／缺失／legacy／未知指紋；首次武裝／創高／1.5%邊界／KD恢復／除息／missing；重複與併發原子更新；Python/Java同輸入正反例；無候選與缺來源；flag-only COMPLETE拒絕；GET零副作用／舊包不變／台股回歸。所有合成輸入去識別，不提交私人ledger或API財務原文。

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
python3 scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-us-api-20261104 --check
```

正式順序：兩專案merge/push/remote核對→registry exact row→乾淨main `docker compose -p asset-management build business-services bff api-gateway frontend`（僅實際變更服務）→`up -d --no-deps --force-recreate`相同必要服務→有界healthy及業務GET驗證→JAR全文比對→SRPP host精確hashUS API收據；不觸碰無關服務與正式secret。新增schema後依db/schema.sql檔頭重產，執行`bash scripts/tests/schema-sql-drift-test.sh`要求0。

## 完成報告

待實作、獨立審查與正式發布後回填實證；未在合法報告時窗實跑必須明列NOT_YET_EXECUTED。
