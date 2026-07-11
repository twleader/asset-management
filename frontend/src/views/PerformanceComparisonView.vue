<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px">
          <span class="section-title">績效比較（報酬率 %，同起點正規化）</span>
          <el-radio-group v-model="range" size="small">
            <el-radio-button label="3m">3 個月</el-radio-button>
            <el-radio-button label="6m">半年</el-radio-button>
            <el-radio-button label="1y">1 年</el-radio-button>
            <el-radio-button label="2y">2 年</el-radio-button>
            <el-radio-button label="5y">5 年</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <div class="controls">
        <div class="control-block">
          <div class="control-label">選擇股票（最多 3 檔，來自你的持股與觀察清單）</div>
          <el-select
            v-model="selectedStocks"
            multiple
            filterable
            :multiple-limit="3"
            collapse-tags
            collapse-tags-tooltip
            placeholder="輸入代號或股名搜尋"
            style="width:100%"
            :loading="stocksLoading"
            no-data-text="尚無可比較的股票（需有歷史收盤資料）">
            <el-option
              v-for="s in myStocks"
              :key="s.code + ':' + s.market"
              :label="optionLabel(s)"
              :value="s.code + ':' + s.market" />
          </el-select>
        </div>
        <div class="control-block">
          <div class="control-label">大盤 / 指數基準</div>
          <el-checkbox-group v-model="selectedBenchmarks" size="small">
            <el-checkbox-button v-for="b in BENCHMARKS" :key="b.value" :label="b.value">
              {{ b.label }}
            </el-checkbox-button>
          </el-checkbox-group>
        </div>
      </div>

      <v-chart v-if="hasData" :option="chartOption" style="height:480px;margin-top:8px" autoresize />
      <el-empty v-else :description="emptyDesc" style="margin-top:8px" />
    </el-card>

    <el-card v-if="summaryRows.length" style="margin-top:20px">
      <template #header>
        <span class="section-title">期間報酬率摘要</span>
      </template>
      <el-table :data="summaryRows" size="small" style="width:100%">
        <el-table-column label="標的" min-width="180">
          <template #default="{ row }">
            <span :style="{ display:'inline-block', width:'10px', height:'10px', borderRadius:'2px', marginRight:'8px', background: row.color }"></span>
            <span>{{ row.label }}</span>
            <el-tag v-if="row.type === 'index'" size="small" type="info" effect="plain" style="margin-left:6px">指數</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="期間報酬率" min-width="140">
          <template #default="{ row }">
            <span v-if="row.totalReturn == null" style="color:#94a3b8">無資料</span>
            <span v-else :style="{ color: priceColor(row.totalReturn), fontWeight: 600 }">{{ fmtPct(row.totalReturn) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="截至日" min-width="120">
          <template #default="{ row }">
            <span style="color:#64748b">{{ row.asOfDate || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="hint">報酬率＝(當日收盤 ÷ 區間起點收盤 − 1)×100%；各標的一律以所選區間起點為 0% 對齊。跨市場交易日不同時以缺日前值延伸，故各標的「截至日」可能不同。</div>
    </el-card>
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent,
  GridComponent, DataZoomComponent, MarkLineComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { bffApi } from '@/api'

// tree-shaking 註冊：0% 基準線用 MarkLineComponent（非 GdpTwseView 的 MarkPointComponent），漏註冊會靜默不畫
use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent,
     GridComponent, DataZoomComponent, MarkLineComponent])

// 5 個大盤基準（固定中文 label，代碼與後端白名單一致）
const BENCHMARKS = [
  { value: 'TWSE', label: '台股大盤' },
  { value: 'DJI',  label: '道瓊工業' },
  { value: 'SPX',  label: '標普500' },
  { value: 'IXIC', label: '那斯達克' },
  { value: 'SOX',  label: '費半' }
]
const BENCH_LABEL = Object.fromEntries(BENCHMARKS.map(b => [b.value, b.label]))

// 多線配色：股票實線、指數虛線，色盤循環指派
const PALETTE = ['#2563eb', '#dc2626', '#16a34a', '#f59e0b', '#7c3aed', '#0891b2', '#db2777', '#65a30d']

const myStocks = ref([])              // [{ code, market, name }]
const stocksLoading = ref(false)
const selectedStocks = ref([])        // ['2330:台股', ...]
const selectedBenchmarks = ref(['TWSE'])
const range = ref('1y')
const chartData = ref({ dates: [], series: [] })
const loading = ref(false)

function num(v) { return v == null ? null : Number(v) }
function fmtPct(v) { if (v == null) return '—'; const n = Number(v); return `${n > 0 ? '+' : ''}${n.toFixed(2)}%` }
function priceColor(v) { if (v == null) return '#475569'; const n = Number(v); return n > 0 ? '#dc2626' : n < 0 ? '#16a34a' : '#475569' }

function optionLabel(s) {
  const nm = s.name && s.name !== s.code ? s.name : ''
  return nm ? `${nm}（${s.code}）` : s.code
}

// key ('2330:台股') → 股名，用於圖例/摘要 label
const stockNameMap = computed(() => {
  const m = {}
  for (const s of myStocks.value) m[`${s.code}:${s.market}`] = s.name
  return m
})
function seriesLabel(s) {
  if (s.type === 'index') return BENCH_LABEL[s.code] || s.code
  const nm = stockNameMap.value[s.key]
  return nm && nm !== s.code ? `${nm}（${s.code}）` : s.code
}

const hasData = computed(() => chartData.value.series.length > 0 && chartData.value.dates.length > 0)
const nothingSelected = computed(() => selectedStocks.value.length === 0 && selectedBenchmarks.value.length === 0)
const emptyDesc = computed(() =>
  nothingSelected.value ? '請選擇股票或勾選大盤基準進行比較'
    : loading.value ? '載入中…' : '所選標的在此區間尚無可比較資料')

const summaryRows = computed(() =>
  chartData.value.series.map((s, i) => ({
    label: seriesLabel(s),
    type: s.type,
    color: PALETTE[i % PALETTE.length],
    totalReturn: num(s.totalReturn),
    asOfDate: s.asOfDate
  })))

async function fetchMyStocks() {
  stocksLoading.value = true
  try {
    myStocks.value = await bffApi.performanceComparison.myStocks() ?? []
  } catch { myStocks.value = [] } finally {
    stocksLoading.value = false
  }
}

async function fetchCompare() {
  const keys = selectedStocks.value
  const codes = selectedBenchmarks.value
  if (keys.length === 0 && codes.length === 0) {
    chartData.value = { dates: [], series: [] }
    return
  }
  loading.value = true
  try {
    const res = await bffApi.performanceComparison.compare(keys, codes, range.value)
    chartData.value = { dates: res.dates ?? [], series: res.series ?? [] }
  } catch {
    chartData.value = { dates: [], series: [] }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchMyStocks()
  fetchCompare()   // 預設勾選 TWSE，進頁即畫大盤基準
})

watch([selectedStocks, selectedBenchmarks, range], fetchCompare, { deep: true })

const chartOption = computed(() => {
  const dates = chartData.value.dates
  const series = chartData.value.series.map((s, i) => {
    const color = PALETTE[i % PALETTE.length]
    const isIndex = s.type === 'index'
    return {
      name: seriesLabel(s),
      type: 'line',
      data: (s.returns ?? []).map(num),
      showSymbol: false,
      connectNulls: true,        // 跨市場缺日前值延伸，不斷線
      sampling: 'lttb',
      lineStyle: { width: 2, color, type: isIndex ? 'dashed' : 'solid' },
      itemStyle: { color }
    }
  })
  // 0% 基準水平線掛在第一條 series（yAxis 值線，不依賴 series 資料點）
  if (series.length > 0) {
    series[0].markLine = {
      silent: true,
      symbol: 'none',
      lineStyle: { color: '#94a3b8', type: 'dashed', width: 1 },
      data: [{ yAxis: 0 }],
      label: { show: true, formatter: '0%', position: 'insideEndTop', color: '#94a3b8' }
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
          const txt = v == null ? '-' : `${v > 0 ? '+' : ''}${Number(v).toFixed(2)}%`
          s += `${p.marker}${p.seriesName}: ${txt}<br/>`
        })
        return s
      }
    },
    legend: { top: 0, type: 'scroll' },
    grid: { left: 60, right: 30, top: 50, bottom: 60 },
    xAxis: { type: 'category', data: dates, axisLabel: { fontSize: 11 } },
    yAxis: {
      type: 'value',
      name: '報酬率 (%)',
      scale: true,
      axisLabel: { formatter: v => `${v}%` },
      splitLine: { lineStyle: { color: '#eef2f7' } }
    },
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },
      { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }
    ],
    series
  }
})
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.controls { display: flex; flex-wrap: wrap; gap: 20px; align-items: flex-start; }
.control-block { flex: 1 1 320px; min-width: 280px; }
.control-label { font-size: 12px; color: #64748b; margin-bottom: 6px; }
.hint { font-size: 12px; color: #94a3b8; margin-top: 10px; line-height: 1.6; }
</style>
