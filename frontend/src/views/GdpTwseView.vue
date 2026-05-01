<template>
  <div>
    <el-card>
      <template #header>
        <span class="section-title">台灣人均 GDP vs 台股大盤年末收盤（近 30 年）</span>
      </template>
      <v-chart v-if="hasData" :option="chartOption" style="height:520px" autoresize />
      <el-empty v-else description="尚無資料" />
    </el-card>
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent,
  GridComponent, DataZoomComponent, MarkPointComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { bffApi } from '@/api'

use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent,
     GridComponent, DataZoomComponent, MarkPointComponent])

const years = ref([])
const gdp = ref([])
const twse = ref([])

const hasData = computed(() => years.value.length > 0)

onMounted(async () => {
  try {
    const res = await bffApi.gdpTwse.get(30)
    years.value = res.years ?? []
    gdp.value = (res.gdpPerCapitaUsd ?? []).map(v => v == null ? null : Number(v))
    twse.value = (res.twseYearEndClose ?? []).map(v => v == null ? null : Number(v))
  } catch {}
})

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
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
</style>
