import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const dialog = readFileSync(new URL('../components/StockAnalysisDialog.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
const quoteSection = dialog.slice(dialog.indexOf('const quoteLevels'), dialog.indexOf('// 股利歷史'))
const chartSection = dialog.slice(dialog.indexOf('const chartOption'), dialog.indexOf('</script>'))
const holdingsTemplate = dialog.slice(dialog.indexOf('<!-- 持股明細'), dialog.indexOf('<!-- 股利歷史'))
const holdingsSection = dialog.slice(dialog.indexOf('const holdingsData'), dialog.indexOf('// Task 261'))
const fetchHoldingsBody = dialog.slice(dialog.indexOf('async function fetchHoldings'), dialog.indexOf('// 配色比照'))

test('quote-detail contract reads server totals directly, is once-per-open, and guards stale requests', () => {
  assert.match(dialog, /stock\?\.market === '台股' && stock\?\.stockCode !== '0000'/)
  for (const label of ['成交', '昨收', '開盤', '漲跌幅', '最高', '漲跌', '最低', '總量', '均價', '昨量', '成交金額(億)', '振幅']) assert.ok(dialog.includes(label))
  assert.match(dialog, /quoteDetail\.bidTotalLots/)
  assert.match(dialog, /quoteDetail\.askTotalLots/)
  assert.doesNotMatch(quoteSection, /reduce\(/)
  assert.match(dialog, /innerPercent == null && quoteDetail\.outerPercent == null/)
  assert.match(dialog, /無分類資料/)
  assert.match(dialog, /const quoteDetailAttempted = ref\(false\)/)
  assert.match(dialog, /function onOpen\(\)[\s\S]*quoteDetailAttempted\.value = false/)
  assert.match(dialog, /if \(name === 'quote-detail' && !quoteDetailAttempted\.value && !quoteDetailLoading\.value\) \{\s*quoteDetailAttempted\.value = true\s*await fetchQuoteDetail\(\)/)
  assert.doesNotMatch(dialog, /name === 'quote-detail' && !quoteDetail\.value\?\.available/)
  assert.match(dialog, /@click="refreshQuoteDetail"/)
  assert.match(dialog, /function refreshQuoteDetail\(\) \{\s*quoteDetailAttempted\.value = true\s*return fetchQuoteDetail\(\)/)
  assert.match(dialog, /quoteRequestToken/)
  assert.match(dialog, /props\.stock\?\.stockCode === stockCode/)
  assert.match(dialog, /Number\.isNaN\(date\.getTime\(\)\)/)
  assert.match(dialog, /\{\{ quoteSourceLabel \}\} · \{\{ quoteSourceTime \}\}/)
  assert.match(dialog, /quoteDetail\.value\?\.source === 'FUBON_BOOKS' \? '富邦證券'/)
  assert.match(dialog, /quoteDetail\.value\?\.source === 'YAHOO_TW' \? 'Yahoo 股市'/)
  assert.match(dialog, /: '行情來源不明'/)
  assert.doesNotMatch(dialog, /<span>Yahoo 股市 · \{\{ quoteSourceTime \}\}<\/span>/)
  assert.match(dialog, /\.volume>span\{position:relative;z-index:1\}/)
  assert.match(dialog, /<span class="volume"><i[^>]*><\/i><span>\{\{ fmtLots\(row\.bidVolumeLots\) \}\}<\/span><\/span>/)
  assert.match(dialog, /\$\{Math\.abs\(Number\(q\.changePercent\)\)\.toFixed\(2\)\}%/)
  assert.doesNotMatch(dialog, /\$\{Number\(q\.changePercent\)\.toFixed\(2\)\}%/)
  assert.match(api, /getQuoteDetail[\s\S]*skipErrorToast: true/)
  assert.doesNotMatch(api, /quotes\/.*quote-detail|quote-detail.*quotes\//)
})

test('chart controls merge into a single no-wrap row at dialog width and degrade by horizontal row scrolling when squeezed', () => {
  assert.match(dialog, /width="min\(1100px, calc\(100vw - 32px\)\)"/)
  for (const token of ['chart-controls', 'chart-control-row', 'chart-control-group', 'chart-control-label', 'period-control-group', 'weekly-candle-hint', 'chart-zoom-hint']) assert.ok(dialog.includes(token))
  assert.match(dialog, /\.chart-controls \{ display:flex;flex:0 1 auto;align-items:center;margin-left:auto;min-width:0 \}/)
  // 常駐（非只在 media query 內）overflow-x:auto：合併成一排後，~800~1093px 寬度區間單靠 gap 塞不下，
  // 這排要能自行橫向捲動，別讓內容溢出 analysis-meta 保留給 echarts 的 96px 區塊。
  assert.match(dialog, /\.chart-control-row \{ display:flex;align-items:center;gap:5px;white-space:nowrap;max-width:100%;overflow-x:auto;padding-bottom:2px \}/)
  assert.match(dialog, /\.chart-control-group \{ display:flex;align-items:center;gap:4px;flex:none;white-space:nowrap \}/)
  // 11 顆按鈕擠進同一排，靠收緊 small 按鈕左右 padding 換空間（字級與按鈕/文字內容不變）
  assert.match(dialog, /\.chart-control-row :deep\(\.el-button\) \{ padding:5px \}/)
  assert.match(dialog, /@media \(max-width:800px\)\{\.analysis-meta\{flex-wrap:wrap;padding-right:0\}\.chart-controls\{width:100%;align-items:flex-start;margin-left:0\}\.chart-control-row\{max-width:100%;overflow-x:auto/)
  // 結構面：兩排已合併為一個 chart-control-row 容器
  const rowOpenCount = (dialog.match(/class="chart-control-row"/g) || []).length
  assert.equal(rowOpenCount, 1)
  // 排序面：圖型／指標／週K提示／期間／滾輪縮放提示由左到右排在同一排內
  const controlsBlock = dialog.slice(dialog.indexOf('<div class="chart-controls">'), dialog.indexOf('intraday-quote'))
  const order = ['圖型：', '指標：', 'weekly-candle-hint', '期間：', 'chart-zoom-hint']
  const positions = order.map(token => controlsBlock.indexOf(token))
  assert.ok(positions.every(p => p !== -1), 'all control tokens must exist inside the merged row')
  for (let i = 1; i < positions.length; i++) assert.ok(positions[i] > positions[i - 1], `expected ${order[i - 1]} before ${order[i]}`)
})

test('K branches use only BFF daily or weekly frames with frame-local close state', () => {
  for (const token of ["'line'", "'daily-candle'", "'weekly-candle'", 'CandlestickChart', 'series.value?.daily', 'series.value?.weekly', '無完整 OHLC 資料']) assert.ok(dialog.includes(token))
  assert.match(dialog, /const activeClose = computed\(\(\) => isCandle\.value \? activeFrame\.value\.currentClose : lastNonNull\(chartPrices\.value\)\)/)
  assert.doesNotMatch(dialog, /activeClose[\s\S]{0,180}lastNonNull\(frame\.closes\)/)
  assert.match(chartSection, /const priceCurrent = candleMode \? frame\.currentClose : activeClose\.value/)
  assert.match(chartSection, /const pricePrevious = candleMode \? frame\.previousClose : null/)
  assert.match(chartSection, /frame\.latest \|\| \{\}/)
  assert.match(chartSection, /\[num\(source\.opens\?\.\[i\]\), num\(source\.closes\?\.\[i\]\), num\(source\.lows\?\.\[i\]\), num\(source\.highs\?\.\[i\]\)\]/)
  assert.match(chartSection, /type: candleMode \? 'candlestick' : 'line'/)
  assert.match(chartSection, /color: '#dc2626', borderColor: '#dc2626', color0: '#16a34a', borderColor0: '#16a34a'/)
  assert.match(chartSection, /開 <b>\$\{p\.value\[0\]\}<\/b>／高 <b>\$\{p\.value\[3\]\}<\/b>／低 <b>\$\{p\.value\[2\]\}<\/b>／收 <b>\$\{p\.value\[1\]\}/)
  assert.match(dialog, /週 K；技術指標為日線值的週末取樣/)
  assert.match(dialog, /if \(value === 0\) chartMode\.value = 'line'/)
})

test('line retains its own close state while intraday consumes the server session object', () => {
  // The line mode still owns its historical display close; Task 409 deliberately removes the
  // old intraday history scan and requires the verified server tuple instead.
  assert.match(dialog, /lastNonNull\(chartPrices\.value\)/)
  assert.match(dialog, /normalizeIntradaySession\(data\)/)
  assert.match(dialog, /comparisonLabel\(session\.comparisonKind\)/)
  assert.match(dialog, /comparisonPrice: session\.comparisonPrice/)
  assert.match(dialog, /session\.comparisonPrice == null\s*\|\| session\.comparisonKind === 'UNAVAILABLE'\s*\|\| !session\.comparisonSource\s*\|\| session\.lastPrice == null\s*\|\| session\.change == null\s*\|\| session\.changePercent == null/)
  assert.doesNotMatch(dialog, /let previousClose = null/)
  assert.doesNotMatch(dialog, /prices\[i\] != null\) \{\s*previousClose/)
  assert.match(chartSection, /const vals = prices\.filter\(v => v != null\)/)
})

test('etf holdings tab lazy-fetches the existing API into a pie chart and keeps weight as-is', () => {
  // Task 359.1a：頁籤啟用時（onTabChange）才 lazy-fetch，走 props.stock（本檔沒有解構出裸 stock）
  assert.match(dialog, /if \(name === 'holdings' && !holdingsAttempted\.value && !holdingsLoading\.value\) \{\s*holdingsAttempted\.value = true\s*await fetchHoldings\(\)/)
  assert.match(fetchHoldingsBody, /bffApi\.stockAnalysis\.getEtfHoldings\(props\.stock\.stockCode, props\.stock\.market\)/)
  assert.doesNotMatch(dialog, /const \{ stock \}/)
  // 不是走勢圖 tab 的 eager-fetch 模式——onOpen() 本身不得直接呼叫 fetchHoldings
  const onOpenBody = dialog.slice(dialog.indexOf('function onOpen()'), dialog.indexOf('async function fetchDividendHistory'))
  assert.doesNotMatch(onOpenBody, /fetchHoldings\(\)/)

  // Task 359.1a／359.1b：holdings 有資料時渲染圓餅圖，視覺風格比照 DashboardView 的台股個股穿透圖
  assert.match(holdingsTemplate, /v-else-if="holdingsData\?\.holdings\?\.length"/)
  assert.match(holdingsTemplate, /<v-chart :option="holdingsPieOption"/)
  assert.match(holdingsSection, /radius: \['46%', '78%'\]/)
  assert.match(holdingsSection, /center: \['50%', '50%'\]/)
  assert.match(holdingsSection, /labelLayout: \{ hideOverlap: true \}/)
  assert.match(holdingsSection, /itemStyle: \{ borderRadius: 6 \}/)
  // PieChart 是 tree-shaking echarts 陷阱（同檔 MarkPointComponent 註解記錄的同一類問題）：漏 use() 會靜默不畫
  assert.match(dialog, /import \{ BarChart, LineChart, CandlestickChart, PieChart \} from 'echarts\/charts'/)
  assert.match(dialog, /use\(\[CanvasRenderer, LineChart, BarChart, CandlestickChart, PieChart,/)

  // Task 359.1c：圖例/hover 顯示代號、名稱、比例、股數；weight 直接當百分比使用，不做多餘的 ×100 / ÷100；
  // shares 為 null（Yahoo／FinMind 路徑）時比照 fmtLots 的既有 null-safe 慣例顯示「—」
  assert.match(holdingsSection, /value: Number\(h\.weight\)/)
  assert.doesNotMatch(holdingsSection, /weight\s*\*\s*100/)
  assert.doesNotMatch(holdingsSection, /weight\s*\/\s*100/)
  assert.match(holdingsSection, /code: h\.stockCode \|\| ''/)
  assert.match(holdingsSection, /name: h\.stockName \|\| h\.stockCode \|\| ''/)
  assert.match(holdingsSection, /shares: h\.shares/)
  assert.match(holdingsSection, /p\.data\?\.shares == null \? '—'/)
  // 不透過 ECharts {d} 的自動歸一化——Yahoo「前 10 大」來源可視 slice 總和恆小於 100%，{d} 會失真放大比例
  assert.doesNotMatch(holdingsSection, /\{d\}%/)

  // Task 359.1d：資料來源與時間標示（asOfDate／source）
  assert.match(holdingsTemplate, /資料來源：\{\{ holdingsData\.source \}\}．\{\{ holdingsData\.asOfDate \|\| '時間不明' \}\}/)

  // Task 359.2a：supported=false 或 holdings 為空陣列時，落回既有靜態連結 fallback（文案與 etfExternalLinks 原封不動）
  assert.match(holdingsTemplate, /v-else style="padding:24px 8px"/)
  assert.match(holdingsTemplate, /ETF 成分股資料目前免費資料源都有限制（TWSE 無此 API、FinMind 需付費方案、發行商官網為 SPA）。/)
  assert.match(holdingsTemplate, /v-for="link in etfExternalLinks"/)
  assert.match(dialog, /const etfExternalLinks = computed/)

  // Task 359.2b／359.3a：getEtfHoldings 呼叫比照 getQuoteDetail 帶 skipErrorToast:true，避免全域 toast
  // 汙染其他頁籤；契約路徑／參數本身不動（359.3a 範圍界定：不新增不修改後端／BFF API）
  assert.match(api, /getEtfHoldings[\s\S]*skipErrorToast: true/)
  assert.match(api, /api\.get\('\/bff\/stock-analysis\/etf-holdings', \{ params: \{ code, market \}, skipErrorToast: true \}\)/)
  // catch 只動 holdingsData 自己這組 state，不污染 quoteDetail／dividendHistory／series 等其它頁籤的既有 state
  assert.match(fetchHoldingsBody, /catch \(e\) \{\s*holdingsData\.value = null\s*\}/)
  assert.doesNotMatch(fetchHoldingsBody, /quoteDetail\.value|dividendHistory\.value|series\.value/)

  // Task 359.2c：holdingsAttempted 旗標避免重複打 API（同 quoteDetailAttempted 模式）
  assert.match(dialog, /const holdingsData = ref\(null\)/)
  assert.match(dialog, /const holdingsLoading = ref\(false\)/)
  assert.match(dialog, /const holdingsAttempted = ref\(false\)/)
  // 切換股票後舊資料不殘留：onOpen()（destroy-on-close 重開對話框時的手動 reset 保險）一併清空 holdings state
  assert.match(dialog, /function onOpen\(\)[\s\S]*holdingsData\.value = null\s*holdingsLoading\.value = false\s*holdingsAttempted\.value = false\s*fetchHistory\(\)/)
  // reset 不是走 watch(() => props.stock, ...)——本檔唯一的 watch 是監看 months
  assert.doesNotMatch(dialog, /watch\(\(\) => props\.stock/)
  assert.match(dialog, /watch\(months, \(m\) => \{/)
})

test('etf holdings pie maps BFF-provided top10+others as-is and colors the others slice gray', () => {
  // Task 359.4e：holdings 現在由 BFF 保證已是「前 10 大 + 其它」，前端只單純映射，
  // 不應再自己排序／截斷（排序／截斷邏輯已下放到 BFF EtfHoldingsAggregator）
  assert.doesNotMatch(holdingsSection, /\.sort\(/)

  // stockCode 為 null（「其它」聚合列）時走固定灰階 ETF_HOLDINGS_OTHERS_COLOR，
  // 不進入 ETF_HOLDINGS_PIE_COLORS 迴圈色票；灰色值比照 Dashboard TW_PIE_COLORS 最後一色
  assert.match(dialog, /const ETF_HOLDINGS_OTHERS_COLOR = '#94a3b8'/)
  assert.match(holdingsSection,
    /itemStyle: \{ color: h\.stockCode == null \? ETF_HOLDINGS_OTHERS_COLOR : ETF_HOLDINGS_PIE_COLORS\[i % ETF_HOLDINGS_PIE_COLORS\.length\] \}/)

  // 既有欄位映射（value／code／name／shares）不因 359.4 變動——沿用既有 null-safe 慣例即可，
  // 不需要為「其它」列（stockCode/shares 為 null）另寫特例
  assert.match(holdingsSection, /value: Number\(h\.weight\)/)
  assert.match(holdingsSection, /code: h\.stockCode \|\| ''/)
  assert.match(holdingsSection, /name: h\.stockName \|\| h\.stockCode \|\| ''/)
  assert.match(holdingsSection, /shares: h\.shares/)

  // Task 359.4f：舊註解宣稱「不合成其它分類」在 359.4 之後不再成立，不得留下矛盾註解
  assert.doesNotMatch(dialog, /不合成「其它」分類/)
})
