# [t582] D-215具名深回撤、固定前高與美元資產匯率偏好

**對應 Requirements:** Requirement 205（SRPP具名52週回撤免止穩及非長期首批固定前高觀察）
**前置任務:** t542、t581
**Liquibase changeset:** 無

## 背景

雷達V27已對0050／00881近52週完成高點回撤>=20%、美股GOOG／GOOGL／MSFT／AVGO／NVDA／AAPL／AMZN>=30%提供MEDIUM免止穩積極分批，但SRPP UsTacticalCalculator的independentGate及fullChecks仍各自硬擋RISK_OFF／MA200／熊市與非空stabilizationEvidence；不能只改文字。Steven本輪確認：非長期部位回到首批實際買進時的52週前高，可評估反手賣出；0050／00881原長期核心不適用。

## 要做什麼

- [x] 582.1 僅明確D-215 enabled政策且exact美股七碼、MEDIUM的可信52週深回撤>=30%啟用窄例外。新增純helper或同等方法，在independentGate與fullChecks共用；普通標的不變，不新增API／公開DTO欄位／Swagger身分。
- [x] 582.2 既有US_TACTICAL_BULL_DIP正式收據body承載namedDeepDrawdownD215；fact固定為status AVAILABLE、horizon MEDIUM、stockCode、market 美股、windowStart、completedAsOfDate、highDate、high、referencePrice、priceBasis STORED_PRICE_ONLY、completedPriceHistory嚴格降序列{tradingDate,high,close}、droppedDates日期陣列、droppedUndated boolean。先驗既有sourceId／source／sha256／capturedAt；完成日D<同輪sessionDate且必須等於當輪canonicalRadarGate為PASS之同標的雷達dailyCandle.asOfDate（typed欄位缺失／不符或canonical閘門未PASS即不啟用例外），首列為D、windowStart=D.minusWeeks(52)、至少邊界列<=windowStart，閉區間重算最大H與highDate，正高價／close且high>=close、日期不重複／倒序／未來／live；窗口已知droppedDates或droppedUndated拒絕，不能因自然假期猜缺日。P精確等於dip.currentPrice及item.quote.last；以P*100<=H*70判斷，不解析reasons或只信旗標。重用現有pure RadarCompletedPriceDrawdown，不宣稱跨度證明逐日完整、STORED_PRICE_ONLY為官方split或總報酬價基。
- [x] 582.3 合格者只免RISK_OFF／MA200方向／熊市門檻與stabilizationEvidence，仍驗市場來源／正數價格均線／market/session identity；不需完成日收紅／KD轉強。後續批次只免notAveragingDown、保留thesisStillValid／三批上限／同向最終雷達／基本面與估值／淨15%／成本／即時價／獨立交叉價／資金底線／交割去重等原檢查，不能先全域刪除阻擋。來源不足不視為豁免。
- [x] 582.4 GOOG和GOOGL在單公司0.75%成本／三批與pending公司額度採共同company key；逐股類的quote／H／P仍不得混用；單批0.25%、全部戰術2%保留。既有非Google行為與舊SYNTH fixtures不變。
- [x] 582.5 台股0050／00881保持核心target／caps，沒有新建短線額度或核心出售觸發；SRPP共同規則／保存證據工具實作exact20%免技術方向判斷，其他買進來源與D173照舊。固定前高觀察工具只有確認非長期實際lot、tradeId／firstFillId及首批H來源才能SELL_REVIEW；H不得隨52週窗下修，未持有／核心／未成交建議不能觸發，無股數／金額／交易授權。不新增production tactical sell端點或自動ledger，不借此繞過已有API BLOCKED。
- [x] 582.6 新tests驗30%上下界、普通標的、錯碼／market／horizon／價基、窗口跨度／排序／缺高／live／tamper／舊hash、P綁定、新鮮收據但整段歷史過期／刻意截掉較新完成列、RISK_OFF+MA200下行+無止穩正向、論點／資金／淨利／精確雷達反向否決、後續較低價仍受三批與公司上限、GOOG／GOOGL共同成本與pending；維持原eight source/check IDs與consumer驗證。
- [x] 582.7 同步SRPP D215／政策2.63.0來源bytes、manifest、正式policy document、golden與insert-only registry SQL；純離線檢查不寫DB。獨立spec審查後實作，完成架構／功能審查與限定單元測試／離線build。雙repo commit→no-ff merge→push→refs readback後，才正式登錄與exact row回讀，再從乾淨main與原.env只build／force-recreate business-services。此核准發布順序優先於一般feature先部署規則；不提前以未合併feature替換正式image。
- [x] 582.8 SRPP當輪主機驗9090日曆／quote／市場技術與exact新bundle業務API；正式交易日時窗外明記NOT_YET_EXECUTED，409 OUTSIDE_REPORT_WINDOW不是正式採用成功。US adapter既有formalSources={}仍為缺資料，不注入假行情或誇稱取得全部正式來源；保留無關容器／排程／通知設定，不手動producer／寄信／下單。

- [x] 582.9 同輪使用者追加美元資產匯率偏好：quoteCurrency=USD的既有合法宇宙股票／ETF買進，另含已證實台幣交易BOND_ETF（D130與緊急00865B也適用），current美元買入成本sellRate>32.8（嚴格）或近2曆年同源sellRate區間位置>=90%時優先WAIT／HOLD_CAUTION；不是全報告停止、不取消既有券商定期定額、不把>33原半速規則當自動放行。ETF只在本身完成52週價格回撤嚴格>10%（P*100<H*90）豁免此FX偏好；個股>=30%（P*100<=H*70），恰10%ETF不例外。必須具來源驗證instrumentKind=STOCK／EQUITY_ETF／BOND_ETF，UNKNOWN無例外；instrumentKindComplete本身不是證據，NAME_RULE／CODE_RULE／MARKET_DEFAULT及未附證據OVERRIDE都不能驗證，不用代號後綴／名稱猜分類。允許原始官方typed metadata（精確market/code、型別、來源原文hash）或本版明確Accepted之instrument fact map（精確market/code/type＋既有已核准政策來源及其bytes hash）作為來源；map不擴宇宙／買進資格，未知者維持UNVERIFIED。這只放寬FX，原具名0050／00881>=20%及七個美股>=30%的MEDIUM免止穩集合不變。
- [x] 582.10 新typed UsdFxPurchaseContext包含quality、quoteCurrency、instrumentKind及verified、currentRate/currentAsOf、同源2年history range、source/provider與不可用原因；StockInput加到32元件但保留V27的31參數及所有相容形狀。FxContext追加typed range且保留原2參數constructor及5年MIDpercentile分數；不把新range塞進舊fxPercentile欄位。production每radar request可增加一次canonical persisted FX cache唯讀，禁止vendor／refresh／寫入。當前只接受真正LIVE、時點不在decisionInstant後且通過既有freshness的銀行sellRate，不以INDICATIVE／HISTORY／STALE／LAST_AVAILABLE／歷史fallback當LIVE。
- [x] 582.11 2年區間重用既有5年歷史讀取，以當前LIVE時點的台北日期為windowEnd，start=windowEnd.minusYears(2)，D為台北17:00時鐘截點內最新qualified native銀行日期（17:00前截點為前一曆日），原TW日曆完成日只作最低新鮮度，D不得落後它而不能以TW休市截掉銀行原生列；裁[start,D]之sellRate low/high（D<=windowEnd），當前LIVE不混入歷史極值、完整跨度邊界、日期／正值／可證明同即期賣出價基／max>min；當前與歷史皆BANK_SELLING_SPOT美元買入成本，不能mixmid／buy／cash_sell與spot_sell價基。以精確交叉乘法判90%，不以捨入值判邊界。當前來源不合格或跨度不足只具名觀察，不能用公開1年history替代；FX新2年資訊用既有reason/risk揭露而不新增公開DTO／端點／schema。原5年midscore數值保留，文案明示歷史分數而非新偏好硬否決。
- [x] 582.12 RadarCompletedPriceDrawdown對所有美股建立可用原始價格窗口供FX例外，但selected／threshold／Context.eligible仍保留原具名集合；增加或等價純方法判任意stock>=30／ETF>10，只改FX分支，SHORT／SWING／非具名一般買方仍守原止穩、MA、KD、基本面、溢價及evidence品質。production／LOCAL cache與回測既有兩個StockInput接線相同asof，不拿未來LIVE回灌歷史；歷史若只能以同日歷史sell做代理須明示diagnostic proxy或不可用，不宣稱實盤current已驗證。
- [x] 582.13 以確定性tests覆蓋2年90%精確邊界／>32.8嚴格邊界、source/basis/provider/跨度/currentfreshness缺口、STOCK30%與ETF10%嚴格差異、未知type、不擴免止穩與SHORT／SWING、其他幣別不變、list/detail/cache/backtest同源、不看未來。行為身分升TW_RULES_V28及compatibilityV28，通知升版首輪只baseline零寄信；OpenAPIexamples／Javadoc／必要version與兩mirror／SRPPmetadata在語義凍結commit後按受控renderer→sync→check鏈更新，再雙repo完整發布，禁止在dirtySRPP語義尚未落地時繞過metadata guard。

- [x] 582.14 修正已知來源限制：exchange_rate_history無provider／價基，且fetcher歷史優先cash_sell、LIVE為spotSell、可能混Yahoo中間價；buy!=sell不足以證明SPOT_SELL。沒有額外可信價基原文且綁定window/rows時production2yr range一律UNAVAILABLE，明示legacy原因；不捏造同provider／同銀行證明，不做DB新增／回填／外呼。OR短路：合格LIVEcurrent>32.8獨立HIGH，range缺失仍可由verifiedtype+ownDD評估FX例外；<=32.8且range不可用=UNKNOWN，不得NORMAL。測mixed-provider／cash-spot／中間價退回及來源未知；provider未記錄本身不另創禁買條件，真正問題是價基無法證明。
- [x] 582.15 型別verified只允許官方typed原文，或本輪Accepted之精確market/code/type instrument fact map且綁既有已核准政策來源bytes hash；profile.instrumentKindComplete及NAME_RULE／CODE_RULE／MARKET_DEFAULT／無證據OVERRIDE均不足。增加complete=true但名稱／預設來源不能FX豁免的反例；既有合法宇宙以外即使map有項也不變成新候選。TWD債券ETF依明示納入美元資產且ETF>10%例外，不要求另填underlyingUSD；台幣股票ETF不納入。D130／緊急API正腿只是原公式ceiling，報告mandatory FXhelper可採WAIT；零腿／BLOCKED不能反轉。

## 驗證

```bash
bash scripts/spec-check.sh main
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='UsTacticalCalculatorTest,SrppReportShortUsCalculatorTest' test
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/d215-deep-drawdown/SRPP --check
ruby scripts/render-9090-openapi-docs.rb --check --asset-only
```

合併／登錄後，從正式main且使用原正式.env執行，只重建必要business-services：

```bash
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

保存image/container、健康與JAR authority／registry exact hash證據。fixture pass不等於正式日報已採用；無合法時窗列NOT_YET_EXECUTED。

## 完成報告

2026-11-14已完成實作、獨立審查、兩專案no-ff merge／push、registry、三服務部署與精確hash業務回讀。固定H僅為可選退出觀察；正式交易日時窗採用NOT_YET_EXECUTED。完整證據：[發布驗收](../../docs/reviews/t582_t583_d215_fx/release-20261114/README.md)。
