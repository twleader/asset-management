<template>
  <div v-loading="loading">
    <!-- KPI Cards：三標的最新價與對前一交易日漲跌 -->
    <el-row :gutter="20" style="margin-bottom:20px">
      <el-col :span="6" v-for="c in COMMODITIES" :key="c.code">
        <el-card class="kpi-card">
          <div class="kpi-label">{{ c.label }}</div>
          <div class="kpi-value">{{ latest[c.code] ? latest[c.code].close.toFixed(2) : '-' }}</div>
          <div class="kpi-sub" v-if="latest[c.code]">
            <span :style="{ color: latest[c.code].change >= 0 ? '#dc2626' : '#16a34a' }">
              {{ latest[c.code].change >= 0 ? '▲' : '▼' }}
              {{ Math.abs(latest[c.code].change).toFixed(2) }}
              ({{ latest[c.code].changePct.toFixed(2) }}%)
            </span>
            ｜{{ latest[c.code].date }}
          </div>
          <div class="kpi-sub" v-else>查無資料</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="kpi-card">
          <div class="kpi-label">資料筆數</div>
          <div class="kpi-value">{{ totalRows.toLocaleString() }}</div>
          <div class="kpi-sub" v-if="dateSpan">{{ dateSpan }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Main Chart -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;justify-content:space-between">
          <span class="section-title">國際油價／金價走勢（美元計價）</span>
          <div style="display:flex;gap:8px;align-items:center">
            <el-button-group>
              <el-button size="small" v-for="r in rangeOptions" :key="r.key"
                :type="selectedRange === r.key ? 'primary' : ''"
                @click="selectedRange = r.key">{{ r.label }}</el-button>
            </el-button-group>
            <el-button size="small" @click="onRefresh" :loading="refreshing">更新資料</el-button>
            <el-button size="small" type="primary" @click="openExport">匯出 Excel</el-button>
          </div>
        </div>
      </template>
      <v-chart :option="mainChartOption" style="height:460px" autoresize />
      <div class="chart-note">
        左軸為原油（USD/桶）、右軸為黃金（USD/盎司）——兩者量級差距大，同軸會使油價曲線貼底。
        點選圖例可單獨顯示某一序列。
      </div>
    </el-card>

    <!-- 區間統計 -->
    <el-card>
      <template #header><span class="section-title">區間統計（{{ currentRangeLabel }}）</span></template>
      <el-table :data="statsRows" size="small" border>
        <el-table-column prop="label" label="標的" width="200" />
        <el-table-column prop="latest" label="最新" align="right" />
        <el-table-column prop="max" label="最高" align="right" />
        <el-table-column prop="min" label="最低" align="right" />
        <el-table-column prop="avg" label="平均" align="right" />
        <el-table-column prop="changePct" label="區間漲跌幅" align="right">
          <template #default="{ row }">
            <span :style="{ color: row.rawChangePct >= 0 ? '#dc2626' : '#16a34a' }">
              {{ row.changePct }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 匯出對話框：指定時間區間，存檔位置由瀏覽器另存對話框決定 -->
    <el-dialog v-model="exportDialog.visible" title="匯出油價金價" width="480px">
      <el-form label-width="90px">
        <el-form-item label="時間區間">
          <el-date-picker
            v-model="exportDialog.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="起始日"
            end-placeholder="結束日"
            :clearable="false"
            style="width:100%"
          />
        </el-form-item>
        <el-form-item label="輸出內容">
          <div class="dialog-note">
            單一 Excel 檔、一張工作表，欄位為
            <b>日期／WTI原油／布蘭特原油／黃金</b>，三個標的依日期對齊；
            某標的當日無報價則留空。
          </div>
        </el-form-item>
        <el-form-item label="存檔位置">
          <div class="dialog-note">
            <template v-if="canPickDirectory">
              按下匯出後會開啟系統「另存新檔」對話框，可自行選擇資料夾與檔名。
            </template>
            <template v-else>
              目前瀏覽器不支援選擇資料夾，檔案將存到瀏覽器預設下載資料夾。
            </template>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="onExport">匯出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
import { ElMessage } from 'element-plus'
import { bffApi } from '@/api'
import dayjs from 'dayjs'

use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent,
     GridComponent, DataZoomComponent])

// 標的定義：yAxis 0＝原油（USD/桶）、1＝黃金（USD/盎司）
const COMMODITIES = [
  { code: 'WTI', label: 'WTI 原油 (USD/桶)', color: '#16a34a', yAxisIndex: 0, unit: 'USD/桶' },
  { code: 'BRENT', label: '布蘭特原油 (USD/桶)', color: '#78716c', yAxisIndex: 0, unit: 'USD/桶' },
  { code: 'GOLD', label: 'COMEX 黃金 (USD/盎司)', color: '#eab308', yAxisIndex: 1, unit: 'USD/盎司' }
]

const rangeOptions = [
  { key: '1m', label: '1M', months: 1 },
  { key: '3m', label: '3M', months: 3 },
  { key: '6m', label: '6M', months: 6 },
  { key: '1y', label: '1Y', months: 12 },
  { key: '3y', label: '3Y', months: 36 },
  { key: '5y', label: '5Y', months: 60 },
  { key: 'all', label: '10Y', months: null }
]

const series = ref({})       // { WTI: [{date, close}], BRENT: [...], GOLD: [...] }
const selectedRange = ref('1y')
const loading = ref(false)
const refreshing = ref(false)
const exporting = ref(false)
const exportDialog = reactive({ visible: false, range: [] })

// File System Access API：可讓使用者自選存檔目錄；Safari／舊版瀏覽器沒有，退回一般下載
const canPickDirectory = typeof window !== 'undefined' && 'showSaveFilePicker' in window

onMounted(fetchData)

async function fetchData() {
  loading.value = true
  try {
    const res = await bffApi.commodityPrice.getHistory()
    series.value = normalize(res.series)
  } catch {
  } finally {
    loading.value = false
  }
}

/** 後端 BigDecimal 序列化為字串／數字皆有可能，統一轉 Number。 */
function normalize(raw) {
  const out = {}
  for (const c of COMMODITIES) {
    out[c.code] = (raw?.[c.code] ?? []).map(d => ({
      date: d.priceDate,
      close: Number(d.closePrice)
    }))
  }
  return out
}

const cutoffDate = computed(() => {
  const opt = rangeOptions.find(r => r.key === selectedRange.value)
  if (!opt?.months) return null
  return dayjs().subtract(opt.months, 'month').format('YYYY-MM-DD')
})

// 區間切換為記憶體切片，不重打 API（日期為 YYYY-MM-DD 字串，可直接比較）
const filtered = computed(() => {
  const cutoff = cutoffDate.value
  const out = {}
  for (const c of COMMODITIES) {
    const rows = series.value[c.code] ?? []
    out[c.code] = cutoff ? rows.filter(d => d.date >= cutoff) : rows
  }
  return out
})

const currentRangeLabel = computed(() =>
  rangeOptions.find(r => r.key === selectedRange.value)?.label ?? '')

/** 三序列聯集的日期軸——各市場假日不完全重疊，取聯集才不會漏掉單邊有報價的日子。 */
const axisDates = computed(() => {
  const set = new Set()
  for (const c of COMMODITIES) for (const d of filtered.value[c.code]) set.add(d.date)
  return [...set].sort()
})

const totalRows = computed(() =>
  COMMODITIES.reduce((sum, c) => sum + (series.value[c.code]?.length ?? 0), 0))

const dateSpan = computed(() => {
  const all = COMMODITIES.flatMap(c => series.value[c.code] ?? []).map(d => d.date).sort()
  return all.length ? `${all[0]} ~ ${all[all.length - 1]}` : ''
})

/** 各標的最新價與對前一交易日漲跌（衍生值，前端即時算，不入庫）。 */
const latest = computed(() => {
  const out = {}
  for (const c of COMMODITIES) {
    const rows = series.value[c.code] ?? []
    if (!rows.length) continue
    const last = rows[rows.length - 1]
    const prev = rows.length > 1 ? rows[rows.length - 2] : null
    const change = prev ? last.close - prev.close : 0
    out[c.code] = {
      close: last.close,
      date: last.date,
      change,
      changePct: prev && prev.close ? (change / prev.close) * 100 : 0
    }
  }
  return out
})

const statsRows = computed(() => COMMODITIES.map(c => {
  const rows = filtered.value[c.code] ?? []
  if (!rows.length) {
    return { label: c.label, latest: '-', max: '-', min: '-', avg: '-', changePct: '-', rawChangePct: 0 }
  }
  const values = rows.map(d => d.close)
  const first = values[0]
  const last = values[values.length - 1]
  const pct = first ? ((last - first) / first) * 100 : 0
  return {
    label: c.label,
    latest: last.toFixed(2),
    max: Math.max(...values).toFixed(2),
    min: Math.min(...values).toFixed(2),
    avg: (values.reduce((a, b) => a + b, 0) / values.length).toFixed(2),
    changePct: `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`,
    rawChangePct: pct
  }
}))

const mainChartOption = computed(() => {
  const dates = axisDates.value
  if (!dates.length) return {}
  // 依聯集日期軸對齊；缺報價的日子放 null，ECharts 會斷點而非畫成 0
  const byDate = {}
  for (const c of COMMODITIES) {
    const map = new Map(filtered.value[c.code].map(d => [d.date, d.close]))
    byDate[c.code] = dates.map(d => (map.has(d) ? map.get(d) : null))
  }
  return {
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        let s = `<strong>${params[0].axisValue}</strong><br/>`
        for (const p of params) {
          if (p.value == null) continue
          s += `${p.marker}${p.seriesName}: ${Number(p.value).toFixed(2)}<br/>`
        }
        return s
      }
    },
    legend: { data: COMMODITIES.map(c => c.label), top: 0 },
    grid: { left: 60, right: 70, top: 40, bottom: 70 },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 30, fontSize: 11 } },
    yAxis: [
      {
        type: 'value', scale: true, name: '原油 USD/桶',
        nameTextStyle: { fontSize: 11, color: '#64748b' },
        axisLabel: { formatter: v => v.toFixed(0) }
      },
      {
        type: 'value', scale: true, name: '黃金 USD/盎司',
        nameTextStyle: { fontSize: 11, color: '#64748b' },
        axisLabel: { formatter: v => v.toFixed(0) },
        splitLine: { show: false }
      }
    ],
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },
      { type: 'slider', start: 0, end: 100, height: 20, bottom: 10 }
    ],
    series: COMMODITIES.map(c => ({
      name: c.label,
      type: 'line',
      yAxisIndex: c.yAxisIndex,
      data: byDate[c.code],
      // 十年約 2,500 點，畫 symbol 會嚴重掉幀
      symbol: 'none',
      smooth: false,
      connectNulls: false,
      lineStyle: { width: 1.5, color: c.color },
      itemStyle: { color: c.color }
    }))
  }
})

async function onRefresh() {
  refreshing.value = true
  try {
    await bffApi.commodityPrice.refresh()
    await fetchData()
    ElMessage.success('油金價資料已更新')
  } catch {
  } finally {
    refreshing.value = false
  }
}

function openExport() {
  // 預設帶入目前圖表所選區間
  const dates = axisDates.value
  const start = cutoffDate.value ?? (dates.length ? dates[0] : dayjs().subtract(10, 'year').format('YYYY-MM-DD'))
  const end = dates.length ? dates[dates.length - 1] : dayjs().format('YYYY-MM-DD')
  exportDialog.range = [start, end]
  exportDialog.visible = true
}

async function onExport() {
  const [start, end] = exportDialog.range ?? []
  if (!start || !end) {
    ElMessage.warning('請選擇匯出時間區間')
    return
  }
  exporting.value = true
  try {
    const blob = await bffApi.commodityPrice.exportExcel(start, end)
    const filename = `油價金價_${start.replaceAll('-', '')}_${end.replaceAll('-', '')}.xlsx`
    const saved = await saveBlob(blob, filename)
    if (saved) {
      ElMessage.success('匯出完成')
      exportDialog.visible = false
    }
  } catch {
  } finally {
    exporting.value = false
  }
}

/**
 * 優先開啟系統「另存新檔」對話框讓使用者選目錄；不支援時退回一般下載。
 * 回傳 false 代表使用者主動取消（不顯示成功訊息）。
 */
async function saveBlob(blob, filename) {
  if (canPickDirectory) {
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName: filename,
        types: [{
          description: 'Excel 活頁簿',
          accept: { 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx'] }
        }]
      })
      const writable = await handle.createWritable()
      await writable.write(blob)
      await writable.close()
      return true
    } catch (e) {
      if (e?.name === 'AbortError') return false   // 使用者按取消
      // 其他錯誤（權限、沙箱等）退回一般下載，功能不中斷
    }
  }
  downloadBlob(blob, filename)
  return true
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; }
.kpi-card { text-align: center; }
.kpi-label { font-size: 13px; color: #64748b; margin-bottom: 4px; }
.kpi-value { font-size: 24px; font-weight: 700; color: #1e293b; }
.kpi-sub { font-size: 12px; color: #94a3b8; margin-top: 4px; }
.chart-note { font-size: 12px; color: #94a3b8; margin-top: 8px; }
.dialog-note { font-size: 12px; color: #64748b; line-height: 1.6; }
</style>
