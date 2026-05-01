<template>
  <div>
    <el-card>
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

const hasData = computed(() => years.value.length > 0)

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

onMounted(fetchData)

async function onRefresh() {
  refreshing.value = true
  try {
    const r = await bffApi.gdpTwse.refresh(30)
    const g = r.gdp?.upserted ?? 0
    const k = r.korea?.upserted ?? 0
    const t = r.twse?.upserted ?? 0
    ElMessage.success(`回補完成：台灣 GDP ${g} 筆、韓國 GDP ${k} 筆、大盤 ${t} 筆`)
    await fetchData()
  } catch {} finally {
    refreshing.value = false
  }
}

const chartOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    formatter: params => {
      const year = params[0]?.axisValue
      let s = `<strong>${year}</strong><br/>`
      params.forEach(p => {
        const v = p.value
        const txt = v == null ? '-' :
          (p.seriesName.includes('GDP')
            ? `US$ ${Number(v).toLocaleString()}`
            : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }))
        s += `${p.marker}${p.seriesName}: ${txt}<br/>`
      })
      return s
    }
  },
  legend: { data: ['人均 GDP (USD)', '台股大盤年末收盤'], top: 0 },
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
      name: '大盤收盤點位',
      position: 'right',
      axisLine: { show: true, lineStyle: { color: '#dc2626' } },
      axisLabel: { formatter: v => v.toLocaleString() }
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
