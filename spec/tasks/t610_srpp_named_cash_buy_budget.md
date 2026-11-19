# [t610] D226獨立00881買方跨日預算

**對應 Requirements:** Requirement 217
**前置任務:** t608、t609
**Liquibase changeset:** 無

## 要做什麼

- [ ] 610.1 新增純SrppNamedCashBuyBudget及測試，依Requirement217完整source contract計跨日400000含費預算，不用spent自報，驗fills唯一/完整日期與amount。
- [ ] 610.2 完整pending與預留依plan/strategy歸屬，精確linkedOrderId去重；未知00881 BUY不猜獨立，linked缺掛單拒絕。已驗D215與SHORT獨立不佔plan但全帳仍佔用。
- [ ] 610.3 整合端新增NAMED_CASH_BUY_D226/00881 BUY lots/CORE_D226_BUY，15gates完整、全部原資金與風險不放寬，D215未20%仍可獨立正量。兩條評完D215正量優先，只本輪候選去重。
- [ ] 610.4 source hash/formula、BFF/OpenAPI、完整consumer fixture與部署由既有整合及release責任人完成。不增外部抓取、下單或日報重跑。

### 自足來源與輸出契約

## Requirement 217：D226 00881獨立買方與跨日預算（Task 610）

D223買方以精確ADD_CANDIDATE及consumer同輪LLM採納獨立評估；不套D215的20%回撤、38元、2881的152元或D174條件。既有STRATEGIC_BUY_GATES新增NAMED_CASH_BUY_D226/00881/BUY，整張lots、subaccount CORE_D226_BUY，沿用全部配置、資金、帳戶、費用、五檔、風險與原交易硬閘，新增D223_PLAN_BUDGET；不新增路由或下單。

跨日400000元含費上限不得重設。正式namedCashRotation完整自2026-11-18至交易日confirmed fills逐筆amount+fees，加上全部有效同plan pending剩餘股數乘限價加剩餘費用、未連結正式預留amount+fees；精確linkedOrderId去重。缺完整owner帳掛單、預留或plan歸屬不猜0。D215與SHORT_TERM_D173已證獨立策略不扣此plan預算，但仍扣全帳資金。兩路徑都評估後D215優先，僅本輪正數候選去重，不把候選當已用額。預算helper只回PASS/UNAVAILABLE/BLOCKED與used/committed/remaining；零已用PASS 400000、額滿PASS 0、超額BLOCKED 0、缺證remaining=null。完整fixture須在回撤不足20%但ADD成立仍有獨立正量，最終risk與LLM仍由consumer採納。

## Requirement 217／Task 610：獨立D226買方預算

純SrppNamedCashBuyBudget.evaluate(policyConfig,planSource,pendingSource,reservationSource,tradingDate)由caller先驗全部外層owner/date/bundle/vector與內容hash；policyConfig為同版named_cash_rotation_d223。planSource={verified:true,coverage:COMPLETE,planId,source_ref,fills:{verified:true,coverage:COMPLETE,startDate:2026-11-18,endDate:交易日,rows:[{fillId,planId,symbol:00881,market:台股,side:BUY,confirmed:true,tradeDate,shares,price,amount,fees,source_ref}]}}；逐筆驗唯一id、日期、正數shares/price/amount、非負fees與amount=shares*price。不得信任spent自報。pendingSource={verified:true,coverage:COMPLETE,orders:[{orderId,symbol,market,side,currency:TWD,planId或strategy,remainingShares,limitPrice,remainingFees}]}；reservationSource={verified:true,status:VERIFIED,coverage:COMPLETE,rows:[{reservationId,symbol,market,action,status:ACTIVE,currency:TWD,planId或strategy,amount,fees,linkedOrderId}]}。同plan精確識別為cfg.plan_id或NAMED_CASH_BUY_D226；已知獨立NAMED_DEEP_DRAWDOWN_D215/SHORT_TERM_D173不扣plan，未知00881 BUY歸屬UNAVAILABLE；相衝突歸屬UNAVAILABLE。精確linkedOrderId必有同symbol/market/side掛單，允許正式reservation strategy補足該掛單plan歸屬，兩側currency皆須textual且精確TWD（不得缺欄位預設），驗證後單筆計max(剩餘pending debit,immutable reservation amount+fees)，保留較大承諾且不雙算；孤立link或跨幣別拒絕，不以partial fill為由抹除較大immutable預留。全域重複id或畸形來源拒絕，缺證不計猜值。

輸出ObjectNode含status、reason、budgetTwd、usedTwd、committedTwd、remainingTwd（canonical decimal字串或未知null）、placesOrders=false、tradeAuthorization=false。PASS僅代表預算計算完整，下游將remaining作maximumDebit上限；仍須全部原買方交易與風險gate。NAMED_CASH_BUY_D226共15gate含D226_BUY_PATH/D223_PLAN_BUDGET與原共同買方gates及SHARED；receipt refs沿完整assets/policy/market:台股:00881/topup-state/report-reservations/classifications，不讓獨立budget helper自行聲稱風險PASS。

內部helper另回accountingEntries=[{id:FILL:<fillId>或PENDING:<orderId>或RESERVATION:<reservationId>,status:FILLED或PENDING或RESERVED,source_ref:原來源或凍結API路徑,plan_id:本plan,stock_code:00881,side:BUY,cash_debit_twd:canonical字串}]。linked只回一筆pending；未知回entries=[]但status非PASS不得當0。整合端僅投影為合法receipt.values數值fact(name=planEntryDebitTwd,subject=entry.id,value=debit,unit=TWD,quality=EXACT,reason=null)，不新增schema外module欄位；consumer直接採API算妥ledger，不本機重算數量/費。

required精確15項為D226_BUY_PATH、QUOTE、RADAR、STRATEGIC_PRICE、ALLOCATION、FUNDING、PENDING、RESERVATIONS、DEDUPLICATION、DEPTH、EXECUTION_ACCOUNT、THESIS、TRADABILITY、D223_PLAN_BUDGET、SHARED_CURRENCY_CAPACITY。D226_BUY_PATH/QUOTE/RADAR/STRATEGIC_PRICE/DEPTH refs={policy,market:台股:00881}；ALLOCATION refs={assets,policy,classifications,fx,market:美股:VOO,market:台股:0050,market:台股:00881}；THESIS refs={policy,market:台股:00881,classifications}；TRADABILITY refs={policy,market:台股:00881,topup-state}；SHARED_CURRENCY_CAPACITY沿現行{assets,policy,topup-state,report-reservations}；其餘refs={assets,policy,topup-state,report-reservations,classifications,market:台股:00881}。STRATEGIC_PRICE不引D215回撤，仍檢現行正式價格/成本。

## 驗證

bash scripts/spec-check.sh；獨立CLI spec最多三輪；Maven SrppNamedCashBuyBudgetTest及既有中期/賣方/BFF測試；完整envelope verifier；正式image重建與讀回由release完成。

## 完成報告

待驗證。

STRATEGIC_PRICE僅執行原政策適用symbols規則；00881不在D188 price_position_symbols/ex_dividend_symbols時原規則NOT_APPLICABLE之PASS，不新增不買高或20%回撤硬閘。完整envelope fixture須含至少一筆planEntryDebitTwd六欄fact經BFF及consumer採納。
