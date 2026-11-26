# [t638] 全部正式零目標股票的跨市場高檔分批退出（D-236）

**對應 Requirements:** 187、193；**前置:** t633、t637；**Liquibase:** 無。本任務採D-236／policy2.78.0／model3.25.0，政策與正式登錄由跨repo發布證明，不提前宣稱已生效。

## 使用者與範圍

使用者明確要求「最終要清0的股票，不限2881，同樣規則適用於全部清0股票」，並確認「2330、00882依現行規則」。本輪保留D-212的2330暫停清零（未知仍豁免）與00882本批五檔可成交最低價嚴格大於16元；不因本路徑高檔解封。不新增券商交易、HTTP route、日報時段、LLM自算批量或來源後備。股票庫存包含ETF及債券ETF，台股、美股皆納評估；銀行存款／基金不是本路徑。

正式零目標以當版model `zero_target_do_not_rebuy.zero_target`明示集合為正本，發布guard必證明與`portfolio/target_allocation_2027.csv`的股票退出標的0%列一致；不是固定11碼白名單。現行11碼含GOOGL、2330、2881及零目標ETF。若`detailed_target_allocation_2027[symbol]`存在，必須是合法數值0；正值／負值／null／畸形均衝突。未列入明示集合者不能因detail缺漏、四捨五入或未分類而當成0；正目標00919等不納新退出。L0實際持有與market/type/owner須核實，不能由請求者自行指定退出標的或申請core subaccount。

## 正式輸出與相容性

- 唯一新strategy `ZERO_TARGET_HIGH_ZONE_EXIT_D236`。台股正式12-module report-calculations在既有`STRATEGIC_BUY_GATES.data.legs`附加本路徑；只評估該包市場允許的股票，台灣包不以台北同日LIVE偽裝美股盤中。美股既有`us-report-calculations`正式包新增`ZERO_TARGET_EXIT` module，data閉合為`coverage,reasonCodes,legs,facts,receipts,missing`，沿用既有canonical leg／fact／receipt型別。當版D236有效模型要求US三module；舊無D236模型的兩module immutable包仍可驗，不以新欄解除舊BLOCKED。
- D236 leg新增`planId`：新strategy欄位必要，ACTIONABLE必非空且對應owner state全域唯一真plan；BLOCKED缺正式plan時允null、不虛構placeholder ID；既有leg保持無此欄相容。symbol、market、broker、subaccount、planId與原始owner state／holding／fill exact綁定；USD leg的amount／fees及COSTS facts為USD，台股TWD，不冒充TWD。TW／US同代號或不同broker／account可多leg；unique鍵為(symbol,market,brokerId,accountFingerprint,planId)，禁止以whole module最多1 SELL的舊2881限制拒合法多標的。
- 新路徑在正式policy啟用且生效、明示零目標的owner core庫存中評估。調整後52週位置合法[0,1]且>=0.95時，HOLD／HOLD_CAUTION／EXIT_CANDIDATE／REDUCE_CANDIDATE均進評估。小於0.95時本路徑BLOCKED/0且具名暫停；缺證UNAVAILABLE不補0。合法高檔選D236後即使其他gate BLOCKED，也不得降為D235／D226或另一strategy清零求解封。低檔原獨立EXIT/REDUCE路徑保留原規則，HOLD不得因此普遍通過。
- 新舊策略同一physical inventory的pending／report reservations共用一次容量；linked reservation不重扣官方pending，unlinked預留仍扣。跨strategy及跨TW/US包的同庫存同輪SELL互斥、blocked新路不回舊路；獨立資產／帳戶不互相誤擋。LLM仍逐腿採納／等待／否決，API正量不等於交易授權。

## canonical來源契約（唯一DTO草案）

TW沿frozen `sources[market:台股:<symbol>]`及sourceVector；US沿既有`sources.market.symbols[]`逐`stockCode`，外層market來源SHA與每筆exact symbol、session、quote／radar／book互綁。新pure calculator使用明示TW/US context adapter，不複製兩套金融算法。所有refs指向同一immutable input；required raw source SHA、sourceId、coverage／validation、capturedAt、owner、日期、policyBundleSha256逐一核對，缺欄不當false/0/PASS。

新增owner來源 `zero-target-exit-state`，由既有capture來源port擴充，沒有正式producer時只能具名UNAVAILABLE，禁止從D223或portfolio總量推導可賣／core attribution。來源外層：

```
verified:boolean, coverage:COMPLETE|NONE, ownerUserId, tradingDate, policyBundleSha256, source_ref,
executionAccounts:[], positionAttributions:{verified,coverage,rows:[]},
plans:[], sellablePositions:{verified,coverage,rows:[]},
pending:{verified,coverage,orders:[]}, cooldowns:[], tradability:[]
```

- `executionAccounts[]`每筆`verified,ownerUserId,brokerId,broker,subaccount,accountFingerprint,source_ref,costsByInstrument`。key `${market}:${symbol}`的cost schedule逐security驗證，不共用台股股票稅給ETF／美股。帳戶身分由canonical custody／attribution提供，不是新plan自稱core。
- `positionAttributions.rows[]`：`holdingId,symbol,market,brokerId,accountFingerprint,subaccount,coreShares,protectedShares,source_ref`；唯一holdingId，core+protected須等於同L0實際holding數。protected含獨立短線／其他保護份額，不得重新貼標成core。每筆core數量與已核實snapshot/live inventory、account一致，全部holding coverage完整。
- `plans[]`：`verified,planId,symbol,market,brokerId,subaccount,accountFingerprint,source_ref,instrument,baselineSnapshot,fills`。planId在同owner來源全域唯一；同實體／用途只允一個active immutable plan，不能換planId／baseline洗首批或冷卻。`instrument={verified,securityType,regularLotSize,minimumShareStep,source_ref}`，securityType只COMMON_STOCK/EQUITY_ETF/BOND_ETF，與canonical分類STOCK/BOND相容。
- `baselineSnapshot={id,snapshotDate,stocks:[{id,stockCode,market,brokerId,coreShares,currentValueTwd}]}`：id唯一，固定起始核心庫存，不使用當前餘量當新baseline；可驗原起始TWD市值，缺原市值不可使用小額例外。`fills={verified,coverage,rows:[{fillId,batchId,planId,confirmed,side,symbol,market,brokerId,accountFingerprint,tradeDate,shares,source_ref}]}`：fillId唯一、真confirmed SELL、來源計畫／帳戶／期間 exact；distinct batchId計數，baseline coreShares減confirmed shares必等於目前core attribution，不可跨plan借成交增加首批。
- `sellablePositions.rows[]`：`holdingId,symbol,market,brokerId,accountFingerprint,settledShares,sellableShares,source_ref`；官方已交割與可賣不得超實際持股，不能以shares代settled／sellable。pending orders沿既有`orderId,side,symbol,market,brokerId,accountFingerprint,remainingShares`，真空集合須verified COMPLETE。`report-reservations`沿既有canonical owner來源，所有同physical inventory active SELL與linkedOrderId逐筆唯一綁定，不依strategy逃漏。
- `cooldowns[]`：`verified,coverage,scope=OWNER_ALL_BROKERS_SAME_SYMBOL_DIRECTION,symbol,market,side=SELL,coverageStart,coverageEnd,rows,source_ref`。rows同confirmed fill身份，涵owner全部broker同symbol／market SELL，覆蓋本市場至少五個完成交易日到本輪；plan fills落在window須全被此來源包含。最近成交後至少五個本市場完整交易日才可下一批，今天成交不能重啟plan洗掉。
- `tradability[]`：`verified,symbol,market,status,source_ref`；官方禁止即BLOCK。缺官方來源沿既有D144 fallback只許best-level限價且仍所有其他gate，不把缺來源當正式允許。長債00679B／00751B淨款維持BOND_ROTATION_RESERVE用途，不能輸出為一般可用現金／迫使買腿。

### 明確book／calendar／2330 raw proof

- TW `market:台股:symbol.book`完整欄位`available,stockCode,market,marketStatus,source,sourceTime,fetchedAt,levels`，5筆levels每筆`bidPrice,bidVolumeLots`；regular volumeUnit為LOTS。oddLotBook另需`bookType=ODD_LOT,volumeUnit=SHARES`，levels用`bidVolumeShares`。book metadata與frame symbol/market／時區session一致、available=true/marketStatus=OPEN，不從一般boardLot book推造odd depth。
- US `sources.market.symbols[]`每row的`book`採同identity/time欄位，`bookType=REGULAR,volumeUnit=SHARES`、5levels為`bidPrice,bidVolumeShares`，其stockCode對應row.stockCode、market=美股。現US capture不具有原生book producer時，明示available=false／UNAVAILABLE並保存原缺口，不能取bid/ask單價當五檔或新打未發布API。
- TW完成日直接用同market body的`completedSessions`、`calendarVerified`、`calendarCoverageStart/End`；日期唯一遞增、都在當日前，coverage涵至本輪前一日且含至少5個最近本市場完成日；當日開市證據另由同輪quote/radar/tradingDate驗證，不要求completed coverage把未完成今天列入。US用既有`calendar.days=[{date,us:boolean}]`連續逐日權威旗標以及`calendar.session={timezone,open,close,calendarVersion}`，不得weekday猜交易日；取true且<sessionDate作完成日，含五日；COOLDOWN US inputRefs必含calendar來源SHA。來源unknown或coverage不全不能自行把missing當休市。
- 2330保護raw proof為`zero-target-exit-state.protections.tsmc={aiEvidence,catchUpEvidence}`，格式精確沿既有`scripts/correction_dip.py`的`ai_cycle_status`／`tsmc_catch_up_status` request evidence，含dated named sources、signals、公司減資本支出與競爭事件／事件前收盤和最新收盤及同事件歸因等原欄位。依當版D212模型門檻、來源時效與既有函式重新演算，不信plan waiver旗標；waiver為`ai_status!=PRESENT AND catch_up_status!=PRESENT`，兩者均缺／UNVERIFIED仍暫停，任一有完整PRESENT證據才解除。Java做pure同契約移植，SRPP reuse既有函式，新的formula來源須納correction_dip.py；不新增新聞抓取或虛構證據。

### 市場證據與費稅

- TW使用TW交易日曆、當地有效session／精確雷達／本輪LIVE quote及台股既有freshness；US使用America/New_York本session date、既有US session open/close（含DST）與US quote freshness，不套09:05／12:00 TW低量老價例外。休市／盤外／非本輪／過期／future timestamps皆不得正量。
- 每個market body需exact symbol/market，radar stock相同、dataComplete、supported ruleVersion、generatedAt本輪新鮮；distributionAdjusted必真且week52Position為原已發布調整來源，不自行用未還原區間重算。交易前直接final quote/radar/book仍必重核>=.95與四action，離高檔立即暫停，不以frozen PASS蓋過final資料。
- 買方五檔必完整5level、價格有效且由高到低、同本輪／市場／native units。TW regular `bidVolumeLots*1000`只對regular lot市場，odd book必ODD_LOT/SHARES；US五檔原生SHARES，不能乘1000。price grid依COMMON_STOCK/ETF/US market原規則，不能stock tick套ETF。整股US native step1；fractional／非整數庫存若無官方native fractional能力與深度，仍產具名BLOCKED／UNAVAILABLE評估，不假稱支援正量。00882累計至本批股數的最差成交bid及最終限價均嚴格>16，16與更低拒絕。
- Costs每instrument需`verified,coverage=COMPLETE,symbol,market,currency,securityType,source_ref,effectiveFrom,validUntil,brokerageRate,minimumFee,sellTaxRate,regulatoryRate,regulatoryPerShare,minimumRegulatoryFee,maximumRegulatoryFee`；cap欄必存在、null明示無cap，其餘缺/null非0。數值有限非負，rates<1，schedule有效日涵session，currency僅TW/TWD或US/USD。真零稅／零監管須有明示0及來源，不用TW stock0.003／ETF0.001填US，BOND current免稅不能拿future assumption當當前。
- 佣金=max(gross*brokerageRate,minimumFee)、稅=gross*sellTaxRate、監管=max(gross*regulatoryRate+shares*regulatoryPerShare,minimumRegulatoryFee)再套明示cap；各向上至2小數，總fees=sum，net=gross-fees>0。若需要TWD換算另需同輪正確方向FX；本USD SELL僅輸出USD，不自行換算或用未成交淨款增加buy capacity。

## 數量與typed receipts

唯一15 gates：`D236_HIGH_ZONE_PATH,D236_TARGET_ZERO,QUOTE,RADAR,POSITION,PENDING,RESERVATIONS,DEDUPLICATION,CORE_RETENTION,D152_BATCH,COOLDOWN,DEPTH,EXECUTION_ACCOUNT,COSTS,TRADABILITY`，每項exact一次，不接受用舊path receipt或任意ALL PASS代替。gate未通過正量必0／amount/limit null；僅原D144具名TRADABILITY UNAVAILABLE窄例外可維持其他gate全PASS後的best-level限價。

| gate | 必要typed facts（subject=leg.symbol, quality=EXACT，按name唯一後核subject/unit） |
|---|---|
| D236_HIGH_ZONE_PATH | d236Week52Position/RATIO；d236Threshold/RATIO=.95；d236DistributionAdjusted/BOOLEAN=1 |
| D236_TARGET_ZERO | zeroTargetDeclared/BOOLEAN=1；targetWeight/RATIO=0（是明示集合的正式0，不是把missing detail補0） |
| D152_BATCH | baselineShares/SHARES；confirmedBatches/COUNT；firstBatchSmallClearAllowed/BOOLEAN=0；nativeShareStep/SHARES |
| POSITION | settledShares、officialSellableShares、pendingSellShares、unlinkedReservedSellShares／SHARES |
| COSTS | sellCommission、sellTax、sellRegulatoryFees、netProceeds／leg.currency |

高檔與quote/radar/depth refs=`policy`+exactmarket來源；zero refs=policy/assets/classifications；其他owner gates還需zero-target-exit-state/report-reservations。US exactmarket來源ref是`market`並核內symbol，不創不存在market:美股ref。

首批禁止D152 small-exception一次清零，固定immutable baseline三分之一向下至native step，至少一native step但必須小於現core remaining；不足以留下餘量則BLOCK。後續沿D152至少3批／經證明小額例外規則（只有首批禁例外），USlot_size=1原生整股，TW原1000整張／必要odd step1。令pendingAll與unlinkedReservedAll為同physical inventory全部策略的相關承諾（linked只計pending一次）。本輪不新增可令承諾逃離core上限的caller bucket：無論自稱core/protected，均保守扣core；`coreUncommitted=max(0,coreHeld-pendingAll-unlinkedReservedAll)`，`totalUncommitted=max(0,settled-pendingAll-unlinkedReservedAll)`，`容量=min(coreUncommitted,officialSellable,totalUncommitted,matchingBookDepth,其他已驗原保護容量)`。兩個剩餘量是交集上限，不把它們相加；未知／缺account歸屬不能當0或跳過相關承諾，應UNAVAILABLE。floor不能擴量。第二／三批只認原plan確認成交批數，不能把掛單或LLM採納算confirmed；最後批才含餘量，缺深度留餘股。2330 waiver未解除（含UNKNOWN）本路徑零量。

## 任務與驗收

- [x] 638.1 完成requirements/design/自足task、collision scan、initial spec-check及獨立localCodex spec review≤3輪，每輪major修完；初gate後才改implementation。
- [x] 638.2 SRPP policy／動態helper／TW+US consumer+final adapter同步，對當版model/CSV target、raw baseline/fills/fees/source-binding獨立驗證；不只相信Java receipt。D235/D226相容路由與scope dedup同步，舊非本target資產不受新policy阻斷。
- [x] 638.3 Java共用pure calculator、TW/US frame adapter與publisher/capture UNAVAILABLE來源、dynamic modules，BFF TW/US closed schemas／rawbinding／route／多leg去重，明示planId；不新增券商或HTTP取數路徑、不捏造live owner producers。
- [x] 638.4 跨repoOpenAPI/Swagger與consumer identity完整同步；formula原generator來源列表必加入新Java calculator/context helper、BFF若列管及Python zero-target helper，所有mirror/formalprojection/golden/SQL按工具鏈重產，不漏新金融來源。
- [x] 638.5 測完整TW單股/ETF、US GOOGL正量與blocked整包、same symbol多account/two symbols多leg、跨old/new降標拒；zero unknown/positive/null conflict、.95 inclusive/.9499 pause、adjustment false、四actions、first-batch small禁清／第2+confirm batches／baseline換plan／fill重複／pending/reservation linked去重／allbrokercooldown5sessions／TWodd-USshares-unit互換／TW-US時間DST/fee/taxcurrency串用／缺官方來源，並驗混合庫存core300/protected600/settled900/sellable600/pending核心300時coreUncommitted=0不可再提100，已知與未知protected歸屬、linked只扣一次，Javapublisher→BFF→SRPP一致拒超用核心；並驗2330 UNKNOWN waiver與00882 worstbid16/16.01邊界；既有其他策略回歸。
- [x] 638.6 本機Codex獨立跨repo一般code及arch≤3輪，每輪major修完，第三輪修後未複審如實記；完整raw stdout/errors/handled留外部收據。真正Javapublisher包→BFF→SRPP fullverifier，不用只手造Python legs互相通過。完整source與所有generated/task定稿後執行最後spec3/gate；此checkbox只記已完成的code/arch三輪修復與整包驗證，最後spec/gate結果留外部收據，不在通過後修改tracked文件。
- [ ] 638.7 全部通過才交operator兩repoGit/no-ff/push/readback、insert-only registry、雙service重建及9090host維護實測；自然TW/US日報採用與live source availability另列，不用維護PASS/合成正量宣稱實盤可交易。無任何券商API下單或mail送出。

## CORE_RETENTION共用股票floor與原D222完成度

本節固化初審後已核准的既有政策延續，並非新固定40%門檻。所有運算division採DECIMAL128（34位、HALF_EVEN）、canonical strip trailing zeros；不另加epsilon。T=canonical L0 `assets.liveAssets.liveTotalAssets` TWD；E=所有resolvedStockClasses為STOCK的stock liveValue及STOCK基金currentValue，基金以holdingId分類優先，名稱fallback必唯一，不把BOND計入E。原policy `rotation_equity_floor_d141`：至2027-06-30採rotation floor；2028-07-01起普通stock band下限；中間期依原managed rotation完成度選40/50，缺完整來源不能猜false取得40優惠。BOND_ETF退出不減股票room，淨款仍BOND_ROTATION_RESERVE。

中間期唯一raw proof為state.`rotationCompletion={verified,coverage,ownerUserId,tradingDate,policyBundleSha256,scope:FULL_MANAGED_ROTATION_SUBSET_EXCLUDING_VOO_SGOV_24B2,source_ref,completionSnapshot,targetAuthority}`；`completionSnapshot={snapshotDate,assets,classifications,subaccounts}`，snapshotDate不得未來，L0 snapshot IDs/owner/classes需真可核。`targetAuthority={source_ref:"policy",rawModelBytes,marketCandidates}`，rawModelBytes必逐bytes等當輪frozen policy，不信caller自製typed targets、tolerance或completed bool。完成50須原D222 reserve selector/project、當版managed targets與原容差重新演算；VOO/SGOV/24B2及其原bridge alias、cash/deposit/emergency buckets不偷納managed securities。當日同snapshot明示zero退出庫存仍>0可證ANDfalse而採40，不需要無關candidate；歷史false不可證當前未完成，歷史完整完成事件可沿原sticky-completion語義。

00865B emergency/strategic需canonical raw `completionSnapshot.subaccounts={verified,coverage,ownerUserId,tradingDate,policyBundleSha256,source_ref,rows:[{holdingId,brokerId,accountFingerprint,symbol,market,subaccount,shares,valueTwd,source_ref}]}`。每physical holding的EMERGENCY_CATHAY及STRATEGIC_FUBON分帳shares/value exact reconcile原L0，不從broker名猜role。managed00865B actual僅總值扣明示emergency，不能把emergency併入strategic .025 target或憑null當0；缺此必要source則UNKNOWN。現有capture的subaccounts只是UNAVAILABLE Evidence，不宣稱已有完整producer。

同owner所有市場/broker待賣與ACTIVE unlinked reservations，加本輪其他positive equity SELL，先各扣一次共用room；canonicalreservation沿既有`action=SELL,status=ACTIVE`。linked只可exact SELL/symbol/market/broker/fingerprint/order、sumlinked不超officialremaining；未知帳戶或必要估值不能忽略。m=同L0 holding TWDliveValue/shares；USD nativeMark=m/L0exchangeRate，nativeUpper=max(委託limit,同輪有效quote,bestbid,nativeMark)，USD轉TWD upper再乘fx.sellRate。debit(q)=q*m+f*max(0,q*upperTwd-q*m)，不以未成交費用或預期買腿增加room。全部既有承諾先產prefix E'=E-sum(mark)、Tupper=T+sum(positive gain)、room=E'-f*Tupper；D236按position降冪、coreExcessTwd降冪、symbol/broker/accountFingerprint/subaccount/plan穩定排序，逐腿共用/消耗room，不按帳戶或市場重置。CORE投影facts含此前prefix。

US股票floor需原`fx` LiveRate raw：pair USD/TWD、baseUSD/quoteTWD、date、buyRate/sellRate、source、polledAt/sourceUpdatedAt、liveUpdateStatus ACTIVE、quoteStatus LIVE；rate正且buy<=L0valuationRate<=sell、timestamp不未來/<=5min且同台北日，vector/hash VERIFIED。FX缺證只阻相關US股票腿，BOND不借此擋住。CORE_RETENTION typed facts為`portfolioEquityBeforeTwd,portfolioTotalBeforeTwd,sharedEquityRoomBeforeTwd,equityDebitTwd,projectedEquityAfterTwd,projectedTotalUpperTwd`（TWD）、`applicableEquityFloor`（RATIO）；US股票另`floorFxUpperBoundTwdPerUsd`（RATIO），subject仍leg symbol、quality EXACT/name先唯一。US該CORE inputRefs加fx，BOND真plan不依賴fx，missing-plan blocked仍保留必要ref且scope可null。positive CORE scope僅STOCK_ROTATION_RESERVE/BOND_ROTATION_RESERVE。

D144缺官方tradability時只緊限價到best bid，原定批量不減；若最終best-limit可執行深度不足即BLOCKED，不能自動縮量逃條件。原生TW regular量整張，odd單腿必<1000；後批2500餘量先regular2000，再後續可評估odd500，不把2500包成一筆odd。nativeShareStep為D152 rounding step，非book mode；US只SHARES原生1。任何帶manifest bundle marker的驗證先核canonical seal==marker==externally fixed expected policy identity，不能先看D236字樣才驗，雙decision降版亦不得以重簽caller資料洗成舊兩module；真舊immutable identity原bytes保持可驗。

## 驗證指令與收據

`bash scripts/spec-check.sh`、`bash scripts/codex-review.sh spec <材料> <1-3> <external_receipts> [handled]`先行；原formula/authority/golden/Ruby工具與相關Java/BFF測試沿現有工具，新增cross-market positive/blocked fixture測試於實作時明確命名並保留完整stdout。新策略資料需更新OpenAPI/docs及renderer→identity→兩check，SRPP clean source phase commit guard不得繞過；source／生成物／Git／registry／容器／9090／自然日報是不同證據。

統一收據：`/Users/steven/.codex/release-receipts/srpp-d236-zero-target-exit-20261126/`。實作、生成物、第三輪code/arch全部必要修復及測試已完成；最後spec第3輪PASS（0Critical／0Major／1Minor）；Minor為三處US新舊module描述，已限定修，metadata鏈重對齊後記最後gate，不送第四spec。最後兩個獨立code/arch判定仍為CHANGES_REQUESTED，第三輪去重8項已修並完成反例/整包驗證，依CLAUDE上限不送第四輪，未再獨立複審。正式Git/main/registry/雙service部署/9090由operator在gate後完成並另存外部收據；不宣稱live正量或自然採用。user已授權完整發布；主控負責協調sourceclean phase和最終operator。


## 最終實作與驗證範圍（送最後spec3版本；發布事件另留外部）

- 待發布策略契約為D236/2.78.0/model3.25.0；原入口/27金融routes不增HTTP通道，US僅加第三ZERO_TARGET_EXIT模組；OpenAPI1.31.0與三Swagger mirrors一致，官方US33defs transitive closure三鏡像exact。來源model及manifest按原bytes同步，formula新增zeroTargetHighZoneExit與18必要Java/Python/BFF依賴；V1/V2原身分保持，正式policy/goldens/insert-onlySQL按原generator生成。正式bundle/hash由外部最後authority receipt查證，不把Git mainSHA/container回寫本檔造成自指重建。
- 必要sourceVector coverage只精確market composite可PARTIAL/VERIFIED，仍逐腿核quote/radar/nativebook/calendar全部必要raw條件；assets/policy/ownerstate/reservations/classifications/必要FX/calendar等非market來源必COMPLETE/VERIFIED。原本缺NAV/其他symbol不得連坐有效市場腿，必要owner來源缺證仍不得放行。
- 股票floor上界同時保守納入同輪提供的原生regular/odd book best（本腿適用book必已通過身份/單位/深度/時效），不漏零股較高best而低估debit。linkedOrderId空/null為unlinked；非空必有sameidentity官方SELL，允多row累計但sumlinked不得超remaining，pending仍只扣一次。歷史任何managed target偏離均不能證當日false取得40，所有incomplete出口都需同日；完整歷史完成才可sticky50。
- frozen API radar沿原package最長1h且當日身份一致；final direct radar最長5min，其他同輪quote/books/sellable/owner時效硬閘維持。完成日TW coverage涵昨日/最近五日且列日均落範圍；US當日唯一us=true並對齊session，不信weekday猜測。
- LLM候選必要集合從已驗不可改的原始d236_package推導，不能刪adopted copies/evaluations/candidates或原包求空集合PASS。current-bound成功API證據必保留原包/identity/content來源；每原腿含BLOCKED保留具名ADOPT/WAIT/VETO及evidence_ref，WAIT/VETO不得靠省略腿。正量ADOPT還要原context、固定API量價及最新官方owner/radar/book重核，不本地補算解BLOCKED。
- 獨立code3原文6Majors、arch3原文5Majors（去重8）保持原判定；第三修後未再獨立複審。所有對應修復/原始stdout/機械反例與停止規則在external third-handled與verification matrix，不能冒稱獨立PASS。必要驗證：source252tests/9checks；Java196相關tests及另1opt-in原三日回放；BFF1101全套與最後四包50相關wire；最終4個真Javapublisher→BFF→SRPP fullverifier，TW/US正量各300或paused0，另16完整audit/adoption/fixed-order案例。全部是明示synthetic或離線診斷，不等同live下單/自然報告採用。
- 留存13原檔SHA/time不變，10/27原503保持無法評估；11/13、11/16原TW packages用正式generic Java calculator做明示當版policy-input-only診斷（effective仍11/26，不改日期/報價/其他owner來源），各原heldzero評估全units0，before-effective及真generic ownerstate缺口保留。純門檻/其他市場歷史UNKNOWN另列，不把舊D235日期overlay改標D236，不造歷史正量。
- 真D235 US2自然原包未找到；使用原c9版UsReportPublisher+原D235 authority/Swagger產出明示synthetic legacy2原包，產後rawbytes不重簽，以外部固定舊identity通過新BFF；同包對新current identity拒。原D235 TW12包及D209自然US2留存另列，不能互相冒稱。D174 model/corePython/coreJava本輪不變、semantic watch指紋不變，舊semantic state normalize保留原high/date/evidence，不重設既有觀察或成交/冷卻。
- **正式來源限制仍存在**：canonical zero-target-exit-state無live producer，既有official inventory沒有完整settled/sellable、allbroker五日成交/plan batch attribution ledger及prospective fee schedule；US native SHARES五檔來源未正式生產，US新FX/owner port仍UNAVAILABLE。正式capture誠實保留缺口，不從L0持股或D223借值、不造分帳/成交；僅相關腿等待，舊US兩模組及其他TW腿依其原來源獨立。自然TW/US日報採用與實盤正量均尚待，部署HTTP/健康/合成PASS不能替代。

最後spec/gates與正式發布結果以統一external release receipts為準；本輪finding必要修復、生成物定稿並記錄最後gate後，不再補tracked勾選、SHA或部署紀錄。
