<template>
  <div>
    <!-- KPI Cards -->
    <el-row :gutter="20" style="margin-bottom:20px">
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">最新匯率 (USD/TWD)</div>
          <div class="kpi-value">{{ latestRate ? latestRate.midRate.toFixed(4) : '-' }}</div>
          <div class="kpi-sub" v-if="latestRate">{{ latestRate.rateDate }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">即期買入</div>
          <div class="kpi-value" style="color:#16a34a">{{ latestRate ? latestRate.buyRate?.toFixed(4) : '-' }}</div>
          <div class="kpi-sub">銀行買入美元</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">即期賣出</div>
          <div class="kpi-value" style="color:#dc2626">{{ latestRate ? latestRate.sellRate?.toFixed(4) : '-' }}</div>
          <div class="kpi-sub">銀行賣出美元</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">資料筆數</div>
          <div class="kpi-value">{{ rateData.length.toLocaleString() }}</div>
          <div class="kpi-sub" v-if="rateData.length">{{ rateData[0].rateDate }} ~ {{ rateData[rateData.length-1].rateDate }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Main Chart -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">台幣兌美元走勢 (USD/TWD)</span>
          <div style="display:flex;gap:8px;align-items:center">
            <el-button-group>
              <el-button size="small" v-for="r in rangeOptions" :key="r.key"
                :type="selectedRange === r.key ? 'primary' : ''"
                @click="selectedRange = r.key">{{ r.label }}</el-button>
            </el-button-group>
            <el-button size="small" @click="onBackfill" :loading="backfilling">回補資料</el-button>
          </div>
        </div>
      </template>
      <v-chart :option="mainChartOption" style="height:420px" autoresize />
    </el-card>

    <!-- Stats -->
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title">區間統計</span></template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="最高">{{ stats.max?.toFixed(4) || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最高日期">{{ stats.maxDate || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最低">{{ stats.min?.toFixed(4) || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最低日期">{{ stats.minDate || '-' }}</el-descriptions-item>
            <el-descriptions-item label="平均">{{ stats.avg?.toFixed(4) || '-' }}</el-descriptions-item>
            <el-descriptions-item label="波動幅度">{{ stats.range?.toFixed(4) || '-' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header><span class="section-title">買入/賣出價差走勢</span></template>
          <v-chart :option="spreadChartOption" style="height:250px" autoresize />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { bffApi } from '@/api'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'

use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent, MarkPointComponent])

const rateData = ref([])
const backfilling = ref(false)
const selectedRange = ref('all')

const rangeOptions = [
  { key: '1m', label: '1月' },
  { key: '3m', label: '3月' },
  { key: '6m', label: '6月' },
  { key: '1y', label: '1年' },
  { key: '2y', label: '2年' },
  { key: 'all', label: '全部' },
]

onMounted(fetchData)

async function fetchData() {
  try {
    // BFF 一支端點：自動 refresh + 回傳 5 年歷史
    const res = await bffApi.exchangeRate.getHistory('USD')
    rateData.value = (res.rates ?? []).map(d => ({
      ...d,
      midRate: Number(d.midRate),
      buyRate: d.buyRate ? Number(d.buyRate) : null,
      sellRate: d.sellRate ? Number(d.sellRate) : null
    }))
  } catch {}
}

async function onBackfill() {
  backfilling.value = true
  try {
    const result = await bffApi.exchangeRate.backfill('USD')
    ElMessage.success(`匯率回補完成，新增 ${result.backfilled} 筆`)
    await fetchData()
  } catch {} finally {
    backfilling.value = false
  }
}

const latestRate = computed(() => {
  if (!rateData.value.length) return null
  return rateData.value[rateData.value.length - 1]
})

const filteredData = computed(() => {
  if (!rateData.value.length) return []
  if (selectedRange.value === 'all') return rateData.value

  const now = dayjs()
  const map = { '1m': 1, '3m': 3, '6m': 6, '1y': 12, '2y': 24 }
  const months = map[selectedRange.value] || 999
  const cutoff = now.subtract(months, 'month').format('YYYY-MM-DD')
  return rateData.value.filter(d => d.rateDate >= cutoff)
})

const stats = computed(() => {
  const data = filteredData.value
  if (!data.length) return {}
  const rates = data.map(d => d.midRate)
  const max = Math.max(...rates)
  const min = Math.min(...rates)
  const avg = rates.reduce((a, b) => a + b, 0) / rates.length
  const maxDate = data.find(d => d.midRate === max)?.rateDate
  const minDate = data.find(d => d.midRate === min)?.rateDate
  return { max, min, avg, range: max - min, maxDate, minDate }
})

const mainChartOption = computed(() => {
  const data = filteredData.value
  if (!data.length) return {}
  return {
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        const p = params[0]
        const d = data[p.dataIndex]
        let s = `<strong>${p.axisValue}</strong><br/>`
        s += `中間價: ${d.midRate.toFixed(4)}<br/>`
        if (d.buyRate) s += `買入: ${d.buyRate.toFixed(4)}<br/>`
        if (d.sellRate) s += `賣出: ${d.sellRate.toFixed(4)}`
        return s
      }
    },
    grid: { left: 60, right: 30, top: 30, bottom: 70 },
    xAxis: {
      type: 'category',
      data: data.map(d => d.rateDate),
      axisLabel: { rotate: 30, fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLabel: { formatter: v => v.toFixed(2) }
    },
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },
      { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }
    ],
    series: [{
      name: 'USD/TWD',
      type: 'line',
      data: data.map(d => d.midRate),
      smooth: false,
      symbol: 'none',
      lineStyle: { width: 1.5, color: '#3b82f6' },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(59,130,246,0.2)' },
            { offset: 1, color: 'rgba(59,130,246,0)' }
          ]
        }
      },
      markPoint: {
        data: [
          { type: 'max', name: '最高' },
          { type: 'min', name: '最低' }
        ],
        symbolSize: 50,
        label: { formatter: p => p.value.toFixed(2) }
      },
      markLine: {
        data: [{ type: 'average', name: '平均' }],
        label: { formatter: p => p.value.toFixed(2) }
      }
    }]
  }
})

const spreadChartOption = computed(() => {
  const data = filteredData.value.filter(d => d.buyRate && d.sellRate)
  if (!data.length) return {}
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, top: 10, bottom: 40 },
    xAxis: { type: 'category', data: data.map(d => d.rateDate), axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: { type: 'value', scale: true, axisLabel: { formatter: v => v.toFixed(2) } },
    series: [
      { name: '買入', type: 'line', data: data.map(d => d.buyRate), symbol: 'none', lineStyle: { width: 1, color: '#16a34a' } },
      { name: '賣出', type: 'line', data: data.map(d => d.sellRate), symbol: 'none', lineStyle: { width: 1, color: '#dc2626' } }
    ]
  }
})
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.kpi-card { text-align: center; }
.kpi-label { font-size: 13px; color: #64748b; margin-bottom: 4px; }
.kpi-value { font-size: 24px; font-weight: 700; color: #1e293b; }
.kpi-sub { font-size: 12px; color: #94a3b8; margin-top: 4px; }
</style>
