<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px">
          <span class="section-title">台股大盤每日收盤（近 10 年，含月線/季線/年線）</span>
          <div style="display:flex;align-items:center;gap:12px">
            <el-radio-group v-model="dailyRange" size="small">
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
      <el-empty v-else description="尚無日線資料，請先按「回補日線（10 年）」" />
    </el-card>

    <el-card style="margin-top:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">台灣人均 GDP vs 台股大盤年末收盤（近 30 年）</span>
          <el-button size="small" @click="onRefresh" :loading="refreshing">
            回補資料（IMF + TWSE）
          </el-button>
        </div>
      </template>
      <v-chart v-if="hasData" :option="chartOption" style="height:520px" autoresize />
      <el-empty v-else description="尚無資料" />
    </el-card>

    <el-card style="margin-top:20px">
      <template #header>
        <span class="section-title">台韓人均 GDP 比較（近 30 年）</span>
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
const twse = ref([])
const twGrowth = ref([])
const krGrowth = ref([])
const refreshing = ref(false)

// 大盤日線（近 10 年）
const dailyDates = ref([])
const dailyCloses = ref([])
const dailyMa20 = ref([])
const dailyMa60 = ref([])
const dailyMa240 = ref([])
const dailyRange = ref('1y')
const dailyRefreshing = ref(false)

const hasData = computed(() => years.value.length > 0)
const hasDailyData = computed(() => dailyDates.value.length > 0)

function num(v) { return v == null ? null : Number(v) }

async function fetchData() {
  try {
    const res = await bffApi.gdpTwse.get(30)
    years.value = res.years ?? []
    gdp.value = (res.gdpPerCapitaUsd ?? []).map(num)
    koreaGdp.value = (res.koreaGdpPerCapitaUsd ?? []).map(num)
    twse.value = (res.twseYearEndClose ?? []).map(num)
    twGrowth.value = (res.taiwanGdpGrowthRate ?? []).map(num)
    krGrowth.value = (res.koreaGdpGrowthRate ?? []).map(num)
  } catch {}
}

async function fetchDailyData() {
  try {
    const res = await bffApi.gdpTwse.getTwseDaily(10)
    dailyDates.value = res.dates ?? []
    dailyCloses.value = (res.closes ?? []).map(num)
    dailyMa20.value = (res.ma20 ?? []).map(num)
    dailyMa60.value = (res.ma60 ?? []).map(num)
    dailyMa240.value = (res.ma240 ?? []).map(num)
  } catch {}
}

onMounted(() => {
  Promise.allSettled([fetchData(), fetchDailyData()])
})

async function onRefresh() {
  refreshing.value = true
  try {
    const r = await bffApi.gdpTwse.refresh(30)
    const g = r.gdp?.upserted ?? 0
    const k = r.korea?.upserted ?? 0
    const t = r.twse?.upserted ?? 0
    const d = r.twseDaily?.upserted ?? 0
    ElMessage.success(`回補完成：台灣 GDP ${g} 筆、韓國 GDP ${k} 筆、大盤年末 ${t} 筆、大盤日線 ${d} 筆`)
    await Promise.allSettled([fetchData(), fetchDailyData()])
  } catch {} finally {
    refreshing.value = false
  }
}

async function onRefreshDaily() {
  dailyRefreshing.value = true
  try {
    const r = await bffApi.gdpTwse.refreshTwseDaily(10)
    ElMessage.success(`大盤日線回補完成：${r.upserted ?? 0} 筆（${r.from} ~ ${r.to}）`)
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

const dailyChartOption = computed(() => ({
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
        '收盤': dailyCloses.value,
        '月線 (MA20)': dailyMa20.value,
        '季線 (MA60)': dailyMa60.value,
        '年線 (MA240)': dailyMa240.value
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
    data: dailyDates.value,
    axisLabel: { fontSize: 11 }
  },
  yAxis: {
    type: 'value',
    name: '收盤點位',
    scale: true,
    axisLabel: { formatter: v => v.toLocaleString() }
  },
  dataZoom: [
    { type: 'inside', start: dailyZoomRange.value.start, end: dailyZoomRange.value.end },
    { type: 'slider', start: dailyZoomRange.value.start, end: dailyZoomRange.value.end, height: 20, bottom: 10 }
  ],
  series: [
    {
      name: '收盤',
      type: 'line',
      data: dailyCloses.value,
      showSymbol: false,
      sampling: 'lttb',
      lineStyle: { width: 1.5, color: '#1f2937' },
      itemStyle: { color: '#1f2937' }
    },
    {
      name: '月線 (MA20)',
      type: 'line',
      data: dailyMa20.value,
      showSymbol: false,
      smooth: true,
      lineStyle: { width: 1.5, color: '#f59e0b' },
      itemStyle: { color: '#f59e0b' }
    },
    {
      name: '季線 (MA60)',
      type: 'line',
      data: dailyMa60.value,
      showSymbol: false,
      smooth: true,
      lineStyle: { width: 1.5, color: '#10b981' },
      itemStyle: { color: '#10b981' }
    },
    {
      name: '年線 (MA240)',
      type: 'line',
      data: dailyMa240.value,
      showSymbol: false,
      smooth: true,
      lineStyle: { width: 1.5, color: '#3b82f6' },
      itemStyle: { color: '#3b82f6' }
    }
  ]
}))

// 人均 GDP 年增率（USD 基礎）：以前一年值反推 ((curr - prev) / prev × 100)；首個年無 prev → null
const gdpYoy = computed(() => gdp.value.map((v, i) => {
  if (i === 0 || v == null || gdp.value[i - 1] == null || gdp.value[i - 1] === 0) return null
  return Number(((v - gdp.value[i - 1]) / gdp.value[i - 1] * 100).toFixed(2))
}))

const chartOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    formatter: params => {
      const year = params[0]?.axisValue
      let s = `<strong>${year}</strong><br/>`
      params.forEach(p => {
        const v = p.value
        let txt
        if (v == null) txt = '-'
        else if (p.seriesName.includes('成長率')) txt = `${v >= 0 ? '+' : ''}${v.toFixed(2)}%`
        else if (p.seriesName.includes('GDP')) txt = `US$ ${Number(v).toLocaleString()}`
        else txt = Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
        s += `${p.marker}${p.seriesName}: ${txt}<br/>`
      })
      return s
    }
  },
  legend: { data: ['人均 GDP (USD)', '台股大盤年末收盤', '人均 GDP 成長率'], top: 0 },
  grid: { left: 130, right: 70, top: 50, bottom: 60 },
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
      name: '大盤收盤點位',
      position: 'right',
      axisLine: { show: true, lineStyle: { color: '#dc2626' } },
      axisLabel: { formatter: v => v.toLocaleString() }
    },
    {
      type: 'value',
      name: '成長率 (%)',
      position: 'left',
      offset: 60,
      axisLine: { show: true, lineStyle: { color: '#94a3b8' } },
      axisLabel: { formatter: v => `${v}%` },
      splitLine: { show: false }
    }
  ],
  dataZoom: [
    { type: 'inside', start: 0, end: 100 },
    { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }
  ],
  series: [
    {
      name: '人均 GDP (USD)',
      type: 'line',
      yAxisIndex: 0,
      data: gdp.value,
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2, color: '#3b82f6' },
      itemStyle: { color: '#3b82f6' }
    },
    {
      name: '台股大盤年末收盤',
      type: 'line',
      yAxisIndex: 1,
      data: twse.value,
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2, color: '#dc2626' },
      itemStyle: { color: '#dc2626' }
    },
    {
      name: '人均 GDP 成長率',
      type: 'bar',
      yAxisIndex: 2,
      data: gdpYoy.value,
      barWidth: '40%',
      itemStyle: {
        color: p => (p.value == null ? '#94a3b8' : (p.value >= 0 ? '#10b98155' : '#ef444455')),
        borderColor: p => (p.value == null ? '#94a3b8' : (p.value >= 0 ? '#10b981' : '#ef4444')),
        borderWidth: 1
      }
    }
  ]
}))

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
