import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const dialog = readFileSync(new URL('../components/StockAnalysisDialog.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
const quoteSection = dialog.slice(dialog.indexOf('const quoteLevels'), dialog.indexOf('// 股利歷史'))
const chartSection = dialog.slice(dialog.indexOf('const chartOption'), dialog.indexOf('</script>'))

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
  assert.match(dialog, /\.volume>span\{position:relative;z-index:1\}/)
  assert.match(dialog, /<span class="volume"><i[^>]*><\/i><span>\{\{ fmtLots\(row\.bidVolumeLots\) \}\}<\/span><\/span>/)
  assert.match(dialog, /\$\{Math\.abs\(Number\(q\.changePercent\)\)\.toFixed\(2\)\}%/)
  assert.doesNotMatch(dialog, /\$\{Number\(q\.changePercent\)\.toFixed\(2\)\}%/)
  assert.match(api, /getQuoteDetail[\s\S]*skipErrorToast: true/)
  assert.doesNotMatch(api, /quotes\/.*quote-detail|quote-detail.*quotes\//)
})

test('chart controls keep two no-wrap rows at dialog width and degrade by horizontal row scrolling', () => {
  assert.match(dialog, /width="min\(1100px, calc\(100vw - 32px\)\)"/)
  for (const token of ['chart-controls', 'chart-control-row', 'chart-control-group', 'chart-control-label', 'period-control-group', 'weekly-candle-hint', 'chart-zoom-hint']) assert.ok(dialog.includes(token))
  assert.match(dialog, /\.chart-controls \{ display:flex;flex:0 1 auto;flex-direction:column;align-items:flex-end;gap:4px;margin-left:auto;min-width:0 \}/)
  assert.match(dialog, /\.chart-control-row \{ display:flex;align-items:center;gap:10px;white-space:nowrap \}/)
  assert.match(dialog, /\.chart-control-group \{ display:flex;align-items:center;gap:6px;flex:none;white-space:nowrap \}/)
  assert.match(dialog, /@media \(max-width:800px\)\{\.analysis-meta\{flex-wrap:wrap;padding-right:0\}\.chart-controls\{width:100%;align-items:flex-start;margin-left:0\}\.chart-control-row\{max-width:100%;overflow-x:auto/)
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

test('line and intraday implementations intentionally retain their own last-close and axis behavior', () => {
  // These are explicit allowances: they must not be mistaken for K-frame violations above.
  assert.match(dialog, /lastNonNull\(chartPrices\.value\)/)
  assert.match(dialog, /let previousClose = null/)
  assert.match(chartSection, /const vals = prices\.filter\(v => v != null\)/)
})
