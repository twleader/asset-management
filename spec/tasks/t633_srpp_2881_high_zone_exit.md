# [t633] SRPP 2881 還原權息後高檔分批退出

**對應 Requirements:** Requirement 187／193（不可變正式計算與 authority）；本任務擴充 2881 退出的金融契約。使用者已授權評估及接手其他worktree工作；本輪於乾淨feature更新共用規格，保留其他工作內容。
**前置任務:** t609（具名雷達退出既有計算路徑）
**Liquibase changeset:** 無

## 背景

Steven 2026-11-26 授權 2881 以還原權息後 52 週位置 ≥95% 作初版高檔門檻，高檔 HOLD／HOLD_CAUTION 也需首批退出評估，首批不得套 D-152 小額一次清零。原 NAMED_RADAR_ROTATION_D226 只接受 EXIT_CANDIDATE／REDUCE_CANDIDATE，且 baseline≤5000股及開倉值<100萬元可全清，無法表達此需求。回放限定留存的2026-10-27、11-13、11-16，不重新抓當時行情或偽造缺欄通過。

## 要做什麼

- [x] 633.1 SRPP 正式 model 新增 execution_policy.named_high_zone_exit_d235：enabled=true、decision=D-235、effective_date=2026-11-26、symbol=2881、market=台股、minimum_week52_position_inclusive=0.95、require_distribution_adjusted=true、radar_actions=[HOLD,HOLD_CAUTION,EXIT_CANDIDATE,REDUCE_CANDIDATE]、first_batch_disallow_small_clear=true、pause_outside_high_zone=true、requires_llm_adoption=true。精確驗證政策；數值使用 Decimal／BigDecimal，不接受非有限數、boolean冒充數值，position範圍[0,1]。
- [x] 633.2 新 strategy NAMED_HIGH_ZONE_EXIT_D235，沿用 CORE_D226 帳戶與 D223_00881_CASH_2881_EXIT_20261118 計畫。正式計算在 SrppNamedRadarSellCalculator 執行，每輪每標的僅一條 SELL 腿。新舊共用同一 baseline、confirmed fills、pending/reservations及冷卻帳本，不重置批數／計畫日。
- [x] 633.3 選路在任何容量與費稅計算前固定：新政策生效且 exact radar 的 distributionAdjusted 精確boolean true、week52Position合法且≥0.95，選新路徑；選後即使其他閘門BLOCKED也不能回舊路徑求解封。有效還原位置<0.95時，新路徑暫停；若action為EXIT／REDUCE，既有D226獨立路徑可照原規則評估。HOLD／HOLD_CAUTION低於門檻輸出新路徑BLOCKED、明確離高暫停理由，不能清零。缺少／無效還原標記或position時HOLD／HOLD_CAUTION輸出新路徑UNAVAILABLE／BLOCKED零腿。EXIT／REDUCE若尚未因有效高檔選中D235，既有D226獨立路徑仍按原規評估，不強加新的全域52週欄位依賴。ADD／BUY等其他action不因高檔而成賣出。
- [x] 633.4 新路徑包含14項硬閘 D235_HIGH_ZONE_PATH、QUOTE、RADAR、POSITION、PENDING、RESERVATIONS、DEDUPLICATION、CORE_RETENTION、D152_BATCH、COOLDOWN、DEPTH、EXECUTION_ACCOUNT、COSTS、TRADABILITY，共14 receipt。新gate inputRefs恰policy、market:台股:2881，其values為d235Week52Position（RATIO）、d235Threshold（RATIO=0.95）、d235DistributionAdjusted（BOOLEAN=1），均EXACT且唯一。D235_HIGH_ZONE_PATH驗新政策與既有計畫政策；RADAR新路徑允許上述四action，原路徑保持原兩action。同輪、同owner、原始source hash、同symbol/market/date及quote freshness照原驗證。
- [x] 633.5 首批 confirmedBatches=0 的新路徑強制禁止small-clear；baseline三分之一向下整張；baseline<3000股則用整股三分之一（仍須該交易單位的原生零股五檔），最少單位不得使首批等於全部持股；不足兩股等不能保留餘量的情況BLOCKED。例如3000股／45萬首批1000股，500股首批166股，6000股首批2000股。後續原D152已確認批數與尾量規則保留，不以建議／未成交掛單增加批數。D152_BATCH保留baselineShares、confirmedBatches、openingValueTwd，另輸出firstBatchSmallClearAllowed BOOLEAN=0供新路徑首批驗證。既有D226小額清零語義不變。
- [x] 633.6 保留可賣量、已交割量、同帳戶實際持股、掛單與linked reservation去重、跨券商同標的同方向成交後五個已完成交易日冷卻、五檔相反側累計深度、股票費稅與D144既有限價例外。無容量／深度不擴量，不因新路徑放寬未知來源。LLM只能逐腿採納／等待／否決，不能本機重算解BLOCKED；沒有自動下單、寄信、observer POST或request-time來源刷新。
- [x] 633.7 同步 SRPP input_facts 獨立驗證器、named_radar_sell adapter、report_evidence_guard 的strategy與receipt白名單。新策略ACTIONABLE需完整14gate及高檔事實/首批非全清證據；竄改門檻、缺失或重複receipt、small-clear、離高、錯symbol/subaccount一律拒絕。最終同輪direct quote及五檔仍驗API固定數量及價格，不本地resize。
- [x] 633.10 同步 BFF `bff/src/main/java/com/steven/assets/bff/publicsrpp/SrppReportResponseValidator.java` 的封閉策略白名單、SELL／2881／CORE_D226 身分、D235 的14 gate集合與逐gate精確refs、D235_HIGH_ZONE_PATH事實及D152_BATCH首批約束；必須接受合法ACTIONABLE及BLOCKED零腿，不得使新策略整包502。既有D226策略與D144窄例外保持原意。BFF驗證與SRPP獨立validator保持相同新契約，未知策略、錯市場/動作/帳戶、缺失或重複receipt、錯refs及首批清零仍拒絕。新增 `SrppReportResponseValidatorTest` 回歸；至少包含完整publisher產物經BFF驗證的ACTIONABLE及BLOCKED包，以及各項竄改。
- [x] 633.11 更新機器契約 `docs/openapi/docker-external-api.yaml` 的D235 strategy、14 receipts、refs及values說明，不新增route。必須依 renderer → identity sync → renderer check → identity check 順序同步資產端兩份Swagger與SRPP managed feature mirror，更新consumer schema文件身分、runtime contract、policy manifest及最終bundle。跨repo renderer/sync前先合法commit其他SRPP來源變更，使worktree dirty僅為工具允許產物，不放寬工具guard。改calculator後由原工具重產formula V3及backend/BFF mirrors、goldens、authority及insert-only registry SQL，所有收據綁最後同一bundle/Swagger/formula身分。
- [x] 633.8 離線回放原始raw只讀，輸出來源路徑與SHA256、stockCode/日期/radar action/distributionAdjusted/week52Position。10/27與11/13、11/16上午高檔HOLD兩態應為高檔資格通過；11/13 Claude上午0.94033399及11/16午盤約0.89應暫停。保留各owner/consumer/slot差異。完整金融回放只能使用留存已驗來源，不補造可賣量／帳本／費稅等；缺必要原始證據記UNAVAILABLE，不以人工合成的PASS宣稱歷史可交易。生效日前資料僅在明確離線反事實 policy 評估中套新規，保留raw原日期及身分，不改正式effective_date。
- [ ] 633.9 測試包含門檻等於0.95與稍低、還原標記缺/false、HOLD/HOLD_CAUTION/EXIT/REDUCE、非法action、首批3000股不全清、後批尾量、深度不足、零股原生book、冷卻第4/5日、linked reservation、費稅、兩路不重複及BLOCKED不回退。再依既有發布鏈重建runtime／bundle、authority／golden／insert-only SQL；雙repo feature commit及main no-ff merge/push/ref readback後登錄精確row，乾淨main rebuild/recreate business-services與bff、兩服務JAR／formula mirror及相關authority逐位元比對、SRPP host 9090三個GET實測。正式日報採用只在合法本輪契約與時窗成立才驗，否則NOT_YET_EXECUTED。

## 驗證

```sh
bash scripts/spec-check.sh
bash scripts/codex-review.sh spec <完整材料檔> 1 <外部scratchpad>
mvn -q -f backend/pom.xml -Dtest=SrppNamedRadarSellCalculatorTest test
mvn -q -f bff/pom.xml -Dtest=SrppReportResponseValidatorTest test
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root <SRPP_FEATURE>
python3 -B scripts/sync-srpp-swagger-identity.py --check --srpp-root <SRPP_FEATURE>
python3 -B scripts/generate-srpp-formula-v3.py --srpp-root <SRPP_FEATURE> --check
python3 -B scripts/check-srpp-report-authority.py --srpp-root <SRPP_FEATURE> --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root <SRPP_FEATURE> --check
ruby scripts/tests/srpp-report-policy-register-test.rb
git diff --check
```

另外SRPP執行相應named sell、formal verifier、report evidence與execution policy測試，以及留存raw回放。依發布skill產生最終release receipts；本任務的檢查不是正式日報採用證據。

## 完成報告

已完成D235政策/正式calculator/BFF與SRPP日報驗證主要實作、原始回放及離線測試。spec獨立審查第2輪PASS，架構獨立審查第1／2／3輪PASS；一般金融契約code review第1／2輪發現首批來源綁定及策略降標驗證缺口，已逐輪修復並補測，最後第3輪PASS，無保留finding。核准批量保持三分之一向下整張，ceil finding為呼叫端prompt誤寫已記錄，未改政策。

目前驗證：backend報告/authority相關311測0失敗0error（2 skip），最後BFF全套1044測0失敗0error（2 skip，包含完整publisher wire與降標反竄改），針對性39測0失敗0error（1 skip）；SRPP針對性90測通過，實際Java retained replay有2個原包BLOCKED、1個原始錯誤保留、10個獨立radar門檻案例。明示synthetic的實際PureSrppReportCalculations→SrppReportPublisher→BFF→SRPP完整獨立verifier兩包，正腿1000股/離高paused0均曾通過；金融第二輪來源修復後，最後eb8222053002afe54a1f42d0daa8832e320a2eb57f3ee1b9edd857e250401c21規則包與ecc3fa55751c01a1583480f0c5b257ded94444689db2f305a5851b35eb38c6bf Swagger的12modules完整兩包已再通過真正publisher→BFF→SRPP full verifier，沒有只用leg_check冒稱整包採用。這些都是離線驗證，不代表正式日報已採用或live可賣。

633.3／633.7與633.11已完成金融最後一輪、最後來源/metadata對齊及完整wire再驗。633.9包含正式Git/registry/deploy，須外部release operator完成實際動作才可驗收，不提前勾選。本任務不寫入自指mainSHA/container資訊而反覆build；完整review、命令stdout、hash矩陣與發布狀態保存在 `/Users/steven/.codex/release-receipts/srpp-d235-20261126/`。

正式來源可用性查證（2026-11-26）：production `rotationExit`／`sellablePositions`／`rotationCooldown` 僅見於正式calculator消費端；`DecisionInputCaptureAdapter`未產生topup-state，`SrppReportExtraCaptureAdapter`對未提供topup-state輸出TOPUP_STATE_CANONICAL_SOURCE_UNAVAILABLE，scheduler沒有新增此owner state producer。本輪不補造可賣量、baseline/fills、帳戶或冷卻來源，也不放寬硬閘。因此正式路徑發布不代表已能產正量；正腿只由明示synthetic離線publisher證實，真實原包仍BLOCKED，正式自然日報採用及此必要owner來源可用性尚未實證。此外現官方portfolio僅提供持有量/成本，沒有settled/sellable欄；已保存orders只有當日configured-owner帳戶，沒有全broker五日coverage/D223批次歸屬；券商設定沒有prospective brokerage/minimum費率schedule。目前pipeline不會自然補齊這些正式來源，現來源下這條路徑應保留BLOCKED。詳 `live-source-feasibility.md`，本輪不擴增owner-state producer。原始唯讀查證與hash收據：release-receipts/srpp-d235-20261126/asset/live-owner-source-audit.{log,json}。
