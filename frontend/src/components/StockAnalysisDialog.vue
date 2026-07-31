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
          <div>{{ backfilling ? '首次載入，補齊 10 年歷史中…（約需數秒）' : '載入歷史股價中…' }}</div>
        </div>
        <div v-else-if="!chartDates.length" class="analysis-empty">
          無歷史資料，請先執行股價補齊
        </div>
        <template v-else>
          <div class="analysis-meta">
            <el-tag size="small" type="info">雙擊任意股票可開啟分析</el-tag>
            <div style="display:flex;align-items:center;gap:6px;margin-left:auto">
              <span style="color:#64748b;font-size:12px">指標：</span>
              <el-select v-model="selectedIndicator" size="small" style="width:104px">
                <el-option v-for="o in INDICATOR_OPTIONS" :key="o" :label="o" :value="o" />
              </el-select>
              <span style="color:#64748b;font-size:12px;margin-left:8px">期間：</span>
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
          <div v-if="isIntraday && intradayQuote" class="intraday-quote">
            <span class="iq-label">昨收</span>
            <span class="iq-val">{{ fmtQuote(intradayQuote.previousClose) }}</span>
            <template v-if="intradayQuote.change != null">
              <span class="iq-label">今日漲跌</span>
              <span class="iq-val" :style="{ color: quoteColor(intradayQuote.change) }">
                {{ intradayQuote.change > 0 ? '▲' : intradayQuote.change < 0 ? '▼' : '' }}{{ fmtQuote(Math.abs(intradayQuote.change)) }}
                （{{ intradayQuote.changePct > 0 ? '+' : intradayQuote.changePct < 0 ? '-' : '' }}{{ Math.abs(intradayQuote.changePct).toFixed(2) }}%）
              </span>
            </template>
          </div>
          <div v-if="isIntraday && intradayLoading" class="analysis-loading" style="height:580px">
            <el-icon class="is-loading" size="36"><Loading /></el-icon>
            <div>載入當日分時資料中…</div>
          </div>
          <div v-else-if="isIntraday && !intradayTicks.length" class="analysis-empty" style="height:580px">
            無當日分時資料
          </div>
          <!-- notMerge 必要：切換指標時子圖 series 數量會變（KD,J 五個 vs 威廉指標一個），
               vue-echarts 預設 merge 不會移除多餘的舊 series，舊指標的線會殘留在畫面上。
               dataZoom 的 start/end 本就由 option 明確指定（ez），故 notMerge 不會丟失縮放狀態。 -->
          <v-chart v-else ref="chartRef" :option="chartOption" :update-options="{ notMerge: true }"
                   style="height:580px" autoresize @datazoom="onZoom" />
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
import { BarChart, LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { Loading } from '@element-plus/icons-vue'
import { bffApi } from '@/api/index.js'

// 注意：tree-shaking 版 echarts 必須顯式註冊元件才生效。MarkPointComponent 漏註冊時，
// 收盤線的 markPoint（最高/最低標記）會被 ECharts 靜默忽略、完全不畫（markLine 有註冊故 KD 80/20 正常）。
use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent])

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  stock: { type: Object, default: null },
  usdRate: { type: [Number, String], default: null }
})
defineEmits(['update:modelValue'])

const activeTab = ref('chart')
const loading = ref(false)
const backfilling = ref(false)  // Task 136：首次無歷史 → 即時補齊 10 年中
// 走勢圖資料：BFF chart-series 已把股價與技術指標以 tradingDate 聯集對齊，前端零計算、零 join。
// 形狀：{ dates[], prices[], ma20[], ma60[], ma240[], k[], d[], j9[], k3d2[], rsv[], latest{} }
const series = ref(null)
const chartDates  = computed(() => series.value?.dates  ?? [])
const chartPrices = computed(() => series.value?.prices ?? [])
// legend／「當日」水平線用的最新值：BFF 已挑好「指標序列本身的最後一筆」，
// 不是對齊後陣列的最後一筆——0000 大盤盤中兩者不同（股價側不併 live、指標側併）
const latestIndicators = computed(() => series.value?.latest ?? null)
const months = ref(12)
const intradayTicks = ref([])
const intradayLoading = ref(false)
const dividendHistory = ref({ rows: [] })
const dividendsLoading = ref(false)
const chartRef = ref(null)
// 使用者手動拖曳縮放後的 [start,end]%；null＝跟隨期間按鈕預設（最高/最低標記只在可視區間內計算）
const zoomPct = ref(null)

const isIntraday = computed(() => months.value === 0)

// 期間按鈕的預設縮放窗（日線：以 months 換算 ~21 個交易日/月；當日：整段全顯示）
const defaultZoomRange = computed(() => {
  if (isIntraday.value) return { start: 0, end: 100 }
  const total = chartDates.value.length
  const want = Math.max(20, Math.round(months.value * 21))
  const start = total > 0 ? Math.max(0, 100 * (total - want) / total) : 0
  return { start, end: 100 }
})
// 目前實際可視窗（手動拖曳優先，否則跟隨期間按鈕；當日恆整段）— 最高/最低標記依此計算
const effectiveZoom = computed(() =>
  isIntraday.value ? { start: 0, end: 100 } : (zoomPct.value ?? defaultZoomRange.value))

// 使用者拖曳 dataZoom 後，讀回圖表目前 start/end%，讓最高/最低標記跟著可視區間更新（去抖避免迴圈）
function onZoom() {
  const opt = chartRef.value?.getOption?.()
  const dz = opt?.dataZoom?.[0]
  if (!dz || dz.start == null || dz.end == null) return
  const cur = zoomPct.value
  if (cur && Math.abs(cur.start - dz.start) < 1e-6 && Math.abs(cur.end - dz.end) < 1e-6) return
  zoomPct.value = { start: dz.start, end: dz.end }
}

const latestTradingDate = computed(() => {
  if (isIntraday.value && intradayTicks.value.length) {
    const t = intradayTicks.value[intradayTicks.value.length - 1]?.time
    return t ? String(t).substring(0, 10) : null
  }
  const d = chartDates.value
  return d.length ? d[d.length - 1] : null
})

// 「當日」報價摘要：昨收、現價、今日漲跌（僅當日模式且已有分時 tick 時回值）。
// 昨收＝該分時交易日「前一交易日」的日線收盤，取自已載入的 history（＝stock_price_history 收盤，
// 與 Dashboard／管理資產「當日漲跌」同一 business API、同一「vs 前一交易日原始收盤」口徑；
// 刻意不採 Redis LivePrice.previousClose，因 TWSE `y` 於除息日為除息參考價、與全站慣例不一致）。
// 現價＝最後一筆非 null 分時成交（＝ legend「股價」的 lastNonNull）。今日漲跌由畫面現價自算，
// 確保「股價 − 昨收 = 今日漲跌」三值一致（不另抓 live，免與現價對不上）。
const intradayQuote = computed(() => {
  if (!isIntraday.value) return null
  const ticks = intradayTicks.value
  if (!ticks.length) return null
  let price = null
  for (let i = ticks.length - 1; i >= 0; i--) {
    if (ticks[i]?.price != null) { price = Number(ticks[i].price); break }
  }
  if (price == null) return null
  const sessionDate = String(ticks[0]?.time || '').substring(0, 10)  // 分時序列所屬交易日（YYYY-MM-DD）
  // 昨收：日線序列（升冪）中 tradingDate 嚴格早於當日交易日的最後一筆收盤。
  // 聯集對齊後尾格可能是「有指標、無股價」的 null（0000 大盤盤中必然如此），故須跳過 null。
  let previousClose = null
  const dates = chartDates.value
  const prices = chartPrices.value
  for (let i = dates.length - 1; i >= 0; i--) {
    if (dates[i] && dates[i] < sessionDate && prices[i] != null) {
      previousClose = Number(prices[i]); break
    }
  }
  if (!(previousClose > 0)) return { price, previousClose: null, change: null, changePct: null }
  const change = price - previousClose
  return { price, previousClose, change, changePct: (change / previousClose) * 100 }
})
// 漲跌配色（台股慣例：漲紅、跌綠、平灰），與 markPoint／Dashboard 同義同色
const quoteColor = v => (v == null ? '#94a3b8' : v > 0 ? '#dc2626' : v < 0 ? '#16a34a' : '#94a3b8')
// 千分位 2 位小數（與 legend fmt 同口徑）；null → 「—」
const fmtQuote = v => (v == null ? '—' : Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))

// Task 262：子圖指標選單。切換不重抓資料——chart-series 一次回傳全部指標欄位。
const INDICATOR_OPTIONS = ['KD,J', 'MACD', 'RSI', '乖離率', '威廉指標']
const selectedIndicator = ref('KD,J')

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
  backfilling.value = false
  series.value = null
  try {
    // 一次抓 10 年（DB 查詢 < 100ms），之後切期間只調 dataZoom，不再 roundtrip
    const end = new Date().toISOString().split('T')[0]
    const startDate = new Date()
    startDate.setMonth(startDate.getMonth() - 120)
    const start = startDate.toISOString().split('T')[0]
    const code = props.stock.stockCode
    const market = props.stock.market
    let data = await bffApi.stockAnalysis.getChartSeries(code, market, start, end)
    // Task 136：無歷史（多為 ETF 透視成份股尚未被 startup / 每日 cron 補到）→ 即時觸發單檔 10 年回補後重載一次。
    // 只寫 stock_price_history、不入主檔（backfill-stock 端點本就不碰主檔）；今日列獨佔給 ClosePersister。
    // 台股大盤 0000 不觸發（歷史走 twse_index_daily_history）。
    const isTaiex = code === '0000' && market === '台股'
    if (!data?.dates?.length && !isTaiex) {
      backfilling.value = true
      try {
        await bffApi.stockAnalysis.backfillStock(code, market)
        data = await bffApi.stockAnalysis.getChartSeries(code, market, start, end)
      } catch (e) {
        console.warn('歷史回補失敗:', e)
      } finally {
        backfilling.value = false
      }
    }
    series.value = data?.dates?.length ? data : null
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
  // 切期間：清掉手動縮放，最高/最低標記回到該區間預設窗
  zoomPct.value = null
  if (m === 0 && !intradayTicks.value.length && !intradayLoading.value) {
    fetchIntraday()
  }
})

function onOpen() {
  activeTab.value = 'chart'
  months.value = 12
  intradayTicks.value = []
  zoomPct.value = null
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

// Task 261：MA 與 KD 一律由後端 TechnicalIndicatorService 供給（經 BFF chart-series 對齊），
// 前端不再自算——原本的 calcMA()／calcKD() 已刪除。理由：走勢圖自算的值與觀察清單表格顯示的
// 後端 computeAll() 值在盤中會不一致（後端併今日 live 的真實盤中高低價且不濾 source，
// 前端資料源只併 source 不含括號的實際成交、且合成列沒有 high/low），
// 同一畫面雙擊同一列會看到兩組 K/D。

// 各市場交易時段 [開盤, 收盤] HH:mm（市場當地時區、DST 不變）。鏡射後端 MarketZones（單一事實來源）：
// 美股 09:30–16:00 / 英股 08:00–16:30 / 其餘（台股、大盤 0000）09:00–13:30。
// 供「當日」走勢 X 軸建「開盤→收盤」整段網格、固定延伸到收盤時間（非現在時間）。
function sessionHours(market) {
  if (market === '美股') return ['09:30', '16:00']
  if (market === '英股') return ['08:00', '16:30']
  return ['09:00', '13:30']
}

// 陣列最後一筆非 null 值（當日網格末段恆為未來 null，legend / 成本漲跌色須取此而非末格）
const lastNonNull = arr => {
  for (let i = arr.length - 1; i >= 0; i--) if (arr[i] != null) return arr[i]
  return null
}

// 在可視索引區間 [lo, hi] 內找股價「最高 / 最低」點，回傳 ECharts markPoint data（紅最高、綠最低，符合紅漲綠跌）
// coord 以 x 軸類別字串（labels[i]）定位，避免 dataZoom filterMode 重新索引後絕對索引對不準
// labels[i]＝日線模式為日期、當日模式為 HH:mm 時間（標籤第二行直接顯示，不必分支）
function maxMinMarkPoints(data, labels, lo, hi) {
  let maxI = -1, minI = -1, maxV = -Infinity, minV = Infinity
  for (let i = lo; i <= hi; i++) {
    const v = data[i]
    if (v == null) continue
    if (v > maxV) { maxV = v; maxI = i }
    if (v < minV) { minV = v; minI = i }
  }
  if (maxI < 0) return []
  const fmt = v => Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const span = Math.max(1, hi - lo)
  // 色塊位置依該點在可視窗的水平位置避邊：靠右→放左、靠左→放右、其餘上下（最高在下、最低在上，避免撞 legend / 縮放軸）
  const pos = (i, isMax) => {
    const fx = (i - lo) / span
    if (fx > 0.82) return 'left'
    if (fx < 0.18) return 'right'
    return isMax ? 'bottom' : 'top'
  }
  const mk = (i, v, isMax) => ({
    name: isMax ? '最高' : '最低',
    coord: [labels[i], v],
    value: fmt(v),
    xlabel: labels[i],
    itemStyle: { color: isMax ? '#dc2626' : '#16a34a' },
    label: { position: pos(i, isMax), backgroundColor: isMax ? '#dc2626' : '#16a34a' }
  })
  const pts = [mk(maxI, maxV, true)]
  if (minI !== maxI) pts.push(mk(minI, minV, false))
  return pts
}

// 值接近時讓 endLabel 沿 Y 軸自動錯開（例：股價 2405.50 與季線MA60 2347.75 在 y 軸上僅差約 10px）。
// labelLayout 由 echarts/core 自動註冊（export/core.js 已 use(installLabelLayout)），無須額外 use()。
// 只影響 series 自己的 label：markPoint 的「最高/最低」色塊屬獨立的 MarkPointView，不在此列。
const SHIFT_Y = { moveOverlap: 'shiftY', hideOverlap: false }

// Task 262：子圖指標規格。lines/bars/legendOnly 的元素為 [顯示名稱, chart-series 欄位, 顏色, 是否虛線]。
//  legendOnly = 只在 legend 顯示數值、不畫線——但仍必須掛同名空 series，
//               否則 ECharts LegendView 找不到同名 series 會整項連數值都不繪製（production 無提示）。
//  refs       = 參考虛線；axis: kd|fixed|zero（見 subYAxis）
const INDICATOR_SPEC = {
  'KD,J': {
    lines: [['K9', 'k', '#f59e0b'], ['D9', 'd', '#15803d'], ['J9', 'j9', '#0ea5e9', true]],
    legendOnly: [['K3D2', 'k3d2'], ['RSV', 'rsv']],
    refs: [80, 20], axis: 'kd'
  },
  'MACD': {
    // 價基為 DI=(H+L+2C)/4（台股慣例）。EMA12/EMA26 與價格同量級故不畫線、只顯示數值，
    // 畫上去會把 DIF/MACD/OSC 壓成一條水平線；y 軸值域也只取 dif/macd/osc。
    lines: [['DIF9', 'dif', '#f59e0b'], ['MACD', 'macd', '#8b5cf6']],
    bars: [['OSC', 'osc']],
    legendOnly: [['EMA12', 'ema12'], ['EMA26', 'ema26']],
    refs: [0], axis: 'zero'
  },
  'RSI': {
    lines: [['RSI5', 'rsi5', '#f59e0b'], ['RSI10', 'rsi10', '#15803d']],
    refs: [70, 30], axis: 'fixed'
  },
  '乖離率': {
    lines: [['BIAS10', 'bias10', '#f59e0b'], ['BIAS20', 'bias20', '#15803d']],
    legendOnly: [['B10-B20', 'b10b20']],
    refs: [0], axis: 'zero'
  },
  '威廉指標': {
    // W%R9 = 100 − RSV9，值越小越超買 → 超買線 20 在下、超賣線 80 在上
    lines: [['W%R9', 'wr9', '#f59e0b']],
    refs: [20, 80], axis: 'fixed'
  },
}

const chartOption = computed(() => {
  const sr = series.value
  if (!sr?.dates?.length) return {}
  const s = props.stock || {}

  // 日線基礎：BFF chart-series 已聯集對齊，直接取用（前端不再計算 MA / KD）
  const num = v => (v == null ? null : Number(v))
  const col = key => (sr[key] ?? []).map(num)
  const dailyDates  = sr.dates
  const dailyPrices = col('prices')
  const dailyMa20   = col('ma20')
  const dailyMa60   = col('ma60')
  const dailyMa240  = col('ma240')
  // legend／「當日」水平線的最新值一律取 BFF 挑好的「指標序列本身最後一筆」，
  // 不可取對齊後陣列的末格——0000 大盤盤中末格是「有指標、無股價」，兩者不同
  const lt = latestIndicators.value || {}

  const intraday = isIntraday.value
  // intraday 模式但 tick 序列還沒抓到 → 暫時不畫，由外層 v-if 的 loading 處理
  if (intraday && !intradayTicks.value.length) return {}

  const spec = INDICATOR_SPEC[selectedIndicator.value] || INDICATOR_SPEC['KD,J']
  // 子圖要用到的所有欄位（畫線的、畫柱的、只顯示數值的）
  const subKeys = [...spec.lines, ...(spec.bars || []), ...(spec.legendOnly || [])].map(x => x[1])

  let dates, prices, ma20, ma60, ma240, xLabelFormatter
  const subVals = {}
  // subYAxis 的可視區間切片以子圖 x 軸長度為準（intraday 為分鐘網格、日線為交易日）
  let subDates = []
  if (intraday) {
    const ticks = intradayTicks.value
    // 當日 X 軸固定延伸到「收盤時間」而非「現在時間」（與指數當日圖 Requirement 18 同設計、同視覺行為）：
    // 以該市場交易時段建整段「開盤→收盤」每分鐘 category 網格，真實 tick 依 HH:mm 落格、
    // 盤中尚未到達的時段留 null（畫空白、股價線只到最新一筆，靠 connectNulls 讓稀疏 tick 連續）。
    // time 為 ISO LocalDateTime（市場當地時區，如 "2026-06-05T13:25:00"）→ 取 HH:mm。
    const [openHHmm, closeHHmm] = sessionHours(s.market)
    const toMin = hhmm => { const [h, m] = hhmm.split(':').map(Number); return h * 60 + m }
    const openMin = toMin(openHHmm), closeMin = toMin(closeHHmm)
    const slots = Math.max(1, closeMin - openMin + 1)
    dates = Array.from({ length: slots }, (_, i) => {
      const mm = openMin + i
      return `${String(Math.floor(mm / 60)).padStart(2, '0')}:${String(mm % 60).padStart(2, '0')}`
    })
    const priceGrid = new Array(slots).fill(null)
    for (const t of ticks) {
      const hhmm = String(t.time).substring(11, 16)
      if (!/^\d\d:\d\d$/.test(hhmm) || t.price == null) continue
      // 邊界外（開盤前 / 剛收盤寬限窗）夾到端點避免遺漏最新一筆；同一分鐘後到者覆蓋＝取該分鐘最後成交
      const idx = Math.max(0, Math.min(slots - 1, toMin(hhmm) - openMin))
      priceGrid[idx] = parseFloat(Number(t.price).toFixed(2))
    }
    prices = priceGrid
    // intraday tick 數不足以重算日線 MA / KD → 取指標序列最新值、以整段網格常數填滿畫成水平參考線（畫到收盤）
    const fill = v => dates.map(() => v)
    ma20  = fill(num(lt.ma20))
    ma60  = fill(num(lt.ma60))
    ma240 = fill(num(lt.ma240))
    // 子圖指標同樣以 latest 常數填滿——長度必須跟隨「分鐘網格」而非日線陣列，
    // 貼錯會在前 271 分鐘畫出十年前的值
    for (const key of subKeys) subVals[key] = fill(num(lt[key]))
    subDates = dates
    // 整段分鐘網格：軸標籤只在整點 / 半點顯示，避免數百格 HH:mm 全擠上
    xLabelFormatter = v => v
  } else {
    dates  = dailyDates
    prices = dailyPrices
    ma20   = dailyMa20
    ma60   = dailyMa60
    ma240  = dailyMa240
    for (const key of subKeys) subVals[key] = col(key)
    xLabelFormatter = v => v.substring(0, 7)
    subDates = dates
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

  // ⚠ ez 必須宣告在子圖 y 軸之前：MACD／乖離率的值域只取「目前可視區間」，
  // 若沿用下方原本的宣告位置，y 軸區塊讀 ez 會落在 TDZ（Cannot access 'ez' before initialization）
  const ez = effectiveZoom.value

  // 子圖 Y 軸：三種型態，由指標決定
  //  kd     — 依 K9/D9/J9 值域自適應（有界 0~100，取整條陣列即可），強制涵蓋 20/80 讓超買超賣線恆在畫面內
  //  fixed  — 固定 0~100（RSI、威廉指標本就有界）
  //  zero   — MACD／乖離率：與價位同量級，**只取可視區間**的值域並強制涵蓋 0
  //           （取整條 10 年序列的話，2330 十年價位差兩個數量級，短期間視窗會被壓成一條平線）
  const subYAxis = (() => {
    const base = { gridIndex: 1, type: 'value',
      axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { color: '#f0f0f0' } } }
    if (spec.axis === 'fixed') return { ...base, min: 0, max: 100, interval: 50 }

    const pool = spec.axis === 'zero'
      ? (() => {   // 只取可視區間
          const n = subDates.length
          const lo = Math.max(0, Math.floor(n * ez.start / 100))
          const hi = Math.min(n - 1, Math.ceil(n * ez.end / 100))
          return spec.lines.concat(spec.bars || [])
            .flatMap(([, key]) => (subVals[key] || []).slice(lo, hi + 1))
        })()
      : spec.lines.flatMap(([, key]) => subVals[key] || [])

    const vals = pool.filter(v => v != null && Number.isFinite(v))
    if (!vals.length) return { ...base, min: 0, max: 100, interval: 50 }
    const lo = Math.min(...vals), hi = Math.max(...vals)

    if (spec.axis === 'zero') {
      // 不對齊 10 的倍數：BIAS 典型值域僅 ±5，10 的粒度會把線壓平。沿用股價軸的 pad fallback 鏈。
      const pad = (hi - lo) * 0.1 || Math.abs(hi) * 0.001 || 1
      const min = Math.min(0, lo - pad)
      const max = Math.max(0, hi + pad)
      return { ...base, min, max, interval: (max - min) / 2 }
    }
    // kd：刻度只放三個且必須等距——min/max 對齊 10 的倍數後 interval 取 (max-min)/2，
    // range 恆為 10 的倍數故 interval 必為整數。
    // ⚠ 不可只給 min/max 讓 echarts 自己挑間隔（會生出 -40/0/100/130 這種不等距刻度）；
    // ⚠ 也不可對齊到 50 的倍數（J9 跌破 0 一點點就被 floor 到 -50，線被壓成一半高度）。
    const pad = (hi - lo) * 0.1 || 5
    const min = Math.min(20, Math.floor((lo - pad) / 10) * 10)
    const max = Math.max(80, Math.ceil((hi + pad) / 10) * 10)
    return { ...base, min, max, interval: (max - min) / 2 }
  })()

  // 最高 / 最低點：只在目前可視區間內找（資料一次載 10 年、期間鈕只調縮放窗，不可用 ECharts 原生 markPoint max/min）

  // x 軸標籤：日線只在「月份切換」的那一格顯示——ECharts 預設每隔 N 格顯示一個，
  // 而 N 個交易日常落在同一個月，實機因此出現連續好幾個「2025-08」。
  // 再依目前可視範圍抽稀到最多 10 個，10 年期間才不會擠成一團；不旋轉，維持水平好讀。
  const xLabelInterval = intraday
    ? ((idx, val) => typeof val === 'string' && (val.endsWith(':00') || val.endsWith(':30')))
    : (() => {
        const lo = Math.max(0, Math.floor(dates.length * ez.start / 100))
        const hi = Math.min(dates.length - 1, Math.ceil(dates.length * ez.end / 100))
        const monthStarts = []
        for (let i = lo; i <= hi; i++) {
          const cur = String(dates[i] || '').substring(0, 7)
          const prev = i > 0 ? String(dates[i - 1] || '').substring(0, 7) : null
          if (cur && cur !== prev) monthStarts.push(i)
        }
        const stride = Math.max(1, Math.ceil(monthStarts.length / 10))
        const show = new Set(monthStarts.filter((_, k) => k % stride === 0))
        return idx => show.has(idx)
      })()
  let markData = []
  const totalPts = prices.length
  if (totalPts > 0) {
    const loIdx = Math.max(0, Math.floor((ez.start / 100) * (totalPts - 1)))
    const hiIdx = Math.min(totalPts - 1, Math.ceil((ez.end / 100) * (totalPts - 1)))
    markData = maxMinMarkPoints(prices, dates, loIdx, hiIdx)
  }

  // 「當日」模式 Y 軸鎖定當日股價區間（+10% padding），避免被遠離現價的均線水平線（尤其年線 MA240
  // 常在多頭時遠低於現價）撐平走勢、日內波動被壓成一條平線；日線模式維持 scale:true。均線 / 成本均價
  // 落在區間外時由 series clip 自動裁切，數值仍保留在 legend（比照指數圖 GdpTwseView Task 96.7 同一修法）。
  let priceYAxis = {
    gridIndex: 0, type: 'value', scale: true,
    axisLabel: { formatter: v => v.toFixed(0) }, splitLine: { lineStyle: { color: '#f0f0f0' } }
  }
  if (intraday) {
    const vals = prices.filter(v => v != null)
    if (vals.length) {
      const lo = Math.min(...vals), hi = Math.max(...vals)
      const pad = (hi - lo) * 0.1 || hi * 0.001 || 1
      priceYAxis = {
        gridIndex: 0, type: 'value', min: lo - pad, max: hi + pad,
        axisLabel: { formatter: v => v.toFixed(2) }, splitLine: { lineStyle: { color: '#f0f0f0' } }
      }
    }
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
      const fmt = v => (v == null ? '' : Number(v).toLocaleString('en-US', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
      }))
      // 子圖指標（依 selectedIndicator 而定）的最新值一律取 BFF 的 latest（＝指標序列本身最後一筆），
      // 與「當日」水平線同值；均線同理，確保切換期間看到相同數字
      // 子圖 legend 的項目依選單而變（畫線的 + 只顯示數值的；OSC 柱狀不列 legend）
      const subLegend = [...spec.lines, ...(spec.legendOnly || [])]
      const map = {
        // 當日網格末格恆為未來 null → 取最後一筆非 null 分時價（日線模式 == 末格，行為不變）
        '股價':       fmt(lastNonNull(prices)),
        '月線MA20':   fmt(num(lt.ma20)),
        '季線MA60':   fmt(num(lt.ma60)),
        '年線MA240':  fmt(num(lt.ma240)),
        '成本均價':   cost != null ? fmt(cost) : ''
      }
      for (const [name, key] of subLegend) map[name] = fmt(num(lt[key]))
      // 漲跌箭頭：只加在子圖指標上（股價／均線／成本均價維持無箭頭）。
      // 比較基準為指標序列的最後兩筆（BFF 已備妥 prev*），台股慣例漲紅跌綠。
      const arrowOf = (cur, prev) => {
        const a = num(cur), b = num(prev)
        if (a == null || b == null || a === b) return null
        return a > b ? { ch: '▲', color: '#dc2626' } : { ch: '▼', color: '#16a34a' }
      }
      // BFF 的 latest 帶了每個欄位的 prev*（欄名為 prev + 首字大寫）
      const arrowMap = {}
      for (const [name, key] of subLegend) {
        arrowMap[name] = arrowOf(lt[key], lt['prev' + key.charAt(0).toUpperCase() + key.slice(1)])
      }
      // 每個 series 在 legend 數值的色彩，對應線條顏色（與 logo 一致）。
      const colorMap = {
        '股價':      '#3b82f6',
        '月線MA20':  '#f59e0b',
        '季線MA60':  '#8b5cf6',
        '年線MA240': '#ef4444',
        '成本均價':  '#64748b'
      }
      // 畫線的用自己的顏色；只顯示數值的（K3D2/RSV、EMA12/EMA26、B10-B20）用中性深灰
      for (const [name, , color] of spec.lines) colorMap[name] = color
      for (const [name] of (spec.legendOnly || [])) colorMap[name] = '#334155'
      // 用 index 當 rich key：colorMap 仍含「股價」「月線MA20」等中文鍵，
      // 而 zrender 的 rich style key 只接受 [a-zA-Z0-9_]
      const keyByName = {}, arrowKeyByName = {}
      const richStyles = { n: { fontSize: 12, color: '#475569', lineHeight: 16 } }
      Object.entries(colorMap).forEach(([name, color], i) => {
        const k = 'v' + i
        keyByName[name] = k
        richStyles[k] = {
          fontSize: 12, color, lineHeight: 16, fontWeight: 700, padding: [2, 0, 0, 0]
        }
        const arrow = arrowMap[name]
        if (arrow) {
          const ak = 'a' + i
          arrowKeyByName[name] = ak
          richStyles[ak] = {
            fontSize: 11, color: arrow.color, lineHeight: 16, fontWeight: 700, padding: [2, 0, 0, 3]
          }
        }
      })
      const formatter = name => {
        if (!map[name]) return name
        const arrow = arrowMap[name]
        const valuePart = `{${keyByName[name] || 'n'}|${map[name]}}`
        const arrowPart = arrow ? `{${arrowKeyByName[name]}|${arrow.ch}}` : ''
        return `{n|${name}}\n${valuePart}${arrowPart}`
      }
      const textStyle = { fontSize: 12, color: '#475569', rich: richStyles }
      // 兩組 legend，各自貼著自己的 pane：股價／均線在上圖頂端，子圖指標移到
      // 兩張圖中間（＝KD 子圖正上方）。十項全擠在頂端一列會過密且與股價無關聯。
      // KD 那組用 bottom 定位（不依賴容器總高）：grid[1] 頂端距底部 = bottom 60 + height 155 = 215，
      // legend 兩行約 36px，加上下各 16px 間隙 → bottom 231，落在 231~267，grid[0] 則收在 283。
      return [
        {
          data: cost != null
            ? ['股價', '月線MA20', '季線MA60', '年線MA240', '成本均價']
            : ['股價', '月線MA20', '季線MA60', '年線MA240'],
          top: 8,
          itemGap: 30,
          formatter, textStyle
        },
        {
          data: subLegend.map(([name]) => name),
          bottom: 231,
          itemGap: 30,
          formatter, textStyle
        }
      ]
    })(),
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      // 容器總高 580。上下 pane 約 58:42（225 / 155）——子圖過矮時三條 KD 線會擠成一團看不出交叉。
      // bottom 283 = grid[1] 頂端(215) + 16 + KD legend 一列(36) + 16，
      // legend 上下各留 16px 呼吸空間；只留 6~8px 時它會緊貼上圖底軸，實機看起來很擠。
      { left: 64, right: 96, top: 72, bottom: 283 },
      { left: 64, right: 96, top: 'auto', height: 155, bottom: 60 }
    ],
    dataZoom: [
      // 期間按鈕只調 dataZoom 窗（不 roundtrip）；ez 合成自手動拖曳(zoomPct)優先、否則期間預設，最高/最低標記同窗
      { type: 'inside', xAxisIndex: [0, 1], start: ez.start, end: ez.end },
      { type: 'slider', xAxisIndex: [0, 1], start: ez.start, end: ez.end, height: 20, bottom: 8 }
    ],
    xAxis: [
      { gridIndex: 0, type: 'category', data: dates, boundaryGap: false, axisLabel: { show: false }, axisLine: { onZero: false } },
      { gridIndex: 1, type: 'category', data: dates, boundaryGap: false,
        axisLabel: { rotate: 0, fontSize: 10, margin: 12, hideOverlap: true,
          formatter: xLabelFormatter, interval: xLabelInterval } }
    ],
    yAxis: [
      priceYAxis,
      subYAxis
    ],
    series: [
      { name: '股價', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: prices, labelLayout: SHIFT_Y,
        // 當日：稀疏 tick 落在整段分鐘網格上，connectNulls 讓 2 分輪詢 / 5 分 K 之間連成連續線；
        // 末端未來時段的 trailing null 無後續點不會被橋接，故線正確止於最新一筆
        connectNulls: intraday,
        lineStyle: { width: 2, color: '#3b82f6' }, itemStyle: { color: '#3b82f6' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#3b82f6', fontWeight: 700 },
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: 'rgba(59,130,246,0.12)' }, { offset: 1, color: 'rgba(59,130,246,0)' }] } },
        markPoint: {
          symbol: 'circle',
          symbolSize: 9,
          data: markData,
          // 第一行「最高/最低 + 股價」、第二行日期（當日模式為時間 HH:mm）；色塊（紅/綠底白字）置於點外、位置自適應避邊
          label: {
            show: true, color: '#fff', fontSize: 11, fontWeight: 'bold', lineHeight: 15,
            align: 'center', padding: [3, 6], borderRadius: 4, distance: 7,
            formatter: p => `${p.name} ${p.value}\n${p.data.xlabel}`
          }
        }
      },
      { name: '月線MA20', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma20, labelLayout: SHIFT_Y,
        lineStyle: { width: 1.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#f59e0b' } },
      { name: '季線MA60', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma60, labelLayout: SHIFT_Y,
        lineStyle: { width: 1.5, color: '#8b5cf6' }, itemStyle: { color: '#8b5cf6' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#8b5cf6' } },
      { name: '年線MA240', type: 'line', xAxisIndex: 0, yAxisIndex: 0, data: ma240, labelLayout: SHIFT_Y,
        lineStyle: { width: 1.5, color: '#ef4444' }, itemStyle: { color: '#ef4444' }, showSymbol: false,
        endLabel: { show: true, formatter: '{c}', fontSize: 11, color: '#ef4444' } },
      ...(cost != null ? [{
        name: '成本均價', type: 'line', xAxisIndex: 0, yAxisIndex: 0, labelLayout: SHIFT_Y,
        data: dates.map(() => parseFloat(cost.toFixed(2))),
        lineStyle: { color: '#64748b', type: 'dashed', width: 1.5 },
        itemStyle: { color: '#64748b' }, showSymbol: false,
        endLabel: {
          show: true, formatter: '成本 {c}', fontSize: 11,
          color: lastNonNull(prices) >= cost ? '#16a34a' : '#ef4444'
        }
      }] : []),
      // ── 子圖：依指標選單動態產生 ──────────────────────────────
      // 畫線的指標；參考虛線（80/20、70/30、20/80 或 0 軸）掛在第一條上
      ...spec.lines.map(([name, key, color, dashed], idx) => ({
        name, type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: subVals[key] || [],
        lineStyle: { width: 1.5, color, ...(dashed ? { type: 'dashed' } : {}) },
        itemStyle: { color }, showSymbol: false,
        ...(idx === 0 ? {
          markLine: {
            silent: true, data: spec.refs.map(v => ({ yAxis: v })),
            lineStyle: { color: '#94a3b8', type: 'dashed', width: 1 },
            label: { formatter: '{c}', fontSize: 10, color: '#94a3b8' }
          }
        } : {})
      })),
      // MACD 的 OSC：柱狀，正紅負綠（台股慣例）。⚠ 需 use(BarChart)，漏註冊會靜默不畫
      ...(spec.bars || []).map(([name, key]) => ({
        name, type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: subVals[key] || [],
        itemStyle: { color: p => (p.value >= 0 ? '#dc2626' : '#16a34a') },
        barMaxWidth: 6
      })),
      // 只在 legend 顯示數值、不畫線的欄位（K3D2/RSV、EMA12/EMA26、B10-B20）。
      // 空 series 不可省：ECharts LegendView 找不到同名 series 時，該 legend 項目
      // 連同 formatter 產生的數值都不會被畫出來（production build 連 warning 都沒有）。
      ...(spec.legendOnly || []).map(([name]) => ({
        name, type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: [],
        itemStyle: { color: '#334155' }, showSymbol: false
      }))
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
/* 「當日」昨收 / 今日漲跌資訊列（僅當日期間顯示） */
.intraday-quote   { display:flex;align-items:baseline;gap:8px;margin:0 0 4px 2px;font-size:13px;line-height:1.4 }
.intraday-quote .iq-label { color:#64748b }
.intraday-quote .iq-val   { color:#1e293b;font-weight:700;margin-right:8px }
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
