# [t581] 具名標的52週價格深回撤的積極中期分批累積

**對應 Requirements:** Requirement 204（TW0050／00881回撤20%、US六公司七碼回撤30%的積極中期機會）
**前置任務:** t565、t575、t576、t577、t579、t580
**Liquibase changeset:** 無

## 背景

使用者明確指定台股僅0050／00881在近52週高點跌至少20%、美股六家公司GOOGL／GOOG／MSFT／AVGO／NVDA／AAPL／AMZN跌至少30%，希望依3~6月資金安排積極分批加碼，並已選擇「跌深即列積極分批加碼，允許尚未止穩」。V26只有普通順勢BUY/ADD及小額TRIAL：普通買進需MA20/60 ABOVE與分數，trial要求完成日止跌／KD轉強並受V25 stabilized控制，因此會把這個明示偏好封死。

既有week52High實際取240根、盤中241根含live；maxHigh缺high時以close補值，且價格經現金股利還原，不能直接作本次52曆週「股價回撤」峰值。StockInput現有30元件沒有code／market／peak，需要顯式typed來源。t580已修正最終動作分歧，交付main80bf5b02；本任務保留它並獨立發布V27。

## 要做什麼

- [x] 581.1 固定封閉政策集合與精確門檻：market="台股"且stockCode=0050或00881，回撤>=20%；market="美股"且stockCode=GOOGL／GOOG／MSFT／AVGO／NVDA／AAPL／AMZN，回撤>=30%。其他市場／股票、006208、名稱或GROWTH相似者不加入。GOOG和GOOGL分讀各自股類。僅MEDIUM（既有1月~6月欄位）改行為，理由標示此次3~6月資金意圖，不改全站持有期名稱，不擴既有持股／警示分析宇宙。
- [x] 581.2 新增純、deterministic的RadarCompletedPriceDrawdown（可採等價具名類）與不可變MediumAccumulationContext。context欄位為status(AVAILABLE／UNAVAILABLE／NOT_EVALUATED)、stockCode、market、windowStart、completedAsOfDate、highDate、BigDecimal high、BigDecimal referencePrice、priceBasis與unavailableReason。status不是可管理業務分類，而是閉合資料品質協定。缺資料不得用0／空值冒充AVAILABLE；null context視同NOT_EVALUATED。
- [x] 581.3 來源僅既有canonical raw OHLC長序列、同輪既有事件、同一accepted price P，不新增外呼／DB查詢。D為排除live後最新完成市場日期；峰值只取閉區間[D.minusWeeks(52),D]的high。必須有日期<=windowStart的邊界列證明完整跨度；使用的列日期嚴格降序且不重複，window內high／close正且high>=close，不能缺high補close。已知上游剔除日期落入窗口、未定日期、既有可信事實已證實的漏交易列、跨度不足或已知價基不一致→UNAVAILABLE。不得將短結構視窗的DAILY_MAX_GAP_DAYS=10套到整個52週窗口；0050／00881的2026-02-11至2026-02-23自然長假有12曆日間距，不能僅因日距而判新fact不可用。邊界列僅證明覆蓋完整52週跨度，不證明每個交易日均完整；缺完整權威日曆時不猜漏交易日，不為此新增外呼／DB／日曆查詢，也不修改既有RadarCompletedStructure短窗10日規則。邊界外更舊資料不得污染峰值；已知剔除／漏列診斷仍fail closed。live／未來high永不入峰值。
- [x] 581.4 新峰值專用價格報酬價基，不含現金股利總報酬因子。TW重用DistributionAdjustedPriceService現有分割與股票股利倍率建立同單位價格序列，但排除cash factor；不得改原adjust()預設行為、篡改canonical股利金額或寫回history。US沿現有priceOnlyBasis及其斷層、最近15根除息與日期未知guard，標STORED_PRICE_ONLY，不假稱verified split／total-return或因source null全面否決。必要時normalization可讀live以判定當前股數價基，只有完成列可入peak。已知分割混基假跌幅不得觸發，不能為讓策略可達而放寬原guard；同時必測既有合格US PRICE_ONLY可真正產生新中期機會，不能要求不可得的額外官方split proof而永久封鎖七碼。
- [x] 581.5 資格採BigDecimal精確交叉乘法：TW P×100<=H×80，US P×100<=H×70；P/H皆正且P與StockInput.price compareTo相等。drawdown=(H-P)×100/H僅作證據顯示，固定格式捨入不能決定門檻。保存窗口／日期／高點日期／H/P／價基，Google兩股類不混價、不用持股成本替代峰值。
- [x] 581.6 StockInput尾端追加一個typed context，保留現行30參數及所有歷史相容形狀，全部缺context傳NOT_EVALUATED。Prepared／Assembled保存當輪新fact並保留所有既有建構式（缺新元件同樣不啟用）；EMPTY／null不產生資格。production唯一StockInput建構點以及BacktestService的legacy evaluate與跨市場v13Input兩形狀顯式帶code／market與同一pure calculator的as-of事實；backtest嚴格只讀訊號時點以前資料。LOCAL cache命中時仍由當輪canonical來源建立新fact，不從provider overlay／舊近似high或cache猜出。
- [x] 581.7 「同源」限新價格回撤事實與其guard／as-of。舊MA/KD/週K/score及production既有US技術fallback不改；不為修補既有US回測fallback差異而抽換全宇宙技術流程，不讓新策略解除舊技術不可用。若replay技術本就不可用，保持原行為並如實記錄限制，不能把新fact同源稱為整個technical engine已一致。非名單、未達門檻、SHORT／SWING輸出除V27身分外維持V26。
- [x] 581.8 在actionFor舊trial／獲利了結／分數映射之前加入新MEDIUM分支。只有exact範圍＋AVAILABLE＋滿門檻啟用：不要求MA方向ABOVE、KD黃金交叉／低點墊高／V25 stabilized、完成日收紅或RISK_ON；RISK_OFF、KD熱、季線正乖離／短期極端超買、日漲達原8%門檻都改為本路徑的方向／急彈風險，不否決此中期政策。必要MA20/60/240、KD與confirmation的可用性、合法價基、資料fresh/complete、marketStale與DATA_INCOMPLETE仍fail closed，不把技術方向未轉強誤稱資料不足。分數／權重／公式與candidate參數值不變，普通買方／舊trial／另兩軌保持原硬條件。
- [x] 581.9 將買方安全判斷以typed政策分出正常與具名MEDIUM路徑，不能原封套用舊buySafetyBlockers再暗中恢復技術否決。保留FX昂貴、ETF昂貴溢價、fundamentalDeteriorating及必要品質限制；US基本面缺值不稱健康，TW ETF N/A不要求個股EPS/ROE。新路徑受保留條件阻擋，立即持有者HOLD_CAUTION／未持有者WAIT＋具名risk，不跌入TRIAL、其他BUY或賣出。後續既有evidence與官方SMA20 gate可降級且不能復活，SHORT分鐘gate仍只作用SHORT。
- [x] 581.10 合格持有者為ADD_CANDIDATE，未持有者BUY_CANDIDATE。internal typed result來源標記供TradingRadarService列表／full／單檔summary一致投影；只有原始MEDIUM確由本政策且最後仍為ADD／BUY才顯示「積極分批加碼候選」／「積極分批買進候選」。後置gate降級後用實際動作標籤，不殘留已放行強標籤。現有reasons列出標的、52週窗口與as-of、H/P、高點日期、回撤／門檻與3~6月目標；大幅只表示積極分批與可提高比重意向，不計算股數／金額、不動資金、不下單。
- [x] 581.11 修正文案來源而非刪字串：describeHeat已逐軌，傳typed情境給它；marketContribution及completedDayContribution的共用「禁止BUY/ADD」「仍須等待止跌」「依硬追漲門檻」文字須在產生處留下typed fact或normal/deep variant，再由evaluateHorizon按horizon／policy render。23因子數值只算一次，不重算三次、不以contains/replace/remove搬移理由。CounterTrendResult維持短線觀察；當與本政策同列時，其小額／等待文字須具名限定短線，不絕對否決MEDIUM也不暗示成本已放行。新MEDIUM可保留未止穩／熊市／急彈風險，但不得同時宣稱該軌「本日不列BUY/ADD」「必須等止跌」。SHORT／SWING與非目標原文字內容／順序保持。t580最終horizonConflict邏輯不退回原始候選。
- [x] 581.12 邊界測試放在既有TradingRadarRuleEngineTest、RadarInputAssemblerWindowRegressionTest、TradingRadarStockWeeklyWiringTest、BacktestServiceTest與TradingRadarPanelServiceTest；新pure calculator測試可新增同名Test。覆蓋20%／30%前一個可表達值、等值與後一值、symbol/market錯配／Google股類、窗口端點／live／缺high／缺跨度／亂序／已剔除／split假跌幅、compat不啟用；以0050／00881的2026-02-11至2026-02-23自然長假12日間距作仍AVAILABLE正例，與相同窗口明確droppedDates／既有可信漏列診斷造成UNAVAILABLE反例對照（不能依缺日曆自行猜缺日）；合格時持有／未持有、三軌與全技術例外、每個保留成本品質blocker、evidence/SMA20最後降級、強label只在最後BUY/ADD、資料完整與PRICE_ONLY正向可達、cache本輪重建及production/replay新fact同值/no-lookahead。正向例必含MA BELOW、完成日負值、KD未轉強、stabilized=false、RISK_OFF，不能只證明舊安全fixture仍買。非目標／未達／SHORT／SWING回歸逐位不變；分數／候選／最終動作以政策契約驗證，不任改期望求綠燈。
- [x] 581.13 RULE_VERSION由TW_RULES_V26升TW_RULES_V27，FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION同步V27，API現在的reason／label內容改變但schema／路由不新增。金融OpenAPI info.version 1.27.4→1.27.5，同步當前examples／DTO Javadoc／Service註解／Backend與BFF測試fixtures／兩支Ruby版本期望，歷史Task／immutable receipts不改；ACTION_POLICY_VERSION仍EVIDENCE_GATE_V2。通知V26→V27首輪只重建baseline零寄信；測試釘住。
- [x] 581.14 完整發布而非留草稿。先獨立Codex spec審查、實作、獨立diff-scoped arch審查、限定回歸與離線build。由受控SRPP feature `/Users/steven/.codex/worktrees/radar-v27-identity/SRPP`（非main，起始759cc279）走asset renderer --srpp-root→sync→renderer check→sync check；只允許Swagger mirror與consumer schema／runtime contract／manifest metadata改動，不改SRPP金融規則語義。驗bundle／runtime／manifest／authority，原generator產insert-only registry SQL與必要identity golden。兩repo feature commit→main no-ff merge／push→四remote refs核對，之後insert-only登錄並精確回讀；再從乾淨main權威設定只build／force-recreate business-services，保持BFF運作、等待健康後驗恢復。最後從SRPP主機以相同新policyBundleSha256／swaggerSha256讀9090；合法正式日報時窗外採用標NOT_YET_EXECUTED，不手動producer／重跑報告／寄信／交易。禁止未合併feature提前替換正式容器。

## 驗證

```bash
bash scripts/spec-check.sh main
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*Radar*,*Backtest*,*RuleParameters*,*OpenApi*,*TreasuryYield*,*Assembler*,*Structure*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*TradingRadar*' test
ruby scripts/render-9090-openapi-docs.rb --check --asset-only
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/tests/unified-9090-openapi-renderer-test.rb
```

本任務會改Swagger identity，因此正式部署必須等雙repo已merge/push、insert-only registry及同hash回讀；一般「feature先run-stack驗收」順序在此由本任務明示受控發布順序取代。先完成離線build與測試，不提前換正式image。合併後從乾淨main和main .env／secrets，僅執行：

```bash
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

驗證正式列表／detail皆V27、每筆最終分歧與label一致、peak回撤證據及阻擋來源可讀、effective config與main相同且前後image SHA穩定；jar的Swagger／authority與registry新hash一致，再由SRPP主機做exact-hash唯讀驗收。正式資料當天沒有20%／30%自然案例時，必區分固定fixture邏輯證據與自然資料readback，禁止向正式DB/Redis注入假行情或誇稱實際市場新策略已觸發。3~6月是目標，不使用歷史簡化診斷的勝率作放行或宣稱保證回原高。

## 完成報告

2026-11-14：581.1～581.14 的規則實作、雙專案發布、登錄、部署與實機唯讀維護驗收完成。正式日報計算包採用仍為 `NOT_YET_EXECUTED`；此項與部署完成分開記錄。規格語義維持已審版本，本次只回填 checkbox 與完成報告，不偽記新的 spec PASS。

- 新純價格事實／精確門檻、production／LOCAL cache／replay 接線、typed 中期政策與最後動作標籤完成；舊 US 回測技術 fallback 限制保留，只聲明新價格事實同源。t580 最終動作分歧判定保留。
- 獨立 Codex 規格 round1、功能 round1 與架構 round1 全部 PASS；架構 critical／major／minor 皆 0，已審實作 hash `981a32697a1f` 有效。Backend 合併驗證 898 項，0 failure／0 error／1 原既有 opt-in size-probe skip；BFF 151 項通過，通知含 V26→V27 首輪只建 baseline／零寄信。離線 package、spec-check BLOCK0／CHECK0、renderer、Ruby 契約與 diff 檢查通過。三支審查後僅測試 fixture／coverage 補強各已重跑；正式來源及發布資源與已審內容一致。
- asset feature `a57bee5030f709436defd15739c99895125b953a`、code main no-ff merge `cd04d8c03076268f6d5b748816fd1d4af8d8cf82`；SRPP feature `1a6eb2cb8d88eac1014b4020cc63dd4b87748c9f`、main no-ff merge `95b12035d03143f92e25f4b7b045fd069b3d1eb0`，四個 remote refs 均精確讀回，合併後 35＋4 檔 bytes 與凍結來源相同。SRPP 只改四份 metadata，model、金融規則語義與排程不變；111／15 案 golden 僅 bundle 身分改變，22 案 state-topup check 通過。
- bundle `26848f0b6f62ea7481448be99817f3fc697c47d17d350d7fac06b7be855b5b7c`、Swagger `106f112f489f13f03ac228e4f1fd6b036225b0daefa3c82be3db57481b631a4b`；manifest 檔案 SHA-256 `d080fa5c55b186f63b26072aa0439e8d52ae4ec49f0defeb41398724a28d8fac`，model SHA-256 `89148de05d594eff541a760cbd60a53752221a9b719089829ee8be06f40da75d` 不變。從已合併受追蹤 SQL 單交易 INSERT 登錄，registry 47→48；四欄全文與原 generator 完全一致，原 47 列含時間均未變，沒有 UPDATE／DELETE。
- 由乾淨 asset main 與原正式設定僅重建／recreate business-services：image `sha256:ad5dd487865f7abb14a20c7d6e4b0eacb6a46aa4db2d2fc4c3d7708e6caa8454`、container `6cddf927eeee1d3df494c7513d17abd49089d69b814cb496faf9555368638617` healthy，前後 image pin 穩定。其他八個容器不變，BFF 未重啟；47 設定鍵與三掛載同 main，七個 class／五個 JAR 資源與已審合併來源一致，部署後 registry 四欄再核對原 generator 相同。
- V27 自然資料回讀：today 36 列，最後三軌分歧不一致數 0，積極強標籤 1。00881 的完成日 2026-11-13、窗口 2025-11-14～2026-11-13、高點 57.95（2026-06-23）、參考價 44.8、顯示回撤 22.7%，MEDIUM 為 ADD_CANDIDATE／「積極分批加碼候選」，SHORT／SWING 仍 HOLD_CAUTION，horizonConflict=true。NVDA 顯示回撤 33.0% 但受 FX 昂貴與 market stale 阻擋，最終 HOLD_CAUTION 且無強標籤；0050／GOOGL 也未顯示強標籤。未擴分析宇宙、未注入假行情，不以固定 fixture 冒稱自然案例。
- SRPP 實際執行目錄安全 ff-only 至 `95b12035`，142 個既有未追蹤檔案與 ignored 輸入完整保留。從其既有 `.venv` 主機環境對三個維護 GET 均 PASS：日曆 365 列且 AVAILABLE、TWSE 日線 21 點、台股報價 40 筆正價。以同一新 bundle／Swagger 實呼美股業務 API 得 HTTP 409 `OUTSIDE_REPORT_WINDOW`（紐約 2026-11-13 16:09），屬時窗外診斷，不能稱計算包採用成功；美股與台股正式採用均 `NOT_YET_EXECUTED`，下一台股交易日為 2026-11-16。
- 精簡安全發布摘要保存在 `docs/reviews/t581_deep_accumulation_20261114/release.json`；完整工作階段收據在 `/private/tmp/radar-deep-accumulation-v27/release/` 與 `/private/tmp/radar-current-runtime-audit-20261114/t581-main/`。本次純完成紀錄不改執行資源，依發布流程保留已驗證 image，不為寫入紀錄重建相同 binary。未手動 producer、重跑報告、寄信或交易。
