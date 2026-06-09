<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    width="1100px"
    destroy-on-close
    draggable
    @open="onOpen">
    <template #header>
      <span style="font-size:18px;font-weight:600;color:#1e293b">
        {{ stock?.stockCode }} {{ stock?.stockName || '' }}　股票分析
      </span>
    </template>
    <div class="tabs-wrap">
      <span v-if="activeTab === 'chart' && latestTradingDate" class="tabs-trailing">
        資料截止：{{ latestTradingDate }}
      </span>
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- 走勢圖 -->
      <el-tab-pane label="走勢圖" name="chart">
        <div v-if="loading" class="analysis-loading">
          <el-icon class="is-loading" size="36"><Loading /></el-icon>
          <div>載入歷史股價中…</div>
        </div>
        <div v-else-if="!history.length" class="analysis-empty">
          無歷史資料，請先執行股價補齊
        </div>
        <template v-else>
          <div class="analysis-meta">
            <el-tag size="small" type="info">雙擊任意股票可開啟分析</el-tag>
            <div style="display:flex;align-items:center;gap:6px;margin-left:auto">
              <span style="color:#64748b;font-size:12px">期間：</span>
              <el-button-group>
                <el-button
                  v-for="opt in rangeOptions" :key="opt.label"
                  size="small"
                  :type="months === opt.months ? 'primary' : 'default'"
                  @click="months = opt.months">
                  {{ opt.label }}
                </el-button>
              </el-button-group>
              <span style="color:#64748b;font-size:12px;margin-left:8px">滾輪縮放 / 拖曳平移</span>
            </div>
          </div>
          <div v-if="isIntraday && intradayLoading" class="analysis-loading" style="height:580px">
            <el-icon class="is-loading" size="36"><Loading /></el-icon>
            <div>載入當日分時資料中…</div>
          </div>
          <div v-else-if="isIntraday && !intradayTicks.length" class="analysis-empty" style="height:580px">
            無當日分時資料
          </div>
          <v-chart v-else :option="chartOption" style="height:580px" autoresize />
        </template>
      </el-tab-pane>

      <!-- 持股明細（ETF only） -->
      <el-tab-pane v-if="isEtf" label="持股明細" name="holdings">
        <div style="padding:24px 8px">
          <div style="color:#475569;font-size:14px;line-height:1.8;margin-bottom:16px">
            ETF 成分股資料目前免費資料源都有限制（TWSE 無此 API、FinMind 需付費方案、發行商官網為 SPA）。
            <br>點擊以下外部連結可查看最新完整成分股：
          </div>
          <div style="display:flex;flex-direction:column;gap:10px">
            <el-link
              v-for="link in etfExternalLinks" :key="link.url"
              :href="link.url" target="_blank" type="primary"
              style="font-size:14px">
              🔗 {{ link.label }}
            </el-link>
          </div>
        </div>
      </el-tab-pane>

      <!-- 股利歷史 -->
      <el-tab-pane label="股利歷史（10 年）" name="dividends">
        <div v-if="dividendsLoading" class="analysis-loading">
          <el-icon class="is-loading" size="36"><Loading /></el-icon>
          <div>載入股利資料中…</div>
        </div>
        <div v-else-if="!dividendHistory.rows?.length" class="analysis-empty">
          {{ dividendHistory.message || '查無股利資料' }}
        </div>
        <template v-else>
          <div style="margin-bottom:8px;color:#64748b;font-size:12px">
            資料來源：{{ dividendHistory.source }}
          </div>
          <el-table :data="dividendDisplayRows" size="small" border max-height="500" style="width:100%"
            :row-class-name="dividendRowClass">
            <el-table-column label="年度" width="80" align="center">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="font-weight:700">{{ row.year }}</span>
                <span v-else>{{ row.year }}</span>
              </template>
            </el-table-column>
            <el-table-column label="現金股利" width="100" align="right">
              <template #default="{ row }">${{ Number(row.cashDividend || 0).toFixed(4) }}</template>
            </el-table-column>
            <el-table-column label="股票股利" width="100" align="right">
              <template #default="{ row }">{{ Number(row.stockDividend || 0).toFixed(4) }}</template>
            </el-table-column>
            <el-table-column label="現金殖利率" width="110" align="right">
              <template #default="{ row }">
                <span v-if="row.yieldPct != null">{{ row.yieldPct.toFixed(2) }}%</span>
                <span v-else style="color:#94a3b8">—</span>
              </template>
            </el-table-column>
            <el-table-column label="除息日昨收價" width="120" align="right">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else-if="row.previousClose != null">${{ Number(row.previousClose).toFixed(2) }}</span>
                <span v-else style="color:#94a3b8">—</span>
              </template>
            </el-table-column>
            <el-table-column label="除息日" width="120" align="center">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else>{{ row.exDividendDate || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="現金股利發放日" width="140" align="center">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else>{{ row.cashPaymentDate || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="股票股利發放日" width="140" align="center">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else>{{ row.stockPaymentDate || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="填息天數" width="100" align="right">
              <template #default="{ row }">
                <span v-if="row.isYearSummary" style="color:#94a3b8">—</span>
                <span v-else-if="row.fillDays === 0" style="color:#16a34a">當日</span>
                <span v-else-if="row.fillDays != null">{{ row.fillDays }} 天</span>
                <span v-else style="color:#94a3b8">尚未填息</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>
    </el-tabs>
    </div>
  </el-dialog>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { Loading } from '@element-plus/icons-vue'
import { bffApi } from '@/api/index.js'

use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent])

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  stock: { type: Object, default: null },
  usdRate: { type: [Number, String], default: null }
})
defineEmits(['update:modelValue'])

const activeTab = ref('chart')
const loading = ref(false)
const history = ref([])
const months = ref(12)
const intradayTicks = ref([])
const intradayLoading = ref(false)
const dividendHistory = ref({ rows: [] })
const dividendsLoading = ref(false)

const isIntraday = computed(() => months.value === 0)

const latestTradingDate = computed(() => {
  if (isIntraday.value && intradayTicks.value.length) {
    const t = intradayTicks.value[intradayTicks.value.length - 1]?.time
    return t ? String(t).substring(0, 10) : null
  }
  const h = history.value
  if (!h || !h.length) return null
  return h[h.length - 1]?.tradingDate ?? null
})

const rangeOptions = [
  { label: '當日',   months: 0 },
  { label: '1個月', months: 1 },
  { label: '3個月', months: 3 },
  { label: '1年',   months: 12 },
  { label: '2年',   months: 24 },
  { label: '3年',   months: 36 },
  { label: '5年',   months: 60 },
  { label: '10年',  months: 120 },
]

async function fetchHistory() {
  if (!props.stock) return
  loading.value = true
  history.value = []
  try {
    // 一次抓 10 年（DB 查詢 < 100ms），之後切期間只調 dataZoom，不再 roundtrip
    const end = new Date().toISOString().split('T')[0]
    const startDate = new Date()
    startDate.setMonth(startDate.getMonth() - 120)
    const start = startDate.toISOString().split('T')[0]
    const data = await bffApi.stockAnalysis.getStockHistory(props.stock.stockCode, props.stock.market, start, end)
    history.value = Array.isArray(data) ? data : []
  } catch (e) {
    console.warn('無法取得歷史股價:', e)
  } finally {
    loading.value = false
  }
}

async function fetchIntraday() {
  if (!props.stock) return
  intradayLoading.value = true
  intradayTicks.value = []
  try {
    const data = await bffApi.stockAnalysis.getIntradayTicks(props.stock.stockCode, props.stock.market)
    intradayTicks.value = Array.isArray(data) ? data : []
  } catch (e) {
    console.warn('無法取得當日分時資料:', e)
  } finally {
    intradayLoading.value = false
  }
}

watch(months, (m) => {
  if (m === 0 && !intradayTicks.value.length && !intradayLoading.value) {
    fetchIntraday()
  }
})

function onOpen() {
  activeTab.value = 'chart'
  months.value = 12
  intradayTicks.value = []
  dividendHistory.value = { rows: [] }
  fetchHistory()
}

async function fetchDividendHistory() {
  if (!props.stock) return
  dividendsLoading.value = true
  try {
    dividendHistory.value = await bffApi.stockAnalysis.getDividendHistory(props.stock.stockCode, props.stock.market, 10)
  } catch (e) {
    dividendHistory.value = { rows: [], message: '查詢失敗' }
  } finally {
    dividendsLoading.value = false
  }
}

async function onTabChange(name) {
  if (name === 'dividends' && !dividendHistory.value.rows?.length && !dividendsLoading.value) {
    await fetchDividendHistory()
  }
}

// 股利歷史：將原始事件依年度分組，年度小計列插在每年事件之上
const dividendDisplayRows = computed(() => {
  const rows = dividendHistory.value.rows || []
  if (!rows.length) return []
  // 為每筆事件附上 yieldPct（以除息日昨收價為分母）
  const enriched = rows.map(r => {
    const cash = Number(r.cashDividend || 0)
    const prev = r.previousClose != null ? Number(r.previousClose) : null
    return {
      ...r,
      isYearSummary: false,
      yieldPct: prev && prev > 0 ? (cash / prev) * 100 : null
    }
  })
  // 依年度（除息日年）分組；年度新→舊
  const byYear = new Map()
  for (const r of enriched) {
    const y = r.year
    if (y == null) continue
    if (!byYear.has(y)) byYear.set(y, [])
    byYear.get(y).push(r)
  }
  const years = [...byYear.keys()].sort((a, b) => b - a)
  const out = []
  for (const y of years) {
    const items = byYear.get(y)
    const totalCash = items.reduce((s, r) => s + Number(r.cashDividend || 0), 0)
    const totalStock = items.reduce((s, r) => s + Number(r.stockDividend || 0), 0)
    // 年度殖利率：以該年最近一次除息事件的昨收價為分母（與 Yahoo 顯示口徑一致）
    const firstWithPrev = items.find(r => r.previousClose != null)
    const yearYield = firstWithPrev && Number(firstWithPrev.previousClose) > 0
      ? (totalCash / Number(firstWithPrev.previousClose)) * 100
      : null
    out.push({
      isYearSummary: true,
      year: y,
      cashDividend: totalCash,
      stockDividend: totalStock,
      yieldPct: yearYield
    })
    out.push(...items)
  }
  return out
})

function dividendRowClass({ row }) {
  return row.isYearSummary ? 'dividend-year-summary' : ''
}

const isEtf = computed(() => {
  const s = props.stock
  if (!s) return false
  if (s.market === '台股') return /^00/.test(s.stockCode || '')
  if (s.market === '美股') {
    const white = ['VOO','VT','VTI','VGT','VYM','VNQ','VXUS','SPY','QQQ','DIA','IVV','IWM','AVGO','SCHD','JEPI','JEPQ']
    return white.includes((s.stockCode || '').toUpperCase())
  }
  if (s.market === '英股') {
    const white = ['CSPX','VWRA','VUSA','EIMI','IWDA']
    return white.includes((s.stockCode || '').toUpperCase())
  }
  return false
})

const etfExternalLinks = computed(() => {
  const s = props.stock
  if (!s) return []
  const code = s.stockCode || ''
  if (s.market === '台股') {
    return [
      { label: `MoneyDJ 成分股（${code}）`, url: `https://www.moneydj.com/etf/x/basic/basic0007A.xdjhtm?etfid=${code}.TW` },
      { label: `玩股網 成分股（${code}）`, url: `https://www.wantgoo.com/stock/etf/${code.toLowerCase()}/constituent` },
      { label: `Yahoo 奇摩股市（${code}）`, url: `https://tw.stock.yahoo.com/quote/${code}.TW/holding` },
    ]
  }
  if (s.market === '美股') {
    return [
      { label: `ETFdb 成分股（${code}）`, url: `https://etfdb.com/etf/${code}/#holdings` },
      { label: `Morningstar 成分股（${code}）`, url: `https://www.morningstar.com/etfs/arcx/${code}/portfolio` },
      { label: `Yahoo Finance（${code}）`, url: `https://finance.yahoo.com/quote/${code}/holdings` },
    ]
  }
  if (s.market === '英股') {
    return [
      { label: `iShares 官網（${code}）`, url: `https://www.ishares.com/uk/individual/en/products/search?keyword=${code}` },
    ]
  }
  return []
})

function calcMA(prices, n) {
  return prices.map((_, i) => {
    if (i < n - 1) return null
    const avg = prices.slice(i - n + 1, i + 1).reduce((s, v) => s + v, 0) / n
    return parseFloat(avg.toFixed(2))
  })
}

function calcKD(hist, period = 9) {
  const highs  = hist.map(d => Number(d.highPrice  || d.closePrice || 0))
  const lows   = hist.map(d => Number(d.lowPrice   || d.closePrice || 0))
  const closes = hist.map(d => Number(d.closePrice || 0))
  const K = [], D = []
  let prevK = 50, prevD = 50
  for (let i = 0; i < closes.length; i++) {
    if (i < period - 1) { K.push(null); D.push(null); continue }
    const hh = Math.max(...highs.slice(i - period + 1, i + 1))
    const ll = Math.min(...lows.slice(i - period + 1, i + 1))
    const rsv = hh === ll ? 50 : (closes[i] - ll) / (hh - ll) * 100
    const k = prevK * 2 / 3 + rsv / 3
    const d = prevD * 2 / 3 + k  / 3
    K.push(parseFloat(k.toFixed(2)))
    D.push(parseFloat(d.toFixed(2)))
    prevK = k; prevD = d
  }
  return { K, D }
}

const chartOption = computed(() => {
  const hist = history.value
  if (!hist.length) return {}
  const s = props.stock || {}

  // 日線基礎：所有期間（含「當日」的 MA/KD 水平參考線）都從這份算
  const dailyDates  = hist.map(d => d.tradingDate)
  const dailyPrices = hist.map(d => parseFloat(Number(d.closePrice || 0).toFixed(2)))
  const dailyMa20   = calcMA(dailyPrices, 20)
  const dailyMa60   = calcMA(dailyPrices, 60)
  const dailyMa240  = calcMA(dailyPrices, 240)
  const dailyKD     = calcKD(hist)
  const lastOf = arr => arr.length ? arr[arr.length - 1] : null

  const intraday = isIntraday.value
  // intraday 模式但 tick 序列還沒抓到 → 暫時不畫，由外層 v-if 的 loading 處理
  if (intraday && !intradayTicks.value.length) return {}

  let dates, prices, ma20, ma60, ma240, K, D, xLabelFormatter
  if (intraday) {
    const ticks = intradayTicks.value
    // time 為 ISO LocalDateTime（如 "2026-06-05T13:25:00"）→ x 軸用 HH:mm
    dates  = ticks.map(t => String(t.time).substring(11, 16))
    prices = ticks.map(t => t.price != null ? parseFloat(Number(t.price).toFixed(2)) : null)
    // intraday tick 數不足以重算日線 MA / KD → 取日線最新值畫成水平參考線
    const fill = v => ticks.map(() => v)
    ma20  = fill(lastOf(dailyMa20))
    ma60  = fill(lastOf(dailyMa60))
    ma240 = fill(lastOf(dailyMa240))
    K     = fill(lastOf(dailyKD.K))
    D     = fill(lastOf(dailyKD.D))
    xLabelFormatter = v => v
  } else {
    dates  = dailyDates
    prices = dailyPrices
    ma20   = dailyMa20
    ma60   = dailyMa60
    ma240  = dailyMa240
    K      = dailyKD.K
    D      = dailyKD.D
    xLabelFormatter = v => v.substring(0, 7)
  }

  // 成本均價 = 買入均價（原幣，交易當下匯率鎖定）。一律取 BFF 已算好的 avgCostOriginal，
  // 與 Dashboard 表格「買入均價」同義同源；禁止用「台幣成本 ÷ 今日即時匯率」反推（今日匯率每日
  // 浮動，會與表格對不上且非真實買入成本）。詳見 spec/design.md BFF Enrichment 註記。
  let cost = null
  if (s.avgCostOriginal != null) {
    cost = Number(s.avgCostOriginal)
  } else if (s.shares > 0 && s.investmentCostOriginal != null) {
    cost = Number(s.investmentCostOriginal) / s.shares
  } else if (s.shares > 0 && s.investmentCost) {
    // fallback（呼叫端未帶原幣成本欄位時）：台股 investmentCost 即原幣 TWD；美/英股才用匯率反推
    const costTwd = s.investmentCost / s.shares
    const usd = props.usdRate ? Number(props.usdRate) : null
    cost = (s.market === '美股' || s.market === '英股') && usd ? costTwd / usd : costTwd
  }

  return {
    backgroundColor: '#fff',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', link: [{ xAxisIndex: 'all' }] },
      formatter: params => {
        let html = `<strong>${params[0].axisValue}</strong><br/>`
        params.forEach(p => { if (p.value != null) html += `${p.marker} ${p.seriesName}: <b>${p.value}</b><br/>` })
        return html
      }
    },
    legend: (() => {
      const last = arr => arr.length ? arr[arr.length - 1] : null
      const fmt = v => (v == null ? '' : Number(v).toLocaleString('en-US', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
      }))
      const map = {
        '股價':       fmt(last(prices)),
        '月線MA20':   fmt(last(ma20)),
        '季線MA60':   fmt(last(ma60)),
        '年線MA240':  fmt(last(ma240)),
        '成本均價':   cost != null ? fmt(cost) : '',
        'K':          fmt(last(K)),
        'D':          fmt(last(D))
      }
      // 每個 series 在 legend 數值的色彩，對應線條顏色（與 logo 一致）
      const colorMap = {
        '股價':      '#3b82f6',
        '月線MA20':  '#f59e0b',
        '季線MA60':  '#8b5cf6',
        '年線MA240': '#ef4444',
        '成本均價':  '#64748b',
        'K':         '#f59e0b',
        'D':         '#15803d'
      }
      // 用 index 當 rich key（避免中文字無法作為 echarts rich style key）
      const keyByName = {}
      const richStyles = { n: { fontSize: 12, color: '#475569', lineHeight: 16 } }
      Object.entries(colorMap).forEach(([name, color], i) => {
        const k = 'v' + i
        keyByName[name] = k
        richStyles[k] = {
          fontSize: 12, color, lineHeight: 16, fontWeight: 700, padding: [2, 0, 0, 0]
        }
      })
      return {
        data: cost != null
          ? ['股價', '月線MA20', '季線MA60', '年線MA240', '成本均價', 'K', 'D']
          : ['股價', '月線MA20', '季線MA60', '年線MA240', 'K', 'D'],
        top: 8,
        itemGap: 36,
        formatter: name => map[name]
          ? `{n|${name}}\n{${keyByName[name] || 'n'}|${map[name]}}`
          : name,
        textStyle: {
          fontSize: 12,
          color: '#475569',
          rich: richStyles
        }
      }
    })(),
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: 64, right: 96, top: 72, bottom: 190 },
      { left: 64, right: 96, top: 'auto', height: 90, bottom: 60 }
    ],
    dataZoom: (() => {
      // 日線：抓 10 年資料，期間按鈕只調 dataZoom start% 不再 roundtrip
      // intraday：只有 ~78 個 5m bar，整段全顯示
      let startPct = 0
      if (!intraday) {
        const total = dates.length
        const want = Math.max(20, Math.round(months.value * 21))  // ~21 trading days/month
        startPct = total > 0 ? Math.max(0, 100 * (total - want) / total) : 0
      }
      return [
        { type: 'inside', xAxisIndex: [0, 1], start: startPct, end: 100 },
        { type: 'slider', xAxisIndex: [0, 1], start: startPct, end: 100, height: 20, bottom: 8 }
      ]
    })(),
    xAxis: [
      { gridIndex: 0, type: 'category', data: dates, boundaryGap: false, axisLabel: { show: false }, axisLine: { onZero: false } },
      { gridIndex: 1, type: 'category', data: dates, boundaryGap: false, axisLabel: { rotate: 30, fontSize: 10, formatter: xLabelFormatter } }
    ],
    yAxis: [
      { gridIndex: 0, type: 'value', scale: true, axisLabel: { formatter: v => v.toFixed(0) }, splitLine: { lineStyle: { color: '#f0f0f0' } } },
      { gridIndex: 1, type: 'value', min: 0, max: 100, splitNumber: 2, axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { color: '#f0f0f0' } } }
    ],
    series: [
      { name: '股價', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: prices,
        lineStyle: { width: 2, color: '#3b82f6' }, itemStyle: { color: '#3b82f6' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#3b82f6', fontWeight: 700 },
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: 'rgba(59,130,246,0.12)' }, { offset: 1, color: 'rgba(59,130,246,0)' }] } }
      },
      { name: '月線MA20', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma20,
        lineStyle: { width: 1.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#f59e0b' } },
      { name: '季線MA60', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma60,
        lineStyle: { width: 1.5, color: '#8b5cf6' }, itemStyle: { color: '#8b5cf6' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#8b5cf6' } },
      { name: '年線MA240', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma240,
        lineStyle: { width: 1.5, color: '#ef4444' }, itemStyle: { color: '#ef4444' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#ef4444' } },
      ...(cost != null ? [{
        name: '成本均價', type: 'line', xAxisIndex: 0, yAxisIndex: 0,
        data: dates.map(() => parseFloat(cost.toFixed(2))),
        lineStyle: { color: '#64748b', type: 'dashed', width: 1.5 },
        itemStyle: { color: '#64748b' }, showSymbol: false,
        endLabel: {
          show: true, formatter: '成本 {c}', fontSize: 11,
          color: prices[prices.length - 1] >= cost ? '#16a34a' : '#ef4444'
        }
      }] : []),
      { name: 'K', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: K,
        lineStyle: { width: 1.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#f59e0b' },
        markLine: {
          silent: true, data: [{ yAxis: 80 }, { yAxis: 20 }],
          lineStyle: { color: '#94a3b8', type: 'dashed', width: 1 },
          label: { formatter: '{c}', fontSize: 10, color: '#94a3b8' }
        }
      },
      { name: 'D', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: D,
        lineStyle: { width: 1.5, color: '#15803d' }, itemStyle: { color: '#15803d' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#15803d' }
      }
    ]
  }
})
</script>

<style scoped>
.analysis-loading { display:flex;flex-direction:column;align-items:center;gap:12px;padding:60px 0;color:#64748b;font-size:14px }
.analysis-empty   { text-align:center;padding:60px 0;color:#94a3b8;font-size:14px }
/* 右邊保留的空間要對齊 echarts grid.right (96px)，這樣 period selector / 資料截止 才會
   和 chart 內容（endLabels 落點）的右緣切齊，不會越界到圖外。 */
.analysis-meta    { display:flex;align-items:center;margin-bottom:8px;padding-right:96px }
.tabs-wrap        { position: relative; }
.tabs-trailing    {
  position: absolute;
  right: 96px;
  top: 12px;
  color: #1e293b;
  font-size: 13px;
  font-weight: 600;
  z-index: 1;
}
</style>

<style>
/* 年度小計列（el-table row-class-name 注入後不在 scoped 範圍內，改用全域 style） */
.el-table .dividend-year-summary > td {
  background: #f1f5f9 !important;
  font-weight: 700;
  color: #0f172a;
}
</style>
