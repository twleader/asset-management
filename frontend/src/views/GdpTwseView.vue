<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px">
          <div style="display:flex;align-items:baseline;gap:14px;flex-wrap:wrap">
            <span class="section-title">{{ cardTitle }}</span>
            <span v-if="isIntraday && intradayPrevClose != null"
                  style="display:flex;align-items:baseline;gap:12px;font-size:13px">
              <span style="color:#64748b">昨收 {{ fmtPoint(intradayPrevClose) }}</span>
              <span :style="{ color: priceColor(intradayChange), fontWeight: 600 }">{{ fmtChange(intradayChange) }}</span>
              <span :style="{ color: priceColor(intradayChange), fontWeight: 600 }">{{ fmtPct(intradayChangePct) }}</span>
            </span>
          </div>
          <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
            <el-select v-model="market" size="small" style="width:150px" @change="onMarketChange">
              <el-option v-for="m in MARKETS" :key="m.value" :label="m.label" :value="m.value" />
            </el-select>
            <el-radio-group v-model="dailyRange" size="small">
              <el-radio-button label="d">當日</el-radio-button>
              <el-radio-button label="1m">1 個月</el-radio-button>
              <el-radio-button label="3m">3 個月</el-radio-button>
              <el-radio-button label="6m">半年</el-radio-button>
              <el-radio-button label="1y">1 年</el-radio-button>
              <el-radio-button label="2y">2 年</el-radio-button>
              <el-radio-button label="5y">5 年</el-radio-button>
              <el-radio-button label="10y">10 年</el-radio-button>
            </el-radio-group>
            <el-button size="small" @click="onRefreshDaily" :loading="dailyRefreshing">
              回補日線（10 年）
            </el-button>
          </div>
        </div>
      </template>
      <v-chart v-if="hasDailyData" :option="dailyChartOption" style="height:480px" autoresize />
      <el-empty v-else :description="emptyDesc" />
    </el-card>

    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">台韓人均 GDP 比較（近 30 年）</span>
          <el-button size="small" @click="onRefresh" :loading="refreshing">
            回補 GDP（IMF）
          </el-button>
        </div>
      </template>
      <v-chart v-if="hasData" :option="compareChartOption" style="height:520px" autoresize />
      <el-empty v-else description="尚無資料" />
    </el-card>
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent,
  GridComponent, DataZoomComponent, MarkPointComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { bffApi } from '@/api'
import { ElMessage } from 'element-plus'

use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, LegendComponent,
     GridComponent, DataZoomComponent, MarkPointComponent])

const years = ref([])
const gdp = ref([])
const koreaGdp = ref([])
const twGrowth = ref([])
const krGrowth = ref([])
const refreshing = ref(false)

// 指數日線（近 10 年）— 可切換台股大盤與美股四大指數
const MARKETS = [
  { value: 'TWSE', label: '台股大盤' },
  { value: 'DJI',  label: '道瓊工業' },
  { value: 'SPX',  label: '標普 500' },
  { value: 'IXIC', label: '那斯達克綜合' },
  { value: 'SOX',  label: '費城半導體' }
]
const market = ref('TWSE')
const marketLabel = computed(() => MARKETS.find(m => m.value === market.value)?.label ?? '台股大盤')
const dailyDates = ref([])
const dailyCloses = ref([])
const dailyMa20 = ref([])
const dailyMa60 = ref([])
const dailyMa240 = ref([])
const dailyRange = ref('1y')
const dailyRefreshing = ref(false)

// 「當日」分時（盤中即時 / 盤後最後交易日）
const intradayTimes = ref([])
const intradayCloses = ref([])
const intradayDate = ref(null)
const intradayPrevClose = ref(null)   // 昨日收盤（BFF 由日線表算，與觀察清單同一事實來源）
const intradayChange = ref(null)      // 漲跌＝最新點位 − 昨收
const intradayChangePct = ref(null)   // 漲跌%
const isIntraday = computed(() => dailyRange.value === 'd')

const hasData = computed(() => years.value.length > 0)
const hasDailyData = computed(() =>
  isIntraday.value ? intradayTimes.value.length > 0 : dailyDates.value.length > 0)

const cardTitle = computed(() =>
  isIntraday.value
    ? `${marketLabel.value}當日走勢${intradayDate.value ? `（${intradayDate.value}）` : ''}`
    : `${marketLabel.value}每日收盤（近 10 年，含月線/季線/年線）`)
const emptyDesc = computed(() =>
  isIntraday.value
    ? `尚無${marketLabel.value}當日分時資料`
    : `尚無${marketLabel.value}日線資料，請先按「回補日線（10 年）」`)

function num(v) { return v == null ? null : Number(v) }
function lastOf(arr) { for (let i = arr.length - 1; i >= 0; i--) { if (arr[i] != null) return arr[i] } return null }

// 當日昨收/漲跌顯示（指數為點位、不帶 $；紅漲綠跌比照觀察清單 priceColor）
function fmtPoint(v) { return v == null ? '—' : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmtChange(v) {
  if (v == null) return '—'
  const n = Number(v), sign = n > 0 ? '▲' : n < 0 ? '▼' : ''
  return `${sign}${Math.abs(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
function fmtPct(v) { if (v == null) return '—'; const n = Number(v); return `${n > 0 ? '+' : ''}${n.toFixed(2)}%` }
function priceColor(v) { if (v == null) return '#475569'; const n = Number(v); return n > 0 ? '#dc2626' : n < 0 ? '#16a34a' : '#475569' }

async function fetchData() {
  try {
    const res = await bffApi.gdpTwse.get(30)
    years.value = res.years ?? []
    gdp.value = (res.gdpPerCapitaUsd ?? []).map(num)
    koreaGdp.value = (res.koreaGdpPerCapitaUsd ?? []).map(num)
    twGrowth.value = (res.taiwanGdpGrowthRate ?? []).map(num)
    krGrowth.value = (res.koreaGdpGrowthRate ?? []).map(num)
  } catch {}
}

async function fetchDailyData() {
  try {
    const res = await bffApi.gdpTwse.getIndexDaily(market.value, 10)
    dailyDates.value = res.dates ?? []
    dailyCloses.value = (res.closes ?? []).map(num)
    dailyMa20.value = (res.ma20 ?? []).map(num)
    dailyMa60.value = (res.ma60 ?? []).map(num)
    dailyMa240.value = (res.ma240 ?? []).map(num)
  } catch {}
}

async function fetchIntraday() {
  try {
    const res = await bffApi.gdpTwse.getIndexIntraday(market.value)
    intradayTimes.value = res.times ?? []
    intradayCloses.value = (res.closes ?? []).map(num)
    intradayDate.value = res.tradingDate ?? null
    intradayPrevClose.value = num(res.previousClose)
    intradayChange.value = num(res.change)
    intradayChangePct.value = num(res.changePercent)
  } catch {}
}

function onMarketChange() {
  // 日線（含 MA 水平線值）必抓；當日模式同時重抓分時
  fetchDailyData()
  if (isIntraday.value) fetchIntraday()
}

// 切到「當日」即時抓分時（每次切入都重抓以反映最新）
watch(dailyRange, v => { if (v === 'd') fetchIntraday() })

onMounted(() => {
  Promise.allSettled([fetchData(), fetchDailyData()])
})

async function onRefresh() {
  refreshing.value = true
  try {
    const r = await bffApi.gdpTwse.refresh(30)
    const g = r.gdp?.upserted ?? 0
    const k = r.korea?.upserted ?? 0
    ElMessage.success(`GDP 回補完成：台灣 ${g} 筆、韓國 ${k} 筆`)
    await fetchData()
  } catch {} finally {
    refreshing.value = false
  }
}

async function onRefreshDaily() {
  dailyRefreshing.value = true
  try {
    const r = await bffApi.gdpTwse.refreshIndexDaily(market.value, 10)
    ElMessage.success(`${marketLabel.value}日線回補完成：${r.upserted ?? 0} 筆（${r.from} ~ ${r.to}）`)
    await fetchDailyData()
  } catch {} finally {
    dailyRefreshing.value = false
  }
}

// 區間 → dataZoom start/end（百分比，以陣列末端對齊）
const RANGE_TRADING_DAYS = {
  '1m': 21, '3m': 63, '6m': 125, '1y': 250, '2y': 500, '5y': 1250, '10y': 2500
}
const dailyZoomRange = computed(() => {
  const total = dailyDates.value.length
  if (total === 0) return { start: 0, end: 100 }
  const want = RANGE_TRADING_DAYS[dailyRange.value] ?? 250
  const startIdx = Math.max(0, total - want)
  return { start: (startIdx / total) * 100, end: 100 }
})

const dailyChartOption = computed(() => {
  const intraday = isIntraday.value
  // 當日模式：x 軸為分時 HH:mm、收盤＝分時 closes、月/季/年線改畫水平參考線（取日線最新 MA 值，同口徑）
  const xData = intraday ? intradayTimes.value : dailyDates.value
  const closeData = intraday ? intradayCloses.value : dailyCloses.value
  const ma20Data = intraday ? xData.map(() => lastOf(dailyMa20.value)) : dailyMa20.value
  const ma60Data = intraday ? xData.map(() => lastOf(dailyMa60.value)) : dailyMa60.value
  const ma240Data = intraday ? xData.map(() => lastOf(dailyMa240.value)) : dailyMa240.value
  const dataZoom = intraday
    ? [{ type: 'inside', start: 0, end: 100 }, { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }]
    : [
        { type: 'inside', start: dailyZoomRange.value.start, end: dailyZoomRange.value.end },
        { type: 'slider', start: dailyZoomRange.value.start, end: dailyZoomRange.value.end, height: 20, bottom: 10 }
      ]
  // 當日模式 Y 軸鎖定「當日價格區間」(+10% padding)，避免被遠離當日價位的均線水平線撐平走勢；
  // 日線模式維持 scale:true。均線水平線落在區間外時由 series clip 自動裁切，數值仍保留在 legend。
  let yAxis = { type: 'value', name: '收盤點位', scale: true, axisLabel: { formatter: v => v.toLocaleString() } }
  if (intraday) {
    const vals = closeData.filter(v => v != null)
    if (vals.length) {
      const lo = Math.min(...vals), hi = Math.max(...vals)
      const pad = (hi - lo) * 0.1 || hi * 0.001 || 1
      yAxis = {
        type: 'value', name: '收盤點位',
        min: lo - pad, max: hi + pad,
        axisLabel: { formatter: v => v.toLocaleString() }
      }
    }
  }
  return {
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        if (!params || params.length === 0) return ''
        let s = `<strong>${params[0].axisValue}</strong><br/>`
        params.forEach(p => {
          const v = p.value
          const txt = v == null ? '-' : Number(v).toLocaleString(undefined, {
            minimumFractionDigits: 2, maximumFractionDigits: 2
          })
          s += `${p.marker}${p.seriesName}: ${txt}<br/>`
        })
        return s
      }
    },
    legend: {
      data: ['收盤', '月線 (MA20)', '季線 (MA60)', '年線 (MA240)'],
      top: 0,
      itemGap: 30,
      textStyle: { lineHeight: 18 },
      formatter: name => {
        const map = {
          '收盤': closeData,
          '月線 (MA20)': ma20Data,
          '季線 (MA60)': ma60Data,
          '年線 (MA240)': ma240Data
        }
        const arr = map[name] ?? []
        let v = null
        for (let i = arr.length - 1; i >= 0; i--) {
          if (arr[i] != null) { v = arr[i]; break }
        }
        if (v == null) return `${name}\n-`
        return `${name}\n${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
      }
    },
    grid: { left: 70, right: 30, top: 70, bottom: 60 },
    xAxis: {
      type: 'category',
      data: xData,
      axisLabel: { fontSize: 11 }
    },
    yAxis,
    dataZoom,
    series: [
      {
        name: '收盤',
        type: 'line',
        data: closeData,
        showSymbol: false,
        sampling: 'lttb',
        lineStyle: { width: 1.5, color: '#1f2937' },
        itemStyle: { color: '#1f2937' }
      },
      {
        name: '月線 (MA20)',
        type: 'line',
        data: ma20Data,
        showSymbol: false,
        smooth: !intraday,
        lineStyle: { width: 1.5, color: '#f59e0b', type: intraday ? 'dashed' : 'solid' },
        itemStyle: { color: '#f59e0b' }
      },
      {
        name: '季線 (MA60)',
        type: 'line',
        data: ma60Data,
        showSymbol: false,
        smooth: !intraday,
        lineStyle: { width: 1.5, color: '#10b981', type: intraday ? 'dashed' : 'solid' },
        itemStyle: { color: '#10b981' }
      },
      {
        name: '年線 (MA240)',
        type: 'line',
        data: ma240Data,
        showSymbol: false,
        smooth: !intraday,
        lineStyle: { width: 1.5, color: '#3b82f6', type: intraday ? 'dashed' : 'solid' },
        itemStyle: { color: '#3b82f6' }
      }
    ]
  }
})

// 成長率改用 IMF NGDP_RPCH（實質 GDP 成長率，twGrowth），不再以人均 GDP（USD）相減推算
// ——USD 相減會被匯率波動扭曲（如 2022 台幣貶值會被算成負成長，但實質仍為 +2.7%）

const compareChartOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: params => {
      const year = params[0]?.axisValue
      let s = `<strong>${year}</strong><br/>`
      params.forEach(p => {
        const v = p.value
        const isGrowth = p.seriesName.includes('成長率')
        const txt = v == null ? '-'
          : isGrowth ? `${Number(v).toFixed(2)}%`
          : `US$ ${Number(v).toLocaleString()}`
        s += `${p.marker}${p.seriesName}: ${txt}<br/>`
      })
      return s
    }
  },
  legend: { data: ['台灣 GDP', '韓國 GDP', '台灣成長率', '韓國成長率'], top: 0 },
  grid: { left: 70, right: 70, top: 50, bottom: 60 },
  xAxis: {
    type: 'category',
    data: years.value,
    axisLabel: { rotate: 30, fontSize: 11 }
  },
  yAxis: [
    {
      type: 'value',
      name: '人均 GDP (USD)',
      position: 'left',
      axisLine: { show: true, lineStyle: { color: '#3b82f6' } },
      axisLabel: { formatter: v => v.toLocaleString() }
    },
    {
      type: 'value',
      name: '年增率 (%)',
      position: 'right',
      axisLine: { show: true, lineStyle: { color: '#64748b' } },
      axisLabel: { formatter: v => `${v}%` }
    }
  ],
  dataZoom: [
    { type: 'inside', start: 0, end: 100 },
    { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }
  ],
  series: [
    {
      name: '台灣成長率',
      type: 'bar',
      yAxisIndex: 1,
      data: twGrowth.value,
      itemStyle: { color: 'rgba(59,130,246,0.55)' },
      barGap: 0
    },
    {
      name: '韓國成長率',
      type: 'bar',
      yAxisIndex: 1,
      data: krGrowth.value,
      itemStyle: { color: 'rgba(239,68,68,0.55)' }
    },
    {
      name: '台灣 GDP',
      type: 'line',
      yAxisIndex: 0,
      data: gdp.value,
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2.5, color: '#1d4ed8' },
      itemStyle: { color: '#1d4ed8' },
      z: 5
    },
    {
      name: '韓國 GDP',
      type: 'line',
      yAxisIndex: 0,
      data: koreaGdp.value,
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2.5, color: '#b91c1c' },
      itemStyle: { color: '#b91c1c' },
      z: 5
    }
  ]
}))
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
</style>
